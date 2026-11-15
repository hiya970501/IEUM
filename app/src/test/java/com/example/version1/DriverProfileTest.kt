package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class DriverProfileTest {
    private val now = 1_800_000_000_000L
    private fun phone(id: String = "e14ddceb-e682-4f1b-a072-a589a9c24576") =
        PairedDriverIdentity.create(id, "내 iPhone", "apple-health", now)

    @Test fun pairedPhoneCannotSelectUntilLocallyBoundAndConsented() {
        val settings = DriverProfileSettings().add("동행")
        val identity = phone()
        assertNull(settings.automaticSelection(identity, now, true))
        val bound = settings.bindPhone(identity, now)
        assertNull(bound.automaticSelection(identity, now, true))
        val enabled = bound.copy(autoSelectPairedPhone = true)
        assertEquals(bound.selected.id, enabled.automaticSelection(identity, now, true)?.id)
        assertNull(enabled.automaticSelection(identity, now, false))
        assertNull(enabled.automaticSelection(identity, now + 60_001, true))
        assertNull(enabled.automaticSelection(identity, now - 1, true))
        assertNull(enabled.automaticSelection(phone("c080efc5-0db3-4d64-9199-e357a28f2d50"), now, true))
    }

    @Test fun creatingAndRemovingProfilesKeepsPreferenceNamespacesAndBindingsIsolated() {
        val original = DriverProfileSettings()
        assertEquals("", original.selected.preferencePrefix)
        val added = original.add("운전자 2")
        assertTrue(added.selected.preferencePrefix.startsWith("driver_"))
        val bound = added.bindPhone(phone(), now)
        assertEquals(added.selected.id, bound.phoneBindings[phone().phoneId])
        val removed = bound.remove(added.selected.id)
        assertEquals(original.selected, removed.selected)
        assertTrue(removed.phoneBindings.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { original.remove(original.selected.id) }
    }

    @Test fun corruptOrUnboundedStoredStateCannotSelectAnyDriver() {
        for (raw in listOf("invalid", "", "null", "[]", "x".repeat(16_385))) {
            assertThrows(IllegalStateException::class.java) { DriverProfileCodec.decode(raw) }
        }
        var settings = DriverProfileSettings()
        repeat(DriverProfileSettings.MAX_PROFILES - 1) { settings = settings.add("운전자 $it") }
        val full = settings
        assertThrows(IllegalArgumentException::class.java) { full.add("초과") }
        val encoded = DriverProfileCodec.encode(full.bindPhone(phone(), now).copy(autoSelectPairedPhone = true))
        assertEquals(full.bindPhone(phone(), now).copy(autoSelectPairedPhone = true), DriverProfileCodec.decode(encoded))
        assertThrows(IllegalStateException::class.java) {
            DriverProfileCodec.decode(encoded.replace("\"version\":1", "\"version\":2"))
        }
    }

    @Test fun onlyAbsentDirectoryCreatesTheFirstDriver() {
        assertEquals(DriverProfileSettings(), DriverProfileCodec.decode(null))
    }

    @Test fun unknownSelectedDriverCannotFallBackToAnExistingDefaultDriver() {
        val original = DriverProfileSettings().add("다른 운전자")
        val malformed = org.json.JSONObject(DriverProfileCodec.encode(original)).put("selected", "missing-driver").toString()
        assertThrows(IllegalStateException::class.java) { DriverProfileCodec.decode(malformed) }
        assertEquals(original, DriverProfileCodec.decode(DriverProfileCodec.encode(original)))
    }

    @Test fun invalidDirectoryShapeCannotSelectDefaultOrExposeStoredFields() {
        val raw = org.json.JSONObject(DriverProfileCodec.encode(DriverProfileSettings()))
            .put("profiles", org.json.JSONArray()).toString()
        assertThrows(IllegalStateException::class.java) { DriverProfileCodec.decode(raw) }
        val privateInput = "{\"profiles\":[{\"id\":\"qa-private\",\"name\":\"QA-secret-name\"}]}"
        val failure = assertThrows(IllegalStateException::class.java) { DriverProfileCodec.decode(privateInput) }
        assertFalse(failure.message.orEmpty().contains("QA-secret-name"))
        assertNull(failure.cause)
    }

    @Test fun identityIsOpaquePhoneClaimAndNamesCannotInjectPreferenceKeys() {
        assertEquals("e14ddceb-e682-4f1b-a072-a589a9c24576", phone("E14DDCEB-E682-4F1B-A072-A589A9C24576").phoneId)
        assertThrows(IllegalArgumentException::class.java) {
            PairedDriverIdentity.create("driver-default", "이름", "apple-health", now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairedDriverIdentity.create(phone().phoneId, "이름", "face-recognition", now)
        }
        assertThrows(IllegalArgumentException::class.java) { DriverProfile("../settings", "이름") }
        assertThrows(IllegalArgumentException::class.java) { DriverProfile.create("가".repeat(25)) }
        assertThrows(IllegalArgumentException::class.java) { DriverProfile.create("운전자\n이름") }
        assertEquals("연결한 iPhone", PairedDriverIdentity.create(phone().phoneId, null,
            "apple-shortcuts", now).displayName)
    }

    @Test fun revokingPhoneBindingsAlsoRevokesAutomaticSelection() {
        val enabled = DriverProfileSettings().bindPhone(phone(), now).copy(autoSelectPairedPhone = true)
        val revoked = enabled.unbindAllPhones()
        assertFalse(revoked.autoSelectPairedPhone)
        assertTrue(revoked.phoneBindings.isEmpty())
    }
    @Test fun bindingLimitBlocksNewPhonesButAllowsExistingPhoneUpdates() {
        var settings = DriverProfileSettings()
        repeat(DriverProfileSettings.MAX_PHONE_BINDINGS) { index ->
            settings = settings.bindPhone(phone("00000000-0000-0000-0000-${index.toString(16).padStart(12, '0')}"), now)
        }
        val extra = phone()
        assertFalse(settings.canBindPhone(extra, now))
        val first = phone("00000000-0000-0000-0000-000000000000")
        assertTrue(settings.canBindPhone(first, now))
        assertEquals(settings, settings.bindPhone(first, now))
        assertFalse(settings.canBindPhone(first, now + 60_001))
    }
}
