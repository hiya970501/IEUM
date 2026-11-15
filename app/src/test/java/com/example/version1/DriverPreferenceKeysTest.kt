package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class DriverPreferenceKeysTest {
    @Test fun deletingOriginalDriverPreservesSharedConnectionWeatherAndOtherDriver() {
        val original = DriverProfile(DriverProfile.DEFAULT_ID, "원래 운전자")
        val other = DriverProfile("other", "다른 운전자")
        val protected = setOf("weather_name", "weather_lat", "weather_lon", "bridge_allowed", "bridge_host",
            "auto_enabled", "right_hand_drive", "exterior_transparency") + DriverPreferenceKeys.ownedBy(other)
        val keys = DriverPreferenceKeys.ownedBy(original)
        assertTrue(keys.containsAll(listOf("run_temperature", "water_fan", "hike_wet", "learning_consent",
            "music_link_MELON", "music_auto_resume_VLC", "learning_trip_id", "learning_trip_started", "comfort_mode")))
        assertTrue(keys.intersect(protected).isEmpty())
        assertEquals(protected, (protected + keys) - keys)
    }

    @Test fun deletingNamedDriverCannotDeletePrefixNeighborOrLegacySettings() {
        val profile = DriverProfile("a", "삭제 대상")
        val neighbor = DriverProfile("a_b", "유지 대상")
        val protected = DriverPreferenceKeys.ownedBy(neighbor) +
            DriverPreferenceKeys.ownedBy(DriverProfile("default", "기본")) + setOf("driver_a_unknown_future_setting")
        val keys = DriverPreferenceKeys.ownedBy(profile)
        assertTrue(keys.containsAll(listOf("driver_a_run_sound", "driver_a_run_sound_last", "driver_a_music_source")))
        assertTrue(keys.intersect(protected).isEmpty())
        assertEquals(protected, (protected + keys) - keys)
    }
}
