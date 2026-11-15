package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class AutoMusicResumePolicyTest {
    @Test fun onlyAnIdentifiedPausedSongWithPlayCapabilityCanResume() {
        assertEquals(AutoMusicDecision.RESUME_PAUSED, AutoMusicResumePolicy.decide(2, 4, true))
        assertEquals(AutoMusicDecision.NEED_SELECTION, AutoMusicResumePolicy.decide(2, 2, true))
        assertEquals(AutoMusicDecision.NEED_SELECTION, AutoMusicResumePolicy.decide(2, 4, false))
        for (state in listOf(0, 1, 6, 7, 8, 99))
            assertEquals(AutoMusicDecision.NEED_SELECTION, AutoMusicResumePolicy.decide(state, Long.MAX_VALUE, true))
    }
    @Test fun playingSessionIsNeverRestarted() {
        assertEquals(AutoMusicDecision.KEEP_PLAYING, AutoMusicResumePolicy.decide(3, 0, true))
    }
}
