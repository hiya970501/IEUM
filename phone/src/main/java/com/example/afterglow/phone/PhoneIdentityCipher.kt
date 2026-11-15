package com.example.afterglow.phone

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Independent non-exportable AES-256 key, randomized IV and purpose-bound authenticated data. */
internal class PhoneIdentityCipher(private val key: (Boolean) -> SecretKey) {
    constructor() : this(::androidKey)

    fun seal(value: String): String {
        require(value.length in 1..2048)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(PURPOSE.toByteArray(Charsets.UTF_8))
        check(cipher.iv.size == 12)
        return PREFIX + encode(cipher.iv) + ":" + encode(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    fun open(value: String): String {
        require(value.length <= 8192 && value.startsWith(PREFIX))
        val pieces = value.removePrefix(PREFIX).split(':')
        require(pieces.size == 2)
        val iv = Base64.getDecoder().decode(pieces[0])
        val body = Base64.getDecoder().decode(pieces[1])
        require(iv.size == 12 && body.size in 16..6200)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // A missing/invalidated key must never be replaced while opening old data.
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, iv))
        cipher.updateAAD(PURPOSE.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(body).toString(Charsets.UTF_8).also { require(it.length in 1..2048) }
    }

    companion object {
        private const val PREFIX = "IEUMID1:"
        private const val PURPOSE = "ieum:phone-profile-identity:v1"
        private const val ALIAS = "ieum.phone.profile-identity.aes.v1"
        private val keyGate = Any()
        private fun encode(value: ByteArray) = Base64.getEncoder().encodeToString(value)
        private fun androidKey(create: Boolean): SecretKey = synchronized(keyGate) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(ALIAS)) return@synchronized checkNotNull(store.getKey(ALIAS, null) as? SecretKey)
            check(create) { "Phone identity key unavailable" }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build())
            }.generateKey()
        }
    }
}
