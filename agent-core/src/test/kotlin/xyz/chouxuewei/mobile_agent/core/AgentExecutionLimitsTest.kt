package xyz.chouxuewei.mobile_agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentExecutionLimitsTest {
    @Test
    fun defaultAndBoundariesAreValid() {
        assertEquals(UNLIMITED_SINGLE_RUN_MAX_STEPS, DEFAULT_SINGLE_RUN_MAX_STEPS)
        assertEquals(UNLIMITED_SINGLE_RUN_MAX_STEPS, requireValidSingleRunMaxSteps(UNLIMITED_SINGLE_RUN_MAX_STEPS))
        assertEquals(MIN_SINGLE_RUN_MAX_STEPS, requireValidSingleRunMaxSteps(MIN_SINGLE_RUN_MAX_STEPS))
        assertEquals(1_000, requireValidSingleRunMaxSteps(1_000))
        assertEquals(Int.MAX_VALUE, requireValidSingleRunMaxSteps(Int.MAX_VALUE))
    }

    @Test
    fun valuesOutsideSupportedRangeAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { requireValidSingleRunMaxSteps(-2) }
        assertThrows(IllegalArgumentException::class.java) { requireValidSingleRunMaxSteps(0) }
    }
}
