package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class PreviewPresentationGateTest {
    @Test fun importWithoutAcceptedFrameDoesNotRevealViewport() {
        val gate = PreviewPresentationGate(); val surface = gate.attach(); val ticket = gate.current!!
        gate.markReady(ticket)
        assertFalse(gate.updated(surface, 500)); assertTrue(gate.covered)
        gate.submitted(ticket, false, 500); assertFalse(gate.updated(surface, 500))
    }
    @Test fun queuedPreviousBuffersCannotRevealNewSelection() {
        val gate = PreviewPresentationGate(); val surface = gate.attach(); val old = gate.current!!
        gate.markReady(old); gate.submitted(old, true, 100); assertTrue(gate.updated(surface, 100))
        gate.submitted(old, true, 180)
        val current = gate.begin(); gate.markReady(current); gate.submitted(current, true, 220)
        assertFalse(gate.updated(surface, 180)); assertFalse(gate.updated(surface, 219))
        assertTrue(gate.updated(surface, 220)); assertFalse(gate.covered)
    }
    @Test fun pacerResetStillRequiresAFrameBeyondPreviousSubmissionBound() {
        val gate = PreviewPresentationGate(); val surface = gate.attach(); val first = gate.current!!
        gate.submitted(first, true, 300)
        val current = gate.begin(); gate.markReady(current); gate.submitted(current, true, 280)
        assertFalse(gate.updated(surface, 300)); assertTrue(gate.updated(surface, 301))
    }
    @Test fun targetDoesNotChaseEveryNewFrameAndNeverRevealsTwice() {
        val gate = PreviewPresentationGate(); val surface = gate.attach(); val ticket = gate.current!!
        gate.markReady(ticket); gate.submitted(ticket, true, 100)
        gate.markReady(ticket); gate.submitted(ticket, true, 200)
        assertTrue(gate.updated(surface, 100)); assertFalse(gate.updated(surface, 200))
    }
    @Test fun lateImportOrRenderFromCancelledSelectionDoesNotOpenCurrentOne() {
        val gate = PreviewPresentationGate(); val surface = gate.attach(); val old = gate.current!!
        val current = gate.begin(); gate.markReady(old); gate.submitted(old, true, 100)
        assertFalse(gate.updated(surface, 100)); assertTrue(gate.covered)
        gate.markReady(current); gate.submitted(current, true, 200); assertTrue(gate.updated(surface, 200))
    }
    @Test fun surfaceEpochsDoNotShareTimestamps() {
        val gate = PreviewPresentationGate(); val oldSurface = gate.attach(); val old = gate.current!!
        gate.markReady(old); gate.submitted(old, true, 9000)
        gate.detach(); val currentSurface = gate.attach(); val current = gate.current!!
        gate.markReady(current); gate.submitted(current, true, 10)
        assertFalse(gate.updated(oldSurface, 99999)); assertTrue(gate.updated(currentSurface, 10))
    }
    @Test fun failureCannotBeHiddenByAQueuedTextureUpdate() {
        val gate = PreviewPresentationGate(); val surface = gate.attach(); val ticket = gate.current!!
        gate.markReady(ticket); gate.submitted(ticket, true, 100); gate.fail()
        assertFalse(gate.updated(surface, 1000)); assertTrue(gate.failed); assertTrue(gate.covered)
        val retry = gate.begin(); gate.markReady(retry); gate.submitted(retry, true, 200)
        assertTrue(gate.updated(surface, 200)); assertFalse(gate.failed)
    }
    @Test fun missingOrInvalidProducerTimestampsDoNotBecomeSuccess() {
        val gate = PreviewPresentationGate(); val surface = gate.attach(); val ticket = gate.current!!
        gate.markReady(ticket)
        gate.submitted(ticket, true, 100)
        assertFalse(gate.updated(surface, Long.MAX_VALUE))
        gate.begin(); val invalid = gate.current!!; gate.markReady(invalid)
        listOf(0L, -1L, Long.MAX_VALUE).forEach { gate.submitted(invalid, true, it) }
        assertFalse(gate.updated(surface, Long.MAX_VALUE)); assertTrue(gate.covered)
    }
}
