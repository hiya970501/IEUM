package com.example.version1

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThermalGeometryTest {
    // Direct-buffer parsing runs on Android in ThermalProjectionDeviceTest so the
    // production JsonReader is exercised rather than a mocked framework method.
    private fun accessor(type: Int = 5122, normalized: Boolean = true, lo: String = "[-32768,-16384,0]", hi: String = "[32767,0,16384]") =
        JSONObject("""{"type":"VEC3","componentType":$type,"normalized":$normalized,"count":3,"min":$lo,"max":$hi}""")

    @Test fun signedAndUnsignedQuantizedBoundsAreDecodedBeforeNodeTransforms() {
        val short = ThermalGeometry.positionBounds(accessor())!!
        assertEquals(-1f, short.minimum[0], 0f); assertEquals(1f, short.maximum[0], 0f)
        assertEquals(-16384f / 32767, short.minimum[1], .000001f)
        val byte = ThermalGeometry.positionBounds(accessor(5120, true, "[-128,0,0]", "[127,127,0]"))!!
        assertEquals(-1f, byte.minimum[0], 0f); assertEquals(1f, byte.maximum[0], 0f)
        val unsigned = ThermalGeometry.positionBounds(accessor(5123, true, "[0,0,0]", "[65535,32767,0]"))!!
        assertEquals(1f, unsigned.maximum[0], 0f)
        assertEquals(32767f / 65535, unsigned.maximum[1], .000001f)
    }
    @Test fun invalidOrUnsupportedBoundsDoNotBecomeArbitrarySeatCenters() {
        assertNull(ThermalGeometry.positionBounds(accessor(lo = "[2,0,0]", hi = "[1,1,1]")))
        assertNull(ThermalGeometry.positionBounds(accessor(lo = "[-32769,0,0]")))
        assertNull(ThermalGeometry.positionBounds(accessor(5126, true)))
        assertNull(ThermalGeometry.positionBounds(accessor(5125, false)))
        assertNull(ThermalGeometry.positionBounds(accessor().apply { remove("min") }))
    }
    @Test fun fullBoundsAreRotatedAndMirroredRatherThanOnlyTheWholeMeshesCenter() {
        val bounds = ThermalBounds(floatArrayOf(-2f, 0f, 1f), floatArrayOf(-1f, 2f, 3f))
        val rotation = floatArrayOf(0f,0f,1f,0f, 0f,1f,0f,0f, -1f,0f,0f,0f, 4f,0f,0f,1f)
        val transformed = bounds.world(rotation)!!
        assertArrayEquals(floatArrayOf(1f,0f,-2f), transformed.minimum, .00001f)
        assertArrayEquals(floatArrayOf(3f,2f,-1f), transformed.maximum, .00001f)
        val mirrored = bounds.world(floatArrayOf(-1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f))!!
        assertTrue(mirrored.onSide(.2f)); assertFalse(bounds.onSide(.2f))
    }
    @Test fun combinedPassengerGeometryIsNotSelectedJustBecauseItsCenterIsOnTheDriverSide() {
        val both = ThermalBounds(floatArrayOf(-.3f,0f,0f), floatArrayOf(.7f,1f,1f))
        assertTrue(both.center[0] > 0)
        assertFalse(both.onSide(.2f)); assertFalse(both.onSide(-.2f))
        assertFalse(ThermalBounds(floatArrayOf(.1f,0f,0f), floatArrayOf(.3f,1f,1f)).onSide(0f))
    }
    @Test fun twoPrimitivesOnOneNodeKeepTheirOwnPositionBoundsAndMaterialSlot() {
        val metadata = ThermalGeometry.fromJson(fixture())
        val parts = metadata.getValue("front-seats")
        assertEquals(2, parts.size)
        assertTrue(parts[0].bounds!!.maximum[0] < 0)
        assertTrue(parts[1].bounds!!.minimum[0] > 0)
        assertEquals("FR_SEAT__afterglow_interior", parts[1].material)
    }
    @Test fun ambiguousNodeNamesAndSkinnedGeometryAreNotGuessed() {
        val duplicate = fixture().apply { getJSONArray("nodes").put(JSONObject("""{"name":"front-seats","mesh":0}""")) }
        assertTrue(ThermalGeometry.fromJson(duplicate).isEmpty())
        val skin = fixture().apply { getJSONArray("nodes").getJSONObject(0).put("skin", 0) }
        assertTrue(ThermalGeometry.fromJson(skin).isEmpty())
    }
    @Test fun authoredSteeringRoleIsLocalToThePrimitiveAndExcludedSharedTrimIsNotHeated() {
        val document = fixture()
        document.getJSONArray("materials").getJSONObject(0).put("name", "Handle__afterglow_interior")
        val primitives = document.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives")
        primitives.getJSONObject(0).put("extras", JSONObject().put("afterglowThermalRole", "steering"))
        primitives.getJSONObject(1).put("extras", JSONObject().put("afterglowThermalRole", "none"))
        val parts = ThermalGeometry.fromJson(document).getValue("front-seats")
        assertTrue(parts[0].wheel); assertNotNull(parts[0].baseColor)
        assertFalse(parts[1].wheel); assertNull(parts[1].baseColor)
    }
    private fun fixture() = JSONObject("""{
      "nodes":[{"name":"front-seats","mesh":0}],
      "meshes":[{"primitives":[{"material":0,"attributes":{"POSITION":0}},{"material":0,"attributes":{"POSITION":1}}]}],
      "materials":[{"name":"FR_SEAT__afterglow_interior","pbrMetallicRoughness":{"baseColorFactor":[0.2,0.3,0.4,1]}}],
      "accessors":[
        {"componentType":5126,"type":"VEC3","count":3,"min":[-0.3,0,0],"max":[-0.1,1,1]},
        {"componentType":5126,"type":"VEC3","count":3,"min":[0.1,0,0],"max":[0.3,1,1]}]
    }""")
}
