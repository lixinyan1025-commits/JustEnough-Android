package com.justenough.planner.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FulfillmentTest {
    @Test fun fiveCellsFillAndExtraPointsOverflow() {
        assertEquals(5, fulfillmentFilledCells(8))
        assertEquals(3, fulfillmentOverflow(8))
        assertEquals(4, fulfillmentFilledCells(4))
        assertEquals(0, fulfillmentOverflow(4))
    }
}

internal fun fulfillmentFilledCells(total: Int) = total.coerceIn(0, 5)
internal fun fulfillmentOverflow(total: Int) = (total - 5).coerceAtLeast(0)
