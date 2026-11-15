package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class ForegroundResourceGateTest {
    @Test fun screenOffBlocksEvenRetainedTopResumedActivity() {
        val gate = ForegroundResourceGate(true)
        gate.start(); gate.resume(); gate.top(true); assertTrue(gate.allowed)
        gate.interactive(false); assertFalse(gate.allowed)
        gate.top(true); gate.resume(); assertFalse(gate.allowed)
        gate.interactive(true); assertTrue(gate.allowed)
    }
    @Test fun screenWakeCannotReviveStoppedActivity() {
        val gate = ForegroundResourceGate(true)
        gate.start(); gate.resume(); gate.top(true); gate.interactive(false)
        gate.stop(); gate.interactive(true); assertFalse(gate.allowed)
    }
    @Test fun modernActivityCannotAcquireBeforeTopResumed() {
        val gate = ForegroundResourceGate(true)
        gate.start(); assertFalse(gate.allowed); gate.resume(); assertFalse(gate.allowed)
        gate.top(true); assertTrue(gate.allowed)
    }
    @Test fun focusHandoffWithoutOnStopReleasesResourcesAndCanReacquire() {
        val gate = ForegroundResourceGate(true)
        gate.start(); gate.resume(); gate.top(true); assertTrue(gate.allowed)
        gate.top(false); assertFalse(gate.allowed); assertTrue(gate.awaitingInputFocus)
        gate.top(true); assertTrue(gate.allowed); assertFalse(gate.awaitingInputFocus)
    }
    @Test fun inputFocusHintDoesNotMisdescribeScreenOffPausedStoppedOrLegacyActivity() {
        val gate = ForegroundResourceGate(true)
        gate.start(); assertFalse(gate.awaitingInputFocus)
        gate.resume(); assertTrue(gate.awaitingInputFocus)
        gate.interactive(false); assertFalse(gate.awaitingInputFocus)
        gate.interactive(true); assertTrue(gate.awaitingInputFocus)
        gate.pause(); assertFalse(gate.awaitingInputFocus)
        gate.stop(); assertFalse(gate.awaitingInputFocus)
        val legacy = ForegroundResourceGate(false)
        legacy.start(); legacy.resume(); assertTrue(legacy.allowed); assertFalse(legacy.awaitingInputFocus)
    }
    @Test fun lateTopCallbackAfterStopCannotRestartResources() {
        val gate = ForegroundResourceGate(true)
        gate.start(); gate.resume(); gate.top(true); gate.stop(); gate.top(true)
        assertFalse(gate.allowed)
        gate.start(); gate.resume(); assertFalse(gate.allowed)
    }
    @Test fun pauseAlwaysReleasesEvenWhenNoTopFalseCallbackArrives() {
        val gate = ForegroundResourceGate(true)
        gate.start(); gate.resume(); gate.top(true); gate.pause(); assertFalse(gate.allowed)
        gate.resume(); assertFalse(gate.allowed)
    }
    @Test fun api28UsesResumedLifetimeAndStillReleasesOnPauseAndStop() {
        val gate = ForegroundResourceGate(false)
        gate.start(); assertFalse(gate.allowed); gate.resume(); assertTrue(gate.allowed)
        gate.pause(); assertFalse(gate.allowed); gate.resume(); assertTrue(gate.allowed)
        gate.stop(); assertFalse(gate.allowed)
    }
}
