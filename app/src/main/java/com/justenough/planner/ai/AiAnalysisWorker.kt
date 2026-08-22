package com.justenough.planner.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.*
import android.content.Intent
import androidx.core.content.ContextCompat
import com.justenough.planner.pet.AiPetService
import com.justenough.planner.appContainer
import com.justenough.planner.reminder.NotificationHelper
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

class AiAnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.appContainer
        val s = c.settings.state.first()
        if (!s.aiConnectionVerified || !s.pendingAnalysis) return Result.success()
        if (com.justenough.planner.data.PetVisibility.isVisible(s.petVisibility)) AiPetService.trigger(applicationContext,AiPetService.ACTION_THINK)
        if (s.wifiOnlyAnalysis && !onWifi()) return Result.retry()
        val today = LocalDate.now().toEpochDay(); val count = if (s.analysisEpochDay == today) s.analysisCount else 0
        if (count >= s.autoAnalysisLimit) return Result.success()
        return c.aiClient.chat("请分析最近的计划变化，只给一条最务实、无压力的建议，最多30个汉字。").fold({ reply ->
            c.repository.addAiMessage("assistant", reply.take(30), "ANALYSIS", c.aiClient.lastSource, true)
            c.settings.update { it.copy(pendingAnalysis=false, analysisEpochDay=today, analysisCount=count+1) }
            if (com.justenough.planner.data.PetVisibility.isVisible(s.petVisibility)) AiPetService.trigger(applicationContext,AiPetService.ACTION_REMIND,reply.take(30))
            NotificationHelper.showAiSuggestion(applicationContext); Result.success()
        }, {
            val fallback = localFallback()
            c.repository.addAiMessage("assistant", fallback, "ANALYSIS", "LOCAL", true)
            c.settings.update { current -> current.copy(pendingAnalysis = true, analysisEpochDay = today, analysisCount = count + 1) }
            if (com.justenough.planner.data.PetVisibility.isVisible(s.petVisibility)) AiPetService.trigger(applicationContext,AiPetService.ACTION_REMIND,fallback)
            NotificationHelper.showAiSuggestion(applicationContext)
            Result.success()
        })
    }
    private fun onWifi(): Boolean { val cm=applicationContext.getSystemService(ConnectivityManager::class.java);val n=cm.activeNetwork?:return false;return cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true }
    private fun localFallback() = listOf("先做最小的一步就够了。", "今天不必塞满，选一件开始。", "先完成眼前最明确的一件事。")[LocalDate.now().dayOfYear % 3]
    companion object {
        fun debounce(context: Context) { WorkManager.getInstance(context).enqueueUniqueWork("ai-plan-analysis",ExistingWorkPolicy.REPLACE,OneTimeWorkRequestBuilder<AiAnalysisWorker>().setInitialDelay(30,TimeUnit.SECONDS).build()) }
        fun immediate(context: Context) { WorkManager.getInstance(context).enqueueUniqueWork("ai-plan-analysis",ExistingWorkPolicy.REPLACE,OneTimeWorkRequestBuilder<AiAnalysisWorker>().build()) }
    }
}
