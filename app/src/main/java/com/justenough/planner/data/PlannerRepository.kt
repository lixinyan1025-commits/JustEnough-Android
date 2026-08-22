package com.justenough.planner.data

import android.os.Build
import androidx.room.withTransaction
import com.justenough.planner.ai.PlanProposal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class PlannerState(
    val plans: List<PlanEntity> = emptyList(),
    val blocks: List<TimeBlockEntity> = emptyList(),
    val schedulePlans: List<SchedulePlanCrossRef> = emptyList(),
    val runs: List<TaskRunEntity> = emptyList(),
    val aiMessages: List<AiMessageEntity> = emptyList(),
    val diagnostics: List<DiagnosticEventEntity> = emptyList(),
    val feedback: List<TaskFeedbackEntity> = emptyList(),
    val settings: SettingsState = SettingsState(),
)

data class TodaySnapshot(
    val currentBlock: BlockWithPlans?,
    val recommendations: List<PlanDetails>,
    val todayBlocks: List<BlockWithPlans>,
    val overdueAnchors: List<PlanDetails>,
    val activeRun: ActiveRunDetails?,
    val completedCount: Int,
    val fulfillmentTotal: Int,
    val completedPlanIds: Set<Long>,
    val reviewCandidates: List<PlanDetails> = emptyList(),
)

class PlannerRepository(
    private val database: PlannerDatabase,
    private val settings: AppSettings,
) {
    private val dao = database.plannerDao()

    val state: Flow<PlannerState> = combine(
        combine(dao.observePlans(), dao.observeBlocks(), dao.observeSchedulePlans()) { plans, blocks, refs -> Triple(plans, blocks, refs) },
        combine(dao.observeRuns(), dao.observeAiMessages(), dao.observeDiagnostics(), dao.observeFeedback(), settings.state) { runs, messages, diagnostics, feedback, appSettings ->
            ActivityState(runs, messages, diagnostics, feedback, appSettings)
        },
    ) { catalog, activity ->
        PlannerState(catalog.first, catalog.second, catalog.third, activity.runs, activity.messages, activity.diagnostics, activity.feedback, activity.settings)
    }

    suspend fun ensureStarterContent() {
        database.withTransaction {
            if (dao.planCount() > 0) return@withTransaction
            val reading = dao.insertPlan(PlanEntity(name = "阅读", minimumGoal = "先读5分钟", estimatedMinutes = 20, quadrant = PlanQuadrants.IMPORTANT_NOT_URGENT, matrixOrder = 0))
            val learning = dao.insertPlan(PlanEntity(name = "学习", minimumGoal = "完成一个知识点", estimatedMinutes = 30, quadrant = PlanQuadrants.IMPORTANT_NOT_URGENT, matrixOrder = 1))
            val walking = dao.insertPlan(PlanEntity(name = "散步", minimumGoal = "下楼活动10分钟", estimatedMinutes = 20, quadrant = PlanQuadrants.IMPORTANT_NOT_URGENT, matrixOrder = 2))
            val blockId = dao.insertBlock(TimeBlockEntity(name = "早晨选择", kind = PlanKinds.CHOICE, startMinute = 6 * 60, endMinute = 8 * 60))
            listOf(reading, learning, walking).forEach { dao.insertSchedulePlan(SchedulePlanCrossRef(blockId, it)) }
        }
    }

    suspend fun todaySnapshot(now: Instant = Instant.now()): TodaySnapshot {
        val zone = ZoneId.systemDefault()
        val date = now.atZone(zone).toLocalDate()
        val minute = now.atZone(zone).toLocalTime().toSecondOfDay() / 60
        syncAnchorOccurrences(now, zone)
        val plans = dao.getPlans()
        val blocks = dao.getBlocks().filter { it.enabled && occursOn(it, date) }
        val refs = dao.getSchedulePlans()
        val visible = plans.filter {
            it.enabled && !it.archived && it.dismissedEpochDay != date.toEpochDay() &&
                (it.scheduledEpochDay == null || it.scheduledEpochDay <= date.toEpochDay())
        }.map(::details)
        val byId = visible.associateBy { it.id }
        val plansByBlock = refs.groupBy { it.blockId }.mapValues { (_, values) -> values.mapNotNull { byId[it.planId] } }
        val todayBlocks = blocks.sortedBy { it.startMinute }.map { BlockWithPlans(it, plansByBlock[it.id].orEmpty()) }
        val activeRunEntity = dao.getActiveRun()
        val activePlan = activeRunEntity?.let { plans.firstOrNull { plan -> plan.id == it.planId } }
        val active = if (activeRunEntity != null && activePlan != null) ActiveRunDetails(
            runId = activeRunEntity.id,
            planId = activePlan.id,
            name = activePlan.name,
            minimumGoal = activePlan.minimumGoal,
            plannedMinutes = activeRunEntity.plannedMinutes,
            startedAt = activeRunEntity.startedAt,
            pausedAt = activeRunEntity.pausedAt,
            pausedDurationMillis = activeRunEntity.pausedDurationMillis,
            status = activeRunEntity.status,
        ) else null
        val current = todayBlocks.firstOrNull { it.block.kind == PlanKinds.ANCHOR && minute in it.block.startMinute until it.block.endMinute }
            ?: todayBlocks.firstOrNull { minute in it.block.startMinute until it.block.endMinute }
        val range = dayRange(date, zone)
        val runs = dao.getRuns()
        val completedIds = runs.filter { it.status == RunStatuses.COMPLETED && it.startedAt in range.first until range.second }.map { it.planId }.toSet()
        val overdue = dao.getPendingAnchorOccurrences().filter { it.scheduledAt <= now.toEpochMilli() }.mapNotNull { byId[it.planId] }.distinctBy { it.id }
        val scheduledIds = refs.map { it.planId }.toSet()
        val anchorBlockIds = todayBlocks.filter { it.block.kind == PlanKinds.ANCHOR }.map { it.block.id }.toSet()
        val futureAnchorPlanIds = refs.filter { it.blockId in anchorBlockIds }.map { it.planId }.toSet()
        val choicePlanIds = todayBlocks.filter { it.block.kind == PlanKinds.CHOICE }.flatMap { it.plans }.map { it.id }.toSet()
        val baseCandidates = when {
            overdue.isNotEmpty() -> overdue.filterNot { it.id in completedIds }
            current?.block?.kind == PlanKinds.CHOICE -> current.plans.filterNot { it.id in completedIds }
            else -> visible.filter { plan ->
                plan.id !in completedIds && (plan.id !in scheduledIds || plan.id in choicePlanIds) && plan.id !in futureAnchorPlanIds
            }
        }
        val available = current?.let { (it.block.endMinute - minute).coerceAtLeast(1) } ?: 180
        val appSettings = settings.state.first()
        val reviewCandidates = if (minute >= appSettings.reviewMinute) {
            (todayBlocks.flatMap { it.plans } + overdue)
                .filter { it.id !in completedIds && it.id != active?.planId }
                .distinctBy { it.id }
        } else emptyList()
        return TodaySnapshot(
            currentBlock = current,
            recommendations = recommend(baseCandidates, appSettings.candidateOffset, available),
            todayBlocks = todayBlocks,
            overdueAnchors = overdue,
            activeRun = active,
            completedCount = dao.completedCount(range.first, range.second),
            fulfillmentTotal = dao.fulfillmentTotal(range.first, range.second),
            completedPlanIds = completedIds,
            reviewCandidates = reviewCandidates,
        )
    }

    internal fun recommend(candidates: List<PlanDetails>, offset: Int, availableMinutes: Int = Int.MAX_VALUE): List<PlanDetails> =
        RecommendationEngine.recommend(candidates, offset, availableMinutes)

    suspend fun rotateCandidates() = settings.update { it.copy(candidateOffset = it.candidateOffset + 3) }

    suspend fun addPlan(name: String, minimumGoal: String, estimatedMinutes: Int, quadrant: String): Long {
        require(PlanQuadrants.isValid(quadrant)) { "请选择计划所属象限" }
        return dao.insertPlan(PlanEntity(name = required(name, "计划名称"), minimumGoal = required(minimumGoal, "目标"), estimatedMinutes = estimatedMinutes.coerceIn(1, 1440), quadrant = quadrant, matrixOrder = dao.nextMatrixOrder(quadrant))).also { markPendingAnalysis() }
    }

    suspend fun updatePlan(plan: PlanEntity) = database.withTransaction {
        require(PlanQuadrants.isValid(plan.quadrant)) { "请选择计划所属象限" }
        val previous = requireNotNull(dao.getPlan(plan.id)) { "计划不存在" }
        val moved = previous.quadrant != plan.quadrant
        dao.updatePlan(plan.copy(
            name = required(plan.name, "计划名称"),
            minimumGoal = required(plan.minimumGoal, "目标"),
            estimatedMinutes = plan.estimatedMinutes.coerceIn(1, 1440),
            matrixOrder = if (moved) dao.nextMatrixOrder(plan.quadrant) else plan.matrixOrder,
        ))
        if (moved) normalizeQuadrant(previous.quadrant)
        normalizeQuadrant(plan.quadrant)
        markPendingAnalysis()
    }

    suspend fun archivePlan(planId: Long) = database.withTransaction {
        dao.getPlan(planId)?.let { dao.updatePlan(it.copy(archived = true, enabled = false)) }
        dao.clearSchedulePlansForPlan(planId)
        markPendingAnalysis()
    }

    suspend fun restorePlan(planId: Long, quadrant: String) {
        require(PlanQuadrants.isValid(quadrant)) { "请选择计划所属象限" }
        dao.getPlan(planId)?.let { dao.updatePlan(it.copy(archived = false, enabled = true, quadrant = quadrant, matrixOrder = dao.nextMatrixOrder(quadrant))) }
        markPendingAnalysis()
    }
    suspend fun classifyPlans(values: Map<Long, String>) = database.withTransaction {
        require(values.isNotEmpty()) { "请完成计划分类" }
        values.forEach { (id, quadrant) ->
            require(PlanQuadrants.isValid(quadrant)) { "计划分类无效" }
            val plan = requireNotNull(dao.getPlan(id)) { "计划不存在" }
            dao.updatePlan(plan.copy(quadrant = quadrant, matrixOrder = dao.nextMatrixOrder(quadrant)))
        }
        require(dao.getPlans().filter { !it.archived }.none { !PlanQuadrants.isValid(it.quadrant) }) { "仍有计划尚未分类" }
        markPendingAnalysis()
    }
    suspend fun movePlan(planId: Long, quadrant: String, beforeId: Long? = null) = database.withTransaction {
        require(PlanQuadrants.isValid(quadrant)) { "计划分类无效" }
        val plan = requireNotNull(dao.getPlan(planId)) { "计划不存在" }
        val target = dao.getPlans().filter { !it.archived && it.id != planId && it.quadrant == quadrant }.sortedBy { it.matrixOrder }.toMutableList()
        val index = beforeId?.let { id -> target.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: target.size
        target.add(index, plan.copy(quadrant = quadrant))
        target.forEachIndexed { order, value -> dao.updatePlan(value.copy(quadrant = quadrant, matrixOrder = order)) }
        if (plan.quadrant != quadrant && PlanQuadrants.isValid(plan.quadrant)) normalizeQuadrant(plan.quadrant)
        markPendingAnalysis()
    }
    suspend fun permanentlyDeletePlan(planId: Long) { dao.getPlan(planId)?.let { dao.deletePlan(it) }; markPendingAnalysis() }

    suspend fun addBlock(block: TimeBlockEntity, planIds: List<Long>): Long = database.withTransaction {
        val checked = validateBlock(block, planIds)
        val id = dao.insertBlock(checked)
        planIds.distinct().forEach { dao.insertSchedulePlan(SchedulePlanCrossRef(id, it)) }
        markPendingAnalysis(); id
    }

    suspend fun updateBlock(block: TimeBlockEntity, planIds: List<Long>) = database.withTransaction {
        dao.updateBlock(validateBlock(block, planIds))
        dao.clearSchedulePlansForBlock(block.id)
        planIds.distinct().forEach { dao.insertSchedulePlan(SchedulePlanCrossRef(block.id, it)) }
        markPendingAnalysis()
    }

    suspend fun deleteBlock(block: TimeBlockEntity) { dao.deleteBlock(block); markPendingAnalysis() }
    suspend fun bindApp(planId: Long, packageName: String?, className: String?) {
        dao.getPlan(planId)?.let { dao.updatePlan(it.copy(appPackage = packageName, appClass = className)) }
        markPendingAnalysis()
    }

    suspend fun getPlan(planId: Long): PlanEntity? = dao.getPlan(planId)
    suspend fun getRun(runId: Long): TaskRunEntity? = dao.getRun(runId)
    suspend fun getActiveRun(): TaskRunEntity? = dao.getActiveRun()
    suspend fun blockWithPlans(blockId: Long): BlockWithPlans? {
        val block = dao.getBlock(blockId) ?: return null
        val plans = dao.getSchedulePlans().filter { it.blockId == blockId }
            .mapNotNull { dao.getPlan(it.planId) }
            .filter { it.enabled && !it.archived }
            .map(::details)
        return BlockWithPlans(block, plans)
    }
    suspend fun skipAnchorForBlock(blockId: Long) {
        val day = LocalDate.now().toEpochDay()
        dao.getPendingAnchorForBlock(blockId, day)?.let { dao.handleAnchorOccurrence(it.id, AnchorOccurrenceStatuses.SKIPPED, System.currentTimeMillis()) }
    }

    suspend fun startTask(planId: Long): Result<Long> = runCatching { database.withTransaction {
        check(dao.getActiveRun() == null) { "请先暂停、完成或结束当前任务" }
        val plan = requireNotNull(dao.getPlan(planId)) { "计划不存在" }
        check(plan.enabled && !plan.archived) { "计划已停用或归档" }
        val now = System.currentTimeMillis()
        val id = dao.insertRun(TaskRunEntity(planId = planId, startedAt = now, plannedMinutes = plan.estimatedMinutes))
        dao.markPlanChosen(planId, now)
        id
    } }

    suspend fun pauseOrResume(runId: Long) {
        val run = dao.getRun(runId) ?: return
        val now = System.currentTimeMillis()
        when (run.status) {
            RunStatuses.ACTIVE -> dao.updateRunState(runId, RunStatuses.PAUSED, now, run.pausedDurationMillis)
            RunStatuses.PAUSED -> dao.updateRunState(runId, RunStatuses.ACTIVE, null, run.pausedDurationMillis + ((run.pausedAt?.let { now - it } ?: 0).coerceAtLeast(0)))
        }
    }

    suspend fun extendRun(runId: Long, minutes: Int = 10) {
        dao.getRun(runId)?.let { dao.updateRun(it.copy(plannedMinutes = (it.plannedMinutes + minutes).coerceIn(1, 1440))) }
    }

    suspend fun finishRun(runId: Long, fulfillmentPoints: Int?, abandoned: Boolean = false, enough:Boolean=false):Boolean {
        val changed = database.withTransaction {
            val run = dao.getRun(runId) ?: return@withTransaction false
            val now = System.currentTimeMillis()
            val status = when{abandoned->RunStatuses.ABANDONED;enough->RunStatuses.ENDED;else->RunStatuses.COMPLETED}
            val settled=dao.finishRun(runId, status, now, if(abandoned)null else fulfillmentPoints?.coerceIn(1, 5))>0
            if (settled && !abandoned && !enough) dao.getOldestPendingAnchor(run.planId)?.let { dao.handleAnchorOccurrence(it.id, AnchorOccurrenceStatuses.COMPLETED, now) }
            settled
        }
        if (changed) markPendingAnalysis()
        return changed
    }

    suspend fun markReview(planId: Long, state: String, date: LocalDate? = null, dismissedToday: Boolean = false) = database.withTransaction {
        dao.updateReviewState(planId, state, date?.toEpochDay(), if (dismissedToday) LocalDate.now().toEpochDay() else null)
        dao.getOldestPendingAnchor(planId)?.let {
            dao.handleAnchorOccurrence(it.id, if (state == ReviewStates.RESCHEDULED) AnchorOccurrenceStatuses.RESCHEDULED else AnchorOccurrenceStatuses.SKIPPED, System.currentTimeMillis())
        }
        markPendingAnalysis()
    }

    suspend fun addAiMessage(role: String, content: String, kind: String = "CHAT", source: String = "LOCAL", pinned: Boolean = false) =
        dao.insertAiMessage(AiMessageEntity(role = role, content = content.trim(), kind = kind, source = source, pinned = pinned))

    suspend fun addFeedback(planId:Long,runId:Long?,kind:String,score:Int?,answerCode:String?,answerText:String?,source:String="LOCAL") =
        dao.insertFeedback(TaskFeedbackEntity(planId=planId,runId=runId,kind=kind,score=score?.coerceIn(1,5),answerCode=answerCode,answerText=answerText?.trim()?.take(500),source=source))
    suspend fun addPetPrompt(value:PetPromptEntity)=dao.insertPetPrompt(value)
    suspend fun pendingPetPrompt()=dao.getPendingPetPrompt(System.currentTimeMillis())
    suspend fun getPetPrompt(id:Long)=dao.getPetPrompt(id)
    suspend fun answerPetPrompt(id:Long,status:String){dao.getPetPrompt(id)?.let{dao.updatePetPrompt(it.copy(status=status,answeredAt=System.currentTimeMillis()))}}
    suspend fun petQuestionCount(start:Long)=dao.petQuestionCount(start)
    suspend fun dismissedPetQuestionCount(start:Long)=dao.dismissedPetQuestionCount(start)

    suspend fun recordDiagnostic(type: String, detail: String) = dao.insertDiagnostic(
        DiagnosticEventEntity(type = type, manufacturer = Build.MANUFACTURER, androidVersion = Build.VERSION.RELEASE, detail = detail.take(240)),
    )

    suspend fun pruneAiMessages(cutoff: Long) = dao.deleteAiMessagesBefore(cutoff)
    suspend fun aiMessages() = dao.getAiMessages()
    suspend fun oldAiMessageCount(cutoff: Long) = dao.aiMessagesBeforeCount(cutoff)
    suspend fun markPendingAnalysis() = settings.update { it.copy(pendingAnalysis = true) }
    suspend fun snapshot() = DatabaseSnapshot(dao.getPlans(), dao.getBlocks(), dao.getSchedulePlans(), dao.getRuns(), dao.getCheckIns(), dao.getAnchorOccurrences(),dao.getFeedback())
    suspend fun replaceAll(snapshot: DatabaseSnapshot) = database.withTransaction { dao.replaceAll(snapshot) }

    suspend fun applyProposal(proposal: PlanProposal) = database.withTransaction {
        proposal.plans.forEach { item -> addPlan(item.name, item.minimumGoal, item.estimatedMinutes, item.quadrant) }
        val plansByName = dao.getPlans().filter { !it.archived }.groupBy { it.name }
        proposal.schedules.forEach { schedule ->
            val ids = schedule.planNames.distinct().map { name -> requireUniquePlan(plansByName, name).id }
            addBlock(schedule.toEntity(), ids)
        }
        proposal.planChanges.forEach { change ->
            val plan = requireUniquePlan(dao.getPlans().groupBy { it.name }, change.existingName)
            updatePlan(plan.copy(
                name = change.newName?.takeIf { it.isNotBlank() } ?: plan.name,
                minimumGoal = change.minimumGoal?.takeIf { it.isNotBlank() } ?: plan.minimumGoal,
                estimatedMinutes = change.estimatedMinutes ?: plan.estimatedMinutes,
                archived = change.archive ?: plan.archived,
                enabled = if (change.archive == true) false else plan.enabled,
                quadrant = change.quadrant ?: plan.quadrant,
            ))
            if (change.archive == true) dao.clearSchedulePlansForPlan(plan.id)
        }
        proposal.scheduleChanges.forEach { change ->
            val block = requireNotNull(dao.getBlocks().singleOrNull { it.name == change.existingName }) { "时间安排不存在或名称不唯一：${change.existingName}" }
            val currentIds = dao.getSchedulePlans().filter { it.blockId == block.id }.map { it.planId }
            val nextIds = change.planNames?.distinct()?.map { requireUniquePlan(dao.getPlans().groupBy { it.name }, it).id } ?: currentIds
            val next = block.copy(
                name = change.newName?.takeIf { it.isNotBlank() } ?: block.name,
                kind = change.kind?.takeIf { it == PlanKinds.ANCHOR || it == PlanKinds.CHOICE } ?: block.kind,
                startMinute = change.startMinute ?: block.startMinute,
                endMinute = change.endMinute ?: block.endMinute,
                weekdayMask = change.weekdays?.toMask() ?: block.weekdayMask,
            )
            updateBlock(next, nextIds)
        }
    }

    private fun details(plan: PlanEntity) = PlanDetails(
        plan.id, plan.name, plan.minimumGoal, plan.estimatedMinutes, plan.enabled, plan.archived,
        plan.appPackage, plan.appClass, plan.lastChosenEpochMillis, plan.reviewState, plan.scheduledEpochDay, plan.dismissedEpochDay,
    )

    private fun occursOn(block: TimeBlockEntity, date: LocalDate): Boolean {
        if (block.dateEpochDay != null) return block.dateEpochDay == date.toEpochDay()
        return block.weekdayMask and (1 shl (date.dayOfWeek.value - DayOfWeek.MONDAY.value)) != 0
    }

    private fun validateBlock(block: TimeBlockEntity, planIds: List<Long>): TimeBlockEntity {
        require(block.name.isNotBlank()) { "安排名称不能为空" }
        require(block.kind == PlanKinds.ANCHOR || block.kind == PlanKinds.CHOICE) { "安排类型无效" }
        require(block.startMinute in 0..1439 && block.endMinute in 1..1440 && block.endMinute > block.startMinute) { "时间范围无效" }
        require(block.dateEpochDay != null || block.weekdayMask in 1..127) { "至少选择一天" }
        require(planIds.isNotEmpty()) { "至少选择一个计划" }
        require(block.kind != PlanKinds.ANCHOR || planIds.distinct().size == 1) { "固定任务只能包含一个计划" }
        return block.copy(name = block.name.trim())
    }

    private suspend fun syncAnchorOccurrences(now: Instant, zone: ZoneId) {
        val today = now.atZone(zone).toLocalDate()
        val appSettings = settings.state.first()
        val start = if (appSettings.anchorSyncEpochDay == Long.MIN_VALUE) today else LocalDate.ofEpochDay(appSettings.anchorSyncEpochDay).plusDays(1).coerceAtMost(today)
        val dates = generateSequence(start) { it.plusDays(1).takeIf { next -> !next.isAfter(today) } }.toMutableSet().apply { add(today) }
        val blocks = dao.getBlocks().filter { it.enabled && it.kind == PlanKinds.ANCHOR }
        val refs = dao.getSchedulePlans().groupBy { it.blockId }
        dates.forEach { date -> blocks.filter { occursOn(it, date) }.forEach { block ->
            val scheduled = date.atStartOfDay(zone).plusMinutes(block.startMinute.toLong()).toInstant()
            if (!scheduled.isAfter(now)) refs[block.id].orEmpty().forEach { ref ->
                dao.insertAnchorOccurrence(AnchorOccurrenceEntity(blockId = block.id, planId = ref.planId, occurrenceEpochDay = date.toEpochDay(), scheduledAt = scheduled.toEpochMilli()))
            }
        } }
        if (appSettings.anchorSyncEpochDay != today.toEpochDay()) settings.update { it.copy(anchorSyncEpochDay = today.toEpochDay()) }
    }

    private fun dayRange(date: LocalDate, zone: ZoneId) =
        date.atStartOfDay(zone).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun required(value: String, label: String) = value.trim().also { require(it.isNotEmpty()) { "${label}不能为空" } }
    private suspend fun normalizeQuadrant(quadrant: String) {
        if (!PlanQuadrants.isValid(quadrant)) return
        dao.getPlans().filter { !it.archived && it.quadrant == quadrant }.sortedWith(compareBy<PlanEntity> { it.matrixOrder }.thenBy { it.name }).forEachIndexed { index, plan ->
            if (plan.matrixOrder != index) dao.updatePlan(plan.copy(matrixOrder = index))
        }
    }
    private fun List<Int>.toMask() = fold(0) { acc, day -> acc or (1 shl (day.coerceIn(1, 7) - 1)) }
    private fun com.justenough.planner.ai.ProposedSchedule.toEntity() = TimeBlockEntity(
        name = name.trim(), kind = kind, startMinute = startMinute, endMinute = endMinute, weekdayMask = weekdays.toMask(),
    )
    private fun requireUniquePlan(map: Map<String, List<PlanEntity>>, name: String) =
        requireNotNull(map[name]?.singleOrNull()) { "计划不存在或名称不唯一：$name" }

    private data class ActivityState(
        val runs: List<TaskRunEntity>,
        val messages: List<AiMessageEntity>,
        val diagnostics: List<DiagnosticEventEntity>,
        val feedback: List<TaskFeedbackEntity>,
        val settings: SettingsState,
    )
}
