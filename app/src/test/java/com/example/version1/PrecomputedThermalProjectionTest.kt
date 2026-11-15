package com.example.version1

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class PrecomputedThermalProjectionTest {
    private val document = """{"nodes":[{"name":"driverSeat","mesh":0}],"meshes":[{"primitives":[{"material":0,"attributes":{"POSITION":0}}]}],"materials":[{"name":"car_seat__afterglow_interior","pbrMetallicRoughness":{"baseColorFactor":[0.4,0.5,0.6,1]}}],"accessors":[{"type":"VEC3","count":3,"componentType":5122,"normalized":true,"min":[-32768,-16384,0],"max":[32767,0,16384]}]}"""
    private fun model(): ByteBuffer {
        val json=document.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocateDirect(7+20+json.size+1).order(ByteOrder.LITTLE_ENDIAN).apply {
            position(7);putInt(0x46546c67);putInt(2);putInt(21+json.size)
            putInt(json.size);putInt(0x4e4f534a);put(json);put(0);position(7)
        }
    }
    private fun profile(model: ByteBuffer): JSONObject {
        val digest=MessageDigest.getInstance("SHA-256").apply { update(model.asReadOnlyBuffer()) }.digest()
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return JSONObject().put("thermalProjection",JSONObject().put("version",1)
            .put("sourceBytes",model.remaining()).put("sourceSha256",digest).put("json",document))
    }
    @Test fun exactModelKeepsColorsBoundsSlotsAndCallerWindow() {
        val model=model();val position=model.position();val limit=model.limit()
        val actual=PrecomputedThermalProjection.read(profile(model),model)!!
        val expected=ThermalGeometry.fromJson(JSONObject(document))
        assertEquals(expected.keys,actual.keys)
        val a=actual.getValue("driverSeat").single();val e=expected.getValue("driverSeat").single()
        assertEquals(e.material,a.material);assertEquals(e.wheel,a.wheel)
        assertArrayEquals(e.baseColor,a.baseColor,0f)
        assertArrayEquals(e.bounds!!.minimum,a.bounds!!.minimum,0f)
        assertArrayEquals(e.bounds.maximum,a.bounds.maximum,0f)
        assertEquals(position,model.position());assertEquals(limit,model.limit())
    }
    @Test fun absentHintUsesStreamingFallback() {assertNull(PrecomputedThermalProjection.read(JSONObject(),model()))}
    @Test fun changedModelInvalidatesHintRatherThanApplyingAnotherModelsGeometry() {
        val model=model();val profile=profile(model)
        model.put(model.limit()-1,1)
        assertNull(PrecomputedThermalProjection.read(profile,model))
        model.limit(model.limit()-1)
        assertNull(PrecomputedThermalProjection.read(profile,model))
    }
    @Test fun futureVersionAndMalformedDigestAreRejected() {
        val model=model();val p=profile(model);p.getJSONObject("thermalProjection").put("version",2)
        assertThrows(IllegalArgumentException::class.java){PrecomputedThermalProjection.read(p,model)}
        p.getJSONObject("thermalProjection").put("version",1).put("sourceSha256","invalid")
        assertThrows(IllegalArgumentException::class.java){PrecomputedThermalProjection.read(p,model)}
    }
    @Test fun oversizedProjectionAndInvalidContainerDoNotBypassGuards() {
        val model=model();val p=profile(model)
        p.getJSONObject("thermalProjection").put("json"," ".repeat(PrecomputedThermalProjection.MAX_JSON_BYTES+1))
        assertThrows(IllegalArgumentException::class.java){PrecomputedThermalProjection.read(p,model)}
        model.putInt(model.position(),0)
        assertThrows(IllegalArgumentException::class.java){PrecomputedThermalProjection.read(profile(model),model)}
    }
    @Test fun malformedProjectedDocumentIsNotPromotedToReadiness() {
        val model=model();val p=profile(model);p.getJSONObject("thermalProjection").put("json","{}")
        assertThrows(org.json.JSONException::class.java){PrecomputedThermalProjection.read(p,model)}
    }
    @Test fun unusedDeepFieldIsRejectedBeforeRecursiveTreeParsing() {
        val model=model();val p=profile(model)
        val deep="{\"unused\":"+"[".repeat(32)+"0"+"]".repeat(32)+"}"
        p.getJSONObject("thermalProjection").put("json",deep)
        assertThrows(IllegalArgumentException::class.java){PrecomputedThermalProjection.read(p,model)}
    }
    @Test fun trailingSecondDocumentAndUtf8OversizeAreRejected() {
        val model=model();val p=profile(model)
        p.getJSONObject("thermalProjection").put("json",document+"{}")
        assertThrows(IllegalArgumentException::class.java){PrecomputedThermalProjection.read(p,model)}
        val raw="{\"unused\":\""+"가".repeat(70_000)+"\"}"
        assertTrue(raw.length<PrecomputedThermalProjection.MAX_JSON_BYTES)
        p.getJSONObject("thermalProjection").put("json",raw)
        assertThrows(IllegalArgumentException::class.java){PrecomputedThermalProjection.read(p,model)}
    }
}
