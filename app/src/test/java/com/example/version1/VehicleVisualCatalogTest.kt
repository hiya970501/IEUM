package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class VehicleVisualCatalogTest {
    private val early = VehicleVisualVariant("hyundai_ioniq5", "hyundai_ioniq5_early", "초기형", setOf(2022))
    private val current = VehicleVisualVariant("hyundai_ioniq5", "hyundai_ioniq5_current", "현행형", isDefault = true)
    private val kona = VehicleVisualVariant("hyundai_kona", "hyundai_kona_current", "현행형", setOf(2022))

    @Test fun steeringDefaultsToLeftIndependentlyOfAutomaticVehicleSelection() {
        val preferences = VehicleSelectionPreferences()
        assertFalse(preferences.rightHandDrive)
        val saved = preferences.copy(rightHandDrive = true)
        assertTrue(saved.copy(autoEnabled = false, manualProfileId = "hyundai_kona").rightHandDrive)
    }

    @Test fun receivedYearOnlySelectsAnExplicitlyVerifiedVersion() {
        val available = listOf(current, kona, early)
        assertEquals(early, VehicleVisualCatalog.resolve(available, "hyundai_ioniq5", null, 2022))
        assertEquals(current, VehicleVisualCatalog.resolve(available, "hyundai_ioniq5", null, 2023))
        assertNull(VehicleVisualCatalog.resolve(available, "kia_ev3", null, 2022))
    }

    @Test fun explicitVersionIsScopedToItsCarAndOverridesAutomaticYear() {
        val available = listOf(early, current, kona)
        assertEquals(current, VehicleVisualCatalog.resolve(available, "hyundai_ioniq5", current.assetId, 2022))
        assertEquals(current, VehicleVisualCatalog.resolve(available, "hyundai_ioniq5", kona.assetId))
    }

    @Test fun catalogRejectsUnsafePathsUnknownProfilesAndInventedYearValues() {
        val parsed = VehicleVisualCatalog.parse("""{"schemaVersion":1,"variants":[
            {"profileId":"hyundai_ioniq5","assetId":"../model","label":"unsafe"},
            {"profileId":"not_a_car","assetId":"model","label":"unknown"},
            {"profileId":"hyundai_ioniq5","assetId":"hyundai_ioniq5_early","label":"初期型","modelYears":[2022,0,9999],"sourceHandedness":"right","fullVehicle":true}
        ]}""")
        assertEquals(1, parsed.size)
        assertEquals(setOf(2022), parsed.single().modelYears)
        assertEquals("right", parsed.single().sourceHandedness)
        assertTrue(parsed.single().fullVehicle)
    }
}
