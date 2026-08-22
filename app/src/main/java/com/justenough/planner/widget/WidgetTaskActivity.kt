package com.justenough.planner.widget

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import com.justenough.planner.appContainer
import com.justenough.planner.reminder.NotificationHelper
import com.justenough.planner.ui.TaskConfirmActivity
import com.justenough.planner.ui.TaskFinishActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WidgetTaskActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val planId = intent.getLongExtra("plan_id", -1)
        if (planId < 0) { finish(); return }
        val runId = intent.getLongExtra("run_id", -1)
        CoroutineScope(Dispatchers.IO).launch {
            appContainer.repository.recordDiagnostic("WIDGET_CLICK", "组件任务点击已到达应用")
        }
        runCatching {
            startActivity(if (runId >= 0) Intent(this, TaskFinishActivity::class.java).putExtra("run_id", runId) else Intent(this, TaskConfirmActivity::class.java).putExtra("plan_id", planId))
        }.onFailure { failure ->
            CoroutineScope(Dispatchers.IO).launch {
                appContainer.repository.recordDiagnostic("WIDGET_LAUNCH_FAILED", failure.javaClass.simpleName)
            }
            NotificationHelper.showWidgetFallback(this, planId)
        }
        finish()
    }
}
