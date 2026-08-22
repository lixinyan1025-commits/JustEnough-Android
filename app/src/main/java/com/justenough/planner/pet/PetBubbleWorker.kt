package com.justenough.planner.pet

import android.content.Context
import androidx.work.*
import com.justenough.planner.appContainer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

class PetBubbleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.appContainer
        val settings = c.settings.state.first()
        if (!com.justenough.planner.data.PetVisibility.isVisible(settings.petVisibility) || !settings.aiConnectionVerified) return Result.success()
        PetConversationEngine.casual(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PetBubbleWorker>(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("xiaoman-hourly-bubble", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
        fun cancel(context:Context)=WorkManager.getInstance(context).cancelUniqueWork("xiaoman-hourly-bubble")
    }
}
