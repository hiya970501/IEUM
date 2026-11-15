package com.example.version1

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class PortableDriverProfileTest {
    @Test fun deepValidChecksumInputIsRejectedBeforeRecursiveParsing() {
        val raw="{\"nested\":"+"[".repeat(1000)+"0"+"]".repeat(1000)+"}"
        val bytes=raw.toByteArray(Charsets.UTF_8)
        val code="AFTERGLOW1."+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)+"."+
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        rejects { PortableDriverProfileCodec.decode(code) }
        assertEquals(profile(),PortableDriverProfileCodec.decode(PortableDriverProfileCodec.encode(profile())))
    }
    private fun profile() = PortableDriverProfile("운전자", ActivityKind.entries.map {
        PortableActivityPreference(it, if (it == ActivityKind.RUN) 23f else 25f, 3, ThermalFeeling.COLD, SoundPreset.OFF)
    }, MusicSource.MELON, "https://www.melon.com/test")
    private fun payload(code: String) = JSONObject(String(Base64.getUrlDecoder().decode(code.split('.')[1]), Charsets.UTF_8))
    private fun wrap(json: JSONObject): String {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        return "AFTERGLOW1." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + "." +
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
    private fun rejects(block: () -> Unit) { try { block(); fail("Unsafe code accepted") } catch (_: IllegalArgumentException) { } }
    @Test fun explicitPreferencesRoundTripWithoutHealthConsentOrVehicleIdentity() {
        val data = profile(); val code = PortableDriverProfileCodec.encode(data)
        assertEquals(data, PortableDriverProfileCodec.decode(code))
        assertEquals(setOf("version", "name", "activities", "music", "playlist"), payload(code).keys().asSequence().toSet())
        assertTrue(code.length < 1800)
    }
    @Test fun corruptOversizedOrFutureCodesAreRejected() {
        val code = PortableDriverProfileCodec.encode(profile())
        rejects { PortableDriverProfileCodec.decode(code.dropLast(1) + if (code.last() == '0') '1' else '0') }
        rejects { PortableDriverProfileCodec.decode("AFTERGLOW1." + "a".repeat(8192)) }
        rejects { PortableDriverProfileCodec.decode(wrap(payload(code).put("version", 2))) }
    }
    @Test fun authenticatedLookingExtraFieldsCannotCarryPermissionsOrTokens() {
        val base = payload(PortableDriverProfileCodec.encode(profile()))
        for (key in listOf("learning_consent", "credential", "phoneId", "baseline", "memorySlot", "health")) {
            rejects { PortableDriverProfileCodec.decode(wrap(JSONObject(base.toString()).put(key, true))) }
        }
    }
    @Test fun rangesTypesDuplicateActivitiesAndForeignPlaylistHostsAreRejected() {
        val code = PortableDriverProfileCodec.encode(profile())
        val hot = payload(code).apply { getJSONArray("activities").getJSONObject(0).put("temperature", 90) }
        rejects { PortableDriverProfileCodec.decode(wrap(hot)) }
        val duplicate = payload(code).apply { getJSONArray("activities").getJSONObject(1).put("kind", "RUN") }
        rejects { PortableDriverProfileCodec.decode(wrap(duplicate)) }
        val typed = payload(code).apply { getJSONArray("activities").getJSONObject(0).put("fan", "3") }
        rejects { PortableDriverProfileCodec.decode(wrap(typed)) }
        rejects { PortableDriverProfileCodec.decode(wrap(payload(code).put("playlist", "https://unrelated.example/track"))) }
    }
    @Test fun duplicateNamesGetAvailableSuffixWithoutOverwritingAnExistingProfile() {
        assertEquals("내 프로필 (3)", portableImportedProfileName("내 프로필", listOf("내 프로필", "내 프로필 (2)")))
        assertEquals("다른 운전자", portableImportedProfileName("다른 운전자", listOf("내 프로필")))
    }
    @Test fun longUnicodeNamesRemainWellFormedWhenNumbered() {
        val name = "가".repeat(19) + "😀" + "나다라"
        assertEquals(24, name.length)
        val renamed = portableImportedProfileName(name, listOf(name))
        assertTrue(renamed.length <= 24)
        assertTrue(Charsets.UTF_8.newEncoder().canEncode(renamed))
        assertTrue(renamed.endsWith(" (2)"))
    }
    @Test fun malformedUnicodeInAnOtherwiseValidEscapedJsonNameIsRejected() {
        val json = payload(PortableDriverProfileCodec.encode(profile())).put("name", "bad\uD800")
        // Preserve the JSON escape so the wire bytes themselves are well-formed UTF-8.
        val bytes = json.toString().replace("\uD800", "\\ud800").toByteArray(Charsets.UTF_8)
        val code = "AFTERGLOW1." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + "." +
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        rejects { PortableDriverProfileCodec.decode(code) }
    }
}
