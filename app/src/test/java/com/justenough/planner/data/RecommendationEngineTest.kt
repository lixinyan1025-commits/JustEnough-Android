package com.justenough.planner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {
    @Test fun timeFitAffectsOrderWithoutAskingForEnergy() {
        val short = plan(2, 20, "短任务")
        val long = plan(1, 90, "长任务")
        val result = RecommendationEngine.recommend(listOf(long, short), 0, 30)
        assertEquals("短任务", result.first().name)
    }
    @Test fun rotateChangesVisibleThree() {
        val values=(1..5).map{plan(it.toLong(),20,"任务$it")}
        assertTrue(RecommendationEngine.recommend(values,0).map{it.id} != RecommendationEngine.recommend(values,3).map{it.id})
        assertEquals(3,RecommendationEngine.recommend(values,-3).size)
    }
    private fun plan(id:Long,minutes:Int,name:String)=PlanDetails(id,name,"先做一点",minutes,true,false,null,null,null,ReviewStates.NONE,null,null)
}
