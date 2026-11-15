package com.example.version1

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Counts queued as well as executing directory writes. A UI reservation starts
 * before IO dispatch and ends only after its acknowledgement has been handled.
 * Reads of the last acknowledged immutable preference snapshot remain free. */
internal class DirectoryWriteAdmission {
    private val owners = AtomicInteger()
    val pending: Boolean get() = owners.get() > 0

    fun reserveWhenIdle(): AutoCloseable? {
        if (!owners.compareAndSet(0, 1)) return null
        return lease()
    }

    fun beginWrite(): AutoCloseable {
        owners.incrementAndGet()
        return lease()
    }

    private fun lease(): AutoCloseable {
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) check(owners.decrementAndGet() >= 0)
        }
    }
}
