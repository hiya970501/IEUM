package com.example.version1

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Only names, random IDs, explicit phone bindings and selection consent are persisted. */
class DriverProfileStore internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(ProtectedPreferences.get(context, "driver_profiles"))

    fun load(): DriverProfileSettings = DriverProfileCodec.decode(prefs.getString("state", null))

    fun save(settings: DriverProfileSettings) {
        serializedWrite { writeLocked(settings) }
    }

    private fun writeLocked(settings: DriverProfileSettings) {
        check(prefs.edit().putString("state", DriverProfileCodec.encode(settings)).commit()) { "운전자 프로필 저장 실패" }
    }

    private inline fun <T> serializedWrite(block: () -> T): T = admission.beginWrite().use {
        synchronized(writeGate) { block() }
    }

    /** A slow consent/binding write must not overwrite a newer directory. All
     * writers share this process gate; ProtectedPreferences retains its own
     * durable acknowledgement/rollback contract. No default identity fallback. */
    internal fun saveIfUnchanged(expected: DriverProfileSettings, next: DriverProfileSettings): Boolean =
        serializedWrite {
            if (load() != expected) false
            else { writeLocked(next); true }
        }

    companion object {
        private val writeGate = Any()
        private val admission = DirectoryWriteAdmission()
        internal val writePending: Boolean get() = admission.pending
        internal fun reserveAsyncWrite(): AutoCloseable? = admission.reserveWhenIdle()
    }
}

internal object DriverProfileCodec {
    private const val MAX_STATE_CHARS = 16_384

    fun encode(settings: DriverProfileSettings): String {
        val profiles = JSONArray()
        settings.profiles.forEach { profiles.put(JSONObject().put("id", it.id).put("name", it.name)) }
        val bindings = JSONObject()
        settings.phoneBindings.forEach { (phone, profile) -> bindings.put(phone, profile) }
        return JSONObject().put("version", 1).put("profiles", profiles)
            .put("selected", settings.selectedProfileId).put("autoSelectPairedPhone", settings.autoSelectPairedPhone)
            .put("phoneBindings", bindings).toString().also { check(it.length <= MAX_STATE_CHARS) }
    }

    fun decode(raw: String?): DriverProfileSettings {
        // Absence is a first installation. Existing but unreadable state must
        // never select an unrelated default driver's saved preferences/records.
        if (raw == null) return DriverProfileSettings()
        return try {
            require(raw.length in 1..MAX_STATE_CHARS)
            val root = JSONObject(raw)
            require(root.getInt("version") == 1)
            val profileArray = root.getJSONArray("profiles")
            require(profileArray.length() in 1..DriverProfileSettings.MAX_PROFILES)
            val profiles = (0 until profileArray.length()).map { index ->
                val profile = profileArray.getJSONObject(index)
                DriverProfile(profile.getString("id"), profile.getString("name"))
            }
            val bindingObject = root.getJSONObject("phoneBindings")
            require(bindingObject.length() <= DriverProfileSettings.MAX_PHONE_BINDINGS)
            val bindings = bindingObject.keys().asSequence().associateWith { bindingObject.getString(it) }
            DriverProfileSettings(profiles, root.getString("selected"),
                root.getBoolean("autoSelectPairedPhone"), bindings)
        } catch (_: Exception) {
            // Parser exception details can contain serialized personal fields.
            throw IllegalStateException("저장된 운전자 프로필을 확인하지 못했어요.")
        }
    }
}
