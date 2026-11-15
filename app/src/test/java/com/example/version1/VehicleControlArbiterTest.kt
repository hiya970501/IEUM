package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class VehicleControlArbiterTest {
    @Test fun aVisibleScreenOrAnOperationStillFinishingInBackgroundBlocksDockControl() {
        val gate = VehicleControlArbiter(); val screen = Any()
        gate.screen(screen, true, false, {})
        assertNull(gate.acquireBackground())
        gate.screen(screen, false, true, null)
        assertNull(gate.acquireBackground())
        gate.screen(screen, false, false, null)
        assertNotNull(gate.acquireBackground())
    }
    @Test fun returningScreenCannotStealOrReleaseBackgroundLease() {
        val gate = VehicleControlArbiter(); val lease = gate.acquireBackground()!!
        gate.screen(Any(), true, false, {})
        assertTrue(gate.backgroundActive)
        gate.release(Any()); assertTrue(gate.owns(lease))
        assertNull(gate.acquireBackground())
        gate.release(lease); assertFalse(gate.backgroundActive)
        assertNull(gate.acquireBackground()) // Visible screen still owns admission.
    }
    @Test fun foregroundDockDelegationIsSingleAndIsRemovedOnPauseOrDestruction() {
        val gate = VehicleControlArbiter(); val first = Any(); val second = Any()
        val callback: (ZeroTapEvent) -> Unit = {}
        gate.screen(first, true, false, callback)
        assertSame(callback, gate.foregroundDock())
        gate.screen(second, true, false, {})
        assertNull(gate.foregroundDock()); assertNull(gate.acquireBackground())
        gate.removeScreen(second); assertSame(callback, gate.foregroundDock())
        gate.screen(first, false, false, callback)
        assertNull(gate.foregroundDock()); assertNotNull(gate.acquireBackground())
    }
    @Test fun anOldCompletionCannotReleaseTheNextOperationsLease() {
        val gate = VehicleControlArbiter(); val old = gate.acquireBackground()!!
        gate.release(old)
        val current = gate.acquireBackground()!!
        gate.release(old)
        assertTrue(gate.owns(current))
    }
}
