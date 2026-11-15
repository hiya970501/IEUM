package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class AmbientPlaybackPolicyTest {
    @Test fun navigationInterruptionResumesOnlyRequestedSession() {
        val state = AmbientPlaybackPolicy()
        state.requestStart()
        state.focus(AmbientFocusEvent.TRANSIENT_LOSS)
        assertFalse(state.shouldPlay)
        state.focus(AmbientFocusEvent.GAIN)
        assertTrue(state.shouldPlay)
        assertEquals(.25f, state.volume, .0001f)
    }
    @Test fun stoppedDuringCallDoesNotRestartOnLateGain() {
        val state = AmbientPlaybackPolicy()
        state.requestStart()
        state.focus(AmbientFocusEvent.TRANSIENT_LOSS)
        state.stop()
        state.focus(AmbientFocusEvent.GAIN)
        assertFalse(state.shouldPlay)
    }
    @Test fun permanentLossNeedsNewExplicitStart() {
        val state = AmbientPlaybackPolicy()
        state.requestStart()
        state.focus(AmbientFocusEvent.LOSS)
        state.focus(AmbientFocusEvent.GAIN)
        assertFalse(state.shouldPlay)
        state.requestStart()
        assertTrue(state.shouldPlay)
    }
    @Test fun duckReducesVolumeAndGainRestoresIt() {
        val state = AmbientPlaybackPolicy()
        state.requestStart()
        state.focus(AmbientFocusEvent.DUCK)
        assertTrue(state.shouldPlay)
        assertEquals(.06f, state.volume, .0001f)
        state.focus(AmbientFocusEvent.GAIN)
        assertEquals(.25f, state.volume, .0001f)
    }
    @Test fun duckNeverResumesPausedAudio() {
        val state = AmbientPlaybackPolicy()
        state.requestStart()
        state.focus(AmbientFocusEvent.TRANSIENT_LOSS)
        state.focus(AmbientFocusEvent.DUCK)
        assertFalse(state.shouldPlay)
    }
}
