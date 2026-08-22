package com.justenough.planner.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

private val Context.settingsDataStore by preferencesDataStore("settings")

@Serializable
data class SettingsState(
    val reviewMinute: Int = 21 * 60 + 30,
    val widgetTextMode: String = "SYSTEM",
    val widgetFontScale: Float = 1f,
    val widgetContrast: Float = 1f,
    val subtleBacking: Boolean = false,
    val candidateOffset: Int = 0,
    val aiProvider: String = "DEEPSEEK",
    val aiBaseUrl: String = "https://api.deepseek.com",
    val aiModel: String = "deepseek-v4-flash",
    val fallbackEnabled: Boolean = false,
    val fallbackBaseUrl: String = "https://api.deepseek.com",
    val fallbackModel: String = "deepseek-v4-flash",
    val aiConsent: Boolean = false,
    val aiConnectionVerified: Boolean = false,
    val aiConnectionStatus: String = "UNCONFIGURED",
    val aiConnectionDetail: String = "尚未配置",
    val aiRuntimeOffline: Boolean = false,
    val autoAnalysisLimit: Int = 20,
    val wifiOnlyAnalysis: Boolean = false,
    val pendingAnalysis: Boolean = false,
    val analysisCount: Int = 0,
    val analysisEpochDay: Long = Long.MIN_VALUE,
    val petEnabled: Boolean = false,
    val petVisibility: String = PetVisibility.DISABLED,
    val petSize: Int = 64,
    val petLocked: Boolean = false,
    val petPromptDismissed: Boolean = false,
    val completionVibration: Boolean = true,
    val startSound: String = "LEAF",
    val completionSound: String = "WARM",
    val taskRingtone: String = "BUILTIN",
    val customRingtoneUri: String = "",
    val ringtoneDurationSeconds: Int = 120,
    val ringtoneVibrate: Boolean = true,
    val ringtoneVolumeRamp: Boolean = true,
    val feedbackSoundEnabled: Boolean = true,
    val taskOverlayEnabled: Boolean = true,
    val taskOverlaySeconds: Int = 8,
    val petActiveMessages: Boolean = true,
    val petAskQuestions: Boolean = true,
    val petBubbleSeconds: Int = 8,
    val petCompactChatEnabled: Boolean = true,
    val petSoundEnabled: Boolean = false,
    val petQuestionLimit: Int = 10,
    val petQuietStartMinute: Int = 22 * 60 + 30,
    val petQuietEndMinute: Int = 7 * 60,
    val oemOverride: String = "AUTO",
    val historyRetentionUntil: Long = 0,
    val historyReminderAt: Long = 0,
    val onboardingComplete: Boolean = false,
    val anchorSyncEpochDay: Long = Long.MIN_VALUE,
    val reminderTestScheduledAt: Long = 0,
    val reminderTestCompletedAt: Long = 0,
)

object PetVisibility { const val VISIBLE="VISIBLE";const val HIDDEN="HIDDEN";const val DISABLED="DISABLED";fun isVisible(value:String)=value==VISIBLE }
object DeepSeekConfig { const val BASE_URL="https://api.deepseek.com";const val FLASH="deepseek-v4-flash";const val PRO="deepseek-v4-pro";val MODELS=setOf(FLASH,PRO);fun model(value:String)=value.takeIf{it in MODELS}?:FLASH }

class AppSettings(private val context: Context) {
    private object K {
        val review = intPreferencesKey("review"); val mode = stringPreferencesKey("widget_text_mode"); val scale = floatPreferencesKey("font_scale")
        val contrast = floatPreferencesKey("contrast"); val backing = booleanPreferencesKey("backing"); val offset = intPreferencesKey("candidate_offset")
        val provider = stringPreferencesKey("ai_provider"); val base = stringPreferencesKey("ai_base_url"); val model = stringPreferencesKey("ai_model")
        val fallbackEnabled = booleanPreferencesKey("fallback_enabled"); val fallbackBase = stringPreferencesKey("fallback_base"); val fallbackModel = stringPreferencesKey("fallback_model")
        val consent = booleanPreferencesKey("ai_consent"); val verified = booleanPreferencesKey("ai_connection_verified"); val status = stringPreferencesKey("ai_status"); val detail = stringPreferencesKey("ai_detail"); val runtimeOffline = booleanPreferencesKey("ai_runtime_offline")
        val autoLimit = intPreferencesKey("auto_limit"); val wifi = booleanPreferencesKey("wifi_only"); val pending = booleanPreferencesKey("pending_analysis")
        val count = intPreferencesKey("analysis_count"); val countDay = longPreferencesKey("analysis_day")
        val pet = booleanPreferencesKey("pet_enabled"); val petVisibility=stringPreferencesKey("pet_visibility"); val petSize = intPreferencesKey("pet_size"); val petLocked = booleanPreferencesKey("pet_locked"); val petDismissed = booleanPreferencesKey("pet_prompt_dismissed"); val vibration = booleanPreferencesKey("completion_vibration")
        val startSound=stringPreferencesKey("start_sound");val completionSound=stringPreferencesKey("completion_sound");val questionLimit=intPreferencesKey("pet_question_limit");val quietStart=intPreferencesKey("pet_quiet_start");val quietEnd=intPreferencesKey("pet_quiet_end")
        val taskRingtone=stringPreferencesKey("task_ringtone");val customRingtone=stringPreferencesKey("custom_ringtone_uri");val ringtoneDuration=intPreferencesKey("ringtone_duration_seconds");val ringtoneVibrate=booleanPreferencesKey("ringtone_vibrate");val ringtoneRamp=booleanPreferencesKey("ringtone_volume_ramp");val feedbackSound=booleanPreferencesKey("feedback_sound_enabled");val overlayEnabled=booleanPreferencesKey("task_overlay_enabled");val overlaySeconds=intPreferencesKey("task_overlay_seconds")
        val petActive=booleanPreferencesKey("pet_active_messages");val petAsk=booleanPreferencesKey("pet_ask_questions");val petBubbleSeconds=intPreferencesKey("pet_bubble_seconds");val petCompact=booleanPreferencesKey("pet_compact_chat_enabled");val petSound=booleanPreferencesKey("pet_sound_enabled")
        val oem = stringPreferencesKey("oem_override"); val onboarding = booleanPreferencesKey("onboarding"); val anchor = longPreferencesKey("anchor_sync_day")
        val retention = longPreferencesKey("history_retention_until"); val historyReminder = longPreferencesKey("history_reminder_at")
        val testScheduled = longPreferencesKey("reminder_test_scheduled"); val testCompleted = longPreferencesKey("reminder_test_completed")
    }

    val state: Flow<SettingsState> = context.settingsDataStore.data.map(::read)

    suspend fun update(transform: (SettingsState) -> SettingsState) {
        context.settingsDataStore.edit { p -> write(p, transform(read(p))) }
    }

    private fun read(p: androidx.datastore.preferences.core.Preferences) = SettingsState(
        reviewMinute = p[K.review] ?: 1290, widgetTextMode = p[K.mode] ?: "SYSTEM", widgetFontScale = p[K.scale] ?: 1f,
        widgetContrast = p[K.contrast] ?: 1f, subtleBacking = p[K.backing] ?: false, candidateOffset = p[K.offset] ?: 0,
        aiProvider = p[K.provider] ?: "DEEPSEEK", aiBaseUrl = if((p[K.provider]?:"DEEPSEEK")=="DEEPSEEK")DeepSeekConfig.BASE_URL else p[K.base] ?: DeepSeekConfig.BASE_URL,
        aiModel = if((p[K.provider]?:"DEEPSEEK")=="DEEPSEEK")DeepSeekConfig.model(p[K.model]?:DeepSeekConfig.FLASH)else p[K.model]?:DeepSeekConfig.FLASH,
        fallbackEnabled = p[K.fallbackEnabled] ?: false, fallbackBaseUrl = DeepSeekConfig.BASE_URL, fallbackModel = DeepSeekConfig.model(p[K.fallbackModel]?:DeepSeekConfig.FLASH),
        aiConsent = p[K.consent] ?: false, aiConnectionVerified = p[K.verified] ?: false, aiConnectionStatus = p[K.status] ?: "UNCONFIGURED", aiConnectionDetail = p[K.detail] ?: "尚未配置", aiRuntimeOffline = p[K.runtimeOffline] ?: false,
        autoAnalysisLimit = p[K.autoLimit] ?: 20, wifiOnlyAnalysis = p[K.wifi] ?: false, pendingAnalysis = p[K.pending] ?: false,
        analysisCount = p[K.count] ?: 0, analysisEpochDay = p[K.countDay] ?: Long.MIN_VALUE,
        petEnabled = p[K.petVisibility]?.let(PetVisibility::isVisible) ?: (p[K.pet] ?: false), petVisibility=p[K.petVisibility]?:if(p[K.pet]==true)PetVisibility.VISIBLE else PetVisibility.DISABLED, petSize = p[K.petSize] ?: 64, petLocked = p[K.petLocked] ?: false, petPromptDismissed = p[K.petDismissed] ?: false, completionVibration = p[K.vibration] ?: true,
        startSound=p[K.startSound]?:"LEAF",completionSound=p[K.completionSound]?:"WARM",petQuestionLimit=(p[K.questionLimit]?:10).coerceIn(0,20),petQuietStartMinute=p[K.quietStart]?:1350,petQuietEndMinute=p[K.quietEnd]?:420,
        taskRingtone=p[K.taskRingtone]?:"BUILTIN",customRingtoneUri=p[K.customRingtone]?:"",ringtoneDurationSeconds=(p[K.ringtoneDuration]?:120).coerceIn(0,3600),ringtoneVibrate=p[K.ringtoneVibrate]?:true,ringtoneVolumeRamp=p[K.ringtoneRamp]?:true,feedbackSoundEnabled=p[K.feedbackSound]?:true,taskOverlayEnabled=p[K.overlayEnabled]?:true,taskOverlaySeconds=(p[K.overlaySeconds]?:8).coerceIn(0,3600),
        petActiveMessages=p[K.petActive]?:true,petAskQuestions=p[K.petAsk]?:true,petBubbleSeconds=(p[K.petBubbleSeconds]?:8).coerceIn(3,60),petCompactChatEnabled=p[K.petCompact]?:true,petSoundEnabled=p[K.petSound]?:false,
        oemOverride = p[K.oem] ?: "AUTO", historyRetentionUntil = p[K.retention] ?: 0, historyReminderAt = p[K.historyReminder] ?: 0,
        onboardingComplete = p[K.onboarding] ?: false, anchorSyncEpochDay = p[K.anchor] ?: Long.MIN_VALUE,
        reminderTestScheduledAt = p[K.testScheduled] ?: 0, reminderTestCompletedAt = p[K.testCompleted] ?: 0,
    )

    private fun write(p: MutablePreferences, s: SettingsState) {
        p[K.review]=s.reviewMinute; p[K.mode]=s.widgetTextMode; p[K.scale]=s.widgetFontScale; p[K.contrast]=s.widgetContrast; p[K.backing]=s.subtleBacking; p[K.offset]=s.candidateOffset
        p[K.provider]=s.aiProvider;p[K.base]=if(s.aiProvider=="DEEPSEEK")DeepSeekConfig.BASE_URL else s.aiBaseUrl;p[K.model]=if(s.aiProvider=="DEEPSEEK")DeepSeekConfig.model(s.aiModel)else s.aiModel;p[K.fallbackEnabled]=s.fallbackEnabled;p[K.fallbackBase]=DeepSeekConfig.BASE_URL;p[K.fallbackModel]=DeepSeekConfig.model(s.fallbackModel)
        p[K.consent]=s.aiConsent; p[K.verified]=s.aiConnectionVerified; p[K.status]=s.aiConnectionStatus; p[K.detail]=s.aiConnectionDetail; p[K.runtimeOffline]=s.aiRuntimeOffline; p[K.autoLimit]=s.autoAnalysisLimit; p[K.wifi]=s.wifiOnlyAnalysis
        p[K.pending]=s.pendingAnalysis;p[K.count]=s.analysisCount;p[K.countDay]=s.analysisEpochDay;p[K.petVisibility]=s.petVisibility;p[K.pet]=PetVisibility.isVisible(s.petVisibility);p[K.petSize]=s.petSize;p[K.petLocked]=s.petLocked;p[K.petDismissed]=s.petPromptDismissed;p[K.vibration]=s.completionVibration;p[K.startSound]=s.startSound;p[K.completionSound]=s.completionSound;p[K.questionLimit]=s.petQuestionLimit.coerceIn(0,20);p[K.quietStart]=s.petQuietStartMinute;p[K.quietEnd]=s.petQuietEndMinute
        p[K.taskRingtone]=s.taskRingtone;p[K.customRingtone]=s.customRingtoneUri;p[K.ringtoneDuration]=s.ringtoneDurationSeconds.coerceIn(0,3600);p[K.ringtoneVibrate]=s.ringtoneVibrate;p[K.ringtoneRamp]=s.ringtoneVolumeRamp;p[K.feedbackSound]=s.feedbackSoundEnabled;p[K.overlayEnabled]=s.taskOverlayEnabled;p[K.overlaySeconds]=s.taskOverlaySeconds.coerceIn(0,3600);p[K.petActive]=s.petActiveMessages;p[K.petAsk]=s.petAskQuestions;p[K.petBubbleSeconds]=s.petBubbleSeconds.coerceIn(3,60);p[K.petCompact]=s.petCompactChatEnabled;p[K.petSound]=s.petSoundEnabled
        p[K.oem]=s.oemOverride; p[K.retention]=s.historyRetentionUntil; p[K.historyReminder]=s.historyReminderAt; p[K.onboarding]=s.onboardingComplete; p[K.anchor]=s.anchorSyncEpochDay; p[K.testScheduled]=s.reminderTestScheduledAt; p[K.testCompleted]=s.reminderTestCompletedAt
    }
}
