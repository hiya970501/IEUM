package com.example.afterglow.phone

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneRelayIdentityTest {
    private val id = "e14ddceb-e682-4f1b-a072-a589a9c24576"
    @Test fun normalizesCanonicalIdAndTrimsAlias() {
        val identity = PhoneRelayIdentity(id.uppercase(), "  내 휴대폰  ")
        assertEquals(id, identity.phoneId); assertEquals("내 휴대폰", identity.displayName)
    }
    @Test fun rejectsHardwareLikeOrMalformedIdentifiers() {
        listOf("android-device-123", "1-1-1-1-1", "../profile", "").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { PhoneRelayIdentity(value, "별칭") }
        }
    }
    @Test fun boundsAliasesAndSuppliesNonPersonalDefault() {
        assertEquals("Android 휴대폰", PhoneRelayIdentity(id, " ").displayName)
        assertThrows(IllegalArgumentException::class.java) { PhoneRelayIdentity(id, "가".repeat(25)) }
        assertThrows(IllegalArgumentException::class.java) { PhoneRelayIdentity(id, "운전자\n이름") }
    }
    @Test fun renewedOptInCanCreateANewAppIdentifier() {
        val first = PhoneRelayIdentity.create("내 휴대폰")
        val second = PhoneRelayIdentity.create("내 휴대폰")
        assertNotEquals(first.phoneId, second.phoneId)
        assertEquals(first.phoneId, java.util.UUID.fromString(first.phoneId).toString())
    }
    @Test fun connectionClaimContainsNoInventedHealthData() {
        val payload = JSONObject(PhoneRelayIdentity(id, "내 휴대폰").connectionPayload("request-1"))
        assertEquals(id, payload.getString("driverId")); assertEquals("health-connect", payload.getString("source"))
        assertFalse(payload.has("bpm")); assertFalse(payload.has("kind")); assertFalse(payload.has("endedAt"))
    }
    @Test fun diagnosticStringDoesNotRevealIdentifierOrAlias() {
        val diagnostic = PhoneRelayIdentity(id, "별칭검사").toString()
        assertFalse(diagnostic.contains(id)); assertFalse(diagnostic.contains("별칭검사"))
    }
}
