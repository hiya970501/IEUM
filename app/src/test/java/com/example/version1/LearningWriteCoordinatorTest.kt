package com.example.version1

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LearningWriteCoordinatorTest {
    @Test fun delayedFirstWriteCannotOverwriteLaterObservation()=runBlocking {
        withTimeout(10_000) {
            val writes=LearningWriteCoordinator()
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val values=mutableListOf<Int>()
            val first=launch(start=CoroutineStart.UNDISPATCHED) {
                writes.write { entered.complete(Unit);release.await();values.add(1) }
            }
            entered.await()
            val second=launch(start=CoroutineStart.UNDISPATCHED) { writes.write { values.add(2) } }
            release.complete(Unit);joinAll(first,second)
            assertEquals(listOf(1,2),values)
        }
    }

    @Test fun cancelledWaiterNeverWritesAndDoesNotBlockFollowingFeedback()=runBlocking {
        withTimeout(10_000) {
            val writes=LearningWriteCoordinator()
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val values=mutableListOf<Int>()
            val first=launch(start=CoroutineStart.UNDISPATCHED) {
                writes.write { entered.complete(Unit);release.await();values.add(1) }
            }
            entered.await()
            val cancelled=launch(start=CoroutineStart.UNDISPATCHED) { writes.write { values.add(2) } }
            val feedback=launch(start=CoroutineStart.UNDISPATCHED) { writes.write { values.add(3) } }
            cancelled.cancelAndJoin();release.complete(Unit);joinAll(first,feedback)
            assertEquals(listOf(1,3),values)
        }
    }

    @Test fun failedWriteReleasesOwnershipForNextRequest()=runBlocking {
        val writes=LearningWriteCoordinator()
        try { writes.write<Unit> { error("owned fixture failure") };fail("Expected failure") }
        catch(_:IllegalStateException) { }
        assertEquals(7,writes.write { 7 })
    }

    @Test fun queueIsBoundedAndCapacityReturnsAfterDrain()=runBlocking {
        withTimeout(10_000) {
            val writes=LearningWriteCoordinator()
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val first=launch(start=CoroutineStart.UNDISPATCHED) {
                writes.write { entered.complete(Unit);release.await() }
            }
            entered.await()
            val pending=(1 until LearningWriteCoordinator.MAX_PENDING).map {
                launch(start=CoroutineStart.UNDISPATCHED) { writes.write { Unit } }
            }
            var rejected=false
            try { writes.write { fail("Overflow request must not run") } }
            catch(_:IllegalStateException) { rejected=true }
            assertTrue(rejected)
            release.complete(Unit);first.join();pending.joinAll()
            assertEquals(9,writes.write { 9 })
        }
    }
}
