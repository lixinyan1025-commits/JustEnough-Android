package com.justenough.planner.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

object PlanKinds { const val ANCHOR = "ANCHOR"; const val CHOICE = "CHOICE" }
object PlanQuadrants {
    const val IMPORTANT_URGENT = "IMPORTANT_URGENT"
    const val IMPORTANT_NOT_URGENT = "IMPORTANT_NOT_URGENT"
    const val NOT_IMPORTANT_URGENT = "NOT_IMPORTANT_URGENT"
    const val NOT_IMPORTANT_NOT_URGENT = "NOT_IMPORTANT_NOT_URGENT"
    val ALL = listOf(IMPORTANT_URGENT, IMPORTANT_NOT_URGENT, NOT_IMPORTANT_URGENT, NOT_IMPORTANT_NOT_URGENT)
    fun isValid(value: String) = value in ALL
}
object RunStatuses { const val ACTIVE = "ACTIVE"; const val PAUSED = "PAUSED"; const val COMPLETED = "COMPLETED"; const val ENDED = "ENDED"; const val ABANDONED = "ABANDONED" }
object ReviewStates { const val NONE = "NONE"; const val PENDING = "PENDING"; const val POOL = "POOL"; const val RESCHEDULED = "RESCHEDULED" }
object AnchorOccurrenceStatuses { const val PENDING = "PENDING"; const val COMPLETED = "COMPLETED"; const val RESCHEDULED = "RESCHEDULED"; const val SKIPPED = "SKIPPED" }

@Serializable
@Entity(tableName = "plans", indices = [Index("archived"), Index("enabled")])
data class PlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val minimumGoal: String,
    val estimatedMinutes: Int = 20,
    val enabled: Boolean = true,
    val archived: Boolean = false,
    val appPackage: String? = null,
    val appClass: String? = null,
    val lastChosenEpochMillis: Long? = null,
    val reviewState: String = ReviewStates.NONE,
    val scheduledEpochDay: Long? = null,
    val dismissedEpochDay: Long? = null,
    val quadrant: String = "",
    val matrixOrder: Int = 0,
)

@Serializable
@Entity(tableName = "time_blocks")
data class TimeBlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    val startMinute: Int,
    val endMinute: Int,
    val weekdayMask: Int = 127,
    val dateEpochDay: Long? = null,
    val reminderEnabled: Boolean = true,
    val enabled: Boolean = true,
    val startSoundEnabled: Boolean = true,
    val autoStartEnabled: Boolean = true,
    val autoOpenAppEnabled: Boolean = false,
    val vibrateEnabled: Boolean = true,
)

@Serializable
@Entity(
    tableName = "schedule_plans",
    primaryKeys = ["blockId", "planId"],
    foreignKeys = [
        ForeignKey(entity = TimeBlockEntity::class, parentColumns = ["id"], childColumns = ["blockId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PlanEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("blockId"), Index("planId")],
)
data class SchedulePlanCrossRef(val blockId: Long, val planId: Long)

@Serializable
@Entity(
    tableName = "task_runs",
    foreignKeys = [ForeignKey(entity = PlanEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("planId"), Index("status")],
)
data class TaskRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val startedAt: Long,
    val endedAt: Long? = null,
    val pausedAt: Long? = null,
    val pausedDurationMillis: Long = 0,
    val plannedMinutes: Int,
    val status: String = RunStatuses.ACTIVE,
    val actualLoad: Int? = null,
    val fulfillmentPoints: Int? = null,
)

@Serializable @Entity(tableName = "energy_checkins")
data class EnergyCheckInEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val recordedAt: Long, val level: Int, val blockId: Long? = null)

@Serializable
@Entity(
    tableName = "anchor_occurrences",
    foreignKeys = [
        ForeignKey(entity = TimeBlockEntity::class, parentColumns = ["id"], childColumns = ["blockId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PlanEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("planId"), Index(value = ["blockId", "planId", "occurrenceEpochDay"], unique = true), Index("status")],
)
data class AnchorOccurrenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val blockId: Long,
    val planId: Long,
    val occurrenceEpochDay: Long,
    val scheduledAt: Long,
    val status: String = AnchorOccurrenceStatuses.PENDING,
    val handledAt: Long? = null,
)

@Serializable
@Entity(tableName = "ai_messages", indices = [Index("createdAt"), Index("kind")])
data class AiMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val content: String,
    val kind: String = "CHAT",
    val source: String = "LOCAL",
    val createdAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
)

object FeedbackKinds { const val ANTICIPATED_DIFFICULTY="ANTICIPATED_DIFFICULTY";const val FATIGUE="FATIGUE";const val EASE="EASE";const val MOOD="MOOD" }
object PetPromptStatuses { const val PENDING="PENDING";const val ANSWERED="ANSWERED";const val DISMISSED="DISMISSED";const val EXPIRED="EXPIRED" }

@Serializable
@Entity(
    tableName = "task_feedback",
    foreignKeys = [
        ForeignKey(entity=PlanEntity::class,parentColumns=["id"],childColumns=["planId"],onDelete=ForeignKey.CASCADE),
        ForeignKey(entity=TaskRunEntity::class,parentColumns=["id"],childColumns=["runId"],onDelete=ForeignKey.CASCADE),
    ],
    indices=[Index("planId"),Index("runId"),Index("createdAt"),Index("kind")],
)
data class TaskFeedbackEntity(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val planId:Long,
    val runId:Long?=null,
    val kind:String,
    val score:Int?=null,
    val answerCode:String?=null,
    val answerText:String?=null,
    val source:String="LOCAL",
    val createdAt:Long=System.currentTimeMillis(),
)

@Serializable
@Entity(tableName="pet_prompts",indices=[Index("status"),Index("createdAt"),Index("priority")])
data class PetPromptEntity(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val kind:String,
    val text:String,
    val options:String="",
    val status:String=PetPromptStatuses.PENDING,
    val priority:Int=0,
    val planId:Long?=null,
    val runId:Long?=null,
    val source:String="LOCAL",
    val createdAt:Long=System.currentTimeMillis(),
    val expiresAt:Long?=null,
    val answeredAt:Long?=null,
)

@Serializable
@Entity(tableName = "diagnostic_events", indices = [Index("createdAt"), Index("type")])
data class DiagnosticEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val manufacturer: String,
    val androidVersion: String,
    val detail: String,
    val createdAt: Long = System.currentTimeMillis(),
)

data class PlanDetails(
    val id: Long, val name: String, val minimumGoal: String, val estimatedMinutes: Int,
    val enabled: Boolean, val archived: Boolean, val appPackage: String?, val appClass: String?,
    val lastChosenEpochMillis: Long?, val reviewState: String, val scheduledEpochDay: Long?, val dismissedEpochDay: Long?,
)
data class BlockWithPlans(val block: TimeBlockEntity, val plans: List<PlanDetails>)
data class ActiveRunDetails(
    val runId: Long, val planId: Long, val name: String, val minimumGoal: String, val plannedMinutes: Int,
    val startedAt: Long, val pausedAt: Long?, val pausedDurationMillis: Long, val status: String,
)
