package com.example.version1

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Only read-only SDK entry points. A blocked Binder must not block Main or grow an unbounded queue.
 * Timeouts drop waiting work and ignore late replies; they do not claim to cancel a running Binder. */
internal class SdkReadCall(private val executor: ThreadPoolExecutor = sharedExecutor) {
    suspend fun <T> await(timeoutMillis: Long = 6000,
                         call: ((T) -> Unit, (Exception) -> Unit) -> Unit): T = withTimeout(timeoutMillis) {
        suspendCancellableCoroutine { continuation ->
            val settled = java.util.concurrent.atomic.AtomicBoolean(false)
            val task = Runnable {
                if (continuation.isActive) try {
                    call({ value ->
                        if (settled.compareAndSet(false, true)) continuation.resume(value)
                    }, { error ->
                        if (settled.compareAndSet(false, true)) continuation.resumeWithException(error)
                    })
                } catch (error: Exception) {
                    if (settled.compareAndSet(false, true)) continuation.resumeWithException(error)
                }
            }
            continuation.invokeOnCancellation { settled.set(true); executor.remove(task) }
            try {
                executor.execute(task)
                // Cancellation can race with submission while the worker is occupied.
                if (!continuation.isActive) executor.remove(task)
            } catch (error: java.util.concurrent.RejectedExecutionException) {
                if (settled.compareAndSet(false, true)) continuation.resumeWithException(error)
            }
        }
    }

    companion object {
        private val sharedExecutor = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
            ArrayBlockingQueue(16), { task -> Thread(task, "ieum-sdk-read").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy())
    }
}
