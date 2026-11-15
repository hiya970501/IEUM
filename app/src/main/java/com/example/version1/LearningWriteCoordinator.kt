package com.example.version1

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Acquire in UI observation order, before dispatching database work to IO.
 * A synchronized DB alone cannot preserve the order in which IO jobs were sent.
 * The queue contains only bounded consented summaries, never raw sensor samples.
 */
internal class LearningWriteCoordinator {
    private val mutex=Mutex()
    private val pending=AtomicInteger()

    suspend fun <T> write(action:suspend () -> T):T {
        if(pending.incrementAndGet()>MAX_PENDING) {
            pending.decrementAndGet()
            error("Learning write queue is full")
        }
        try {
            return mutex.withLock { withContext(Dispatchers.IO) { action() } }
        } finally { pending.decrementAndGet() }
    }

    companion object {
        internal const val MAX_PENDING=32
        // The trip ID is deliberately reused across Activity recreation. The
        // outgoing and incoming Activity must therefore share write ordering.
        internal val shared=LearningWriteCoordinator()
    }
}
