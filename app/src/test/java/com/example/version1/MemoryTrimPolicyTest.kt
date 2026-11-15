package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class MemoryTrimPolicyTest {
    @Test fun hiddenNoticeDoesNotMeanMemoryPressureEvenAfterForegroundReentry() {
        listOf(20, 21, 30).forEach { level ->
            listOf(true, false).forEach { foreground ->
                assertFalse(MemoryTrimPolicy.shouldReleaseForPressure(level, foreground))
            }
        }
    }
    @Test fun actualRunningPressureStillReleasesResourcesInBothVisibilityStates() {
        listOf(10, 12, 15, 17).forEach { level ->
            listOf(true, false).forEach { foreground ->
                assertTrue(MemoryTrimPolicy.shouldReleaseForPressure(level, foreground))
            }
        }
    }
    @Test fun queuedBackgroundPressureDoesNotDestroyNewForegroundScene() {
        // BACKGROUND, intermediate values, MODERATE and COMPLETE describe the background LRU.
        listOf(40, 45, 60, 80).forEach { level ->
            assertTrue(MemoryTrimPolicy.shouldReleaseForPressure(level, foreground = false))
            assertFalse(MemoryTrimPolicy.shouldReleaseForPressure(level, foreground = true))
        }
    }
    @Test fun normalAndModerateRunningNoticesDoNotForceSceneDestruction() {
        listOf(0, 5).forEach { level ->
            listOf(true, false).forEach { foreground ->
                assertFalse(MemoryTrimPolicy.shouldReleaseForPressure(level, foreground))
            }
        }
    }
}
