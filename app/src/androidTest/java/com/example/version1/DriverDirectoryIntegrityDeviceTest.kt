package com.example.version1

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.util.UUID

/** Real protected store, isolated owned namespace; never edits the user's directory. */
@RunWith(AndroidJUnit4::class)
class DriverDirectoryIntegrityDeviceTest {
    @Test fun absentDirectoryDoesNotPersistAnInventedIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa_directory_absent_${UUID.randomUUID()}"
        val raw = context.getSharedPreferences(name, 0)
        val secured = ProtectedPreferences.get(context, name)
        try {
            assertEquals(DriverProfileSettings(), DriverProfileStore(secured).load())
            assertFalse(secured.contains("state"))
            assertTrue(raw.all.isEmpty())
        } finally {
            secured.edit().clear().commit()
            context.deleteSharedPreferences(name)
        }
    }

    @Test fun unreadableExistingDirectoryKeepsCiphertextAndNeverReturnsDefault() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa_directory_integrity_${UUID.randomUUID()}"
        val raw = context.getSharedPreferences(name, 0)
        val secured = ProtectedPreferences.get(context, name)
        val store = DriverProfileStore(secured)
        val original = DriverProfileSettings().add("QA 다른 운전자")
        val encoded = DriverProfileCodec.encode(original)
        val unsupported = JSONObject(encoded).put("version", 2).toString()
        try {
            assertTrue(secured.edit().putString("state", unsupported).commit())
            val cipherBefore = raw.all.toMap()
            assertEquals(setOf("__sealed_v1"), cipherBefore.keys)
            assertFalse(cipherBefore.values.any { it.toString().contains("QA 다른 운전자") })
            repeat(2) {
                assertThrows(IllegalStateException::class.java) { store.load() }
                assertEquals(unsupported, secured.getString("state", null))
                assertEquals(cipherBefore, raw.all)
            }
            // Repairing the owned fixture restores its exact profile values;
            // the failing read itself must never perform a reset or a write.
            assertTrue(secured.edit().putString("state", encoded).commit())
            assertEquals(original, store.load())
        } finally {
            secured.edit().clear().commit()
            context.deleteSharedPreferences(name)
        }
    }
}
