package com.example.version1

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Bounds the whole confirmation, rather than multiplying each getter's timeout by attempts. */
internal suspend fun verifyVehicleReadback(
    label: String,
    expected: String,
    read: suspend () -> String,
    equivalent: (String, String) -> Boolean,
    beforeRead: suspend () -> Unit = {},
    attempts: Int = 20,
    pollMillis: Long = 400,
    timeoutMillis: Long = 10_000
) {
    require(attempts > 0 && pollMillis >= 0 && timeoutMillis > 0)
    var actual = "unknown"
    val confirmed = withTimeoutOrNull(timeoutMillis) {
        repeat(attempts) {
            delay(pollMillis)
            // A changed gear, driver or foreground state is not a transient getter failure.
            beforeRead()
            try {
                actual = read()
                if (equivalent(actual, expected)) return@withTimeoutOrNull true
            } catch (cancelled: CancellationException) {
                // Only a getter's OWN timeout may be retried. Parent cancellation must escape.
                currentCoroutineContext().ensureActive()
                if (cancelled !is TimeoutCancellationException) throw cancelled
                actual = "조회 시간 초과"
            } catch (error: Exception) { actual = error.message ?: "조회 실패" }
        }
        false
    }
    check(confirmed == true) { "$label 실제 상태 확인 실패 · 요청 $expected / 응답 $actual" }
}
