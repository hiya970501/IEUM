package com.example.afterglow.phone

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration

class WorkoutRelaySnapshotTest {
    private fun snapshot(kind: String = "run", minutes: Long = 40, bpm: Long? = 130) =
        WorkoutRelaySnapshot(kind, 1_700_000_000_000, Duration.ofMinutes(minutes), bpm,
            if (bpm == null) null else 1_700_000_001_000)

    @Test fun shortWorkoutsAreNeverInventedAsOneMinute() {
        listOf(0L, 1L, 59L).forEach { seconds ->
            val value = WorkoutRelaySnapshot("run", 10, Duration.ofSeconds(seconds))
            assertEquals("1분 미만", value.durationLabel)
            assertFalse(JSONObject(value.payload("request-1")).has("durationMinutes"))
        }
    }
    @Test fun fullMinutesUseMeasuredDuration() {
        val value = WorkoutRelaySnapshot("run", 10, Duration.ofSeconds(119))
        assertEquals(1, value.durationMinutes)
        assertEquals(1, JSONObject(value.payload("request-1")).getInt("durationMinutes"))
        assertEquals(1440, snapshot(minutes = 1440).durationMinutes)
    }
    @Test fun longWorkoutsAreNotClampedToAnInvented1440Minutes() {
        val value = snapshot(minutes = 1441)
        assertNull(value.durationMinutes)
        assertFalse(JSONObject(value.payload("request-1")).has("durationMinutes"))
    }
    @Test fun correctedBpmAtSameSampleTimeIsANewObservation() {
        assertNotEquals(snapshot(bpm = 130).fingerprint, snapshot(bpm = 131).fingerprint)
    }
    @Test fun correctedWorkoutKindOrDurationIsNotSuppressed() {
        assertNotEquals(snapshot().fingerprint, snapshot(kind = "hike").fingerprint)
        assertNotEquals(snapshot().fingerprint, snapshot(minutes = 41).fingerprint)
    }
    @Test fun receiptIdentifiersDoNotCreateNewHealthObservations() {
        val first = snapshot(); val same = snapshot()
        assertEquals(first.fingerprint, same.fingerprint)
        assertNotEquals(first.payload("request-1"), same.payload("request-2"))
        assertFalse(first.toString().contains("130"))
    }
    @Test fun missingHeartRateRemainsMissingInPayload() {
        val json = JSONObject(snapshot(bpm = null).payload("request-1"))
        assertFalse(json.has("bpm")); assertFalse(json.has("bpmMeasuredAt"))
        assertNotEquals(snapshot().fingerprint, snapshot(bpm = null).fingerprint)
    }
    @Test fun profileIdentifierIsAbsentByDefault() {
        val json = JSONObject(snapshot().payload("request-1"))
        assertFalse(json.has("driverId")); assertFalse(json.has("driverName"))
    }
    @Test fun explicitIdentityAppliesToThisPayloadAndFingerprint() {
        val identity = PhoneRelayIdentity("e14ddceb-e682-4f1b-a072-a589a9c24576", "내 휴대폰")
        val identified = WorkoutRelaySnapshot("run", 1_700_000_000_000, Duration.ofMinutes(40),
            130, 1_700_000_001_000, identity)
        val json = JSONObject(identified.payload("request-1"))
        assertEquals(identity.phoneId, json.getString("driverId")); assertEquals(identity.displayName, json.getString("driverName"))
        assertNotEquals(snapshot().fingerprint, identified.fingerprint)
    }
    @Test fun invalidOrUnpairedHealthInputsRejected() {
        assertThrows(IllegalArgumentException::class.java) { WorkoutRelaySnapshot("run", 10, Duration.ofSeconds(-1)) }
        assertThrows(IllegalArgumentException::class.java) { WorkoutRelaySnapshot("unknown", 10, Duration.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { WorkoutRelaySnapshot("run", 10, Duration.ZERO, 130, null) }
        assertThrows(IllegalArgumentException::class.java) { WorkoutRelaySnapshot("run", 10, Duration.ZERO, 300, 20) }
    }
}
