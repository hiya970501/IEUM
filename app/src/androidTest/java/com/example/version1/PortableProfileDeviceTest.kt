package com.example.version1

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real SharedPreferences commits, isolated namespaces; no vehicle, profile or health fixtures. */
@RunWith(AndroidJUnit4::class)
class PortableProfileDeviceTest {
    @Test fun importIsolationAndInterruptedPublishRecovery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "qa_portable_" + UUID.randomUUID().toString().replace("-", "")
        val prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
        var directory = DriverProfileSettings()
        val data = PortableDriverProfile("이동할 선호", ActivityKind.entries.map { PortableActivityPreference(it, 23f, 3) }, MusicSource.VLC)
        var failPublish = false
        val store = PortableDriverProfileStore(prefs, { directory }, { if (failPublish) error("Injected publish failure") else directory = it })
        try {
            assertTrue(prefs.edit().putString("bridge_host", "protected").putBoolean("learning_consent", true).commit())
            val expected = prefs.all.toMap()
            val imported = store.install(data, directory)
            assertEquals(DriverProfile.DEFAULT_ID, imported.selectedProfileId)
            assertEquals(2, imported.profiles.size)
            val created = imported.profiles.last()
            assertEquals(data, store.snapshot(created))
            assertFalse(prefs.contains(created.preferencePrefix + "learning_consent"))
            assertFalse(prefs.contains(created.preferencePrefix + "run_wet"))
            expected.forEach { (key, value) -> assertEquals(value, prefs.all[key]) }
            assertFalse(prefs.contains(PortableDriverProfileStore.PENDING_KEY))
            val duplicate = store.install(data, directory)
            assertEquals(data.name + " (2)", duplicate.profiles.last().name)
            assertEquals(data.copy(name = data.name + " (2)"), store.snapshot(duplicate.profiles.last()))
            assertEquals(data, store.snapshot(created))
            val beforeFailedImport = prefs.all.toMap()
            failPublish = true
            try { store.install(data, directory); fail("Publication failure accepted") } catch (_: IllegalStateException) { }
            assertEquals(beforeFailedImport, prefs.all)
            assertEquals(duplicate, directory)

            val staged = DriverProfile.create("未公開")
            assertTrue(prefs.edit().putString(PortableDriverProfileStore.PENDING_KEY, staged.id)
                .putFloat(staged.preferencePrefix + "run_temperature", 25f).commit())
            store.recoverPending()
            assertFalse(prefs.contains(staged.preferencePrefix + "run_temperature"))
            assertEquals(beforeFailedImport, prefs.all)

            assertTrue(prefs.edit().putString(PortableDriverProfileStore.PENDING_KEY, created.id).commit())
            store.recoverPending()
            assertEquals(data, store.snapshot(created))
            assertEquals(beforeFailedImport, prefs.all)
        } finally { prefs.edit().clear().commit(); context.deleteSharedPreferences(namespace) }
    }
}
