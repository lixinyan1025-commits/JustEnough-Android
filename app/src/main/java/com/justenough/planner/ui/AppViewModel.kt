package com.justenough.planner.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.justenough.planner.appContainer
import com.justenough.planner.ai.PlanProposal
import com.justenough.planner.data.*
import com.justenough.planner.reminder.NotificationHelper
import com.justenough.planner.task.TargetAppLauncher
import com.justenough.planner.widget.WidgetUpdater
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import android.net.Uri
import android.content.Intent
import com.justenough.planner.backup.BackupPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val c = application.appContainer
    val plannerState = c.repository.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlannerState())
    val today = MutableStateFlow<TodaySnapshot?>(null); val proposal = MutableStateFlow<PlanProposal?>(null)
    val busy = MutableStateFlow(false); val message = MutableStateFlow<String?>(null)
    val apiKeyPresent = MutableStateFlow(c.secureKeyStore.hasApiKey()); val fallbackKeyPresent = MutableStateFlow(c.secureKeyStore.hasFallbackApiKey())
    val expiredAiMessageCount = MutableStateFlow(0)
    val backupPreview = MutableStateFlow<BackupPayload?>(null)
    init { refreshToday(); viewModelScope.launch { val s=c.settings.state.first();expiredAiMessageCount.value = if(s.historyRetentionUntil>System.currentTimeMillis()||s.historyReminderAt>System.currentTimeMillis())0 else c.repository.oldAiMessageCount(System.currentTimeMillis()-90L*24*60*60*1000) } }

    fun addPlan(name: String, goal: String, minutes: Int, quadrant: String) = work { c.repository.addPlan(name, goal, minutes, quadrant) }
    fun updatePlan(plan: PlanEntity) = work { c.repository.updatePlan(plan) }
    fun archivePlan(id: Long) = work { c.repository.archivePlan(id) }
    fun restorePlan(id: Long, quadrant: String) = work { c.repository.restorePlan(id, quadrant) }
    fun classifyPlans(values: Map<Long, String>) = work { c.repository.classifyPlans(values) }
    fun movePlan(id: Long, quadrant: String, beforeId: Long? = null) = work { c.repository.movePlan(id, quadrant, beforeId) }
    fun permanentlyDeletePlan(id: Long) = work { c.repository.permanentlyDeletePlan(id) }
    fun bindApp(id: Long, pkg: String?, cls: String?) = work { c.repository.bindApp(id, pkg, cls) }
    fun testOpenApp(plan: PlanEntity) { viewModelScope.launch {
        TargetAppLauncher.launch(getApplication(), plan).fold(
            { c.repository.recordDiagnostic("APP_LAUNCH_OK", "已完成应用启动测试") },
            { failure -> c.repository.recordDiagnostic("APP_LAUNCH_FAILED", failure.message ?: "目标应用无法启动"); message.value = failure.message }
        )
    } }
    fun addBlock(block: TimeBlockEntity, ids: List<Long>) = work { c.repository.addBlock(block, ids); c.scheduler.rescheduleAll() }
    fun updateBlock(block: TimeBlockEntity, ids: List<Long>) = work { c.repository.updateBlock(block, ids); c.scheduler.rescheduleAll() }
    fun deleteBlock(block: TimeBlockEntity) = work { c.repository.deleteBlock(block); c.scheduler.rescheduleAll() }
    fun scheduleSnooze(planId: Long, minutes: Long) = work {
        require(minutes > 0) { "稍后时间必须大于0分钟" }
        val at = System.currentTimeMillis() + minutes * 60_000L
        c.scheduler.scheduleSnooze(planId, at)
        val time = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0)
        message.value = "已安排在今天 $time 自动开始"
    }
    fun cancelSnooze(planId: Long) = work { c.scheduler.cancelSnooze(planId); message.value = "已取消稍后开始" }
    fun rotateCandidates() = work { c.repository.rotateCandidates() }
    fun returnToPool(planId: Long) = work { c.repository.markReview(planId, ReviewStates.POOL, dismissedToday = true) }
    fun scheduleFor(planId: Long, date: java.time.LocalDate) = work { c.repository.markReview(planId, ReviewStates.RESCHEDULED, date) }
    fun removeFromReview(planId: Long) = archivePlan(planId)
    fun toggleRun(id: Long) = work { c.repository.pauseOrResume(id); c.repository.todaySnapshot().activeRun?.let { NotificationHelper.showActive(getApplication(), it) } }
    fun extendRun(id: Long) = work { c.repository.extendRun(id); c.repository.todaySnapshot().activeRun?.let { NotificationHelper.showActive(getApplication(), it) } }
    fun updateSettings(f: (SettingsState) -> SettingsState) = work { c.settings.update(f) }
    fun clearMessage() { message.value = null }
    fun refreshToday() = viewModelScope.launch { today.value = runCatching { c.repository.todaySnapshot() }.getOrNull() }

    fun saveAndTest(provider: String, base: String, model: String, key: String, fallbackEnabled: Boolean, fallbackBase: String, fallbackModel: String, fallbackKey: String) = busyWork {
        require(c.settings.state.first().aiConsent) { "请先同意发送当前计划与近30天聚合摘要" }
        val savedBase=if(provider=="DEEPSEEK")DeepSeekConfig.BASE_URL else base.trimEnd('/');val savedModel=if(provider=="DEEPSEEK")DeepSeekConfig.model(model)else model.trim();val savedFallbackModel=DeepSeekConfig.model(fallbackModel)
        require(savedBase.isNotBlank() && savedModel.isNotBlank()) { "请填写接口地址和模型" }; require(key.isNotBlank() || apiKeyPresent.value) { "请填写主要接口API Key" }
        c.settings.update { it.copy(aiProvider=provider, aiBaseUrl=savedBase, aiModel=savedModel, fallbackEnabled=fallbackEnabled, fallbackBaseUrl=DeepSeekConfig.BASE_URL, fallbackModel=savedFallbackModel, aiConnectionStatus="TESTING", aiConnectionDetail="正在测试…", aiConnectionVerified=false) }
        if (key.isNotBlank()) { c.secureKeyStore.putApiKey(key); apiKeyPresent.value=true }; if (fallbackKey.isNotBlank()) { c.secureKeyStore.putFallbackApiKey(fallbackKey); fallbackKeyPresent.value=true }
        val primaryKey = key.takeIf { it.isNotBlank() } ?: requireNotNull(c.secureKeyStore.getApiKey()) { "主要接口Key读取失败" }
        val primary = c.aiClient.testConnection(savedBase, savedModel, primaryKey)
        val fallback = if (fallbackEnabled) {
            val savedFallback = fallbackKey.takeIf { it.isNotBlank() } ?: requireNotNull(c.secureKeyStore.getFallbackApiKey()) { "请填写备用官方API Key" }
            c.aiClient.testConnection(DeepSeekConfig.BASE_URL, savedFallbackModel, savedFallback)
        } else Result.success("未启用")
        if (primary.isSuccess && fallback.isSuccess) {
            val detail = if (fallbackEnabled) "已连接 · ${displayModel(savedModel)} · 备用接口正常" else "已连接 · ${displayModel(savedModel)}"
            c.settings.update { it.copy(aiConnectionVerified=true, aiConnectionStatus="CONNECTED", aiConnectionDetail=detail) }
            message.value="配置已保存，连接成功"
        } else {
            val detail = buildString {
                if (primary.isFailure) append("主要接口：${primary.exceptionOrNull()?.message ?: "失败"}")
                if (fallback.isFailure) { if (isNotEmpty()) append("；"); append("备用接口：${fallback.exceptionOrNull()?.message ?: "失败"}") }
            }
            c.settings.update { it.copy(aiConnectionVerified=false, aiConnectionStatus="FAILED", aiConnectionDetail=detail) }
            message.value=detail
        }
    }

    fun clearAiConfiguration() = work { c.secureKeyStore.clearAll(); apiKeyPresent.value=false; fallbackKeyPresent.value=false;c.settings.update { it.copy(aiConnectionVerified=false,aiConnectionStatus="UNCONFIGURED",aiConnectionDetail="尚未配置",aiRuntimeOffline=false,petEnabled=false,petVisibility=PetVisibility.DISABLED) };com.justenough.planner.pet.AiPetService.disable(getApplication()) }
    fun setPetVisibility(value:String)=work { when(value){PetVisibility.VISIBLE->{c.settings.update{it.copy(petVisibility=value,petEnabled=true)};com.justenough.planner.pet.AiPetService.show(getApplication())};PetVisibility.HIDDEN->com.justenough.planner.pet.AiPetService.hide(getApplication());else->com.justenough.planner.pet.AiPetService.disable(getApplication())} }
    fun deleteExpiredAiHistory() = work { c.repository.pruneAiMessages(System.currentTimeMillis()-90L*24*60*60*1000); expiredAiMessageCount.value=0 }
    fun extendAiHistory() = work { c.settings.update { it.copy(historyRetentionUntil=System.currentTimeMillis()+90L*24*60*60*1000,historyReminderAt=0) }; expiredAiMessageCount.value=0 }
    fun remindHistoryLater() = work { c.settings.update { it.copy(historyReminderAt=System.currentTimeMillis()+7L*24*60*60*1000) }; expiredAiMessageCount.value=0 }
    fun exportExpiredAiHistory(uri: Uri) = busyWork {
        val cutoff = System.currentTimeMillis()-90L*24*60*60*1000
        val values = c.repository.aiMessages().filter { it.createdAt < cutoff }
        withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openOutputStream(uri,"w")!!.bufferedWriter(Charsets.UTF_8).use { it.write(Json.encodeToString(values)) } }
        c.repository.pruneAiMessages(cutoff); expiredAiMessageCount.value=0; message.value="AI历史已导出并删除到期记录"
    }
    fun exportBackup(uri: Uri, password: String) = busyWork { c.backupManager.export(uri, password.toCharArray()); message.value="加密备份已导出" }
    fun inspectBackup(uri: Uri, password: String) = busyWork { backupPreview.value=c.backupManager.inspect(uri,password.toCharArray()); message.value="备份校验通过，请确认摘要" }
    fun restoreBackup(uri: Uri, password: String) = busyWork { c.backupManager.restore(uri,password.toCharArray()); backupPreview.value=null; c.scheduler.rescheduleAll(); message.value="备份恢复完成" }
    fun clearBackupPreview(){backupPreview.value=null}
    fun sendAiMessage(text: String) = busyWork {
        require(text.isNotBlank()); c.repository.addAiMessage("user", text.trim())
        c.aiClient.chat(text.trim()).fold({ c.repository.addAiMessage("assistant", it, source=c.aiClient.lastSource) }, { message.value=it.message ?: "AI回复失败" })
    }
    fun analyze(text: String) = busyWork { c.aiClient.propose(text).fold({ proposal.value=it }, { message.value=it.message }) }
    fun applyProposal() = busyWork { proposal.value?.let { c.aiClient.apply(it); c.repository.addAiMessage("assistant", "已按你勾选的建议更新计划。", "SYSTEM"); proposal.value=null; c.scheduler.rescheduleAll() } }
    fun applySelectedProposal(selected: Set<Int>) = busyWork {
        proposal.value?.let { value ->
            val filtered = value.copy(
                plans = value.plans.filterIndexed { index, _ -> index in selected },
                planChanges = value.planChanges.filterIndexed { index, _ -> 10_000 + index in selected },
                schedules = value.schedules.filterIndexed { index, _ -> 20_000 + index in selected },
                scheduleChanges = value.scheduleChanges.filterIndexed { index, _ -> 30_000 + index in selected },
            )
            c.aiClient.apply(filtered); c.repository.addAiMessage("assistant", "已应用你勾选的建议。", "SYSTEM"); proposal.value=null; c.scheduler.rescheduleAll()
        }
    }
    fun dismissProposal() { proposal.value=null }
    fun scheduleTest() = work { c.scheduler.scheduleTest(); message.value="已安排10秒后的测试提醒" }

    private fun work(block: suspend () -> Unit) = viewModelScope.launch { runCatching { block(); today.value=c.repository.todaySnapshot(); WidgetUpdater.update(getApplication()); if(c.settings.state.first().pendingAnalysis) com.justenough.planner.ai.AiAnalysisWorker.debounce(getApplication()) }.onFailure { message.value=it.message ?: "操作失败" } }
    private fun busyWork(block: suspend () -> Unit) = viewModelScope.launch { busy.value=true; try { block() } catch(e:Throwable) { message.value=e.message ?: "操作失败" } finally { busy.value=false; today.value=runCatching{c.repository.todaySnapshot()}.getOrNull();WidgetUpdater.update(getApplication()) } }
    private fun displayModel(value:String)=when(value){DeepSeekConfig.FLASH->"DeepSeek V4 Flash";DeepSeekConfig.PRO->"DeepSeek V4 Pro";else->value}
}
