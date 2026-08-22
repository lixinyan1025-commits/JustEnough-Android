package com.justenough.planner.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class AiFallbackTest {
    @Test fun fallbackOnlyForRequiredFailureClasses() {
        assertTrue(AiPlannerClient.shouldUseFallback(SocketTimeoutException()))
        assertTrue(AiPlannerClient.shouldUseFallback(AiPlannerClient.HttpFailure(401, "auth")))
        assertTrue(AiPlannerClient.shouldUseFallback(AiPlannerClient.HttpFailure(429, "limit")))
        assertTrue(AiPlannerClient.shouldUseFallback(AiPlannerClient.HttpFailure(503, "server")))
        assertFalse(AiPlannerClient.shouldUseFallback(AiPlannerClient.HttpFailure(400, "parameter")))
        assertFalse(AiPlannerClient.shouldUseFallback(IOException("offline")))
        assertFalse(AiPlannerClient.shouldUseFallback(IllegalArgumentException("invalid json")))
    }
}
