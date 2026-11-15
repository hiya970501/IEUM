package com.example.version1

import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Optional asset preprocessing, bound to the complete model bytes; never a vehicle control source. */
internal object PrecomputedThermalProjection {
    const val MAX_JSON_BYTES = 196_608

    fun read(profile: JSONObject, model: ByteBuffer): Map<String, List<ThermalPrimitive>>? {
        if (!profile.has("thermalProjection")) return null
        val stored = profile.getJSONObject("thermalProjection")
        require(stored.getInt("version") == 1)
        val sourceBytes = stored.getLong("sourceBytes")
        require(sourceBytes >= 20)
        if (sourceBytes != model.remaining().toLong()) return null
        val expected = stored.getString("sourceSha256")
        require(expected.matches(Regex("[a-f0-9]{64}")))
        val json = stored.getString("json")
        require(json.length in 1..MAX_JSON_BYTES)
        // Bound recursive JSONObject parsing, including unused nested fields,
        // and reject trailing documents before allocating the projected tree.
        JsonIngress.requireObject(json, MAX_JSON_BYTES, 16)
        // Validate the same bounded GLB JSON window as the streaming fallback.
        // Neither validation nor hashing may consume the caller's model window.
        ThermalGeometry.jsonWindow(model)
        val actual = MessageDigest.getInstance("SHA-256").digestModel(model)
        // A different model/handedness variant invalidates the optional hint.
        // The caller recomputes from the actual GLB instead of using stale geometry.
        if (!MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), actual.toByteArray(Charsets.US_ASCII))) return null
        val document = JSONObject(json)
        for (key in listOf("nodes", "meshes", "materials", "accessors")) {
            require(document.getJSONArray(key).length() <= 8_192)
        }
        return ThermalGeometry.fromJson(document)
    }

    private fun MessageDigest.digestModel(model: ByteBuffer): String {
        update(model.asReadOnlyBuffer())
        return digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
