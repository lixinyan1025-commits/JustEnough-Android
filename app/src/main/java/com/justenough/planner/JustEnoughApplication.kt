package com.justenough.planner

import android.app.Application
import com.justenough.planner.data.AppContainer
import com.justenough.planner.reminder.MaintenanceWorker
import com.justenough.planner.reminder.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.justenough.planner.pet.PetBubbleWorker
import com.justenough.planner.pet.AiPetService
import kotlinx.coroutines.flow.first

class JustEnoughApplication : Application() {
    lateinit var container: AppContainer
        private set
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationHelper.ensureChannels(this)
        MaintenanceWorker.schedule(this)
        applicationScope.launch {
            runCatching {
                container.repository.ensureStarterContent()
                container.scheduler.rescheduleAll()
                val settings=container.settings.state.first()
                if(com.justenough.planner.data.PetVisibility.isVisible(settings.petVisibility)) {
                    PetBubbleWorker.schedule(this@JustEnoughApplication)
                } else {
                    PetBubbleWorker.cancel(this@JustEnoughApplication)
                    stopService(android.content.Intent(this@JustEnoughApplication, AiPetService::class.java))
                    getSystemService(android.app.NotificationManager::class.java).cancel(8801)
                }
            }
        }
    }
}

val android.content.Context.appContainer: AppContainer
    get() = (applicationContext as JustEnoughApplication).container
