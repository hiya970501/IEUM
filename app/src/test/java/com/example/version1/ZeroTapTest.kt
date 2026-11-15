package com.example.version1

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.time.Instant

class ZeroTapTest {
    private val now = 1_800_000_000_000L
    private val event = ZeroTapEvent("e3d5fb3a-e350-4b93-a2ba-6c802e867849", "a".repeat(64),
        "8a289849-51d9-4e4b-b5c8-5f32bd22d4c2", now)
    private fun reason(value: ZeroTapEvent = event, enabled: Boolean = true, allowed: Boolean = true,
                       replay: Boolean = false, lastAttempt: Long = 0) = zeroTapRejection(value,
        event.phoneId, zeroTapDigest(event.credential), now, enabled, allowed, replay, lastAttempt)
    @Test fun validEnrollmentCanProceed() { assertNull(reason()) }
    @Test fun disabledCannotApply() { assertEquals("disabled", reason(enabled = false)) }
    @Test fun wrongDeviceSecretCannotApply() {
        assertEquals("unregistered_device", reason(event.copy(credential = "b".repeat(64))))
        assertEquals("unregistered_device", reason(event.copy(phoneId = "another")))
    }
    @Test fun futureAndExpiredCannotApply() {
        assertEquals("expired_event", reason(event.copy(occurredAt = now + 5_001)))
        assertEquals("expired_event", reason(event.copy(occurredAt = now - 30_001)))
    }
    @Test fun replayCooldownAndNonParkCannotApply() {
        assertEquals("duplicate_event", reason(replay = true))
        assertEquals("dock_cooldown", reason(lastAttempt = now - 59_999))
        assertEquals("park_or_busy_required", reason(allowed = false))
    }
    @Test fun realTimestampAndDockedFlagRequired() {
        val json = JSONObject().put("phoneId", event.phoneId).put("eventId", event.eventId)
            .put("credential", event.credential).put("docked", true)
            .put("occurredAt", Instant.ofEpochMilli(now).toString())
        assertEquals(event, ZeroTapEvent.parse(json))
        json.put("docked", false)
        assertTrue(runCatching { ZeroTapEvent.parse(json) }.isFailure)
    }
}
