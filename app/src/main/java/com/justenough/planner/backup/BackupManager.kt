package com.justenough.planner.backup

import android.content.Context
import android.net.Uri
import com.justenough.planner.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable data class BackupPayload(val version: Int = 4, val createdAt: Long, val database: DatabaseSnapshot, val settings: SettingsState)

class BackupManager(private val context: Context, private val repository: PlannerRepository, private val settings: AppSettings) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun export(uri: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        try {
            require(password.size >= 8) { "备份密码至少8位" }
            val plain = json.encodeToString(BackupPayload(createdAt = Instant.now().toEpochMilli(), database = repository.snapshot(), settings = settings.state.first())).toByteArray()
            val salt = ByteArray(16).also(SecureRandom()::nextBytes); val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv)) }
            context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(MAGIC); it.write(salt); it.write(iv); it.write(cipher.doFinal(plain)) }
        } finally { password.fill('\u0000') }
    }

    suspend fun inspect(uri: Uri, password: CharArray) = decrypt(uri, password).also(::validate)

    suspend fun restore(uri: Uri, password: CharArray) {
        val payload = decrypt(uri, password); validate(payload)
        val oldDb = repository.snapshot(); val oldSettings = settings.state.first()
        try {
            repository.replaceAll(payload.database)
            settings.update { payload.settings.copy(
                aiConsent = false, aiProvider = oldSettings.aiProvider, aiBaseUrl = oldSettings.aiBaseUrl, aiModel = oldSettings.aiModel,
                fallbackEnabled = oldSettings.fallbackEnabled, fallbackBaseUrl = oldSettings.fallbackBaseUrl, fallbackModel = oldSettings.fallbackModel,
                aiConnectionVerified = oldSettings.aiConnectionVerified, aiConnectionStatus = oldSettings.aiConnectionStatus,
                anchorSyncEpochDay = Long.MIN_VALUE, reminderTestScheduledAt = 0, reminderTestCompletedAt = 0,
            ) }
        } catch (failure: Throwable) {
            runCatching { repository.replaceAll(oldDb) }; runCatching { settings.update { oldSettings } }; throw failure
        }
    }

    private suspend fun decrypt(uri: Uri, password: CharArray): BackupPayload = withContext(Dispatchers.IO) {
        try {
            require(password.size >= 8) { "备份密码至少8位" }
            val bytes = context.contentResolver.openInputStream(uri)!!.use { input ->
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE); var total = 0
                    while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= MAX) { "备份文件过大" }; output.write(buffer, 0, n) }
                    output.toByteArray()
                }
            }
            require(bytes.size > MAGIC.size + 28 && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "不是有效的刚刚好备份" }
            val salt = bytes.copyOfRange(MAGIC.size, MAGIC.size + 16); val iv = bytes.copyOfRange(MAGIC.size + 16, MAGIC.size + 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv)) }
            val text = String(cipher.doFinal(bytes.copyOfRange(MAGIC.size + 28, bytes.size)))
            runCatching { normalize(json.decodeFromString<BackupPayload>(text)) }.getOrElse { convertLegacy(json.decodeFromString<LegacyPayload>(text)) }
        } finally { password.fill('\u0000') }
    }

    private fun convertLegacy(old: LegacyPayload): BackupPayload {
        val plans = old.database.actions.map { PlanEntity(id=it.id,name=it.name,minimumGoal=it.minimumGoal,estimatedMinutes=it.estimatedMinutes,enabled=it.enabled,archived=false,appPackage=it.appPackage,appClass=it.appClass,lastChosenEpochMillis=it.lastChosenEpochMillis,reviewState=it.reviewState,scheduledEpochDay=it.scheduledEpochDay,dismissedEpochDay=it.dismissedEpochDay) }
        val refs = old.database.blockActions.map { SchedulePlanCrossRef(it.blockId, it.actionId) }
        val runs = old.database.runs.map { TaskRunEntity(it.id, it.actionId, it.startedAt, it.endedAt, it.pausedAt, it.pausedDurationMillis, it.plannedMinutes, it.status, it.actualLoad, it.fulfillmentPoints) }
        val anchors = old.database.anchorOccurrences.map { AnchorOccurrenceEntity(it.id, it.blockId, it.actionId, it.occurrenceEpochDay, it.scheduledAt, it.status, it.handledAt) }
        return BackupPayload(4, old.createdAt, DatabaseSnapshot(plans, old.database.blocks, refs, runs, old.database.checkIns, anchors), old.settings)
    }

    private fun normalize(value: BackupPayload): BackupPayload = value.copy(version=4,database=value.database.copy(plans=value.database.plans.mapIndexed{index,plan->if(PlanQuadrants.isValid(plan.quadrant))plan else plan.copy(quadrant="",matrixOrder=index)}))

    private fun validate(p: BackupPayload) {
        require(p.version in 2..4); require(p.createdAt in 1..System.currentTimeMillis() + 86_400_000)
        val planIds = p.database.plans.map { it.id }.toSet(); val blockIds = p.database.blocks.map { it.id }.toSet()
        require(planIds.size == p.database.plans.size && blockIds.size == p.database.blocks.size) { "备份含重复ID" }
        require(p.database.plans.all { it.name.isNotBlank() && it.minimumGoal.isNotBlank() && it.estimatedMinutes in 1..1440 && (it.archived || PlanQuadrants.isValid(it.quadrant) || it.quadrant.isBlank()) })
        require(p.database.blocks.all { it.kind in setOf(PlanKinds.ANCHOR, PlanKinds.CHOICE) && it.startMinute in 0..1439 && it.endMinute in 1..1440 && it.endMinute > it.startMinute })
        require(p.database.schedulePlans.all { it.blockId in blockIds && it.planId in planIds }); require(p.database.runs.all { it.planId in planIds })
        require(p.database.anchorOccurrences.all { it.blockId in blockIds && it.planId in planIds })
        val runIds=p.database.runs.map{it.id}.toSet();require(p.database.feedback.all{it.planId in planIds&&(it.runId==null||it.runId in runIds)&&it.kind in setOf(FeedbackKinds.ANTICIPATED_DIFFICULTY,FeedbackKinds.FATIGUE,FeedbackKinds.EASE,FeedbackKinds.MOOD)})
    }

    private fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, 210_000, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") } finally { spec.clearPassword() }
    }

    companion object { private val MAGIC = "JEPLAN1\n".toByteArray(Charsets.US_ASCII); private const val MAX = 100 * 1024 * 1024 }
}

@Serializable private data class LegacyPayload(val version: Int = 1, val createdAt: Long, val database: LegacyDatabase, val settings: SettingsState)
@Serializable private data class LegacyDatabase(
    val areas: List<LegacyArea>, val projects: List<LegacyProject>, val actions: List<LegacyAction>, val blocks: List<TimeBlockEntity>,
    val blockActions: List<LegacyRef>, val runs: List<LegacyRun>, val checkIns: List<EnergyCheckInEntity>, val anchorOccurrences: List<LegacyAnchor> = emptyList(),
)
@Serializable private data class LegacyArea(val id: Long, val name: String, val sortOrder: Int = 0)
@Serializable private data class LegacyProject(val id: Long, val areaId: Long, val name: String, val sortOrder: Int = 0)
@Serializable private data class LegacyAction(val id: Long, val projectId: Long, val name: String, val minimumGoal: String, val estimatedMinutes: Int = 20, val estimatedLoad: Int = 2, val enabled: Boolean = true, val appPackage: String? = null, val appClass: String? = null, val lastChosenEpochMillis: Long? = null, val reviewState: String = ReviewStates.NONE, val scheduledEpochDay: Long? = null, val dismissedEpochDay: Long? = null)
@Serializable private data class LegacyRef(val blockId: Long, val actionId: Long)
@Serializable private data class LegacyRun(val id: Long, val actionId: Long, val startedAt: Long, val endedAt: Long? = null, val pausedAt: Long? = null, val pausedDurationMillis: Long = 0, val plannedMinutes: Int, val status: String = RunStatuses.ACTIVE, val actualLoad: Int? = null, val fulfillmentPoints: Int? = null)
@Serializable private data class LegacyAnchor(val id: Long, val blockId: Long, val actionId: Long, val occurrenceEpochDay: Long, val scheduledAt: Long, val status: String = AnchorOccurrenceStatuses.PENDING, val handledAt: Long? = null)
