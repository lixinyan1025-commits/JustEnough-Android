package com.justenough.planner.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.justenough.planner.ai.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerRepositoryDeviceTest {
    private lateinit var database: PlannerDatabase
    private lateinit var settings: AppSettings
    private lateinit var repository: PlannerRepository
    private lateinit var originalSettings: SettingsState

    @Before fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PlannerDatabase::class.java).build()
        settings = AppSettings(context); originalSettings = settings.state.first()
        repository = PlannerRepository(database, settings)
    }
    @After fun tearDown() = runBlocking { settings.update { originalSettings }; database.close() }

    @Test fun missedAnchorPersistsUntilExplicitlyHandled() = runBlocking {
        val today = LocalDate.now(); settings.update { it.copy(anchorSyncEpochDay=today.minusDays(2).toEpochDay()) }
        val dao=database.plannerDao();val plan=dao.insertPlan(PlanEntity(name="晨读",minimumGoal="读一页"));val block=dao.insertBlock(TimeBlockEntity(name="晨读",kind=PlanKinds.ANCHOR,startMinute=1,endMinute=60));dao.insertSchedulePlan(SchedulePlanCrossRef(block,plan))
        val first=repository.todaySnapshot(today.atTime(12,0).atZone(ZoneId.systemDefault()).toInstant())
        Assert.assertEquals(listOf(plan),first.overdueAnchors.map{it.id});Assert.assertEquals(2,repository.snapshot().anchorOccurrences.count{it.status==AnchorOccurrenceStatuses.PENDING})
        val run=repository.startTask(plan).getOrThrow();repository.finishRun(run,2)
        Assert.assertEquals(1,repository.snapshot().anchorOccurrences.count{it.status==AnchorOccurrenceStatuses.PENDING})
        repository.markReview(plan,ReviewStates.POOL,dismissedToday=true)
        Assert.assertTrue(repository.snapshot().anchorOccurrences.none{it.status==AnchorOccurrenceStatuses.PENDING})
    }

    @Test fun onlyOneTaskCanBeActive() = runBlocking {
        val dao=database.plannerDao();val first=dao.insertPlan(PlanEntity(name="一",minimumGoal="一点"));val second=dao.insertPlan(PlanEntity(name="二",minimumGoal="一点"))
        repository.startTask(first).getOrThrow();Assert.assertTrue(repository.startTask(second).isFailure)
    }

    @Test fun deletingAnchorBlockCascadesOccurrences() = runBlocking {
        val dao=database.plannerDao();val plan=dao.insertPlan(PlanEntity(name="晨读",minimumGoal="一页"));val block=TimeBlockEntity(name="晨读",kind=PlanKinds.ANCHOR,startMinute=360,endMinute=390);val id=dao.insertBlock(block);dao.insertAnchorOccurrence(AnchorOccurrenceEntity(blockId=id,planId=plan,occurrenceEpochDay=LocalDate.now().toEpochDay(),scheduledAt=System.currentTimeMillis()));dao.deleteBlock(block.copy(id=id));Assert.assertTrue(dao.getAnchorOccurrences().isEmpty())
    }

    @Test fun aiScheduleCanReuseExistingPlans() = runBlocking {
        val dao=database.plannerDao();dao.insertPlan(PlanEntity(name="阅读",minimumGoal="一页"));dao.insertPlan(PlanEntity(name="散步",minimumGoal="五分钟"));repository.applyProposal(PlanProposal(schedules=listOf(ProposedSchedule("早晨选择",PlanKinds.CHOICE,360,420,planNames=listOf("阅读","散步")))));Assert.assertEquals(1,dao.getBlocks().size);Assert.assertEquals(2,dao.getSchedulePlans().size)
    }

    @Test fun invalidAiReferenceRollsBackTransaction() = runBlocking {
        val dao=database.plannerDao();dao.insertPlan(PlanEntity(name="已有计划",minimumGoal="一点"));val before=repository.snapshot();val result=runCatching{repository.applyProposal(PlanProposal(schedules=listOf(ProposedSchedule("无效",PlanKinds.CHOICE,360,420,planNames=listOf("已有计划","不存在")))))};Assert.assertTrue(result.isFailure);Assert.assertEquals(before,repository.snapshot())
    }

    @Test fun permanentDeleteRemovesHistoryButArchiveKeepsIt() = runBlocking {
        val dao=database.plannerDao();val plan=repository.addPlan("保留历史","一点",10,PlanQuadrants.IMPORTANT_NOT_URGENT);val run=repository.startTask(plan).getOrThrow();repository.finishRun(run,3);repository.archivePlan(plan);Assert.assertEquals(1,dao.getRuns().size);repository.permanentlyDeletePlan(plan);Assert.assertTrue(dao.getRuns().isEmpty())
    }

    @Test fun fulfillmentCanOnlyBeSettledOnceAndAbandonAddsNothing() = runBlocking {
        val plan=repository.addPlan("一次结算","一点",10,PlanQuadrants.IMPORTANT_NOT_URGENT)
        val first=repository.startTask(plan).getOrThrow()
        Assert.assertTrue(repository.finishRun(first,4))
        Assert.assertFalse(repository.finishRun(first,5))
        val second=repository.startTask(plan).getOrThrow()
        Assert.assertTrue(repository.finishRun(second,5,abandoned=true))
        val snapshot=repository.todaySnapshot()
        Assert.assertEquals(4,snapshot.fulfillmentTotal)
    }

    @Test fun enoughEndsRunAndAddsFulfillmentWithoutMarkingComplete() = runBlocking {
        val plan=repository.addPlan("做到够了","一点",10,PlanQuadrants.IMPORTANT_NOT_URGENT)
        val run=repository.startTask(plan).getOrThrow()
        Assert.assertTrue(repository.finishRun(run,2,enough=true))
        val snapshot=repository.todaySnapshot()
        Assert.assertEquals(2,snapshot.fulfillmentTotal)
        Assert.assertFalse(plan in snapshot.completedPlanIds)
        Assert.assertNull(snapshot.activeRun)
    }

    @Test fun classificationIsAllOrNothingAndQuadrantOrderPersists() = runBlocking {
        val dao = database.plannerDao()
        val first = dao.insertPlan(PlanEntity(name="First", minimumGoal="One", quadrant=""))
        val second = dao.insertPlan(PlanEntity(name="Second", minimumGoal="One", quadrant=""))
        Assert.assertTrue(runCatching { repository.classifyPlans(mapOf(first to PlanQuadrants.IMPORTANT_URGENT)) }.isFailure)
        Assert.assertTrue(dao.getPlans().all { it.quadrant.isBlank() })

        repository.classifyPlans(mapOf(
            first to PlanQuadrants.IMPORTANT_URGENT,
            second to PlanQuadrants.IMPORTANT_NOT_URGENT,
        ))
        repository.movePlan(second, PlanQuadrants.IMPORTANT_URGENT, beforeId = first)
        val ordered = dao.getPlans().filter { it.quadrant == PlanQuadrants.IMPORTANT_URGENT }.sortedBy { it.matrixOrder }
        Assert.assertEquals(listOf(second, first), ordered.map { it.id })
        Assert.assertEquals(listOf(0, 1), ordered.map { it.matrixOrder })
    }

    @Test fun editingPlanQuadrantPersistsAndKeepsOrder() = runBlocking {
        val dao = database.plannerDao()
        val planId = repository.addPlan("移动象限", "一点", 10, PlanQuadrants.IMPORTANT_NOT_URGENT)
        val original = dao.getPlan(planId)!!
        repository.updatePlan(original.copy(quadrant = PlanQuadrants.IMPORTANT_URGENT))
        Assert.assertEquals(PlanQuadrants.IMPORTANT_URGENT, dao.getPlan(planId)!!.quadrant)
        repository.updatePlan(dao.getPlan(planId)!!.copy(quadrant = PlanQuadrants.NOT_IMPORTANT_NOT_URGENT))
        Assert.assertEquals(PlanQuadrants.NOT_IMPORTANT_NOT_URGENT, dao.getPlan(planId)!!.quadrant)
    }
}
