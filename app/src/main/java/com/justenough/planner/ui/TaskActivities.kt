package com.justenough.planner.ui

import android.os.*
import android.widget.Toast
import android.content.Intent
import android.content.ComponentName
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.justenough.planner.appContainer
import com.justenough.planner.data.PlanEntity
import com.justenough.planner.data.TaskRunEntity
import com.justenough.planner.reminder.NotificationHelper
import com.justenough.planner.task.TargetAppLauncher
import com.justenough.planner.widget.WidgetUpdater
import kotlinx.coroutines.launch
import com.justenough.planner.ai.AiAnalysisWorker
import kotlin.math.max
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import com.justenough.planner.JustEnoughApplication
import com.justenough.planner.task.TaskSoundPlayer
import com.justenough.planner.task.RingtoneAlarmService
import com.justenough.planner.pet.PetConversationEngine

class TaskConfirmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val planId = intent.getLongExtra("plan_id", intent.getLongExtra("action_id", -1)); if (planId < 0) { finish(); return }
        setContent { JustEnoughTheme {
            var plan by remember { mutableStateOf<PlanEntity?>(null) }; var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
            LaunchedEffect(planId) { plan = appContainer.repository.getPlan(planId); if (plan == null) error = "这个计划已不存在" }
            DialogSurface {
                Text("确认本次任务", color = MaterialTheme.colorScheme.primary)
                plan?.let { Text(it.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("本次目标：${it.minimumGoal}"); Text("预计 ${it.estimatedMinutes} 分钟") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = ::finish) { Text("取消") }
                    Button(enabled = plan != null && !busy, onClick = { busy = true; lifecycleScope.launch {
                        appContainer.repository.startTask(planId).fold({
                            WidgetUpdater.update(this@TaskConfirmActivity); appContainer.repository.todaySnapshot().activeRun?.let {
                                NotificationHelper.showActive(this@TaskConfirmActivity, it)
                                PetConversationEngine.scheduleDuringTask(this@TaskConfirmActivity, it.runId, it.plannedMinutes)
                            }
                            val soundSettings = appContainer.settings.state.first()
                            if (soundSettings.feedbackSoundEnabled) TaskSoundPlayer.play(soundSettings.startSound)
                            plan?.let { target -> if (target.appPackage != null) TargetAppLauncher.launch(this@TaskConfirmActivity, target).onFailure { failure -> Toast.makeText(this@TaskConfirmActivity, "计时已开始；${failure.message}", Toast.LENGTH_LONG).show(); lifecycleScope.launch { appContainer.repository.recordDiagnostic("APP_LAUNCH_FAILED", failure.message ?: "未知错误") } } }
                            TaskStartVerifyWorker.schedule(this@TaskConfirmActivity, planId)
                            finish()
                        }, { error = it.message; busy = false })
                    } }, modifier = Modifier.padding(start = 8.dp)) { Text("开始") }
                }
            }
        } }
    }
}

class TaskStartVerifyWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val run = applicationContext.appContainer.repository.getActiveRun()
        if (run != null && inputData.getLong("plan_id", -1) == run.planId) applicationContext.appContainer.repository.recordDiagnostic("TASK_START_OK", "计时和状态刷新已生效")
        return Result.success()
    }
    companion object { fun schedule(context: android.content.Context, planId: Long) { WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<TaskStartVerifyWorker>().setInputData(workDataOf("plan_id" to planId)).setInitialDelay(4, TimeUnit.SECONDS).build()) } }
}

class TaskFinishActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runId = intent.getLongExtra("run_id", -1); if (runId < 0) { finish(); return }
        val finishMode = intent.getStringExtra("finish_mode") ?: "COMPLETE"
        setContent { JustEnoughTheme {
            var run by remember { mutableStateOf<TaskRunEntity?>(null) }; var plan by remember { mutableStateOf<PlanEntity?>(null) }; var busy by remember { mutableStateOf(false) }; var selectedPoints by remember { mutableStateOf<Int?>(null) }
            LaunchedEffect(runId) {
                run = appContainer.repository.getRun(runId)
                if (run?.status !in setOf(com.justenough.planner.data.RunStatuses.ACTIVE, com.justenough.planner.data.RunStatuses.PAUSED)) { finish(); return@LaunchedEffect }
                plan = run?.let { appContainer.repository.getPlan(it.planId) }
            }
            DialogSurface {
                Text(when(finishMode){"ENOUGH"->"今天做到这里就够了";"ABANDON"->"放弃任务";else->"完成任务"}, color = if(finishMode=="ABANDON") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Text(plan?.name ?: "当前任务", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                run?.let { value ->
                    val paused = value.pausedAt?.let { System.currentTimeMillis() - it } ?: 0
                    val actual = max(1, ((System.currentTimeMillis() - value.startedAt - value.pausedDurationMillis - paused) / 60_000).toInt())
                    Text("本次已投入约 $actual 分钟 · 原计划 ${value.plannedMinutes} 分钟")
                    if (actual >= value.plannedMinutes && finishMode=="COMPLETE") Text("已经达到预计时间，可以确认完成。", color = MaterialTheme.colorScheme.primary)
                }
                if(finishMode=="ABANDON") {
                    Text("放弃表示这次基本没有做，不会增加充实度。")
                    Button(onClick={busy=true;settle(runId,null,abandoned=true)},enabled=!busy,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text("确认放弃")}
                    TextButton(onClick=::finish,modifier=Modifier.fillMaxWidth()){Text("继续进行")}
                } else {
                    Text(if(finishMode=="ENOUGH") "这次做到这里，为今天增加多少充实度？" else "完成后，为今天增加多少充实度？")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { (1..5).forEach { point -> FilterChip(selected = selectedPoints == point, onClick = { selectedPoints = point }, label = { Text("$point") }, enabled = !busy, modifier = Modifier.weight(1f)) } }
                    Button(onClick = { selectedPoints?.let { busy = true; settle(runId, it, enough=finishMode=="ENOUGH") } }, enabled = selectedPoints != null && !busy, modifier = Modifier.fillMaxWidth()) { Text(if(finishMode=="ENOUGH")"确认：今天够了" else "确认完成任务") }
                    TextButton(onClick = ::finish,modifier=Modifier.fillMaxWidth()) { Text("继续进行") }
                }
            }
        } }
    }

    private fun settle(runId: Long, points: Int?, abandoned: Boolean = false, enough:Boolean=false) = lifecycleScope.launch {
        val changed=appContainer.repository.finishRun(runId, points, abandoned, enough)
        if(!changed){Toast.makeText(this@TaskFinishActivity,"这个任务已经结算过了",Toast.LENGTH_SHORT).show();finish();return@launch}
        NotificationHelper.cancelActive(this@TaskFinishActivity)
        NotificationHelper.cancelReminder(this@TaskFinishActivity)
        RingtoneAlarmService.stop(this@TaskFinishActivity)
        AiAnalysisWorker.immediate(this@TaskFinishActivity)
        (application as JustEnoughApplication).applicationScope.launch { WidgetUpdater.update(applicationContext) }
        if (!abandoned && !enough) com.justenough.planner.pet.AiPetService.trigger(this@TaskFinishActivity,com.justenough.planner.pet.AiPetService.ACTION_CELEBRATE)
        if (!abandoned) {
            val settings = appContainer.settings.state.first()
            if (settings.feedbackSoundEnabled) TaskSoundPlayer.play(if (enough) "LIGHT" else settings.completionSound)
            PetConversationEngine.afterTask(this@TaskFinishActivity, runId, completed = !enough)
        }
        if (!abandoned && appContainer.settings.state.first().completionVibration) vibrateGently()
        setResult(RESULT_OK)
        finish()
    }

    private fun vibrateGently() {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        vibrator.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

/** User-confirmed notification action: the tap itself authorizes starting and opening the bound app. */
class ScheduledTaskStartActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val planId = intent.getLongExtra("plan_id", -1)
        if (planId < 0) { finish(); return }
        setContent { JustEnoughTheme {
            var message by remember { mutableStateOf("正在开始任务…") }
            LaunchedEffect(planId) {
                val plan = appContainer.repository.getPlan(planId)
                if (plan == null) { message = "计划已不存在"; delayAndFinish(); return@LaunchedEffect }
                appContainer.repository.startTask(planId).fold({
                    RingtoneAlarmService.stop(this@ScheduledTaskStartActivity)
                    NotificationHelper.cancelReminder(this@ScheduledTaskStartActivity)
                    WidgetUpdater.update(this@ScheduledTaskStartActivity)
                    appContainer.repository.todaySnapshot().activeRun?.let {
                        NotificationHelper.showActive(this@ScheduledTaskStartActivity, it)
                        PetConversationEngine.scheduleDuringTask(this@ScheduledTaskStartActivity, it.runId, it.plannedMinutes)
                    }
                    val feedback = appContainer.settings.state.first()
                    if (feedback.feedbackSoundEnabled) TaskSoundPlayer.play(feedback.startSound)
                    if (plan.appPackage != null) TargetAppLauncher.launch(this@ScheduledTaskStartActivity, plan).onFailure { failure ->
                        Toast.makeText(this@ScheduledTaskStartActivity, "计时已开始；${failure.message}", Toast.LENGTH_LONG).show()
                    }
                    if (plan.appPackage != null) com.justenough.planner.task.TaskOverlayService.start(this@ScheduledTaskStartActivity, plan.id)
                    finish()
                }, { failure -> message = failure.message ?: "无法开始任务"; delayAndFinish() })
            }
            DialogSurface { Text(message, style = MaterialTheme.typography.titleMedium) }
        } }
    }
    private suspend fun delayAndFinish() { kotlinx.coroutines.delay(1400); finish() }
}

class AppBindingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val planId = intent.getLongExtra("plan_id", -1)
        if (planId < 0) { finish(); return }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_PICK_ACTIVITY).putExtra(Intent.EXTRA_INTENT, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)), 41)
    }
    @Deprecated("Deprecated in Android framework")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 41 && resultCode == RESULT_OK) {
            val component: ComponentName? = data?.component
            val planId = intent.getLongExtra("plan_id", -1)
            if (component != null) kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                appContainer.repository.bindApp(planId, component.packageName, component.className)
                WidgetUpdater.update(this@AppBindingActivity)
                runOnUiThread { Toast.makeText(this@AppBindingActivity, "应用绑定完成", Toast.LENGTH_SHORT).show(); finish() }
            } else finish()
        } else finish()
    }
}

@Composable internal fun DialogSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.scrim.copy(alpha = .28f)) {
        Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.Center) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) } }
    }
}
