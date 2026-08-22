package com.justenough.planner.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.justenough.planner.appContainer
import com.justenough.planner.data.ReviewStates
import com.justenough.planner.pet.PetConversationEngine
import com.justenough.planner.task.RingtoneAlarmService
import com.justenough.planner.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NotificationHelper.ensureChannels(context)
                when (intent.action) {
                    ACTION_ENERGY -> {
                        // Older pending intents may still carry this action after upgrade.
                        // Energy check-ins are intentionally ignored in the fulfillment design.
                        WidgetUpdater.update(context)
                    }
                    ACTION_REVIEW -> {
                        val snapshot = context.appContainer.repository.todaySnapshot()
                        val pendingCount = snapshot.todayBlocks.flatMap { it.plans }.distinctBy { it.id }
                            .count { it.id !in snapshot.completedPlanIds && it.id != snapshot.activeRun?.planId }
                        NotificationHelper.showReview(context, pendingCount)
                        context.appContainer.scheduler.rescheduleAll()
                    }
                    ACTION_TEST -> {
                        context.appContainer.settings.update { it.copy(reminderTestCompletedAt = System.currentTimeMillis()) }
                        context.appContainer.repository.recordDiagnostic("REMINDER_TEST_OK", "后台测试提醒已按计划触发")
                        NotificationHelper.showTest(context)
                        WidgetUpdater.update(context)
                    }
                    ACTION_REFRESH -> {
                        WidgetUpdater.update(context)
                        context.appContainer.scheduler.rescheduleAll()
                    }
                    ACTION_SNOOZE -> {
                        val planId = intent.getLongExtra(EXTRA_PLAN_ID, -1)
                        if (planId >= 0) TaskStartFlow.handleSnooze(context, planId)
                        WidgetUpdater.update(context)
                        context.appContainer.scheduler.rescheduleAll()
                    }
                    ACTION_STOP_RING -> {
                        RingtoneAlarmService.stop(context)
                        context.appContainer.repository.recordDiagnostic("RING_STOPPED", "用户关闭了任务开始铃声")
                    }
                    ACTION_BLOCK -> {
                        val id = intent.getLongExtra(EXTRA_BLOCK_ID, -1)
                        val stage = intent.getIntExtra(EXTRA_STAGE, STAGE_START)
                        when (stage) {
                            STAGE_30_MIN, STAGE_5_MIN -> {
                                val snapshot = context.appContainer.repository.todaySnapshot()
                                snapshot.todayBlocks.firstOrNull { it.block.id == id }?.let { value ->
                                    NotificationHelper.showUpcoming(context, value, if (stage == STAGE_30_MIN) 30 else 5)
                                    PetConversationEngine.upcoming(context, id, if (stage == STAGE_30_MIN) 30 else 5)
                                }
                            }
                            else -> {
                                TaskStartFlow.handleBlockStart(context, id)
                            }
                        }
                        WidgetUpdater.update(context)
                        context.appContainer.scheduler.rescheduleAll()
                    }
                }
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_BLOCK = "com.justenough.planner.BLOCK_REMINDER"
        const val ACTION_REVIEW = "com.justenough.planner.REVIEW_REMINDER"
        const val ACTION_TEST = "com.justenough.planner.TEST_REMINDER"
        const val ACTION_ENERGY = "com.justenough.planner.SET_ENERGY"
        const val ACTION_REFRESH = "com.justenough.planner.REFRESH_WIDGET"
        const val ACTION_SNOOZE = "com.justenough.planner.SNOOZE_START"
        const val ACTION_STOP_RING = "com.justenough.planner.STOP_RING"
        const val EXTRA_BLOCK_ID = "block_id"
        const val EXTRA_KIND = "kind"
        const val EXTRA_ENERGY = "energy"
        const val EXTRA_STAGE = "stage"
        const val EXTRA_PLAN_ID = "plan_id"
        const val STAGE_30_MIN = 1
        const val STAGE_5_MIN = 2
        const val STAGE_START = 3
        const val STAGE_END = 4
    }
}
