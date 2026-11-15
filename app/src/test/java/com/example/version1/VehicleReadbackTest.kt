package com.example.version1

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class VehicleReadbackTest {
    @Test fun transientGetterFailureAndOwnTimeoutCanRecover() = runBlocking {
        var reads = 0
        verifyVehicleReadback("fan", "2", {
            when (++reads) {
                1 -> error("not ready")
                2 -> withTimeout(10) { delay(1000); "wrong" }
                else -> "2"
            }
        }, { a, b -> a == b }, pollMillis = 0)
        assertEquals(3, reads)
    }
    @Test fun parentTimeoutIsNotRetriedOrConvertedToSuccess() = runBlocking {
        var reads = 0
        val failure = runCatching {
            withTimeout(40) {
                verifyVehicleReadback("fan", "2", { reads++; delay(1000); "2" },
                    { a, b -> a == b }, pollMillis = 0)
            }
        }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertEquals(1, reads)
    }
    @Test fun totalDeadlineBoundsAnUnresponsiveGetter() = runBlocking {
        val start = System.nanoTime()
        val failure = runCatching {
            verifyVehicleReadback("fan", "2", { delay(10_000); "2" }, { a, b -> a == b },
                pollMillis = 0, timeoutMillis = 60)
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue("Whole confirmation must be bounded", (System.nanoTime() - start) / 1_000_000 < 1500)
    }
    @Test fun safetyExitStopsBeforeTheNextGetter() = runBlocking {
        var checks = 0
        var reads = 0
        val failure = runCatching {
            verifyVehicleReadback("fan", "2", { reads++; "3" }, { a, b -> a == b },
                beforeRead = { check(++checks < 2) { "left Park" } }, pollMillis = 0)
        }.exceptionOrNull()
        assertEquals("left Park", failure?.message)
        assertEquals(1, reads)
    }
    @Test fun exhaustingAttemptsDoesNotClaimConfirmation() = runBlocking {
        val failure = runCatching {
            verifyVehicleReadback("fan", "2", { "3" }, { a, b -> a == b }, attempts = 2, pollMillis = 0)
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("응답 3"))
    }
}
