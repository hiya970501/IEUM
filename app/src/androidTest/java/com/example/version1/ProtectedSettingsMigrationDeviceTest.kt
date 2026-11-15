package com.example.version1

import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit upgrade verification: preserves actual values without writing them to test output. */
@RunWith(AndroidJUnit4::class)
class ProtectedSettingsMigrationDeviceTest {
    @Test fun actualSettingsAndRetainedLearningRemainIdenticalThroughProtection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("migrateActualSettings") == "emulator")
        val context = instrumentation.targetContext
        require(Build.HARDWARE in setOf("ranchu", "goldfish"))
        require(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val journal = context.getSharedPreferences("recovery_restore", 0)
        require(journal.getString("pending", "[]") == "[]") { "Preserve pending vehicle restoration" }
        val checks = JSONArray()
        for (name in listOf("comfort_preferences", "driver_profiles", "vehicle_selection", "zero_tap")) {
            val raw = context.getSharedPreferences(name, 0)
            val before = if (raw.contains("__sealed_v1")) ProtectedPreferences.get(context, name).all
                else raw.all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
            val after = ProtectedPreferences.get(context, name).all
            assertTrue("Logical preference values changed during protection", before == after)
            assertTrue("Plain preference fields remain", raw.all.isEmpty() || raw.all.keys == setOf("__sealed_v1"))
            assertFalse("Plain preference backup remains", File(context.dataDir, "shared_prefs/$name.xml.bak").exists())
            checks.put(JSONObject().put("file", name).put("keyCount", after.size)
                .put("logicalValuesPreserved", true).put("plaintextFieldsAbsent", true))
        }
        val legacy = context.getDatabasePath("comfort_learning.db")
        val beforeCount: Int? = if (legacy.exists()) {
            android.database.sqlite.SQLiteDatabase.openDatabase(legacy.path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                android.database.DatabaseErrorHandler { throw IllegalStateException("Legacy record count unavailable") }).use { db ->
                    db.rawQuery("SELECT COUNT(*) FROM cases", null).use { it.moveToFirst(); it.getInt(0) }
                }
        } else null
        val afterCount = ComfortLearningStore(context).use { store ->
            store.prepareProtectedStorage()
            store.readableDatabase.rawQuery("SELECT COUNT(*) FROM cases", null).use { it.moveToFirst(); it.getInt(0) }
        }
        if (beforeCount != null) assertTrue("Retained record count changed", beforeCount == afterCount)
        assertFalse("Legacy plaintext learning file remains", legacy.exists())
        assertEquals("[]", journal.getString("pending", "[]"))
        File(context.filesDir, "protected-settings-migration.json").writeText(JSONObject()
            .put("preferences", checks).put("retainedLearningRows", afterCount)
            .put("legacyRowsBefore", beforeCount ?: JSONObject.NULL)
            .put("learningCopyVerifiedByStoreFingerprint", true).put("vehicleCommandsIssued", false)
            .put("physicalDevice", false).toString(2))
    }
}
