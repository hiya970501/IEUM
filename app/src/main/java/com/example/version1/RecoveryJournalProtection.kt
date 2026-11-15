package com.example.version1

import org.json.JSONArray
import org.json.JSONObject

/** Preserve the native-stop tool's empty/nonempty array contract without exposing settings. */
internal class RecoveryJournalProtection(private val cipher: SensitiveValueCipher = SensitiveValueCipher()) {
    fun encode(entries: JSONArray): String {
        require(entries.length() <= 64)
        if (entries.length() == 0) return "[]"
        return JSONArray().put(JSONObject().put("sealed", cipher.seal(entries.toString(), PURPOSE))).toString()
    }
    fun decode(raw: String): Pair<JSONArray, Boolean> {
        require(raw.length <= 262_144)
        val envelope = JSONArray(raw)
        if (envelope.length() == 1 && envelope.getJSONObject(0).has("sealed")) {
            require(envelope.getJSONObject(0).length() == 1)
            val decoded = JSONArray(cipher.open(envelope.getJSONObject(0).getString("sealed"), PURPOSE))
            require(decoded.length() in 1..64)
            return decoded to false
        }
        require(envelope.length() <= 64)
        for (i in 0 until envelope.length()) require(!envelope.getJSONObject(i).has("sealed"))
        return envelope to (envelope.length() > 0)
    }
    companion object { private const val PURPOSE = "recovery-journal:v1" }
}
