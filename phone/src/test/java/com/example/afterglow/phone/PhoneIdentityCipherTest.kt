package com.example.afterglow.phone

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

class PhoneIdentityCipherTest {
    private fun newKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun unicodeRoundTripUsesDifferentAuthenticatedCiphertextEachTime() {
        val key = newKey(); val cipher = PhoneIdentityCipher { key }
        val plain = "내 휴대폰 · 日本語 · العربية"
        val first = cipher.seal(plain); val second = cipher.seal(plain)
        assertNotEquals(first, second); assertFalse(first.contains(plain))
        assertEquals(plain, cipher.open(first)); assertEquals(plain, cipher.open(second))
    }
    @Test fun alteredCiphertextAndWrongKeyCannotBeOpened() {
        val key = newKey(); val cipher = PhoneIdentityCipher { key }; val value = cipher.seal("private identity")
        val parts = value.split(':'); val bytes = Base64.getDecoder().decode(parts[2])
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        val altered = parts[0] + ":" + parts[1] + ":" + Base64.getEncoder().encodeToString(bytes)
        assertThrows(Exception::class.java) { cipher.open(altered) }
        val differentKey = newKey()
        assertThrows(Exception::class.java) { PhoneIdentityCipher { differentKey }.open(value) }
    }
    @Test fun missingKeyOnReadNeverRequestsKeyCreation() {
        val originalKey = newKey(); val value = PhoneIdentityCipher { originalKey }.seal("identity")
        val requests = mutableListOf<Boolean>()
        val unavailable = PhoneIdentityCipher { create -> requests += create; error("Unavailable") }
        assertThrows(IllegalStateException::class.java) { unavailable.open(value) }
        assertEquals(listOf(false), requests)
    }
    @Test fun ciphertextFromAnotherStoragePurposeFailsAuthentication() {
        val key = newKey(); val foreign = Cipher.getInstance("AES/GCM/NoPadding")
        foreign.init(Cipher.ENCRYPT_MODE, key); foreign.updateAAD("different-purpose".toByteArray())
        val value = "IEUMID1:" + Base64.getEncoder().encodeToString(foreign.iv) + ":" +
            Base64.getEncoder().encodeToString(foreign.doFinal("identity".toByteArray()))
        assertThrows(Exception::class.java) { PhoneIdentityCipher { key }.open(value) }
    }
    @Test fun malformedAndOversizedEnvelopesAreRejectedBeforeKeyLookup() {
        val cipher = PhoneIdentityCipher { error("Key must not be read") }
        for (value in listOf("", "IEUMID1:", "IEUMID1:abc:def:extra", "IEUMID1:@@@:abcd", "IEUMID1:" + "A".repeat(8192)))
            assertThrows(IllegalArgumentException::class.java) { cipher.open(value) }
    }
}
