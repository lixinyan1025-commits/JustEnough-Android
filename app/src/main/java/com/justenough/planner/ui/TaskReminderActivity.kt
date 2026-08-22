package com.justenough.planner.ui

import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.lifecycleScope
import com.justenough.planner.appContainer
import com.justenough.planner.MainActivity
import com.justenough.planner.data.BlockWithPlans
import com.justenough.planner.data.PlanEntity
import com.justenough.planner.data.PlanKinds
import com.justenough.planner.data.ActiveRunDetails
import com.justenough.planner.data.TimeBlockEntity
import com.justenough.planner.reminder.NotificationHelper
import com.justenough.planner.task.RingtoneAlarmService
import com.justenough.planner.task.TargetAppLauncher
import com.justenough.planner.task.TaskOverlayService
import com.justenough.planner.widget.WidgetUpdater
import kotlinx.coroutines.launch
import java.time.LocalTime

class TaskReminderActivity : ComponentActivity() {
    private var autoOpenPending = false
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) attemptAutoOpen()
        }
    }

    private data class Item(
        val blockId: Long?,
        val plan: PlanEntity,
        val blockName: String,
        val timeText: String,
        val startedRunId: Long?,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val blockId = intent.getLongExtra(EXTRA_BLOCK_ID, -1).takeIf { it >= 0 }
        val planId = intent.getLongExtra("plan_id", -1).takeIf { it >= 0 }
        val startedRunId = intent.getLongExtra(EXTRA_STARTED_RUN_ID, -1).takeIf { it >= 0 }
        val autoOpen = intent.getBooleanExtra(EXTRA_AUTO_OPEN, false)
        autoOpenPending = autoOpen && startedRunId != null
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        setContent { JustEnoughTheme { ReminderScreen(blockId, planId, startedRunId, this) } }
    }

    override fun onResume() {
        super.onResume()
        attemptAutoOpen()
    }

    private fun attemptAutoOpen() {
        if (!autoOpenPending) return
        if (getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return
        autoOpenPending = false
        lifecycleScope.launch {
            val run = appContainer.repository.getActiveRun()
            val plan = run?.let { appContainer.repository.getPlan(it.planId) }
            if (plan != null && plan.appPackage != null) {
                TargetAppLauncher.launch(this@TaskReminderActivity, plan)
                TaskOverlayService.start(this@TaskReminderActivity, plan.id)
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(unlockReceiver) }
        super.onDestroy()
    }

    @Composable
    private fun ReminderScreen(blockId: Long?, planId: Long?, startedRunId: Long?, activity: TaskReminderActivity) {
        val c = appContainer
        var items by remember { mutableStateOf<List<Item>>(emptyList()) }
        var activeRun by remember { mutableStateOf<ActiveRunDetails?>(null) }
        var busy by remember { mutableStateOf(false) }
        var snoozePlan by remember { mutableStateOf<PlanEntity?>(null) }
        var conflictPlan by remember { mutableStateOf<PlanEntity?>(null) }
        LaunchedEffect(blockId, planId) {
            val snapshot = c.repository.todaySnapshot()
            val minute = LocalTime.now().toSecondOfDay() / 60
            val values = snapshot.todayBlocks.filter {
                blockId == null || it.block.id == blockId || it.block.startMinute == minute
            }
            val fromPlans = planId?.let { id -> snapshot.todayBlocks.flatMap { it.plans }.firstOrNull { it.id == id } }
            val all = if (fromPlans != null) {
                values + if (values.none { value -> value.plans.any { it.id == fromPlans.id } }) {
                    listOf(BlockWithPlans(TimeBlockEntity(name = "稍后开始", kind = PlanKinds.CHOICE, startMinute = minute, endMinute = (minute + 1).coerceAtMost(1440)), listOf(fromPlans)))
                } else emptyList()
            } else values
            items = all.flatMap { value ->
                value.plans.mapNotNull { details ->
                    c.repository.getPlan(details.id)?.let { plan ->
                        Item(
                            blockId = value.block.id,
                            plan = plan,
                            blockName = value.block.name,
                            timeText = "${clock(value.block.startMinute)}–${clock(value.block.endMinute)}",
                            startedRunId = if (plan.id == snapshot.activeRun?.planId) snapshot.activeRun?.runId else null,
                        )
                    }
                }
            }.distinctBy { it.plan.id }
            activeRun = snapshot.activeRun
        }
        DialogSurface {
            Text("任务到点了", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text("铃声提醒你查看本次要做什么；关闭铃声后任务仍会继续计时。", style = MaterialTheme.typography.bodySmall)
            if (items.isEmpty()) {
                Text("这次安排里的计划已被移除或归档，可以放心关闭。")
                Button({ activity.finish() }, Modifier.fillMaxWidth()) { Text("知道了") }
            } else {
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(items, key = { it.plan.id }) { item ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${item.timeText}  ${item.blockName}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                                Text(item.plan.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text("目标：${item.plan.minimumGoal}")
                                Text("预计 ${item.plan.estimatedMinutes} 分钟${if (item.plan.appPackage != null) " · 绑定应用：${appLabel(item.plan)}" else ""}")
                                if (item.startedRunId != null) {
                                    Text("已自动开始，正在计时。", color = MaterialTheme.colorScheme.primary)
                                    Row {
                                        TextButton({ RingtoneAlarmService.stop(activity); finish() }) { Text("关闭铃声") }
                                        Button({ enterStarted(activity, item) }, enabled = !busy) { Text("进入任务") }
                                    }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Button({ startPlan(activity, item, activeRun, { conflictPlan = item.plan }, { busy = true }, { busy = false }) }, enabled = !busy) { Text("开始任务") }
                                        TextButton({ snoozePlan = item.plan }, enabled = !busy) { Text("稍后开始") }
                                        TextButton({ cancelItem(activity, item) }, enabled = !busy) { Text("取消本次") }
                                    }
                                }
                            }
                        }
                    }
                }
                if (activeRun != null) {
                    Text("当前已有“${activeRun?.name}”在进行；新任务开始前需要先暂停或完成它。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton({ RingtoneAlarmService.stop(activity) }) { Text("关闭铃声") }
                    TextButton({ activity.finish() }) { Text("稍后再说") }
                }
            }
        }
        snoozePlan?.let { plan -> SnoozeDialog(plan, { snoozePlan = null }, { minutes -> snooze(activity, plan, minutes * 60_000L) }) }
        conflictPlan?.let { plan -> ConflictDialog(activeRun, plan, { conflictPlan = null }, { pauseAndStart(activity, plan) }, { finishCurrent(activity, activeRun) }) }
    }

    private fun startPlan(
        activity: TaskReminderActivity,
        item: Item,
        activeRun: ActiveRunDetails?,
        onConflict: (PlanEntity) -> Unit,
        setBusy: () -> Unit,
        clearBusy: () -> Unit,
    ) {
        if (activeRun != null && activeRun.planId != item.plan.id) { onConflict(item.plan); return }
        setBusy()
        lifecycleScope.launch {
            appContainer.repository.startTask(item.plan.id).fold({
                afterStart(activity, item.plan)
                activity.finish()
            }, { failure -> Toast.makeText(activity, failure.message ?: "无法开始", Toast.LENGTH_LONG).show(); clearBusy() })
        }
    }

    private suspend fun afterStart(context: Context, plan: PlanEntity) {
        RingtoneAlarmService.stop(context)
        WidgetUpdater.update(context)
        appContainer.repository.todaySnapshot().activeRun?.let { NotificationHelper.showActive(context, it) }
        if (plan.appPackage != null) {
            TargetAppLauncher.launch(context, plan).onFailure { failure ->
                Toast.makeText(context, "计时已开始；${failure.message}", Toast.LENGTH_LONG).show()
            }
            TaskOverlayService.start(context, plan.id)
        }
        NotificationHelper.cancelReminder(context)
    }

    private fun enterStarted(activity: TaskReminderActivity, item: Item) {
        RingtoneAlarmService.stop(activity)
        val plan = item.plan
        if (plan.appPackage != null) {
            TargetAppLauncher.launch(activity, plan).onFailure { failure ->
                Toast.makeText(activity, failure.message ?: "无法打开应用", Toast.LENGTH_LONG).show()
            }
            TaskOverlayService.start(activity, plan.id)
        } else {
            activity.startActivity(Intent(activity, MainActivity::class.java).putExtra("screen", "today").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        activity.finish()
    }

    private fun cancelItem(activity: TaskReminderActivity, item: Item) {
        lifecycleScope.launch {
            item.startedRunId?.let { appContainer.repository.finishRun(it, null, abandoned = true) }
            item.blockId?.let { appContainer.repository.skipAnchorForBlock(it) }
            RingtoneAlarmService.stop(activity)
            NotificationHelper.cancelReminder(activity)
            WidgetUpdater.update(activity)
            activity.finish()
        }
    }

    private fun snooze(activity: TaskReminderActivity, plan: PlanEntity, delayMillis: Long) {
        if (delayMillis < 60_000) return
        lifecycleScope.launch {
            val at = System.currentTimeMillis() + delayMillis
            appContainer.scheduler.scheduleSnooze(plan.id, at)
            RingtoneAlarmService.stop(activity)
            NotificationHelper.cancelReminder(activity)
            val time = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0)
            Toast.makeText(activity, "${plan.name} 将在 ${time} 自动开始并响铃", Toast.LENGTH_LONG).show()
            activity.finish()
        }
    }

    private fun pauseAndStart(activity: TaskReminderActivity, plan: PlanEntity) {
        lifecycleScope.launch {
            val current = appContainer.repository.getActiveRun()
            if (current != null) appContainer.repository.pauseOrResume(current.id)
            appContainer.repository.startTask(plan.id).fold({
                afterStart(activity, plan)
                activity.finish()
            }, { failure -> Toast.makeText(activity, failure.message ?: "无法开始", Toast.LENGTH_LONG).show() })
        }
    }

    private fun finishCurrent(activity: TaskReminderActivity, current: ActiveRunDetails?) {
        if (current == null) return
        activity.startActivity(Intent(activity, TaskFinishActivity::class.java).putExtra("run_id", current.runId))
        activity.finish()
    }

    private fun appLabel(plan: PlanEntity): String = runCatching {
        plan.appPackage?.let { pkg -> packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
    }.getOrNull() ?: "未绑定"

    @Composable
    private fun SnoozeDialog(plan: PlanEntity, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
        var customMinutes by remember { mutableStateOf("") }
        var customHours by remember { mutableStateOf("") }
        var atTime by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("稍后开始：${plan.name}") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("到点后会自动开始任务并响铃；可随时在设置或今天页取消。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(5L to "5分钟", 10L to "10分钟", 15L to "15分钟", 30L to "30分钟", 60L to "1小时", 120L to "2小时").forEach { (minute, label) ->
                        AssistChip({ onPick(minute * 60_000) }, { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(customMinutes, { customMinutes = it }, Modifier.weight(1f), label = { Text("自定义分钟") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(customHours, { customHours = it }, Modifier.weight(1f), label = { Text("自定义小时") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(atTime, { atTime = it }, Modifier.weight(1f), label = { Text("今天具体时间 HH:mm") }, singleLine = true)
                    Button({
                        val parsed = parseTime(atTime)
                        if (parsed != null) {
                            val now = LocalTime.now()
                            var delay = (parsed.toSecondOfDay() - now.toSecondOfDay()) * 1000L
                            if (delay <= 0) delay += 24 * 60 * 60 * 1000L
                            onPick(delay)
                        } else Toast.makeText(this@TaskReminderActivity, "时间格式应为 HH:mm", Toast.LENGTH_SHORT).show()
                    }) { Text("确定") }
                }
                if (customMinutes.toIntOrNull()?.let { it > 0 } == true) TextButton({ onPick(customMinutes.toInt() * 60_000L) }) { Text("按分钟开始") }
                if (customHours.toIntOrNull()?.let { it > 0 } == true) TextButton({ onPick(customHours.toInt() * 3_600_000L) }) { Text("按小时开始") }
            } },
            confirmButton = {},
            dismissButton = { TextButton(onDismiss) { Text("取消") } },
        )
    }

    @Composable
    private fun ConflictDialog(current: ActiveRunDetails?, plan: PlanEntity, onDismiss: () -> Unit, onPauseAndStart: () -> Unit, onFinishCurrent: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("当前任务尚未结束") },
            text = { Text("“${current?.name ?: "当前任务"}”正在计时。开始“${plan.name}”前，可以先完成或暂停当前任务。") },
            confirmButton = {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    Button(onPauseAndStart) { Text("暂停当前并开始新任务") }
                    TextButton(onFinishCurrent) { Text("先去完成当前任务") }
                    TextButton(onDismiss) { Text("取消") }
                }
            },
        )
    }

    companion object {
        const val EXTRA_BLOCK_ID = "block_id"
        const val EXTRA_STARTED_RUN_ID = "started_run_id"
        const val EXTRA_AUTO_OPEN = "auto_open"
    }
}

private fun clock(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
private fun parseTime(value: String): LocalTime? = runCatching {
    val parts = value.trim().split(':')
    if (parts.size != 2) return null
    LocalTime.of(parts[0].toInt(), parts[1].toInt())
}.getOrNull()
