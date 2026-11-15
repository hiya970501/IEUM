package com.example.version1

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*

class SdkReadCallTest {
    private fun worker() = ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "owned-read-fixture").apply { isDaemon = true } })

    @Test fun blockedSdkEntryDoesNotBlockCallerAndDuplicateRepliesAreIgnored() = runBlocking {
        val executor = worker(); val release = CountDownLatch(1); val entered = CompletableDeferred<Unit>()
        try {
            withTimeout(5000) {
                val caller = Thread.currentThread()
                val result = async(start = CoroutineStart.UNDISPATCHED) {
                    SdkReadCall(executor).await<Int> { ok, fail ->
                        assertNotSame(caller, Thread.currentThread()); entered.complete(Unit)
                        check(release.await(4, TimeUnit.SECONDS)); ok(7); ok(9); fail(Exception("late duplicate"))
                    }
                }
                entered.await()
                val input = async { 42 }
                assertEquals(42, input.await()); assertFalse(result.isCompleted)
                release.countDown(); assertEquals(7, result.await())
            }
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun cancellationRemovesQueuedReadBeforeItCanReachSdk() = runBlocking {
        val executor = worker(); val release = CountDownLatch(1); val entered = CompletableDeferred<Unit>()
        val reached = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            withTimeout(5000) {
                val reads = SdkReadCall(executor)
                val first = async(start = CoroutineStart.UNDISPATCHED) { reads.await<Int> { ok, _ ->
                    entered.complete(Unit); check(release.await(4, TimeUnit.SECONDS)); ok(1)
                } }
                entered.await()
                val queued = launch(start = CoroutineStart.UNDISPATCHED) { reads.await<Int> { ok, _ -> reached.set(true); ok(2) } }
                assertEquals(1, executor.queue.size)
                queued.cancelAndJoin(); assertEquals(0, executor.queue.size)
                release.countDown(); assertEquals(1, first.await()); assertFalse(reached.get())
            }
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun blockedBinderTimeoutReturnsWithoutClaimingToCancelRunningCall() = runBlocking {
        val executor = worker(); val release = CountDownLatch(1); val entered = CompletableDeferred<Unit>()
        val exited = CountDownLatch(1)
        try {
            withTimeout(5000) {
                val timed = async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        SdkReadCall(executor).await<Int>(100) { ok, _ ->
                            entered.complete(Unit)
                            check(release.await(4, TimeUnit.SECONDS)); ok(9); exited.countDown()
                        }
                        false
                    } catch (_: TimeoutCancellationException) { true }
                }
                entered.await(); assertTrue(timed.await())
                assertEquals(1L, exited.count) // SDK body still running; Main-side wait ended.
                release.countDown(); assertTrue(exited.await(1, TimeUnit.SECONDS))
            }
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun fullQueueRejectsPromptlyInsteadOfGrowingOrBlockingCaller() = runBlocking {
        val executor = worker(); val release = CountDownLatch(1); val entered = CompletableDeferred<Unit>()
        try {
            withTimeout(5000) {
                val reads = SdkReadCall(executor)
                val first = async(start = CoroutineStart.UNDISPATCHED) { reads.await<Int> { ok, _ ->
                    entered.complete(Unit); check(release.await(4, TimeUnit.SECONDS)); ok(1)
                } }
                entered.await()
                val queued = launch(start = CoroutineStart.UNDISPATCHED) { reads.await<Int> { ok, _ -> ok(2) } }
                try { reads.await<Int> { ok, _ -> ok(3) }; fail("Expected bounded rejection") }
                catch (_: RejectedExecutionException) { }
                assertEquals(1, executor.queue.size)
                queued.cancelAndJoin(); release.countDown(); assertEquals(1, first.await())
            }
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
