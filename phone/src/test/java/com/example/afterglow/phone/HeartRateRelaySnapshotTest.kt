package com.example.afterglow.phone

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HeartRateRelaySnapshotTest {
    @Test fun standaloneHeartDoesNotInventWorkoutOrDirectSensorSource() {
        val value=HeartRateRelaySnapshot(88,1700000000000)
        val body=JSONObject(value.payload("pulse-1"))
        assertEquals("health-connect",body.getString("source"))
        assertEquals(1700000000000,body.getLong("measuredAt"))
        assertEquals(88,body.getInt("bpm"))
        assertFalse(body.has("kind"));assertFalse(body.has("endedAt"));assertFalse(body.has("durationMinutes"))
        assertEquals(value.fingerprint,HeartRateRelaySnapshot(88,1700000000000).fingerprint)
        assertNotEquals(value.fingerprint,HeartRateRelaySnapshot(89,1700000000000).fingerprint)
        assertFalse(value.toString().contains("88"))
    }
    @Test fun identityAndChangedMeasurementBelongToDifferentObservations() {
        val identity=PhoneRelayIdentity("e3d5fb3a-e350-4b93-a2ba-6c802e867849","QA")
        val value=HeartRateRelaySnapshot(90,100,identity)
        assertEquals(identity.phoneId,JSONObject(value.payload("pulse-2")).getString("driverId"))
        assertNotEquals(value.fingerprint,HeartRateRelaySnapshot(90,101,identity).fingerprint)
        assertNotEquals(value.fingerprint,HeartRateRelaySnapshot(90,100).fingerprint)
        assertThrows(IllegalArgumentException::class.java) { HeartRateRelaySnapshot(300,100) }
        assertThrows(IllegalArgumentException::class.java) { value.payload("bad id") }
    }
}
