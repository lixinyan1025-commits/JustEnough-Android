package com.justenough.planner.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.justenough.planner.appContainer
import com.justenough.planner.widget.WidgetUpdater
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import com.justenough.planner.ai.AiAnalysisWorker

class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        applicationContext.appContainer.repository.ensureStarterContent()
        applicationContext.appContainer.scheduler.rescheduleAll()
        WidgetUpdater.update(applicationContext)
        if (applicationContext.appContainer.settings.state.first().pendingAnalysis) AiAnalysisWorker.immediate(applicationContext)
    }.fold({ Result.success() }, { Result.retry() })

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(12, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("planner-maintenance", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

class ReminderTestCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WidgetUpdater.update(applicationContext)
        return Result.success()
    }
}
