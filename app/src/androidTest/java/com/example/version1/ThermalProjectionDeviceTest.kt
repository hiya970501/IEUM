package com.example.version1

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** No Activity, renderer, vehicle commands or preferences. Compare to full legacy JSON. */
@RunWith(AndroidJUnit4::class)
class ThermalProjectionDeviceTest {
    private fun signature(parts: Map<String, List<ThermalPrimitive>>): String = JSONObject().apply {
        parts.toSortedMap().forEach { (name, primitives) ->
            put(name, JSONArray().apply { primitives.forEach { p -> put(JSONObject()
                .put("material", p.material).put("wheel", p.wheel)
                .put("color", p.baseColor?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
                .put("min", p.bounds?.minimum?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
                .put("max", p.bounds?.maximum?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)) } })
        }
    }.toString()

    private fun legacy(buffer: ByteBuffer): Map<String, List<ThermalPrimitive>> {
        val copy = buffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        val start = copy.position()
        val bytes = ByteArray(copy.getInt(start + 12))
        copy.position(start + 20); copy.get(bytes)
        return ThermalGeometry.fromJson(JSONObject(String(bytes, Charsets.UTF_8)))
    }

    private fun glb(json: String): ByteBuffer {
        val bytes = json.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocateDirect(8 + 20 + bytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            position(8)
            putInt(0x46546c67).putInt(2).putInt(20 + bytes.size).putInt(bytes.size).putInt(0x4e4f534a).put(bytes)
            position(8)
        }
    }

    @Test fun projectionMatchesLegacyAcrossInstalledFullVehicleCorpusAndExclusionCases() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("thermalProjectionFixture") == "emulator")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = File(context.filesDir, "vehicle-previews").listFiles().orEmpty()
            .filter { it.isFile && it.extension == "glb" }.sortedBy { it.name }
        assertTrue("At least ten installed full-vehicle inputs are required", files.size >= 10)
        val rows = JSONArray()
        var thermalFiles = 0
        var accessorCount = 0
        var retainedAccessorCount = 0
        files.forEach { file ->
            FileInputStream(file).channel.use { channel ->
                val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                val position = buffer.position(); val limit = buffer.limit()
                val expected = legacy(buffer)
                val actual = ThermalGeometry.fromGlb(buffer)
                assertEquals(file.name, signature(expected), signature(actual))
                assertEquals(position, buffer.position()); assertEquals(limit, buffer.limit())
                if (actual.isNotEmpty()) thermalFiles++
                val chunk = buffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
                chunk.limit(20 + chunk.getInt(12)); chunk.position(20)
                val projected = ThermalJsonProjection.read(chunk.slice())
                val accessors = projected.optJSONArray("accessors") ?: JSONArray()
                val retained = (0 until accessors.length()).count { !accessors.isNull(it) }
                accessorCount += accessors.length(); retainedAccessorCount += retained
                rows.put(JSONObject().put("asset", file.name).put("nodes", actual.size).put("equal", true)
                    .put("accessors", accessors.length()).put("retainedAccessors", retained))
            }
        }
        assertTrue("Corpus must contain real thermal metadata", thermalFiles >= 10)
        val fixture = JSONObject("""{
          "textures":[{"unused":{"deep":[1,2,3]}}],
          "nodes":[{"mesh":0,"name":"front-seats"}],
          "materials":[{"pbrMetallicRoughness":{"baseColorFactor":[0.2,0.3,0.4,1]},"name":"FR_SEAT__afterglow_interior"}],
          "accessors":[{"max":[-0.1,1,1],"min":[-0.3,0,0],"count":3,"type":"VEC3","componentType":5126}],
          "meshes":[{"primitives":[{"attributes":{"POSITION":0},"material":0}]}]
        }""")
        val base = glb(fixture.toString())
        val parts = ThermalGeometry.fromGlb(base).getValue("front-seats")
        assertEquals(8, base.position()); assertEquals(base.capacity(), base.limit())
        assertEquals("FR_SEAT__afterglow_interior", parts[0].material)
        assertArrayEquals(floatArrayOf(-.3f, 0f, 0f), parts[0].bounds!!.minimum, .00001f)
        for (kind in listOf("plain", "skinNull", "targetsEmpty", "excludedRole", "duplicateName")) {
            val variant = JSONObject(fixture.toString())
            val node = variant.getJSONArray("nodes").getJSONObject(0)
            val primitive = variant.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives").getJSONObject(0)
            when (kind) {
                "skinNull" -> node.put("skin", JSONObject.NULL)
                "targetsEmpty" -> primitive.put("targets", JSONArray())
                "excludedRole" -> primitive.put("extras", JSONObject().put("afterglowThermalRole", "none"))
                "duplicateName" -> variant.getJSONArray("nodes").put(JSONObject(node.toString()))
            }
            val buffer = glb(variant.toString())
            assertEquals(kind, signature(legacy(buffer)), signature(ThermalGeometry.fromGlb(buffer)))
        }
        // Accessors precede their users and the required POSITION is index 1.
        // Dropping skipped entries rather than preserving holes would select index 2.
        val outOfOrder = """{"accessors":[
          {"type":"SCALAR","count":1,"componentType":5126,"min":[99],"max":[99]},
          {"type":"VEC3","count":3,"componentType":5126,"min":[-0.3,0,0],"max":[-0.1,1,1]},
          {"type":"VEC3","count":3,"componentType":5126,"min":[7,7,7],"max":[8,8,8]}],
          "meshes":[{"primitives":[{"attributes":{"POSITION":1},"material":0}]}],
          "materials":[{"name":"FR_SEAT__afterglow_interior"}],
          "nodes":[{"name":"front-seats","mesh":0}]}"""
        val selected = ThermalJsonProjection.read(ByteBuffer.wrap(outOfOrder.toByteArray(Charsets.UTF_8)))
        val projectedAccessors = selected.getJSONArray("accessors")
        assertEquals(3, projectedAccessors.length())
        assertTrue(projectedAccessors.isNull(0)); assertNotNull(projectedAccessors.optJSONObject(1))
        assertTrue(projectedAccessors.isNull(2))
        assertEquals(signature(ThermalGeometry.fromJson(JSONObject(outOfOrder))),
            signature(ThermalGeometry.fromGlb(glb(outOfOrder))))
        val withoutAccessors = JSONObject(outOfOrder).apply { remove("accessors") }.toString()
        assertEquals(signature(ThermalGeometry.fromJson(JSONObject(withoutAccessors))),
            signature(ThermalGeometry.fromGlb(glb(withoutAccessors))))
        assertTrue("Real corpus must skip unused accessor objects", retainedAccessorCount < accessorCount)
        File(context.filesDir, "thermal-projection-result.json").writeText(JSONObject()
            .put("passed", true).put("scope", "All installed GLBs, original direct-buffer contract, five exclusion fixtures")
            .put("actualVehicleInputs", files.size).put("thermalInputs", thermalFiles).put("rows", rows)
            .put("accessorIndexPreservationVerified", true).put("accessors", accessorCount)
            .put("retainedAccessors", retainedAccessorCount)
            .put("nativeRenderingVerified", false).put("memorySavingMeasured", false).toString(2))
    }
}
