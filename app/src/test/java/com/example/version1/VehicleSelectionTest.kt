package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class VehicleSelectionTest {
    private val manual = VehicleSelectionPreferences(autoEnabled = true, manualProfileId = "kia_ev6")
    private val ioniq = VehicleIdentityState.Available(VehicleIdentity("Hyundai", "IONIQ 5", 2025))

    @Test fun automaticMetadataSelectsKnownModelAndOnlyUsesReceivedYear() {
        val selection = VehicleSelectionResolver.resolve(manual, ioniq)
        assertEquals(VehicleSelectionSource.AUTOMATIC, selection.source)
        assertEquals("hyundai_ioniq5", selection.profile.id)
        assertEquals(2025, selection.profile.modelYear)
        assertEquals(ioniq.identity, selection.identity)
        assertNull(VehicleCatalog.find("hyundai_ioniq5").modelYear)
    }

    @Test fun turningAutomaticOffImmediatelyUsesManualAndHidesCachedIdentity() {
        val selection = VehicleSelectionResolver.resolve(manual.copy(autoEnabled = false), ioniq)
        assertEquals(VehicleSelectionSource.MANUAL, selection.source)
        assertEquals("kia_ev6", selection.profile.id)
        assertNull(selection.identity)
        assertNull(selection.profile.modelYear)
    }

    @Test fun unavailablePermissionDeniedAndLoadingKeepManualFallback() {
        for (state in listOf(VehicleIdentityState.Loading, VehicleIdentityState.Unavailable(), VehicleIdentityState.PermissionDenied())) {
            val selection = VehicleSelectionResolver.resolve(manual, state)
            assertEquals(VehicleSelectionSource.FALLBACK, selection.source)
            assertEquals("kia_ev6", selection.profile.id)
            assertNull(selection.identity)
        }
    }

    @Test fun uncataloguedVehicleUsesActualMetadataWithoutGuessingKnownModel() {
        val raw = VehicleIdentity("Hyundai", "42dot Connect", 2024)
        val selection = VehicleSelectionResolver.resolve(manual, VehicleIdentityState.Available(raw))
        assertEquals(VehicleCatalog.DETECTED_ID, selection.profile.id)
        assertEquals(VehicleSelectionSource.AUTOMATIC, selection.source)
        assertEquals("Hyundai 42dot Connect 2024년형", selection.profile.displayName)
        assertEquals(raw, selection.identity)
        assertNull(VehicleCatalog.match(raw))
        assertTrue(selection.message.contains("공통 컨셉 모델"))
        assertEquals("kia_ev6", manual.manualProfileId)
        assertEquals("kia_ev6", VehicleSelectionResolver.resolve(manual.copy(autoEnabled = false), VehicleIdentityState.Available(raw)).profile.id)
    }

    @Test fun makeAndYearWithoutModelCannotAutomaticallySelectVehicle() {
        for (raw in listOf(VehicleIdentity("Hyundai", null, 2024), VehicleIdentity("Hyundai", " ", null))) {
            val selection = VehicleSelectionResolver.resolve(manual, VehicleIdentityState.Available(raw))
            assertEquals(VehicleSelectionSource.FALLBACK, selection.source)
            assertEquals("kia_ev6", selection.profile.id)
            assertEquals(raw, selection.identity)
        }
    }

    @Test fun missingModelUnknownBrandAndConflictingBrandNeverGuessModel() {
        for (identity in listOf(
            VehicleIdentity("Hyundai", null, 2025), VehicleIdentity("Hyundai", "NE1", 2025),
            VehicleIdentity("Kia", "IONIQ 5", 2025), VehicleIdentity("Unknown", "IONIQ 5", null),
            VehicleIdentity("Hyundai", "IONIQ 5 N Line", 2025)
        )) assertNull(identity.displayName, VehicleCatalog.match(identity))
    }

    @Test fun matchingAcceptsExactPublicNameAliasesWithoutRequiringMakeOrYear() {
        for (identity in listOf(
            VehicleIdentity(null, "IONIQ-5", null), VehicleIdentity("현대자동차", "아이오닉 5", null),
            VehicleIdentity("HYUNDAI", "Hyundai IONIQ 5", null)
        )) {
            val matched = VehicleCatalog.match(identity)
            assertEquals("hyundai_ioniq5", matched?.id)
            assertNull(matched?.modelYear)
        }
    }

    @Test fun performanceModelMatchesItsOwnProfileWithoutCollapsingToStandardCar() {
        assertEquals("hyundai_ioniq5_n", VehicleCatalog.match(VehicleIdentity("Hyundai", "IONIQ 5 N", 2025))?.id)
        assertEquals("hyundai_inster", VehicleCatalog.match(VehicleIdentity("Hyundai", "INSTER", null))?.id)
    }

    @Test fun resolvingAutomaticVehicleNeverOverwritesSavedManualPreference() {
        val saved = manual.copy()
        VehicleSelectionResolver.resolve(manual, ioniq)
        assertEquals(saved, manual)
        assertEquals("kia_ev6", VehicleSelectionResolver.resolve(manual.copy(autoEnabled = false), ioniq).profile.id)
    }

    @Test fun disconnectDropsPreviouslyAvailableAutomaticSelection() {
        assertEquals(VehicleSelectionSource.AUTOMATIC, VehicleSelectionResolver.resolve(manual, ioniq).source)
        val disconnected = VehicleSelectionResolver.resolve(manual, VehicleIdentityState.Unavailable("연결 끊김"))
        assertEquals(VehicleSelectionSource.FALLBACK, disconnected.source)
        assertEquals("kia_ev6", disconnected.profile.id)
        assertNull(disconnected.identity)
    }

    @Test fun invalidManualIdUsesGenericWithoutInventingYearOrEquipment() {
        val selection = VehicleSelectionResolver.resolve(VehicleSelectionPreferences(false, "removed_model"), ioniq)
        assertEquals(VehicleCatalog.GENERIC_ID, selection.profile.id)
        assertNull(selection.profile.modelYear)
        assertEquals(8, VehicleCatalog.profiles.map { it.id }.distinct().size)
        assertTrue(VehicleCatalog.profiles.all { it.modelYear == null })
    }

    @Test fun automaticMetadataWithoutInstalledGeometryKeepsManualPreviewAndOriginalIdentity() {
        val raw = VehicleIdentity("Hyundai", "42dot Connect", 2024)
        val selection = VehicleSelectionResolver.resolve(manual, VehicleIdentityState.Available(raw), setOf("kia_ev6"))
        assertEquals(VehicleSelectionSource.FALLBACK, selection.source)
        assertEquals("kia_ev6", selection.profile.id)
        assertEquals(raw, selection.identity)
        assertTrue(selection.message.contains("3D 자료가 없어"))
        val knownButMissing = VehicleSelectionResolver.resolve(manual, ioniq, setOf("kia_ev6"))
        assertEquals("kia_ev6", knownButMissing.profile.id)
    }

    @Test fun manualChoicesOnlyIncludeInstalledFullModels() {
        val real = VehicleVisualVariant("hyundai_ioniq5", "real_i5", "실제 모델", fullVehicle = true)
        val shell = VehicleVisualVariant("kia_ev6", "shell_ev6", "외형만", fullVehicle = false)
        assertEquals(listOf("hyundai_ioniq5"), VehicleVisualCatalog.selectableProfiles(listOf(real, shell)).map { it.id })
        assertTrue(VehicleVisualCatalog.selectableProfiles(emptyList()).isEmpty())
        assertNull(VehicleCatalog.profiles.firstOrNull { it.id == "genesis_gv80" })
    }

    @Test fun refreshAndDisconnectInvalidateLateReadCallbacks() {
        val gate = VehicleIdentityRequestGate()
        val first = gate.begin()!!
        val refreshed = gate.begin()!!
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(refreshed))
        gate.invalidate()
        assertFalse(gate.isCurrent(refreshed))
    }

    @Test fun closingReaderGateRejectsEveryLateCallbackAndRestart() {
        val gate = VehicleIdentityRequestGate()
        val request = gate.begin()!!
        gate.close()
        assertTrue(gate.closed)
        assertFalse(gate.isCurrent(request))
        assertNull(gate.begin())
    }
}
