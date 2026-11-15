package com.example.afterglow.wear

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import com.example.afterglow.net.WatchActivityRecord

class WalkingObservationTest {
    private val wall=1_800_000_000_000L
    @Test fun noSensorRemainsUnknownAndCounterIsBaselineDeltaNotDailyTotal() {
        val tracker=WalkingObservation(wall,1000)
        assertNull(tracker.snapshot(wall+100,1100).stepsSinceBaseline)
        tracker.counter(5132f,1_100_000_000,1_100_000_000,wall+100)
        assertEquals(0,tracker.snapshot(wall+100,1100).stepsSinceBaseline)
        tracker.counter(5140f,2_100_000_000,2_100_000_000,wall+1100)
        val record=tracker.snapshot(wall+1200,2200,end=true)
        assertEquals(8,record.stepsSinceBaseline);assertEquals(wall+100,record.stepBaselineAt)
        assertEquals(wall+1100,record.stepsMeasuredAt);assertEquals(wall+1200,record.endedAt)
        assertEquals(record,WatchActivityRecord.parse(record.json("own-record"),wall+1200))
        assertEquals(record.endedAt,tracker.snapshot(wall+2000,3000).endedAt)
    }
    @Test fun counterRollbackNeverLooksLikeZeroSteps() {
        val tracker=WalkingObservation(wall,1000)
        tracker.counter(500f,1_100_000_000,1_100_000_000,wall+100)
        tracker.counter(2f,2_100_000_000,2_100_000_000,wall+1100)
        assertThrows(IllegalStateException::class.java) { tracker.snapshot(wall+1200,2200) }
    }
    @Test fun cachedOrInvalidSensorEventsCannotCreateFreshSteps() {
        val tracker=WalkingObservation(wall,1000)
        tracker.counter(500f,900_000_000,1_100_000_000,wall+100)
        tracker.counter(Float.NaN,1_100_000_000,1_100_000_000,wall+100)
        tracker.counter(5.5f,1_100_000_000,1_100_000_000,wall+100)
        assertNull(tracker.snapshot(wall+100,1100).stepsSinceBaseline)
    }
    @Test fun changedWallClockInvalidatesSessionInsteadOfInventingDuration() {
        val tracker=WalkingObservation(wall,1000)
        assertThrows(IllegalStateException::class.java) { tracker.snapshot(wall+100_000,2000,true) }
    }
    @Test fun ingressRejectsCoercedNumbersStaleFutureAndIncompleteStepProvenance() {
        val good=WalkingObservation(wall,1000).snapshot(wall+100,1100).json("request")
        fun copy()=JSONObject(good.toString())
        for (bad in listOf(copy().put("startedAt",wall.toString()),copy().put("stepsSinceBaseline",1),
            copy().put("observedAt",wall+101),copy().put("source","health-connect"),copy().put("bpm",90))) {
            assertThrows(IllegalArgumentException::class.java) { WatchActivityRecord.parse(bad,wall+100) }
        }
        assertThrows(IllegalArgumentException::class.java) { WatchActivityRecord.parse(good,wall+30_100) }
    }
}
