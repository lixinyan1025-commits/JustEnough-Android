package com.justenough.planner.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.justenough.planner.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetHostSeedTest {
    @Test fun seedScrollableWidgetDataset()=runBlocking{
        val context=ApplicationProvider.getApplicationContext<Context>();val db=PlannerDatabase.get(context);val plans=(1L..29L).map{id->PlanEntity(id=id,name="桌面计划$id",minimumGoal="先做${id}分钟",estimatedMinutes=(id+4).toInt())};val blocks=(1L..25L).map{id->TimeBlockEntity(id=id,name="全天安排$id",kind=PlanKinds.CHOICE,startMinute=0,endMinute=1440)};val refs=buildList{(1L..5L).forEach{add(SchedulePlanCrossRef(1,it))};(2L..25L).forEach{add(SchedulePlanCrossRef(it,it+4))}};db.plannerDao().replaceAll(DatabaseSnapshot(plans,blocks,refs,emptyList(),emptyList()));assertEquals(25,PlannerRepository(db,AppSettings(context)).todaySnapshot().todayBlocks.size);WidgetUpdater.update(context)
    }
}
