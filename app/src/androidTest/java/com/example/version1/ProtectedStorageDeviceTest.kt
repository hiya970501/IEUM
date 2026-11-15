package com.example.version1

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID
import android.content.SharedPreferences

@RunWith(AndroidJUnit4::class)
class ProtectedStorageDeviceTest {
    @Test fun unconfirmedRollbackRejectsPreviouslyCachedReads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa_cached_rollback_${UUID.randomUUID()}"
        val raw = context.getSharedPreferences(name, 0)
        var acknowledgementsToFail = 0
        val fault = object : SharedPreferences by raw {
            override fun edit(): SharedPreferences.Editor {
                val delegate = raw.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun clear(): SharedPreferences.Editor { delegate.clear(); return this }
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        delegate.putString(key, value); return this
                    }
                    override fun commit(): Boolean {
                        val actual = delegate.commit()
                        return if (acknowledgementsToFail > 0) { acknowledgementsToFail--; false } else actual
                    }
                }
            }
        }
        try {
            val secured = ProtectedPreferences(fault, "preferences:$name")
            assertTrue(secured.edit().putBoolean("allow_control", false).commit())
            assertFalse(secured.getBoolean("allow_control", true))
            assertEquals(1, secured.all.size) // Populate the confirmed read cache.
            // The underlying API may write memory/disk but fail to acknowledge it.
            // Neither that write nor its rollback can be treated as confirmed.
            acknowledgementsToFail = 2
            assertFalse(secured.edit().putBoolean("allow_control", true).commit())
            assertEquals(0, acknowledgementsToFail)
            val reads: List<() -> Any?> = listOf(
                { secured.all }, { secured.contains("allow_control") },
                { secured.getBoolean("allow_control", true) }, { secured.getString("absent", "fallback") },
                { secured.getInt("absent", 7) }, { secured.getStringSet("absent", mutableSetOf("fallback")) }
            )
            reads.forEach { read ->
                assertTrue("Cached state and default values must remain blocked after an unconfirmed rollback", runCatching { read() }.isFailure)
            }
            assertTrue("Later edits must also fail closed", runCatching { secured.edit().clear().commit() }.isFailure)
        } finally {
            raw.edit().clear().commit(); context.deleteSharedPreferences(name)
        }
    }

    @Test fun journalRetainsEmptyGuardAndEncryptsNonemptyRecords() {
        val protection = RecoveryJournalProtection()
        assertEquals("[]", protection.encode(org.json.JSONArray()))
        val legacy = org.json.JSONArray().put(org.json.JSONObject().put("key", "QA_PRIVATE_SETTING")
            .put("before", "QA_BEFORE_VALUE").put("after", "QA_AFTER_VALUE"))
        assertTrue(protection.decode(legacy.toString()).second)
        val sealed = protection.encode(legacy)
        assertEquals(1, org.json.JSONArray(sealed).length())
        assertFalse(sealed.contains("QA_PRIVATE_SETTING")); assertFalse(sealed.contains("QA_BEFORE_VALUE"))
        val (roundTrip, migrated) = protection.decode(sealed)
        assertFalse(migrated); assertEquals(legacy.toString(), roundTrip.toString())
    }
    @Test fun legacyPreferencesMigrateWithoutPlainKeysAndSurviveReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa_protected_${UUID.randomUUID()}"
        val raw = context.getSharedPreferences(name, 0)
        try {
            val expected = linkedMapOf<String, Any>("QA_PRIVATE_NAME" to "QA_SECRET_PAYLOAD",
                "long" to Long.MAX_VALUE - 7, "int" to -7, "float" to 24.5f,
                "boolean" to true, "set" to setOf("one", "two"))
            raw.edit().putString("QA_PRIVATE_NAME", "QA_SECRET_PAYLOAD").putLong("long", Long.MAX_VALUE - 7)
                .putInt("int", -7).putFloat("float", 24.5f).putBoolean("boolean", true)
                .putStringSet("set", setOf("one", "two")).commit()
            val protected = ProtectedPreferences(raw, "preferences:$name")
            assertEquals(expected, protected.all)
            assertEquals(setOf("__sealed_v1"), raw.all.keys)
            val xml = java.io.File(context.dataDir, "shared_prefs/$name.xml").readText()
            assertFalse(xml.contains("QA_PRIVATE_NAME")); assertFalse(xml.contains("QA_SECRET_PAYLOAD"))
            assertFalse(java.io.File(context.dataDir, "shared_prefs/$name.xml.bak").exists())
            val reopened = ProtectedPreferences(raw, "preferences:$name")
            assertEquals(expected, reopened.all)
            assertTrue(reopened.edit().remove("int").putString("another", "value").commit())
            assertFalse(reopened.contains("int")); assertEquals("value", reopened.getString("another", null))
            assertTrue(reopened.edit().clear().commit())
            assertTrue(ProtectedPreferences(raw, "preferences:$name").all.isEmpty())
        } finally { raw.edit().clear().commit(); context.deleteSharedPreferences(name) }
    }

    @Test fun keystoreCipherRejectsTamperingPurposeSwapAndLostKeys() {
        val alias = "qa.afterglow.${UUID.randomUUID()}"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val cipher = SensitiveValueCipher(alias)
            val first = cipher.seal("QA_SECRET", "first")
            val second = cipher.seal("QA_SECRET", "first")
            assertNotEquals(first, second)
            assertEquals("QA_SECRET", cipher.open(first, "first"))
            assertTrue(runCatching { cipher.open(first, "second") }.isFailure)
            val parts = first.split(':').toMutableList()
            val bytes = android.util.Base64.decode(parts.last(), android.util.Base64.NO_WRAP)
            bytes[0] = (bytes[0].toInt() xor 1).toByte()
            parts[parts.lastIndex] = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            assertTrue(runCatching { cipher.open(parts.joinToString(":"), "first") }.isFailure)
            store.deleteEntry(alias)
            assertTrue(runCatching { cipher.open(first, "first") }.isFailure)
            assertFalse("Decryption must not silently replace a lost key", store.containsAlias(alias))
        } finally { if (store.containsAlias(alias)) store.deleteEntry(alias) }
    }

    @Test fun corruptPreferenceCiphertextDoesNotBecomeDefaultSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa_protected_${UUID.randomUUID()}"
        val raw = context.getSharedPreferences(name, 0)
        try {
            raw.edit().putString("__sealed_v1", "AGENC1:broken:ciphertext").commit()
            val protected = ProtectedPreferences(raw, "preferences:$name")
            assertTrue(runCatching { protected.getBoolean("allow_control", true) }.isFailure)
            assertEquals("AGENC1:broken:ciphertext", raw.getString("__sealed_v1", null))
        } finally { raw.edit().clear().commit(); context.deleteSharedPreferences(name) }
    }
}
