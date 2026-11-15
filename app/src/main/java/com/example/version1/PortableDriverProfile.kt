package com.example.version1

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Explicit comfort preferences only. No health, consent, credentials, IDs or vehicle positions. */
data class PortableActivityPreference(
    val kind: ActivityKind, val temperature: Float = 24f, val fan: Int = 0,
    val feeling: ThermalFeeling? = null, val sound: SoundPreset = SoundPreset.WAVES,
    val lastSound: SoundPreset = SoundPreset.WAVES
) {
    init {
        require(temperature.isFinite() && temperature in 22f..26f)
        require(fan in setOf(0, 2, 3) && lastSound != SoundPreset.OFF)
    }
}

data class PortableDriverProfile(
    val name: String, val activities: List<PortableActivityPreference>,
    val music: MusicSource = MusicSource.AMBIENT, val playlist: String = ""
) {
    init {
        require(name.isNotBlank() && name == name.trim() && name.length <= 24 && name.none { it.isISOControl() })
        require(Charsets.UTF_8.newEncoder().canEncode(name))
        require(activities.size == ActivityKind.entries.size && activities.map { it.kind }.toSet() == ActivityKind.entries.toSet())
        require(playlist.length <= 2048 && (playlist.isEmpty() || music.acceptsLink(playlist)))
        require(Charsets.UTF_8.newEncoder().canEncode(playlist))
    }
    fun summary(): String = "$name\n" + activities.joinToString("\n") {
        "${it.kind.title} · ${it.temperature}°C · ${if (it.fan == 0) "자동 풍량" else "풍량 ${it.fan}"} · ${it.sound.title}"
    } + "\n음악 · ${music.title}" + if (playlist.isEmpty()) "" else "\n재생목록 · $playlist"
}

/** Imported profiles must remain distinguishable, including names at the UTF-16 length limit. */
internal fun portableImportedProfileName(name: String, existing: Collection<String>): String {
    if (name !in existing) return name
    for (index in 2..DriverProfileSettings.MAX_PROFILES + 1) {
        val suffix = " ($index)"
        var stem = name.take(24 - suffix.length)
        if (stem.lastOrNull()?.isHighSurrogate() == true) stem = stem.dropLast(1)
        val candidate = stem + suffix
        if (candidate !in existing) return candidate
    }
    error("구분 가능한 프로필 이름을 만들지 못했어요.")
}

internal object PortableDriverProfileCodec {
    const val MAX_CODE_CHARS = 8192
    private const val PREFIX = "AFTERGLOW1."
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun keys(value: JSONObject, allowed: Set<String>) { require(value.keys().asSequence().toSet() == allowed) }
    private fun string(value: JSONObject, name: String): String = (value.get(name) as? String) ?: error("Invalid string")
    fun encode(profile: PortableDriverProfile): String {
        val activities = JSONArray()
        profile.activities.sortedBy { it.kind.ordinal }.forEach {
            activities.put(JSONObject().put("kind", it.kind.name).put("temperature", it.temperature)
                .put("fan", it.fan).put("feeling", it.feeling?.name ?: JSONObject.NULL)
                .put("sound", it.sound.name).put("lastSound", it.lastSound.name))
        }
        val bytes = JSONObject().put("version", 1).put("name", profile.name).put("activities", activities)
            .put("music", profile.music.name).put("playlist", profile.playlist).toString().toByteArray(Charsets.UTF_8)
        return (PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + "." + digest(bytes)).also {
            require(it.length <= MAX_CODE_CHARS) { "설정 코드가 너무 길어요. 재생목록 링크를 줄여 주세요." }
        }
    }
    fun decode(code: String): PortableDriverProfile {
        require(code.length in 1..MAX_CODE_CHARS && code.startsWith(PREFIX))
        val parts = code.removePrefix(PREFIX).split('.')
        require(parts.size == 2 && parts[1].matches(Regex("[0-9a-f]{64}")))
        require(parts[0].matches(Regex("[A-Za-z0-9_-]+")))
        val bytes = Base64.getUrlDecoder().decode(parts[0])
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == parts[0])
        require(MessageDigest.isEqual(digest(bytes).toByteArray(), parts[1].toByteArray()))
        val raw=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        JsonIngress.requireObject(raw,MAX_CODE_CHARS,4)
        val json = JSONObject(raw)
        keys(json, setOf("version", "name", "activities", "music", "playlist"))
        require(json.get("version") is Int && json.getInt("version") == 1)
        val array = json.getJSONArray("activities")
        require(array.length() == ActivityKind.entries.size)
        val activities = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            keys(item, setOf("kind", "temperature", "fan", "feeling", "sound", "lastSound"))
            require(item.get("temperature") is Number && item.get("fan") is Int)
            PortableActivityPreference(ActivityKind.valueOf(string(item, "kind")), item.getDouble("temperature").toFloat(),
                item.getInt("fan"), if (item.isNull("feeling")) null else ThermalFeeling.valueOf(string(item, "feeling")),
                SoundPreset.valueOf(string(item, "sound")), SoundPreset.valueOf(string(item, "lastSound")))
        }
        return PortableDriverProfile(string(json, "name"), activities, MusicSource.valueOf(string(json, "music")), string(json, "playlist"))
    }
}
