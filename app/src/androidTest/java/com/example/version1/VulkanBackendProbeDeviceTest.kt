package com.example.version1

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Engine
import com.google.android.filament.Renderer
import com.google.android.filament.utils.Utils
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Engine-only diagnostic. No model, swapchain or frame is presented; no CPU-saving claim. */
@RunWith(AndroidJUnit4::class)
class VulkanBackendProbeDeviceTest {
    @Test fun createAndActuallyReleaseVulkanEngine() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("vulkanProbe") == "emulator")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var engine: Engine? = null
        var renderer: Renderer? = null
        val receipt = JSONObject().put("diagnosticOnly", true).put("modelImported", false)
            .put("framePresented", false).put("cpuSavingsProven", false).put("vehicleCommandsIssued", false)
        try {
            instrumentation.runOnMainSync {
                check(NativeRenderLifecycle.nativeReady)
                val before = checkNotNull(ProcessMemoryReader.read())
                Utils.init()
                val started = SystemClock.elapsedRealtime()
                val created = Engine.Builder().backend(Engine.Backend.VULKAN)
                    .feature("engine.frame_info.disable_gpu_complete_metric", true).build()
                engine = created
                renderer = created.createRenderer()
                val after = checkNotNull(ProcessMemoryReader.read())
                receipt.put("backend", created.backend.name)
                    .put("creationMillis", SystemClock.elapsedRealtime() - started)
                    .put("beforeRssBytes", before.rssBytes).put("afterRssBytes", after.rssBytes)
                    .put("afterPssBytes", after.pssBytes).put("afterHwmBytes", after.peakRssBytes)
                assertEquals(Engine.Backend.VULKAN, created.backend)
                assertTrue(after.maxAccountedBytes < MemoryBudgetPolicy.WORKING_LIMIT_BYTES)
            }
        } finally {
            instrumentation.runOnMainSync {
                engine?.let { owned ->
                    DeferredRendererRelease.schedule(owned) {
                        renderer?.let(owned::destroyRenderer)
                        owned.destroy()
                    }
                }
            }
            val deadline = SystemClock.elapsedRealtime() + 60000
            var released = false
            while (!released && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { released = NativeRenderLifecycle.nativeReady }
                if (!released) SystemClock.sleep(50)
            }
            receipt.put("actualNativeCleanupComplete", released)
            File(context.filesDir, "vulkan-engine-probe.json").writeText(receipt.toString(2))
            assertTrue("Actual Vulkan backend cleanup not confirmed", released)
        }
    }
}
