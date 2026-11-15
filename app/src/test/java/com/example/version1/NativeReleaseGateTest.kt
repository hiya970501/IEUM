package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class NativeReleaseGateTest {
    @Test fun backgroundIsNotAcknowledgedBeforeActualNativeCompletion() {
        var stops = 0; val gate = NativeReleaseGate { stops++ }; val owner = Any()
        gate.foreground(owner); val ticket = gate.begin(); gate.background(owner)
        assertEquals(0, stops); assertFalse(gate.nativeReady)
        gate.complete(ticket); assertTrue(gate.nativeReady); assertEquals(1, stops)
        gate.complete(ticket); gate.background(owner); assertEquals(1, stops)
    }
    @Test fun resumedOwnerPreventsStaleStopAcknowledgement() {
        var stops = 0; val gate = NativeReleaseGate { stops++ }; val owner = Any()
        gate.foreground(owner); val ticket = gate.begin(); gate.background(owner); gate.foreground(owner)
        gate.complete(ticket); assertEquals(0, stops)
        gate.background(owner); assertEquals(1, stops)
    }
    @Test fun allPendingOwnersMustFinishBeforeNextEngineOrStop() {
        val events = mutableListOf<String>(); val gate = NativeReleaseGate { events.add("stopped") }
        val first = gate.begin(); val second = gate.begin()
        gate.whenReady(Any(), { events.add("create") }, { fail("Unexpected failure") })
        gate.complete(first); assertTrue(events.isEmpty())
        gate.complete(second); assertEquals(listOf("stopped", "create"), events)
    }
    @Test fun canceledOrReplacedCreationCannotAllocateAnOldScene() {
        val events = mutableListOf<String>(); val gate = NativeReleaseGate { }; val key = Any()
        val first = gate.begin()
        gate.whenReady(key, { events.add("old") }, { }); gate.whenReady(key, { events.add("latest") }, { })
        gate.complete(first); assertEquals(listOf("latest"), events)
        val second = gate.begin(); gate.whenReady(key, { events.add("canceled") }, { }); gate.cancel(key)
        gate.complete(second); assertEquals(listOf("latest"), events)
    }
    @Test fun failedNativeOwnerNeverBecomesFreeOrAcknowledged() {
        var stopped = false; var created = false; var blocked = false
        val gate = NativeReleaseGate { stopped = true }; val ticket = gate.begin()
        gate.whenReady(Any(), { created = true }, { blocked = true }); gate.fail(ticket); gate.complete(ticket)
        assertTrue(blocked); assertFalse(created); assertFalse(stopped); assertFalse(gate.nativeReady)
    }
}
