package com.example.version1

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LearningPrivacyDeviceTest {
    @Test fun consentOffRestartOpensOnlyForDurableDeletionAndPreservesOtherDrivers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa_privacy_restart_${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, 0)
        val dbName = "$name.db"
        val driver = DriverProfile("qa-restart", "QA")
        try {
            ComfortLearningStore(context, dbName).use { old ->
                val c = ComfortContext(driver.id, "ioniq", "RUN", false, ComfortPhase.ARRIVAL, 30f, 60f)
                old.save(ComfortCase("one", 1000, c, LearnedSetting(23f, 2, 0, 0), true))
                old.save(ComfortCase("two", 1000, c.copy(profile = "other"), LearnedSetting(24f, 2, 0, 0), true))
                LearningPrivacyStore(prefs, old::delete).revoke(driver)
            }
            ComfortLearningStore(context, dbName).use { restarted ->
                val privacy = LearningPrivacyStore(prefs, restarted::delete)
                assertFalse(prefs.getBoolean(driver.preferencePrefix + "learning_consent", true))
                assertFalse(restarted.storageOpened)
                assertTrue(restarted.needsPreparation(consent = false, deletionPending = privacy.hasPending()))
                restarted.prepareProtectedStorage()
                assertEquals(setOf(driver.id), privacy.recover())
                assertEquals(0, restarted.count(driver.id))
                assertEquals(1, restarted.count("other"))
                assertFalse(privacy.hasPending())
            }
            ComfortLearningStore(context, dbName).use { assertFalse(it.needsPreparation(false, false)) }
        } finally {
            context.deleteDatabase(dbName); context.deleteDatabase("$dbName.enc")
            ProtectedPreferences.get(context, "learning_database_keys").edit()
                .remove("key_$dbName").remove("migration_$dbName").commit()
            prefs.edit().clear().commit(); context.deleteSharedPreferences(name)
        }
    }
    @Test fun revocationSurvivesFailedDeletionAndPreservesOtherDrivers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa_learning_privacy_${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, 0)
        val dbName = "$name.db"
        val data = ComfortLearningStore(context, dbName)
        val driver = DriverProfile("qa-one", "QA")
        try {
            val c = ComfortContext(driver.id, "ioniq", "RUN", false, ComfortPhase.ARRIVAL, 30f, 60f, cabinC = 29f)
            data.save(ComfortCase("one", 1000, c, LearnedSetting(23f, 2, 0, 0), true))
            data.save(ComfortCase("two", 1000, c.copy(profile = "other"), LearnedSetting(24f, 2, 0, 0), true))
            prefs.edit().putBoolean(driver.preferencePrefix + "learning_consent", true).commit()
            val failing = LearningPrivacyStore(prefs) { error("injected deletion unavailable") }
            failing.revoke(driver)
            assertFalse(prefs.getBoolean(driver.preferencePrefix + "learning_consent", true))
            assertTrue(failing.pending(driver.id))
            assertTrue(runCatching { failing.recover() }.isFailure)
            assertTrue(failing.pending(driver.id))
            assertEquals(1, data.count(driver.id))
            val restarted = LearningPrivacyStore(prefs, data::delete)
            assertEquals(setOf(driver.id), restarted.recover())
            assertFalse(restarted.pending(driver.id))
            assertEquals(0, data.count(driver.id))
            assertEquals(1, data.count("other"))
            data.readableDatabase.rawQuery("PRAGMA secure_delete", null).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(1, cursor.getInt(0))
            }
        } finally {
            data.close(); context.deleteDatabase(dbName); context.deleteDatabase("$dbName.enc")
            ProtectedPreferences.get(context, "learning_database_keys").edit()
                .remove("key_$dbName").remove("migration_$dbName").commit()
            prefs.edit().clear().commit(); context.deleteSharedPreferences(name)
        }
    }
    @Test fun staleDeletionCompletionCannotDropANewerRequest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa_learning_privacy_${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, 0)
        val driver = DriverProfile("qa-two", "QA")
        try {
            var anotherRequest = true
            lateinit var privacy: LearningPrivacyStore
            privacy = LearningPrivacyStore(prefs) {
                if (anotherRequest) { anotherRequest = false; privacy.revoke(driver) }
            }
            privacy.revoke(driver)
            assertTrue(privacy.recover().isEmpty())
            assertTrue(privacy.pending(driver.id))
            assertEquals(setOf(driver.id), privacy.recover())
            assertFalse(privacy.pending(driver.id))
        } finally { prefs.edit().clear().commit(); context.deleteSharedPreferences(name) }
    }
}
