package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class RenderFrameBudgetTest {
    @Test fun gpuSkipsCannotConsumePendingSettingUpdate() {
        val budget = RenderFrameBudget()
        budget.request(12, 100)
        repeat(100) { budget.submitted(false, 100L + it * 33) }
        assertEquals(12, budget.remaining)
        repeat(12) { budget.submitted(true, 4000L + it * 33) }
        assertEquals(0, budget.remaining)
    }
    @Test fun repeatedRequestsCannotHideAStalledRenderer() {
        val budget = RenderFrameBudget()
        budget.request(12, 100)
        budget.request(24, 14_000)
        assertTrue(budget.stalled(15_101))
        budget.submitted(true, 15_102)
        assertFalse(budget.stalled(15_103))
    }
    @Test fun hidingTheViewCancelsWorkAndNewWorkGetsAFreshDeadline() {
        val budget = RenderFrameBudget()
        budget.request(12, 0)
        budget.clear()
        assertFalse(budget.stalled(60_000))
        budget.request(3, 60_000)
        assertFalse(budget.stalled(60_001))
        assertEquals(3, budget.remaining)
    }
}
