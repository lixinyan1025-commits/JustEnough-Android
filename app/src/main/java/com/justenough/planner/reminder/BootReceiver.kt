package com.justenough.planner.reminder

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.justenough.planner.appContainer
import com.justenough.planner.widget.WidgetUpdater
import com.justenough.planner.pet.AiPetService
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                context.appContainer.scheduler.rescheduleAll()
                WidgetUpdater.update(context)
                val state=context.appContainer.settings.state.first()
                if(com.justenough.planner.data.PetVisibility.isVisible(state.petVisibility)&&state.aiConnectionVerified&&Settings.canDrawOverlays(context)) runCatching{AiPetService.show(context)}
            } finally { pending.finish() }
        }
    }
}
