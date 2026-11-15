package com.example.afterglow.phone

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SavedPhoneConnectionTest {
    @Test fun bothTransportsRestoreButNeverAuthorizeWithoutConsentAndPermissionCheck() {
        for(target in listOf(RelayDestination("https://relay.example/ingest/native","a".repeat(32)),RelayDestination.bluetooth("AA:BB:CC:DD:EE:FF","b".repeat(32)))) {
            val restored=SavedPhoneConnection.decode(SavedPhoneConnection.encode(target))
            assertEquals(target.token,restored.token);assertEquals(target.bluetoothAddress,restored.bluetoothAddress)
            val relay=RelaySession().apply {pair(restored);foreground(true)}
            assertNull(relay.transfer());assertFalse(relay.authorizedFor(restored))
            relay.consent(true);assertNull(relay.transfer())
            assertTrue(relay.authorize(relay.beginAuthorization()!!));assertTrue(relay.authorizedFor(restored))
            relay.stop();assertFalse(relay.authorizedFor(restored))
        }
    }
    @Test fun unconsentedCorruptAndUnsafeDestinationsCannotRestore() {
        val valid=SavedPhoneConnection.encode(RelayDestination("https://relay.example/ingest/native","a".repeat(32)))
        for(json in listOf(JSONObject(valid).put("consent",false),JSONObject(valid).put("v","1"),JSONObject(valid).put("address","http://192.168.0.1"),JSONObject(valid).put("token","invalid"),JSONObject(valid).put("extra",true)))
            assertThrows(Exception::class.java) {SavedPhoneConnection.decode(json.toString())}
    }
}
