package com.example.afterglow.phone

import android.content.Context
import android.content.SharedPreferences

/** One process-local store keeps a failed durable write blocked across Activity recreation. */
internal object AndroidPhoneIdentityStore {
    @Volatile private var instance: PhoneIdentityStore? = null
    fun get(context: Context): PhoneIdentityStore = instance ?: synchronized(this) {
        instance ?: run {
            val prefs = context.applicationContext.getSharedPreferences("relay_identity", Context.MODE_PRIVATE)
            create(prefs, PhoneIdentityCipher()).also { instance = it }
        }
    }

    /** Dependency injection also permits isolated storage/key verification without real identity files. */
    internal fun create(prefs: SharedPreferences, cipher: PhoneIdentityCipher): PhoneIdentityStore =
            PhoneIdentityStore(object : PhoneIdentityBlob {
                override fun snapshot(): Map<String, *> = prefs.all
                override fun replace(sealed: String?): Boolean {
                    val previous = prefs.all
                    val edit = prefs.edit().clear()
                    if (sealed != null) edit.putString(PhoneIdentityStore.SEALED, sealed)
                    if (edit.commit()) return true
                    // SharedPreferences can update its in-memory map before a disk failure.
                    // Restore the previous snapshot; the owning store stays blocked either way.
                    val rollback = prefs.edit().clear()
                    previous.forEach { (key, value) -> restore(rollback, key, value) }
                    check(rollback.commit()) { "Phone identity rollback unavailable" }
                    return false
                }
            }, cipher::seal, cipher::open)

    @Suppress("UNCHECKED_CAST")
    private fun restore(edit: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> { check(value.all { it is String }); edit.putStringSet(key, value as Set<String>) }
            else -> error("Unsupported phone identity value")
        }
    }
}
