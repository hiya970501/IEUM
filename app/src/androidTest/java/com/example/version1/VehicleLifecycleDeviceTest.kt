package com.example.version1

import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Production scene/lifecycle, logical protected settings. No plaintext export or vehicle command. */
@RunWith(AndroidJUnit4::class)
class VehicleLifecycleDeviceTest {
    @Test fun allInstalledFullCarsSwitchAndResumeInOneProcess() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("vehicleLifecycle") == "emulator")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.getSharedPreferences("recovery_restore", 0).getString("pending", "[]") == "[]")
        val variants = VehicleVisualCatalog.load(context).filter { it.fullVehicle }
        assertEquals("Expected installed genuine full-vehicle catalog", 10, variants.size)
        val store = VehicleSelectionStore(context)
        val original = store.load()
        val pid = Process.myPid()
        val records = JSONArray()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(target)
        fun snapshot(stage: String): JSONObject {
            assertEquals(pid, Process.myPid())
            val status = File("/proc/self/status").readText()
            fun bytes(key: String): Long = Regex("(?m)^$key:\\s+(\\d+) kB").find(status)!!.groupValues[1].toLong() * 1024
            val memory = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
            return JSONObject().put("stage", stage).put("pid", pid)
                .put("rssBytes", bytes("VmRSS")).put("hwmBytes", bytes("VmHWM"))
                .put("pssBytes", memory.totalPss.toLong() * 1024)
        }
        fun awaitPresented(asset: String? = null, right: Boolean? = null) {
            val deadline = SystemClock.elapsedRealtime() + 60000
            while (SystemClock.elapsedRealtime() < deadline) {
                var ready = false
                scenario.onActivity { activity ->
                    val scene = field(activity, "scene")!!
                    val gate = field(scene, "presentation") as PreviewPresentationGate
                    check(!gate.failed) { "Production preview failed" }
                    ready = !gate.covered && field(scene, "shellAsset") != null &&
                        (asset == null || field(scene, "loadedShellProfileId") == asset) &&
                        (right == null || field(scene, "loadedShellRightHandDrive") == right)
                }
                if (ready) return
                Thread.sleep(100)
            }
            error("Actual displayed vehicle frame not confirmed")
        }
        fun applySelection(selection: VehicleSelectionPreferences) {
            scenario.onActivity { activity ->
                check(MainActivity::class.java.getDeclaredMethod("canChooseVehicleVisual")
                    .apply { isAccessible = true }.invoke(activity) == true) { "Preserve active or restricted session" }
                store.save(selection)
                MainActivity::class.java.getDeclaredField("vehiclePreferences").apply { isAccessible = true }.set(activity, selection)
                MainActivity::class.java.getDeclaredMethod("restartVehicleReader").apply { isAccessible = true }.invoke(activity)
                MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
            }
        }
        var restored = false
        var completed = false
        try {
            awaitPresented()
            records.put(snapshot("initial"))
            for (right in listOf(false, true)) for (variant in variants) {
                applySelection(original.copy(autoEnabled = false, manualProfileId = variant.profileId,
                    rightHandDrive = right, visualVariantIds = original.visualVariantIds + (variant.profileId to variant.assetId)))
                awaitPresented(variant.assetId, right)
                records.put(snapshot("${variant.assetId}:${if (right) "rhd" else "lhd"}"))
            }
            repeat(3) { cycle ->
                scenario.moveToState(Lifecycle.State.CREATED)
                var released = false
                val deadline = SystemClock.elapsedRealtime() + 20000
                while (!released && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { activity ->
                        val scene = field(activity, "scene")!!
                        released = NativeRenderLifecycle.nativeReady && field(scene, "modelViewer") == null && field(scene, "shellAsset") == null &&
                            field(scene, "loadJob") == null && field(scene, "shellLoadJob") == null
                    }
                    if (!released) Thread.sleep(100)
                }
                assertTrue("Background native objects were retained", released)
                records.put(snapshot("background-$cycle").put("nativeResourcesReleased", true))
                scenario.moveToState(Lifecycle.State.RESUMED)
                awaitPresented(variants.last().assetId, true)
                records.put(snapshot("resume-$cycle"))
            }
            completed = true
        } finally {
          try {
            scenario.moveToState(Lifecycle.State.RESUMED)
            applySelection(original)
            awaitPresented()
            restored = store.load() == original
            records.put(snapshot("restored"))
            File(context.filesDir, "vehicle-lifecycle-result.json").writeText(JSONObject()
                .put("pid", pid).put("selectionRestored", restored).put("allStagesCompleted", completed)
                .put("selectionMethod", "programmatic protected preferences through production render; not touch latency")
                .put("testRequestedGcOrTrim", false).put("productionRecoveryMayRequestGc", true).put("vehicleCommandsIssued", false)
                .put("records", records).toString(2))
          } finally { scenario.closeAndAwaitNativeRelease() }
        }
        assertTrue("Original logical selection not restored", restored)
        for (i in 0 until records.length()) {
            val row = records.getJSONObject(i)
            assertTrue("Whole-process working RSS high-water exceeded", row.getLong("hwmBytes") <= 335544320)
            assertTrue("Whole-process PSS working ceiling exceeded", row.getLong("pssBytes") <= 335544320)
        }
    }
}
