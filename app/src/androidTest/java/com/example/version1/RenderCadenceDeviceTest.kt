package com.example.version1

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Counters only: diagnose scheduled work without collecting vehicle/health/profile values. */
@RunWith(AndroidJUnit4::class)
class RenderCadenceDeviceTest {
    @Test fun observeSettledFrameCadence() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("guidelineUi") == "emulator")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rows = JSONArray()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            Thread.sleep(12000)
            repeat(12) {
                scenario.onActivity { activity ->
                    fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
                    val scene = field(activity, "scene")!!
                    val state = field(scene, "state")
                    rows.put(JSONObject().put("atElapsedMs", android.os.SystemClock.elapsedRealtime())
                        .put("acceptedFrames", field(scene, "acceptedFrameCount"))
                        .put("remaining", (field(scene, "frameBudget") as RenderFrameBudget).remaining)
                        .put("touchDown", field(scene, "touchDown")).put("detailsVisible", field(scene, "detailsVisible"))
                        .put("stateAlreadyApplied", state == field(scene, "appliedState"))
                        .put("particleCount", (field(scene, "flowParticles") as List<*>).size))
                }
                Thread.sleep(1000)
            }
            File(context.filesDir, "guideline-render-stats.json").writeText(rows.toString(2))
        } finally { scenario.closeAndAwaitNativeRelease() }
    }
}
