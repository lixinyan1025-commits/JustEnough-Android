package com.justenough.planner.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.justenough.planner.appContainer

class WidgetPinResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        context.getSharedPreferences("widget_setup", Context.MODE_PRIVATE)
            .edit().putLong("last_pin_success", System.currentTimeMillis()).apply()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { context.appContainer.repository.recordDiagnostic("WIDGET_PIN_OK","桌面组件已由系统桌面确认添加");WidgetUpdater.update(context) } finally { pending.finish() }
        }
    }
}
