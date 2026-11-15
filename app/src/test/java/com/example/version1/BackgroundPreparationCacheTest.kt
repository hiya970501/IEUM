package com.example.version1

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class BackgroundPreparationCacheTest {
    @Test fun creatingPreparationDoesNotCompeteWithInitialModelImport() {
        val count = AtomicInteger()
        val cache = BackgroundPreparationCache { count.incrementAndGet() }
        try {
            val task = cache.prepare(String::class.java)
            assertFalse(task.isActive)
            assertEquals(0, count.get())
            runBlocking { withTimeout(3000) { task.await() } }
            assertEquals(1, count.get())
        } finally { cache.close() }
    }
    @Test fun preparationRunsAwayFromCallerAndIsReused() {
        val caller = Thread.currentThread()
        val worker = AtomicReference<Thread>()
        val count = AtomicInteger()
        val cache = BackgroundPreparationCache { worker.set(Thread.currentThread()); count.incrementAndGet() }
        try {
            val first = cache.prepare(String::class.java)
            runBlocking { withTimeout(3000) { first.await() } }
            assertNotSame(caller, worker.get())
            assertSame(first, cache.prepare(String::class.java)); assertEquals(1, count.get())
        } finally { cache.close() }
    }
    @Test fun parallelConsumersShareOneProducer() {
        val start = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        val cache = BackgroundPreparationCache { count.incrementAndGet(); check(release.await(3, TimeUnit.SECONDS)) }
        val callers = Executors.newFixedThreadPool(8)
        try {
            val results = (1..8).map { callers.submit<kotlinx.coroutines.Deferred<Unit>> {
                check(start.await(2, TimeUnit.SECONDS)); cache.prepare(String::class.java)
            } }
            start.countDown()
            val first = results.first().get(2, TimeUnit.SECONDS)
            results.forEach { assertSame(first, it.get(2, TimeUnit.SECONDS)) }
            release.countDown()
            runBlocking { withTimeout(3000) { first.await() } }
            assertEquals(1, count.get())
        } finally { release.countDown(); callers.shutdownNow(); cache.close() }
    }
    @Test fun cancelledScreenDoesNotCancelPreparationForNextScreen() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val cache = BackgroundPreparationCache { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        try {
            val shared = cache.prepare(String::class.java)
            val oldScreen = launch(start = CoroutineStart.UNDISPATCHED) { shared.await() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            oldScreen.cancelAndJoin()
            assertFalse(shared.isCancelled)
            release.countDown()
            withTimeout(3000) { cache.prepare(String::class.java).await() }
        } finally { release.countDown(); cache.close() }
    }
    @Test fun failuresAndCapacityNeverBecomeFalseReadiness() {
        val cache = BackgroundPreparationCache(capacity = 1) { throw IllegalStateException("synthetic preparation failure") }
        try {
            val failed = cache.prepare(String::class.java)
            val failure = runCatching { runBlocking { withTimeout(3000) { failed.await() } } }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertSame(failed, cache.prepare(String::class.java))
            assertThrows(IllegalStateException::class.java) { cache.prepare(Int::class.java) }
        } finally { cache.close() }
    }
}
