package com.example.version1

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer

/** Bounded projection, not a replacement glTF validator or a memory admission credit. */
internal object ThermalJsonProjection {
    private sealed interface Shape
    private data class Fields(val members: Map<String, Shape>) : Shape
    private data class Items(val element: Shape) : Shape
    private data class SelectedItems(val element: Shape, val indices: Set<Int>) : Shape
    private data object Scalar : Shape
    private data object Presence : Shape
    private fun fields(vararg pairs: Pair<String, Shape>) = Fields(mapOf(*pairs))
    private val document = fields(
        "nodes" to Items(fields("name" to Scalar, "mesh" to Scalar, "skin" to Presence)),
        "meshes" to Items(fields("primitives" to Items(fields(
            "material" to Scalar,
            "attributes" to fields("POSITION" to Scalar),
            "extras" to fields("afterglowThermalRole" to Scalar),
            "targets" to Presence
        )))),
        "materials" to Items(fields("name" to Scalar,
            "pbrMetallicRoughness" to fields("baseColorFactor" to Items(Scalar))))
    )
    private val accessor = fields("type" to Scalar, "count" to Scalar,
        "componentType" to Scalar, "normalized" to Scalar,
        "min" to Items(Scalar), "max" to Items(Scalar))

    fun read(json: ByteBuffer): JSONObject {
        val result = project(json, document)
        val needed = mutableSetOf<Int>()
        val materials = result.optJSONArray("materials")
        val meshes = result.optJSONArray("meshes")
        if (materials != null && meshes != null) for (i in 0 until meshes.length()) {
            val primitives = meshes.optJSONObject(i)?.optJSONArray("primitives") ?: continue
            for (j in 0 until primitives.length()) {
                val primitive = primitives.optJSONObject(j) ?: continue
                val materialName = materials.optJSONObject(primitive.optInt("material", -1))?.optString("name").orEmpty()
                val role = primitive.optJSONObject("extras")?.optString("afterglowThermalRole").orEmpty()
                val wheel = CabinThermalStyle.wheelMaterial(materialName) ||
                    (role == "steering" && materialName.endsWith("__afterglow_interior"))
                if (role != "none" && (CabinThermalStyle.seatMaterial(materialName) || wheel)) {
                    val index = primitive.optJSONObject("attributes")?.optInt("POSITION", -1) ?: -1
                    if (index >= 0) needed.add(index)
                }
            }
        }
        // Root fields may appear in any order. A second bounded scan avoids keeping
        // thousands of unrelated accessor objects, arrays and boxed extrema alive.
        // Null placeholders preserve the original glTF indices for fromJson().
        val selected = project(json, fields("accessors" to SelectedItems(accessor, needed)))
        result.put("accessors", selected.opt("accessors") ?: JSONObject.NULL)
        return result
    }

    private fun project(json: ByteBuffer, shape: Shape): JSONObject {
        require(json.remaining() in 1..1_048_576)
        val source = json.asReadOnlyBuffer()
        val stream = object : InputStream() {
            override fun read(): Int = if (source.hasRemaining()) source.get().toInt() and 255 else -1
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                if (length == 0) return 0
                if (!source.hasRemaining()) return -1
                val count = minOf(length, source.remaining())
                source.get(bytes, offset, count)
                return count
            }
        }
        return JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            val result = value(reader, shape) as? JSONObject ?: error("Thermal JSON must be an object")
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing thermal JSON" }
            result
        }
    }

    private fun value(reader: JsonReader, shape: Shape): Any {
        if (shape === Presence) {
            // Null and empty arrays still count as present, excluding skinned/morph geometry.
            reader.skipValue()
            return JSONObject.NULL
        }
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return JSONObject.NULL }
        return when (shape) {
            is Fields -> {
                require(reader.peek() == JsonToken.BEGIN_OBJECT) { "Expected thermal metadata object" }
                val result = JSONObject()
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    val child = shape.members[name]
                    if (child == null) reader.skipValue() else result.put(name, value(reader, child))
                }
                reader.endObject()
                result
            }
            is Items -> {
                require(reader.peek() == JsonToken.BEGIN_ARRAY) { "Expected thermal metadata array" }
                val result = JSONArray()
                reader.beginArray()
                while (reader.hasNext()) result.put(value(reader, shape.element))
                reader.endArray()
                result
            }
            is SelectedItems -> {
                require(reader.peek() == JsonToken.BEGIN_ARRAY) { "Expected thermal accessor array" }
                val result = JSONArray()
                var index = 0
                reader.beginArray()
                while (reader.hasNext()) {
                    if (index++ in shape.indices) result.put(value(reader, shape.element))
                    else { reader.skipValue(); result.put(JSONObject.NULL) }
                }
                reader.endArray()
                result
            }
            Scalar -> when (reader.peek()) {
                JsonToken.STRING -> reader.nextString()
                JsonToken.NUMBER -> reader.nextDouble()
                JsonToken.BOOLEAN -> reader.nextBoolean()
                else -> error("Invalid thermal metadata scalar")
            }
            Presence -> error("Presence handled before traversal")
        }
    }
}
