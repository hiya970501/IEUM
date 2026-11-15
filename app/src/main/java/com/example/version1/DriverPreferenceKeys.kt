package com.example.version1

import java.util.Locale

/** Exact keys only: the original driver's empty prefix also shares this file with app settings. */
internal object DriverPreferenceKeys {
    fun ownedBy(profile: DriverProfile): Set<String> {
        val local = setOf("follow_workout", "manual_kind", "learning_consent", "comfort_mode",
            "learning_trip_id", "learning_trip_marker", "learning_trip_started", "music_source") +
            MusicSource.entries.map { "music_link_${it.name}" } +
            MusicSource.entries.map { "music_auto_resume_${it.name}" } +
            ActivityKind.entries.flatMap { activity ->
                val name = activity.name.lowercase(Locale.ROOT)
                listOf("temperature", "fan", "feeling", "wet", "sound", "sound_last").map { "${name}_$it" }
            }
        return local.mapTo(linkedSetOf()) { profile.preferencePrefix + it }
    }
}
