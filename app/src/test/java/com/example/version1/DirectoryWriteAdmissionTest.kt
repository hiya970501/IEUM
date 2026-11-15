package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class DirectoryWriteAdmissionTest {
    @Test fun onlyOneScreenCanReserveWhileIdleAndCloseIsIdempotent() {
        val gate = DirectoryWriteAdmission()
        assertFalse(gate.pending)
        val owner = checkNotNull(gate.reserveWhenIdle())
        assertTrue(gate.pending); assertNull(gate.reserveWhenIdle())
        owner.close(); owner.close()
        assertFalse(gate.pending)
        checkNotNull(gate.reserveWhenIdle()).close()
    }

    @Test fun queuedWorkersKeepAdmissionClosedAfterTheirScreenOwnerLeaves() {
        val gate = DirectoryWriteAdmission()
        val screen = checkNotNull(gate.reserveWhenIdle())
        val first = gate.beginWrite(); val queued = gate.beginWrite()
        screen.close(); first.close()
        assertTrue(gate.pending); assertNull(gate.reserveWhenIdle())
        queued.close(); assertFalse(gate.pending)
    }

    @Test fun failureReleasesItsOwnWriteWithoutReleasingOtherOwners() {
        val gate = DirectoryWriteAdmission()
        val screen = checkNotNull(gate.reserveWhenIdle())
        assertTrue(runCatching { gate.beginWrite().use { error("owned failure") } }.isFailure)
        assertTrue(gate.pending); assertNull(gate.reserveWhenIdle())
        screen.close(); assertFalse(gate.pending)
    }
}
