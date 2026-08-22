package com.justenough.planner.task

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.justenough.planner.appContainer
import com.justenough.planner.reminder.NotificationHelper
import com.justenough.planner.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TaskActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val runId = intent.getLongExtra("run_id", -1)
                if (runId < 0) return@launch
                when (intent.action) {
                    ACTION_TOGGLE -> context.appContainer.repository.pauseOrResume(runId)
                    ACTION_EXTEND -> context.appContainer.repository.extendRun(runId)
                }
                val snapshot = context.appContainer.repository.todaySnapshot()
                snapshot.activeRun?.let { NotificationHelper.showActive(context, it) }
                WidgetUpdater.update(context)
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.justenough.planner.TOGGLE_RUN"
        const val ACTION_EXTEND = "com.justenough.planner.EXTEND_RUN"
    }
}
