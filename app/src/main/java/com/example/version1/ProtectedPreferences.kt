package com.example.version1

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.example.version1.ProtectedPreferenceValues.encode
import com.example.version1.ProtectedPreferenceValues.decode
import java.util.concurrent.ConcurrentHashMap

/** One authenticated encrypted map: keys, names, identifiers and values stay out of XML.
 * One process-local instance per preference file serializes edits across Activity/service owners.
 * Legacy maps migrate atomically; corrupt ciphertext is never replaced by empty defaults.
 */
internal class ProtectedPreferences internal constructor(
    private val backing: SharedPreferences, private val purpose: String
) : SharedPreferences {
    private val cipher = SensitiveValueCipher()
    private val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val gate = Any()
    @Volatile private var state: Map<String, Any>? = null
    @Volatile private var failed = false

    /** Readers see only the last acknowledged immutable map. A slow encrypted
     * disk commit must not make already-loaded UI readers wait on the writer.
     * Initial load/migration still owns the gate; an unconfirmed rollback fails
     * closed, and no editor's speculative map is ever published here. */
    private fun snapshot(): Map<String, Any> {
        check(!failed) { "Protected preference write unconfirmed" }
        state?.let { return it }
        return synchronized(gate) { loaded() }
    }

    private fun loaded(): Map<String, Any> {
        check(!failed) { "Protected preference write unconfirmed" }
        state?.let { return it }
        val raw = backing.all
        val data = if (raw.containsKey(SEALED)) {
            check(raw.size == 1) { "Unexpected protected preference fields" }
            decode(cipher.open(checkNotNull(raw[SEALED] as? String), purpose))
        } else {
            val legacy = raw.mapValues { (_, value) -> copyValue(checkNotNull(value)) }
            if (legacy.isNotEmpty()) {
                val encrypted = cipher.seal(encode(legacy), purpose)
                if (!backing.edit().clear().putString(SEALED, encrypted).commit()) {
                    failed = true
                    error("Protected preference migration unconfirmed")
                }
            }
            legacy
        }
        return data.also { state = it }
    }

    override fun getAll(): MutableMap<String, *> = snapshot().mapValuesTo(linkedMapOf()) { (_, value) -> copyValue(value) }
    override fun contains(key: String?) = snapshot().containsKey(key)
    private inline fun <reified T> read(key: String?, fallback: T): T {
        val confirmed = snapshot()
        return if (!confirmed.containsKey(key)) fallback else confirmed[key] as T
    }
    override fun getString(key: String?, defValue: String?): String? = read(key, defValue)
    override fun getInt(key: String?, defValue: Int) = read(key, defValue)
    override fun getLong(key: String?, defValue: Long) = read(key, defValue)
    override fun getFloat(key: String?, defValue: Float) = read(key, defValue)
    override fun getBoolean(key: String?, defValue: Boolean) = read(key, defValue)
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        val confirmed = snapshot()
        return if (!confirmed.containsKey(key)) defValues?.toMutableSet() else (confirmed[key] as Set<String>).toMutableSet()
    }
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(gate) { listeners += listener }
    }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(gate) { listeners -= listener }
    }

    private inner class Editor : SharedPreferences.Editor {
        private val changes = linkedMapOf<String, Any?>()
        private var clear = false
        private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply {
            requireNotNull(key); changes[key] = value?.let(::copyValue)
        }
        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values)
        override fun remove(key: String?) = put(key, null)
        override fun clear(): SharedPreferences.Editor = apply { clear = true }
        override fun apply() { check(commit()) { "Protected preference persistence unconfirmed" } }
        override fun commit(): Boolean {
            val changed: Set<String>
            val observers: List<SharedPreferences.OnSharedPreferenceChangeListener>
            synchronized(gate) {
                val before = loaded()
                val after = (if (clear) emptyMap() else before).toMutableMap()
                for ((key, value) in changes) if (value == null) after.remove(key) else after[key] = value
                changed = (before.keys + after.keys).filterTo(linkedSetOf()) { before[it] != after[it] }
                if (changed.isEmpty()) return true
                val previous = backing.getString(SEALED, null)
                val encrypted = cipher.seal(encode(after), purpose)
                if (!backing.edit().clear().putString(SEALED, encrypted).commit()) {
                    val rollback = backing.edit().clear()
                    if (previous != null) rollback.putString(SEALED, previous)
                    if (!rollback.commit()) failed = true
                    return false
                }
                state = after.toMap()
                observers = listeners.toList()
            }
            val notify = Runnable { for (key in changed) for (observer in observers)
                observer.onSharedPreferenceChanged(this@ProtectedPreferences, key) }
            if (Looper.myLooper() == Looper.getMainLooper()) notify.run() else Handler(Looper.getMainLooper()).post(notify)
            return true
        }
    }

    companion object {
        private const val SEALED = "__sealed_v1"
        private val instances = ConcurrentHashMap<String, ProtectedPreferences>()
        fun get(context: Context, name: String): SharedPreferences {
            require(name.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
            val app = context.applicationContext
            val id = app.dataDir.canonicalPath + "/" + name
            return instances.computeIfAbsent(id) {
                ProtectedPreferences(app.getSharedPreferences(name, Context.MODE_PRIVATE), "preferences:$name")
            }
        }
        private fun copyValue(value: Any): Any = if (value is Set<*>) {
            require(value.all { it is String }); value.toSet()
        } else value.also { require(it is String || it is Int || it is Long || it is Float || it is Boolean) }
    }
}
