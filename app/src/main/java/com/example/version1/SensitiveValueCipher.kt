package com.example.version1

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM with a non-exportable Android Keystore key and storage-purpose binding.
 * Decryption failure never generates a replacement key or resets protected settings.
 */
internal class SensitiveValueCipher(private val alias: String = "afterglow.sensitive.aes.v1") {
    private fun key(create: Boolean): SecretKey = synchronized(keyGate) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) {
            return@synchronized checkNotNull(store.getKey(alias, null) as? SecretKey) { "Protected key unavailable" }
        }
        check(create) { "Protected key unavailable" }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    fun seal(plain: String, purpose: String): String {
        require(plain.length <= MAX_CHARS && purpose.length in 1..200)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD("afterglow:v1:$purpose".toByteArray(Charsets.UTF_8))
        return PREFIX + encode(cipher.iv) + ":" + encode(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    fun open(sealed: String, purpose: String): String {
        require(sealed.length <= MAX_CHARS * 6 && sealed.startsWith(PREFIX) && purpose.length in 1..200)
        val parts = sealed.removePrefix(PREFIX).split(':')
        require(parts.size == 2)
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val body = Base64.decode(parts[1], Base64.NO_WRAP)
        require(iv.size == 12 && body.size >= 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, iv))
        cipher.updateAAD("afterglow:v1:$purpose".toByteArray(Charsets.UTF_8))
        return cipher.doFinal(body).toString(Charsets.UTF_8).also { require(it.length <= MAX_CHARS) }
    }

    companion object {
        const val PREFIX = "AGENC1:"
        private const val MAX_CHARS = 1_048_576
        private val keyGate = Any()
        private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
