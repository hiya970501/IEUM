package com.example.version1

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Remove only UUID fixtures created by IeumModeDeviceTest after an interrupted run.
 * No Activity, vehicle connection, user database or user preference map is modified.
 */
@RunWith(AndroidJUnit4::class)
class OwnedModeCleanupDeviceTest {
    @Test fun abandonedOwnedNamespacesAreRemovedWithoutChangingUnrelatedKeys() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ownedModeCleanup") == "emulator")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Restore actual vehicle settings before fixture cleanup", "[]",
            context.getSharedPreferences("recovery_restore", 0).getString("pending", null)?.trim())
        val namespace = "qa_ieum_mode_[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}"
        val preference = Regex("^($namespace)\\.xml(?:\\.bak)?$")
        val database = Regex("^($namespace)\\.db(?:\\.enc)?(?:-(?:wal|shm|journal))?$")
        val names = linkedSetOf<String>()
        File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty().forEach {
            preference.matchEntire(it.name)?.let { match -> names.add(match.groupValues[1]) }
        }
        context.databaseList().forEach { database.matchEntire(it)?.let { match -> names.add(match.groupValues[1]) } }
        check(names.size <= 16) { "Unexpected fixture count; no cleanup performed" }
        val keys = ProtectedPreferences.get(context, "learning_database_keys")
        fun ownedKey(key: String) = names.any { key == "key_$it.db" || key == "migration_$it.db" }
        val unrelated = keys.all.filterKeys { !ownedKey(it) }
        if (names.isNotEmpty()) {
            val edit = keys.edit()
            names.forEach { name ->
                context.deleteSharedPreferences(name)
                for (suffix in listOf(".db", ".db.enc")) {
                    context.deleteDatabase(name + suffix)
                    assertFalse(context.getDatabasePath(name + suffix).exists())
                }
                edit.remove("key_$name.db").remove("migration_$name.db")
                assertFalse(File(context.applicationInfo.dataDir, "shared_prefs/$name.xml").exists())
            }
            assertTrue(edit.commit())
        }
        assertEquals("Unrelated encryption keys must remain unchanged", unrelated, keys.all.filterKeys { !ownedKey(it) })
        assertTrue(keys.all.keys.none(::ownedKey))
        File(context.filesDir, "owned-mode-cleanup.json").writeText(JSONObject()
            .put("ownedNamespacesRemoved", names.size).put("unrelatedKeysPreserved", true)
            .put("remainingOwnedKeys", 0).put("vehicleCommandsIssued", false)
            .put("userDatabasesTouched", false).toString(2))
    }
}
