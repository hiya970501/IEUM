package com.example.version1

import org.json.JSONArray
import org.json.JSONObject

/** Lossless typed values inside the authenticated envelope; never writes a plaintext file. */
internal object ProtectedPreferenceValues {
    fun encode(values: Map<String, Any>): String {
        require(values.size <= 4096)
        return JSONObject().apply {
            values.toSortedMap().forEach { (key, value) ->
                val type = when (value) {
                    is String -> "s"; is Int -> "i"; is Long -> "l"; is Float -> "f"
                    is Boolean -> "b"; is Set<*> -> "set"; else -> error("Unsupported preference type")
                }
                if (value is Set<*>) require(value.all { it is String })
                put(key, JSONObject().put("t", type).put("v",
                    if (value is Set<*>) JSONArray(value.sortedBy { it.toString() }) else value.toString()))
            }
        }.toString().also { require(it.length <= 1_048_576) }
    }
    fun decode(raw: String): Map<String, Any> {
        require(raw.length <= 1_048_576)
        val json = JSONObject(raw)
        require(json.length() <= 4096)
        return json.keys().asSequence().associateWith { key ->
            val entry = json.getJSONObject(key)
            require(entry.length() == 2 && entry.has("t") && entry.has("v"))
            val type = entry.getString("t")
            if (type != "set") require(entry.get("v") is String)
            when (type) {
                "s" -> entry.getString("v"); "i" -> entry.getString("v").toInt()
                "l" -> entry.getString("v").toLong(); "f" -> entry.getString("v").toFloat()
                "b" -> entry.getString("v").toBooleanStrict()
                "set" -> entry.getJSONArray("v").let { values ->
                    require(values.length() <= 4096)
                    (0 until values.length()).map { require(values.get(it) is String); values.getString(it) }.toSet()
                }
                else -> error("Unsupported protected preference type")
            }
        }
    }
}
