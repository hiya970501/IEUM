package com.example.afterglow.phone

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Real Android Keystore and private preferences, with an owned UUID key/file only.
 * Never starts PhoneActivity, Health Connect, a sensor or a vehicle command.
 */
@RunWith(AndroidJUnit4::class)
class PhoneIdentityStorageDeviceTest {
    @Test fun legacyIdentityIsEncryptedAndCorruptionRequiresExplicitRecovery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val prefName = "identity-qa-$suffix"
        val alias = "ieum.identity.qa.$suffix"
        val receipt = JSONObject().put("passed", false).put("physicalPhoneVerified", false)
            .put("healthApiRead", false).put("vehicleCommandsIssued", false).put("productionIdentityTouched", false)
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        check(!keys.containsAlias(alias))
        val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        check(prefs.all.isEmpty())
        try {
            val cipher = PhoneIdentityCipher { create ->
                if (keys.containsAlias(alias)) keys.getKey(alias, null) as SecretKey
                else {
                    check(create)
                    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setRandomizedEncryptionRequired(true).build())
                    }.generateKey()
                }
            }
            val identity = PhoneRelayIdentity.create("IEUM 검증용 별칭")
            check(prefs.edit().putBoolean("enabled", true).putString("phone_id", identity.phoneId)
                .putString("name", identity.displayName).commit())
            val store = AndroidPhoneIdentityStore.create(prefs, cipher)
            val migrated = checkNotNull(store.load())
            assertEquals(identity.phoneId, migrated.phoneId); assertEquals(identity.displayName, migrated.displayName)
            assertEquals(setOf(PhoneIdentityStore.SEALED), prefs.all.keys)
            val primary = File(context.applicationInfo.dataDir, "shared_prefs/$prefName.xml")
            val backup = File(primary.path + ".bak")
            for (file in listOf(primary, backup).filter { it.exists() }) {
                val text = file.readText()
                assertFalse(text.contains(identity.phoneId)); assertFalse(text.contains(identity.displayName))
            }
            assertNull(keys.getKey(alias, null).encoded)
            receipt.put("realAndroidKeystoreNonExportableKey", true).put("legacyIdentityPreserved", true)
                .put("plaintextFieldsRemoved", true).put("preferenceCopiesContainNoFixtureIdentity", true)
            val original = prefs.getString(PhoneIdentityStore.SEALED, null)!!
            val pieces = original.split(':')
            val body = Base64.getDecoder().decode(pieces[2])
            body[0] = (body[0].toInt() xor 1).toByte()
            val altered = pieces[0] + ":" + pieces[1] + ":" + Base64.getEncoder().encodeToString(body)
            check(prefs.edit().putString(PhoneIdentityStore.SEALED, altered).commit())
            assertTrue(runCatching { store.load() }.isFailure)
            assertEquals(altered, prefs.getString(PhoneIdentityStore.SEALED, null))
            check(prefs.edit().putString(PhoneIdentityStore.SEALED, original).commit())
            assertTrue(runCatching { store.load() }.isFailure)
            receipt.put("corruptionPreservedAndBlocked", true)
            store.save(null); assertTrue(prefs.all.isEmpty()); assertNull(store.load())
            val fresh = PhoneRelayIdentity.create("새 검증용 연결")
            store.save(fresh)
            assertEquals(fresh.phoneId, store.load()!!.phoneId); assertNotEquals(identity.phoneId, fresh.phoneId)
            receipt.put("explicitDeleteAndReenrollment", true).put("passed", true)
        } finally {
            val prefsDeleted = context.deleteSharedPreferences(prefName)
            keys.deleteEntry(alias)
            val keyDeleted = !keys.containsAlias(alias)
            receipt.put("ownedPreferencesDeleted", prefsDeleted).put("ownedKeyDeleted", keyDeleted)
            if (!prefsDeleted || !keyDeleted) receipt.put("passed", false)
            File(context.filesDir, "phone-identity-storage-result.json").writeText(receipt.toString())
            assertTrue("Owned fixture storage cleanup required", prefsDeleted && keyDeleted)
        }
    }
}
