package com.example.version1

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Actual local POSITION bounds, decoded before the glTF node's dequantizing transform. */
internal data class ThermalBounds(val minimum: FloatArray, val maximum: FloatArray) {
    val center: FloatArray get() = FloatArray(3) { (minimum[it] + maximum[it]) * .5f }
    fun world(matrix: FloatArray): ThermalBounds? {
        if (matrix.size != 16 || matrix.any { !it.isFinite() } ||
            listOf(3, 7, 11).any { abs(matrix[it]) > .00001f } || abs(matrix[15] - 1f) > .00001f) return null
        val lo = FloatArray(3) { Float.POSITIVE_INFINITY }
        val hi = FloatArray(3) { Float.NEGATIVE_INFINITY }
        // Transform every corner: mirroring, rotation and nonuniform scale invalidate
        // transforming min/max alone. No preview coordinates become vehicle control areas.
        for (corner in 0..7) {
            val x = if (corner and 1 == 0) minimum[0] else maximum[0]
            val y = if (corner and 2 == 0) minimum[1] else maximum[1]
            val z = if (corner and 4 == 0) minimum[2] else maximum[2]
            for (axis in 0..2) {
                val value = matrix[axis] * x + matrix[4 + axis] * y + matrix[8 + axis] * z + matrix[12 + axis]
                if (!value.isFinite()) return null
                lo[axis] = min(lo[axis], value); hi[axis] = max(hi[axis], value)
            }
        }
        return ThermalBounds(lo, hi)
    }
    fun onSide(driverX: Float): Boolean = when {
        driverX > .02f -> minimum[0] > .002f
        driverX < -.02f -> maximum[0] < -.002f
        else -> false
    }
}

internal data class ThermalPrimitive(val material: String, val baseColor: FloatArray?, val bounds: ThermalBounds?, val wheel: Boolean)

internal object ThermalGeometry {
    fun fromGlb(bytes: ByteArray): Map<String, List<ThermalPrimitive>> = fromGlb(ByteBuffer.wrap(bytes))

    fun fromGlb(buffer: ByteBuffer): Map<String, List<ThermalPrimitive>> {
        return fromJson(ThermalJsonProjection.read(jsonWindow(buffer)))
    }

    internal fun jsonWindow(buffer: ByteBuffer): ByteBuffer {
        val header = buffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        val start = header.position()
        require(header.remaining() >= 20)
        val size = header.getInt(start + 12)
        require(header.getInt(start) == 0x46546c67 && header.getInt(start + 16) == 0x4e4f534a &&
            size in 1..1_048_576 && size <= header.remaining() - 20)
        // Keep the caller's window and BIN payload untouched. Project only thermal
        // metadata while streaming; image/buffer/animation JSON never becomes a tree.
        header.position(start + 20); header.limit(start + 20 + size)
        return header.slice()
    }

    fun fromJson(json: JSONObject): Map<String, List<ThermalPrimitive>> {
        val nodes = json.optJSONArray("nodes") ?: return emptyMap()
        val meshes = json.optJSONArray("meshes") ?: return emptyMap()
        val accessors = json.optJSONArray("accessors") ?: return emptyMap()
        val materials = json.optJSONArray("materials") ?: return emptyMap()
        val names = (0 until nodes.length()).map { nodes.getJSONObject(it).optString("name") }.groupingBy { it }.eachCount()
        return buildMap {
            for (index in 0 until nodes.length()) {
                val node = nodes.getJSONObject(index); val name = node.optString("name")
                if (name.isBlank() || names[name] != 1 || node.has("skin")) continue
                val mesh = meshes.optJSONObject(node.optInt("mesh", -1)) ?: continue
                val primitives = mesh.optJSONArray("primitives") ?: continue
                val metadata = (0 until primitives.length()).map { slot ->
                    val primitive = primitives.getJSONObject(slot)
                    val material = materials.optJSONObject(primitive.optInt("material", -1))
                    val materialName = material?.optString("name").orEmpty()
                    val role = primitive.optJSONObject("extras")?.optString("afterglowThermalRole").orEmpty()
                    val wheel = role != "none" && (CabinThermalStyle.wheelMaterial(materialName) ||
                        (role == "steering" && materialName.endsWith("__afterglow_interior")))
                    val thermal = role != "none" && (CabinThermalStyle.seatMaterial(materialName) || wheel)
                    val factor = material?.optJSONObject("pbrMetallicRoughness")?.optJSONArray("baseColorFactor")
                    val color = if (thermal) FloatArray(4) { factor?.optDouble(it, 1.0)?.toFloat() ?: 1f }
                        .takeIf { rgba -> rgba.all { it.isFinite() && it in 0f..1f } } else null
                    val accessor = primitive.optJSONObject("attributes")?.optInt("POSITION", -1) ?: -1
                    val bounds = if (thermal && !primitive.has("targets")) accessors.optJSONObject(accessor)?.let(::positionBounds) else null
                    ThermalPrimitive(materialName, color, bounds, wheel)
                }
                if (metadata.any { it.baseColor != null && it.bounds != null }) put(name, metadata)
            }
        }
    }

    fun positionBounds(accessor: JSONObject): ThermalBounds? = runCatching {
        require(accessor.getString("type") == "VEC3" && accessor.getInt("count") > 0)
        val type = accessor.getInt("componentType")
        val normalized = accessor.optBoolean("normalized")
        val range = when (type) {
            5120 -> -128.0..127.0; 5121 -> 0.0..255.0
            5122 -> -32768.0..32767.0; 5123 -> 0.0..65535.0
            5126 -> null
            else -> error("Unsupported POSITION component")
        }
        require(!normalized || range != null)
        fun decode(key: String): FloatArray {
            val values = accessor.getJSONArray(key); require(values.length() == 3)
            return FloatArray(3) { axis ->
                val value = values.getDouble(axis); require(value.isFinite())
                if (range != null) require(value in range && value == kotlin.math.floor(value))
                val decoded = if (normalized) max(if (range!!.start < 0) -1.0 else 0.0, value / range.endInclusive) else value
                decoded.toFloat().also { require(it.isFinite()) }
            }
        }
        val lo = decode("min"); val hi = decode("max")
        require((0..2).all { lo[it] <= hi[it] })
        ThermalBounds(lo, hi)
    }.getOrNull()
}
