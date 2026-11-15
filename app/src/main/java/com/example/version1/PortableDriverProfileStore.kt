package com.example.version1

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import java.util.UUID

/** Publish a newly allocated profile only after its bounded preference snapshot is committed. */
class PortableDriverProfileStore internal constructor(
    private val prefs: SharedPreferences,
    private val loadDirectory: () -> DriverProfileSettings,
    private val saveDirectory: (DriverProfileSettings) -> Unit
) {
    constructor(context: Context, directory: DriverProfileStore) : this(
        ProtectedPreferences.get(context, "comfort_preferences"), directory::load, directory::save)

    /** Crash between the two files: keep published profiles, remove only unreferenced staged keys. */
    internal fun recoverPending() {
        val id = prefs.getString(PENDING_KEY, null) ?: return
        check(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Invalid staged profile" }
        val edit = prefs.edit()
        if (loadDirectory().profiles.none { it.id == id }) {
            DriverPreferenceKeys.ownedBy(DriverProfile(id, "가져온 선호")).forEach { edit.remove(it) }
        }
        check(edit.remove(PENDING_KEY).commit()) { "이전 가져오기 정리 실패" }
    }

    fun snapshot(profile: DriverProfile): PortableDriverProfile {
        val values = prefs.all
        fun key(name: String) = profile.preferencePrefix + name
        val music = runCatching { MusicSource.valueOf(values[key("music_source")] as? String ?: "AMBIENT") }.getOrDefault(MusicSource.AMBIENT)
        val link = (values[key("music_link_${music.name}")] as? String).orEmpty().takeIf { music.acceptsLink(it) }.orEmpty()
        return PortableDriverProfile(profile.name, ActivityKind.entries.map { kind ->
            val prefix = kind.name.lowercase(Locale.ROOT)
            val sound = runCatching { SoundPreset.valueOf(values[key("${prefix}_sound")] as? String ?: "WAVES") }.getOrDefault(SoundPreset.WAVES)
            PortableActivityPreference(kind,
                ((values[key("${prefix}_temperature")] as? Number)?.toFloat()?.takeIf { it.isFinite() } ?: 24f).coerceIn(22f, 26f),
                (values[key("${prefix}_fan")] as? Int)?.takeIf { it in setOf(0, 2, 3) } ?: 0,
                runCatching { ThermalFeeling.valueOf(values[key("${prefix}_feeling")] as? String ?: "") }.getOrNull(), sound,
                runCatching { SoundPreset.valueOf(values[key("${prefix}_sound_last")] as? String ?: "WAVES") }.getOrDefault(SoundPreset.WAVES).takeUnless { it == SoundPreset.OFF } ?: SoundPreset.WAVES)
        }, music, link)
    }

    /** Import does not select the profile, enroll a phone, transfer consent, or issue vehicle commands. */
    fun install(data: PortableDriverProfile, expected: DriverProfileSettings): DriverProfileSettings {
        recoverPending()
        check(loadDirectory() == expected) { "운전자 목록이 바뀌었어요. 다시 확인해 주세요." }
        check(expected.profiles.size < DriverProfileSettings.MAX_PROFILES) { "프로필을 더 추가할 수 없어요." }
        val created = DriverProfile.create(portableImportedProfileName(data.name, expected.profiles.map { it.name }))
        val ownedKeys = DriverPreferenceKeys.ownedBy(created)
        check(ownedKeys.none { prefs.contains(it) })
        val edit = prefs.edit().putString(PENDING_KEY, created.id)
            .putString(created.preferencePrefix + "music_source", data.music.name)
        if (data.playlist.isNotEmpty()) edit.putString(created.preferencePrefix + "music_link_${data.music.name}", data.playlist)
        data.activities.forEach {
            val prefix = created.preferencePrefix + it.kind.name.lowercase(Locale.ROOT)
            edit.putFloat("${prefix}_temperature", it.temperature).putInt("${prefix}_fan", it.fan)
                .putString("${prefix}_feeling", it.feeling?.name).putString("${prefix}_sound", it.sound.name)
                .putString("${prefix}_sound_last", it.lastSound.name)
        }
        // Health/learning consent, wet/body state and per-vehicle baselines are intentionally fresh.
        try {
            check(edit.commit()) { "선호 저장 실패" }
            check(loadDirectory() == expected) { "운전자 목록이 바뀌었어요." }
            val next = expected.copy(profiles = expected.profiles + created)
            saveDirectory(next)
            // The published profile remains valid if clearing this marker fails; next import repairs it.
            prefs.edit().remove(PENDING_KEY).commit()
            return next
        } catch (error: Exception) {
            runCatching { recoverPending() }.onFailure { error.addSuppressed(it) }
            throw error
        }
    }
    internal companion object { const val PENDING_KEY = "portable_profile_pending" }
}
