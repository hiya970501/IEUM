package com.example.afterglow.phone

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Independent purpose/key, no backup, fail closed. Explicit opt-out can delete unreadable data. */
internal class PhoneConnectionStore(context:Context,prefsName:String="saved_vehicle_connection") {
    private val prefs=context.applicationContext.getSharedPreferences(prefsName,Context.MODE_PRIVATE)
    @Synchronized fun load():RelayDestination? {
        val values=prefs.all
        if(values.isEmpty()) return null
        check(values.keys==setOf("sealed"))
        val sealed=values["sealed"] as? String ?: error("Connection unavailable")
        check(sealed.length in 1..12_288)
        val parts=sealed.split(':');check(parts.size==3 && parts[0]=="IEUMCON1")
        val iv=Base64.getDecoder().decode(parts[1]);val ciphertext=Base64.getDecoder().decode(parts[2])
        check(iv.size==12 && ciphertext.size in 16..8192)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,iv));cipher.updateAAD(PURPOSE.toByteArray())
        return SavedPhoneConnection.decode(cipher.doFinal(ciphertext).toString(Charsets.UTF_8))
    }
    @Synchronized fun save(destination:RelayDestination,allowed:()->Boolean):Boolean {
        if(!allowed()) return false
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key(true));cipher.updateAAD(PURPOSE.toByteArray())
        val encrypted=cipher.doFinal(SavedPhoneConnection.encode(destination).toByteArray(Charsets.UTF_8))
        val sealed="IEUMCON1:${Base64.getEncoder().encodeToString(cipher.iv)}:${Base64.getEncoder().encodeToString(encrypted)}"
        if(!allowed()) return false
        check(prefs.edit().clear().putString("sealed",sealed).commit())
        return true
    }
    @Synchronized fun clear() {check(prefs.edit().clear().commit())}
    private fun key(create:Boolean):SecretKey = synchronized(keyGate) {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        if(store.containsAlias(ALIAS)) return@synchronized checkNotNull(store.getKey(ALIAS,null) as? SecretKey)
        check(create) {"Connection key unavailable"}
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    companion object {
        private const val ALIAS="ieum.phone.vehicle-connection.aes.v1"
        private const val PURPOSE="ieum:phone:approved-vehicle-connection:v1"
        private val keyGate=Any()
    }
}
