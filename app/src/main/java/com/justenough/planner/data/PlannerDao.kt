package com.justenough.planner.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Dao
interface PlannerDao {
    @Query("SELECT * FROM plans ORDER BY archived, quadrant, matrixOrder, name") fun observePlans(): Flow<List<PlanEntity>>
    @Query("SELECT * FROM time_blocks ORDER BY startMinute") fun observeBlocks(): Flow<List<TimeBlockEntity>>
    @Query("SELECT * FROM schedule_plans") fun observeSchedulePlans(): Flow<List<SchedulePlanCrossRef>>
    @Query("SELECT * FROM task_runs ORDER BY startedAt DESC") fun observeRuns(): Flow<List<TaskRunEntity>>
    @Query("SELECT * FROM ai_messages ORDER BY createdAt") fun observeAiMessages(): Flow<List<AiMessageEntity>>
    @Query("SELECT * FROM ai_messages ORDER BY createdAt") suspend fun getAiMessages(): List<AiMessageEntity>
    @Query("SELECT * FROM diagnostic_events ORDER BY createdAt DESC LIMIT 50") fun observeDiagnostics(): Flow<List<DiagnosticEventEntity>>
    @Query("SELECT * FROM task_feedback ORDER BY createdAt DESC") fun observeFeedback(): Flow<List<TaskFeedbackEntity>>

    @Insert suspend fun insertPlan(value: PlanEntity): Long
    @Insert suspend fun insertBlock(value: TimeBlockEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSchedulePlan(value: SchedulePlanCrossRef)
    @Insert suspend fun insertRun(value: TaskRunEntity): Long
    @Insert suspend fun insertCheckIn(value: EnergyCheckInEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAnchorOccurrence(value: AnchorOccurrenceEntity): Long
    @Insert suspend fun insertAiMessage(value: AiMessageEntity): Long
    @Insert suspend fun insertDiagnostic(value: DiagnosticEventEntity): Long
    @Insert suspend fun insertFeedback(value: TaskFeedbackEntity): Long
    @Insert suspend fun insertPetPrompt(value: PetPromptEntity): Long
    @Update suspend fun updatePlan(value: PlanEntity)
    @Update suspend fun updateBlock(value: TimeBlockEntity)
    @Update suspend fun updateRun(value: TaskRunEntity)
    @Update suspend fun updatePetPrompt(value: PetPromptEntity)
    @Delete suspend fun deletePlan(value: PlanEntity)
    @Delete suspend fun deleteBlock(value: TimeBlockEntity)

    @Query("SELECT COUNT(*) FROM plans") suspend fun planCount(): Int
    @Query("SELECT * FROM plans ORDER BY quadrant, matrixOrder, name") suspend fun getPlans(): List<PlanEntity>
    @Query("SELECT * FROM time_blocks ORDER BY startMinute") suspend fun getBlocks(): List<TimeBlockEntity>
    @Query("SELECT * FROM schedule_plans") suspend fun getSchedulePlans(): List<SchedulePlanCrossRef>
    @Query("SELECT * FROM task_runs ORDER BY startedAt DESC") suspend fun getRuns(): List<TaskRunEntity>
    @Query("SELECT * FROM task_feedback ORDER BY createdAt DESC") suspend fun getFeedback(): List<TaskFeedbackEntity>
    @Query("SELECT * FROM pet_prompts WHERE status='PENDING' AND (expiresAt IS NULL OR expiresAt>:now) ORDER BY priority DESC, createdAt DESC LIMIT 1") suspend fun getPendingPetPrompt(now:Long): PetPromptEntity?
    @Query("SELECT * FROM pet_prompts WHERE id=:id") suspend fun getPetPrompt(id:Long): PetPromptEntity?
    @Query("SELECT COUNT(*) FROM pet_prompts WHERE createdAt>=:start") suspend fun petQuestionCount(start:Long):Int
    @Query("SELECT COUNT(*) FROM pet_prompts WHERE status='DISMISSED' AND createdAt>=:start") suspend fun dismissedPetQuestionCount(start:Long):Int
    @Query("SELECT * FROM energy_checkins ORDER BY recordedAt DESC") suspend fun getCheckIns(): List<EnergyCheckInEntity>
    @Query("SELECT * FROM plans WHERE id=:id") suspend fun getPlan(id: Long): PlanEntity?
    @Query("SELECT * FROM time_blocks WHERE id=:id") suspend fun getBlock(id: Long): TimeBlockEntity?
    @Query("SELECT * FROM task_runs WHERE id=:id") suspend fun getRun(id: Long): TaskRunEntity?
    @Query("SELECT * FROM task_runs WHERE status IN ('ACTIVE','PAUSED') ORDER BY startedAt DESC LIMIT 1") suspend fun getActiveRun(): TaskRunEntity?
    @Query("SELECT * FROM energy_checkins ORDER BY recordedAt DESC LIMIT 1") suspend fun getLatestCheckIn(): EnergyCheckInEntity?
    @Query("SELECT * FROM anchor_occurrences ORDER BY occurrenceEpochDay, scheduledAt") suspend fun getAnchorOccurrences(): List<AnchorOccurrenceEntity>
    @Query("SELECT * FROM anchor_occurrences WHERE status='PENDING' ORDER BY scheduledAt") suspend fun getPendingAnchorOccurrences(): List<AnchorOccurrenceEntity>
    @Query("SELECT * FROM anchor_occurrences WHERE planId=:planId AND status='PENDING' ORDER BY scheduledAt LIMIT 1") suspend fun getOldestPendingAnchor(planId: Long): AnchorOccurrenceEntity?
    @Query("UPDATE anchor_occurrences SET status=:status, handledAt=:handledAt WHERE id=:id") suspend fun handleAnchorOccurrence(id: Long, status: String, handledAt: Long)
    @Query("SELECT * FROM anchor_occurrences WHERE blockId=:blockId AND occurrenceEpochDay=:day AND status='PENDING' ORDER BY scheduledAt LIMIT 1") suspend fun getPendingAnchorForBlock(blockId: Long, day: Long): AnchorOccurrenceEntity?
    @Query("UPDATE anchor_occurrences SET status=:status, handledAt=:handledAt WHERE blockId=:blockId AND occurrenceEpochDay=:day AND status='PENDING'") suspend fun handleAnchorForBlock(blockId: Long, day: Long, status: String, handledAt: Long): Int
    @Query("UPDATE task_runs SET status=:status, endedAt=:endedAt, fulfillmentPoints=:points WHERE id=:id AND status IN ('ACTIVE','PAUSED')") suspend fun finishRun(id: Long, status: String, endedAt: Long, points: Int?): Int
    @Query("UPDATE task_runs SET status=:status, pausedAt=:pausedAt, pausedDurationMillis=:pausedDuration WHERE id=:id") suspend fun updateRunState(id: Long, status: String, pausedAt: Long?, pausedDuration: Long)
    @Query("UPDATE plans SET lastChosenEpochMillis=:chosenAt WHERE id=:id") suspend fun markPlanChosen(id: Long, chosenAt: Long)
    @Query("UPDATE plans SET reviewState=:state, scheduledEpochDay=:epochDay, dismissedEpochDay=:dismissed WHERE id=:id") suspend fun updateReviewState(id: Long, state: String, epochDay: Long?, dismissed: Long?)
    @Query("SELECT COALESCE(MAX(matrixOrder), -1) + 1 FROM plans WHERE quadrant=:quadrant") suspend fun nextMatrixOrder(quadrant: String): Int
    @Query("SELECT actualLoad FROM task_runs WHERE planId=:planId AND status='COMPLETED' AND actualLoad IS NOT NULL ORDER BY endedAt DESC LIMIT 7") suspend fun recentLoads(planId: Long): List<Int>
    @Query("SELECT COUNT(*) FROM task_runs WHERE status='COMPLETED' AND startedAt>=:start AND startedAt<:end") suspend fun completedCount(start: Long, end: Long): Int
    @Query("SELECT COALESCE(SUM(fulfillmentPoints),0) FROM task_runs WHERE status IN ('COMPLETED','ENDED') AND startedAt>=:start AND startedAt<:end") suspend fun fulfillmentTotal(start: Long, end: Long): Int
    @Query("DELETE FROM schedule_plans WHERE blockId=:blockId") suspend fun clearSchedulePlansForBlock(blockId: Long)
    @Query("DELETE FROM schedule_plans WHERE planId=:planId") suspend fun clearSchedulePlansForPlan(planId: Long)
    @Query("DELETE FROM ai_messages WHERE createdAt<:cutoff") suspend fun deleteAiMessagesBefore(cutoff: Long)
    @Query("SELECT COUNT(*) FROM ai_messages WHERE createdAt<:cutoff") suspend fun aiMessagesBeforeCount(cutoff: Long): Int

    @Query("DELETE FROM schedule_plans") suspend fun clearSchedulePlans()
    @Query("DELETE FROM anchor_occurrences") suspend fun clearAnchorOccurrences()
    @Query("DELETE FROM energy_checkins") suspend fun clearCheckIns()
    @Query("DELETE FROM task_runs") suspend fun clearRuns()
    @Query("DELETE FROM time_blocks") suspend fun clearBlocks()
    @Query("DELETE FROM plans") suspend fun clearPlans()
    @Query("DELETE FROM task_feedback") suspend fun clearFeedback()
    @Query("DELETE FROM pet_prompts") suspend fun clearPetPrompts()

    @Transaction suspend fun replaceAll(snapshot: DatabaseSnapshot) {
        clearSchedulePlans(); clearAnchorOccurrences(); clearCheckIns(); clearFeedback(); clearPetPrompts(); clearRuns(); clearBlocks(); clearPlans()
        snapshot.plans.forEach { insertPlan(it) }; snapshot.blocks.forEach { insertBlock(it) }
        snapshot.schedulePlans.forEach { insertSchedulePlan(it) }; snapshot.runs.forEach { insertRun(it) }
        snapshot.checkIns.forEach { insertCheckIn(it) }; snapshot.anchorOccurrences.forEach { insertAnchorOccurrence(it) }
        snapshot.feedback.forEach { insertFeedback(it) }
    }
}

@Serializable data class DatabaseSnapshot(
    val plans: List<PlanEntity>, val blocks: List<TimeBlockEntity>, val schedulePlans: List<SchedulePlanCrossRef>,
    val runs: List<TaskRunEntity>, val checkIns: List<EnergyCheckInEntity>, val anchorOccurrences: List<AnchorOccurrenceEntity> = emptyList(),
    val feedback: List<TaskFeedbackEntity> = emptyList(),
)
