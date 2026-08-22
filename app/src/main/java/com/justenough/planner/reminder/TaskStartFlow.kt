package com.justenough.planner.reminder

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.justenough.planner.appContainer
import com.justenough.planner.data.BlockWithPlans
import com.justenough.planner.data.PlanEntity
import com.justenough.planner.data.PlanKinds
import com.justenough.planner.data.TimeBlockEntity
import com.justenough.planner.pet.PetConversationEngine
import com.justenough.planner.task.RingtoneAlarmService
import com.justenough.planner.task.TargetAppLauncher
import com.justenough.planner.task.TaskOverlayService
import com.justenough.planner.ui.TaskReminderActivity
import com.justenough.planner.widget.WidgetUpdater
import kotlinx.coroutines.flow.first
import java.time.LocalDate

object TaskStartFlow {
    private const val PREFS = "task_start_guard"
    private const val EXTRA_PLAN_ID = "plan_id"

    suspend fun handleBlockStart(context: Context, blockId: Long) {
        val c = context.appContainer
        val value = c.repository.blockWithPlans(blockId) ?: return
        val block = value.block
        if (!block.enabled || !block.reminderEnabled) return
        if (!markIfNew(context, block)) return
        val settings = c.settings.state.first()
        if (block.startSoundEnabled) RingtoneAlarmService.start(context, settings)
        val target = if (block.kind == PlanKinds.ANCHOR) value.plans.firstOrNull()?.let { c.repository.getPlan(it.id) } else null
        var startedRunId: Long? = null
        if (target != null && block.autoStartEnabled && c.repository.getActiveRun() == null) {
            startedRunId = c.repository.startTask(target.id).getOrNull()
        }
        // 先拉起本应用提醒页，自动打开绑定 App 由前台页面执行，避免后台启动限制拖慢到点启动。
        launchReminderPage(context, blockId, planId = null, startedRunId = startedRunId, autoOpen = block.autoOpenAppEnabled)
        if (startedRunId != null) {
            WidgetUpdater.update(context)
            c.repository.todaySnapshot().activeRun?.let { NotificationHelper.showActive(context, it) }
        }
        NotificationHelper.showTaskReminder(context, listOf(value), startedRunId != null)
        if (block.kind == PlanKinds.CHOICE) PetConversationEngine.blockStarted(context, blockId)
    }

    suspend fun handleSnooze(context: Context, planId: Long) {
        val c = context.appContainer
        val plan = c.repository.getPlan(planId) ?: return
        if (!plan.enabled || plan.archived) return
        c.scheduler.cancelSnooze(planId)
        val settings = c.settings.state.first()
        val activeRun = c.repository.getActiveRun()
        RingtoneAlarmService.start(context, settings)
        var startedRunId: Long? = null
        if (activeRun == null) {
            startedRunId = c.repository.startTask(planId).getOrNull()
        }
        launchReminderPage(context, blockId = null, planId = planId, startedRunId = startedRunId, autoOpen = plan.appPackage != null)
        if (startedRunId != null) {
            WidgetUpdater.update(context)
            c.repository.todaySnapshot().activeRun?.let { NotificationHelper.showActive(context, it) }
        }
        NotificationHelper.showTaskReminder(context, emptyList(), startedRunId != null, plan = plan, planStarted = startedRunId != null)
    }

    suspend fun openBoundApp(context: Context, plan: PlanEntity, autoOpen: Boolean) {
        if (!autoOpen || plan.appPackage == null) return
        TargetAppLauncher.launch(context, plan).onFailure { failure ->
            context.appContainer.repository.recordDiagnostic("APP_LAUNCH_FAILED", failure.message ?: "未知错误")
            Toast.makeText(context, "计时已开始；${failure.message}", Toast.LENGTH_LONG).show()
        }
        TaskOverlayService.start(context, plan.id)
    }

    private fun launchReminderPage(context: Context, blockId: Long?, planId: Long?, startedRunId: Long?, autoOpen: Boolean) {
        runCatching {
            context.startActivity(
                Intent(context, TaskReminderActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .apply {
                        if (blockId != null) putExtra(TaskReminderActivity.EXTRA_BLOCK_ID, blockId)
                        if (planId != null) putExtra(EXTRA_PLAN_ID, planId)
                        if (startedRunId != null) putExtra(TaskReminderActivity.EXTRA_STARTED_RUN_ID, startedRunId)
                        putExtra(TaskReminderActivity.EXTRA_AUTO_OPEN, autoOpen)
                    },
            )
        }
    }

    private fun markIfNew(context: Context, block: TimeBlockEntity): Boolean {
        val day = LocalDate.now().toEpochDay()
        val key = "block_${block.id}"
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)
        val value = "$day:${block.startMinute}"
        if (last == value) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, value).apply()
        return true
    }
}
