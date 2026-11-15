package com.example.version1

import android.content.ContentValues
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LearningEncryptionDeviceTest {
    private fun cleanup(context: Context, name: String) {
        context.deleteDatabase(name); context.deleteDatabase("$name.enc")
        ProtectedPreferences.get(context, "learning_database_keys").edit()
            .remove("key_$name").remove("migration_$name").commit()
    }

    /** Run alone in a fresh instrumentation process: verifies native mapping, not heap size. */
    @Test fun inactiveStartupDoesNotMapCipherNativeLibrary() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("nativeMappingFixture") == "fresh")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun mapped() = NativeLibraryMappingTestSupport.isMapped(context, "libsqlcipher.so")
        assertFalse("Requires a fresh target process", mapped())
        val name = "qa_cipher_${UUID.randomUUID()}.db"
        try {
            ComfortLearningStore(context, name).use { store ->
                if (store.needsPreparation(consent = false, deletionPending = false)) store.prepareProtectedStorage()
                assertFalse(store.storageOpened)
                assertFalse(context.getDatabasePath("$name.enc").exists())
            }
            assertFalse("Inactive storage must not map SQLCipher", mapped())
            // Positive control: modern Android maps uncompressed libraries using base.apk
            // offsets, so a filename-only /proc/maps search would silently miss them.
            ComfortLearningStore(context, name).use { it.prepareProtectedStorage() }
            assertTrue("Mapping detector must see an actually loaded SQLCipher", mapped())
        } finally { cleanup(context, name) }
    }

    @Test fun inactiveProtectedStorageStaysClosedButConsentAndCleanupRequireOpening() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa_cipher_${UUID.randomUUID()}.db"
        try {
            ComfortLearningStore(context, name).use { store ->
                assertFalse(store.needsPreparation(consent = false, deletionPending = false))
                assertFalse(store.storageOpened)
                assertFalse(context.getDatabasePath("$name.enc").exists())
                assertTrue(store.needsPreparation(consent = true, deletionPending = false))
                store.prepareProtectedStorage()
                assertTrue(store.storageOpened)
                assertEquals(0, store.count("QA"))
            }
            val before = context.getDatabasePath("$name.enc").readBytes()
            ComfortLearningStore(context, name).use { store ->
                assertFalse(store.needsPreparation(consent = false, deletionPending = false))
                assertFalse(store.storageOpened)
                assertTrue(store.needsPreparation(consent = false, deletionPending = true))
            }
            assertArrayEquals(before, context.getDatabasePath("$name.enc").readBytes())
        } finally { cleanup(context, name) }
    }

    @Test fun orphanPlaintextJournalsAreDetectedWithoutCreatingKeysOrLosingBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (suffix in listOf("-journal", "-wal", "-shm")) {
            val name = "qa_cipher_${UUID.randomUUID()}.db"
            val orphan = java.io.File(context.getDatabasePath(name).path + suffix)
            val fixture = "QA_ORPHAN_RECOVERY_BYTES".toByteArray()
            try {
                orphan.parentFile!!.mkdirs(); orphan.writeBytes(fixture)
                ComfortLearningStore(context, name).use { store ->
                    assertTrue(store.needsPreparation(consent = false, deletionPending = false))
                    assertTrue(runCatching { store.prepareProtectedStorage() }.isFailure)
                    assertFalse(store.storageOpened)
                }
                assertArrayEquals(fixture, orphan.readBytes())
                assertFalse(context.getDatabasePath("$name.enc").exists())
                assertFalse(ProtectedPreferences.get(context, "learning_database_keys").contains("key_$name"))
            } finally { orphan.delete(); cleanup(context, name) }
        }
    }

    @Test fun plaintextMigrationPreservesAllDriversAndResumesInterruptedErasure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa_cipher_${UUID.randomUUID()}.db"
        try {
            context.openOrCreateDatabase(name, 0, null).use { legacy ->
                legacy.execSQL("CREATE TABLE cases (id INTEGER PRIMARY KEY, profile TEXT NOT NULL, vehicle TEXT NOT NULL, bucket TEXT NOT NULL, session TEXT NOT NULL, phase TEXT NOT NULL, at INTEGER NOT NULL, satisfied INTEGER NOT NULL, payload TEXT NOT NULL, UNIQUE(profile,session,phase))")
                legacy.execSQL("CREATE INDEX context_lookup ON cases(profile,vehicle,bucket,at)")
                legacy.version = 1
                for (profile in listOf("QA_PRIVATE_ONE", "QA_PRIVATE_TWO")) {
                    legacy.insertOrThrow("cases", null, ContentValues().apply {
                        put("profile", profile); put("vehicle", "ioniq"); put("bucket", "QA_CLIMATE")
                        put("session", "QA_PRIVATE_SESSION"); put("phase", "ARRIVAL"); put("at", 1000L)
                        put("satisfied", 1)
                        put("payload", """{"activity":"RUN","wet":false,"bpm":88,"temperature":24,"fan":2,"heat":0,"vent":0}""")
                    })
                }
            }
            ComfortLearningStore(context, name).use { protected ->
                assertTrue(protected.needsPreparation(consent = false, deletionPending = false))
                protected.prepareProtectedStorage()
                assertEquals(1, protected.count("QA_PRIVATE_ONE"))
                assertEquals(1, protected.count("QA_PRIVATE_TWO"))
                val contextKey = ComfortContext("QA_PRIVATE_ONE", "ioniq", "RUN", false,
                    ComfortPhase.ARRIVAL, 30f, 60f)
                assertEquals(88, protected.candidates(contextKey).single().context.bpm)
            }
            assertFalse(context.getDatabasePath(name).exists())
            val target = context.getDatabasePath("$name.enc")
            val bytes = target.readBytes()
            assertFalse(bytes.copyOfRange(0, 16).contentEquals("SQLite format 3\u0000".toByteArray()))
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("QA_PRIVATE_ONE"))
            // Mimic power loss while erasing legacy bytes, after verified encrypted publication.
            context.getDatabasePath(name).writeBytes(ByteArray(4096))
            ComfortLearningStore(context, name).use { assertEquals(1, it.count("QA_PRIVATE_TWO")) }
            assertFalse(context.getDatabasePath(name).exists())
        } finally { cleanup(context, name) }
    }

    @Test fun invalidLegacyVersionIsPreservedAndCipherCorruptionNeverResetsData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa_cipher_${UUID.randomUUID()}.db"
        try {
            context.openOrCreateDatabase(name, 0, null).use { it.version = 2 }
            val source = context.getDatabasePath(name)
            val before = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
            ComfortLearningStore(context, name).use { assertTrue(runCatching { it.count("QA") }.isFailure) }
            assertTrue(source.exists())
            assertArrayEquals(before, MessageDigest.getInstance("SHA-256").digest(source.readBytes()))
        } finally { cleanup(context, name) }

        val second = "qa_cipher_${UUID.randomUUID()}.db"
        try {
            ComfortLearningStore(context, second).use { store ->
                val c = ComfortContext("QA", "ioniq", "RUN", false, ComfortPhase.ARRIVAL, 30f, 60f)
                store.save(ComfortCase("QA_SESSION", 1000, c, LearnedSetting(24f, 2, 0, 0), true))
            }
            val target = context.getDatabasePath("$second.enc")
            RandomAccessFile(target, "rw").use { file ->
                file.seek(100); val value = file.readByte(); file.seek(100); file.writeByte(value.toInt() xor 1)
            }
            val damaged = MessageDigest.getInstance("SHA-256").digest(target.readBytes())
            ComfortLearningStore(context, second).use { assertTrue(runCatching { it.count("QA") }.isFailure) }
            assertTrue(target.exists())
            assertArrayEquals(damaged, MessageDigest.getInstance("SHA-256").digest(target.readBytes()))
        } finally { cleanup(context, second) }
    }
}
