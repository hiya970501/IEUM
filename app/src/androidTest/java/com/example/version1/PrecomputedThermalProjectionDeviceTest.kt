package com.example.version1

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel

/** Compare optional preprocessing to the production streaming parser, never start an Activity/SDK. */
@RunWith(AndroidJUnit4::class)
class PrecomputedThermalProjectionDeviceTest {
    @Test fun installedPreprocessingExactlyMatchesActualModelGeometry() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("precomputedThermalFixture")=="emulator")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.filesDir,"vehicle-previews")
        var checked=0;var primitives=0
        directory.listFiles().orEmpty().filter { it.isFile && it.extension=="json" }.forEach { metadata ->
            require(metadata.length()<=512_000)
            val profile=JSONObject(metadata.readText())
            if(!profile.has("thermalProjection")) return@forEach
            val file=File(directory,metadata.nameWithoutExtension+".glb")
            assertTrue(file.isFile)
            FileInputStream(file).channel.use { channel ->
                val buffer=channel.map(FileChannel.MapMode.READ_ONLY,0,channel.size())
                val position=buffer.position();val limit=buffer.limit()
                val expected=ThermalGeometry.fromGlb(buffer)
                val actual=checkNotNull(PrecomputedThermalProjection.read(profile,buffer))
                assertEquals(expected.keys,actual.keys)
                expected.forEach { (name,parts) ->
                    val saved=actual.getValue(name);assertEquals(parts.size,saved.size)
                    parts.zip(saved).forEach { (e,a) ->
                        assertEquals(e.material,a.material);assertEquals(e.wheel,a.wheel)
                        if(e.baseColor==null) assertNull(a.baseColor) else assertArrayEquals(e.baseColor,a.baseColor,0f)
                        if(e.bounds==null) assertNull(a.bounds) else {
                            assertNotNull(a.bounds)
                            assertArrayEquals(e.bounds.minimum,a.bounds!!.minimum,0f)
                            assertArrayEquals(e.bounds.maximum,a.bounds.maximum,0f)
                        }
                        primitives++
                    }
                }
                assertEquals(position,buffer.position());assertEquals(limit,buffer.limit());checked++
            }
        }
        assertTrue("At least one real installed precomputed model is required",checked>0)
        assertTrue("Real thermal primitives are required",primitives>0)
        File(context.filesDir,"precomputed-thermal-result.json").writeText(JSONObject()
            .put("passed",true).put("models",checked).put("primitives",primitives)
            .put("callerWindowsUnchanged",true).put("vehicleCommandsIssued",false)
            .put("activityStarted",false).put("preferencesChanged",false).toString())
    }
}
