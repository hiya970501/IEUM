package com.example.afterglow.phone

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class PhoneIdentityStoreTest {
    private class Blob(var values: Map<String, *> = emptyMap<String, Any>()) : PhoneIdentityBlob {
        var fail = false
        var writes = 0
        override fun snapshot() = values.toMap()
        override fun replace(sealed: String?): Boolean {
            writes++
            if (fail) return false
            values = sealed?.let { mapOf(PhoneIdentityStore.SEALED to it) } ?: emptyMap<String, Any>()
            return true
        }
    }
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = PhoneIdentityCipher { key }
    private val identity = PhoneRelayIdentity("e14ddceb-e682-4f1b-a072-a589a9c24576", "내 휴대폰")
    private fun store(blob: Blob) = PhoneIdentityStore(blob, cipher::seal, cipher::open)

    @Test fun unusedIdentityDoesNotGenerateOrPersistAnything() {
        val blob = Blob()
        val store = PhoneIdentityStore(blob, { error("No encryption should be requested") }, { error("No key should be requested") })
        assertNull(store.load()); assertEquals(0, blob.writes)
    }
    @Test fun legacyMigrationPreservesPairingIdentityAndRemovesPlaintextFields() {
        val blob = Blob(mapOf("enabled" to true, "phone_id" to identity.phoneId, "name" to identity.displayName))
        val restored = store(blob).load()!!
        assertEquals(identity.phoneId, restored.phoneId); assertEquals(identity.displayName, restored.displayName)
        assertEquals(setOf(PhoneIdentityStore.SEALED), blob.values.keys)
        assertFalse(blob.values.values.single().toString().contains(identity.phoneId))
        assertFalse(blob.values.values.single().toString().contains(identity.displayName))
        assertEquals(identity.phoneId, store(blob).load()!!.phoneId)
    }
    @Test fun failedMigrationBlocksUseUntilExplicitSuccessfulSave() {
        val original = mapOf("enabled" to true, "phone_id" to identity.phoneId, "name" to identity.displayName)
        val blob = Blob(original).apply { fail = true }; val store = store(blob)
        assertThrows(IllegalStateException::class.java) { store.load() }
        assertEquals(original, blob.values)
        blob.fail = false
        assertThrows(IllegalStateException::class.java) { store.load() }
        store.save(identity)
        assertEquals(identity.phoneId, store.load()!!.phoneId)
    }
    @Test fun corruptCiphertextIsNotResetAndExplicitOptOutCanDeleteIt() {
        val original = mapOf(PhoneIdentityStore.SEALED to "unreadable")
        val blob = Blob(original); val store = store(blob)
        assertThrows(IllegalStateException::class.java) { store.load() }
        assertEquals(original, blob.values); assertEquals(0, blob.writes)
        store.save(null)
        assertTrue(blob.values.isEmpty()); assertNull(store.load())
        val fresh = PhoneRelayIdentity.create("새 연결")
        store.save(fresh)
        assertEquals(fresh.phoneId, store.load()!!.phoneId); assertNotEquals(identity.phoneId, fresh.phoneId)
    }
    @Test fun ciphertextCannotFallBackToInjectedLegacyPlaintext() {
        val blob = Blob(mapOf(PhoneIdentityStore.SEALED to "bad", "enabled" to true,
            "phone_id" to identity.phoneId, "name" to identity.displayName))
        assertThrows(IllegalStateException::class.java) { store(blob).load() }; assertEquals(0, blob.writes)
    }
    @Test fun authenticatedButInvalidSchemaIsRejectedWithoutReplacingData() {
        for (payload in listOf(
            JSONObject().put("version", 1.5).put("phone_id", identity.phoneId).put("name", "별칭").toString(),
            JSONObject().put("version", 1).put("phone_id", identity.phoneId).put("name", "별칭").put("bpm", 90).toString(),
            JSONObject().put("version", 1).put("phone_id", 12).put("name", "별칭").toString())) {
            val blob = Blob(mapOf(PhoneIdentityStore.SEALED to cipher.seal(payload)))
            assertThrows(IllegalStateException::class.java) { store(blob).load() }; assertEquals(0, blob.writes)
        }
    }
    @Test fun failedEditDoesNotAllowContinuedUseOfPreviouslyLoadedIdentity() {
        val blob = Blob(); val store = store(blob); store.save(identity)
        val previous = blob.values; blob.fail = true
        assertThrows(IllegalStateException::class.java) { store.save(PhoneRelayIdentity.create("다른 연결")) }
        assertEquals(previous, blob.values)
        assertThrows(IllegalStateException::class.java) { store.load() }
        blob.fail = false; store.save(null); assertNull(store.load())
    }
    @Test fun disabledLegacyRemovesResidualAliasWithoutOpeningOrCreatingKey() {
        val blob = Blob(mapOf("enabled" to false, "phone_id" to identity.phoneId, "name" to identity.displayName))
        val store = PhoneIdentityStore(blob, { error("No encryption") }, { error("No decryption") })
        assertNull(store.load()); assertTrue(blob.values.isEmpty())
    }
}
