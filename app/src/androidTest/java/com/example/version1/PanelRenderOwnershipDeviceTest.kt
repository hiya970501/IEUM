package com.example.version1

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.view.View
import android.view.ViewGroup
import android.view.TextureView

/** Actual TextureView/native renderer. Scene-input fixture is not a vehicle command/readback test. */
@RunWith(AndroidJUnit4::class)
class PanelRenderOwnershipDeviceTest {
    @Test fun unchangedPanelDoesNotRenderButDeferredStateReachesActualTexture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("panelRenderFixture") == "emulator")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
        fun count(scene: PremiumCabinView, name: String) = (field(scene, name).get(scene) as Number).toLong()
        fun await(label: String, check: (MainActivity, PremiumCabinView) -> Boolean) {
            val end = android.os.SystemClock.uptimeMillis() + 60_000
            while (android.os.SystemClock.uptimeMillis() < end) {
                var ready = false
                scenario.onActivity { ready = check(it, field(it, "scene").get(it) as PremiumCabinView) }
                if (ready) return
                Thread.sleep(25)
            }
            error(label)
        }
        fun panel(activity: MainActivity) = field(activity, "modal").get(activity) as? UiPanel
        fun open(activity: MainActivity) = activity.javaClass.getDeclaredMethod("requestSettings")
            .apply { isAccessible = true }.invoke(activity)
        var original: CabinSceneState? = null
        var backdrop: View? = null
        var originalBackdropAlpha = 1f
        var originalViewer: Any? = null
        try {
            await("Real parked scene did not settle") { activity, scene ->
                val gate = field(scene, "presentation").get(scene) as PreviewPresentationGate;
                !gate.covered && !gate.failed && (field(scene, "frameBudget").get(scene) as RenderFrameBudget).remaining == 0 &&
                    (field(scene, "state").get(scene) as CabinSceneState).connected &&
                    activity.javaClass.getDeclaredMethod("safeToAct").apply { isAccessible = true }.invoke(activity) == true &&
                    !field(activity, "busy").getBoolean(activity)
            }
            // This activity's poll would intentionally overwrite a projection fixture with SDK data.
            // Stop only that owned coroutine; closing this activity restarts no other owner's work.
            var poll: Job? = null
            scenario.onActivity { poll = field(it, "poll").get(it) as? Job; poll?.cancel() }
            runBlocking { poll?.join() }
            // Cancelling an in-flight SDK read can leave its last scene update queued.
            // Establish the unchanged/presented precondition after that work drains.
            await("Latest scene state not submitted before panel") { _, scene ->
                field(scene, "state").get(scene) == field(scene, "appliedState").get(scene) &&
                    (field(scene, "frameBudget").get(scene) as RenderFrameBudget).remaining == 0 &&
                    !field(scene, "scheduled").getBoolean(scene)
            }
            Thread.sleep(500)
            var before = 0L
            scenario.onActivity {
                val scene = field(it, "scene").get(it) as PremiumCabinView
                original = field(scene, "state").get(scene) as CabinSceneState
                backdrop = it.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                originalBackdropAlpha = backdrop!!.alpha
                originalViewer = field(scene, "modelViewer").get(scene)
                assertNotNull(originalViewer)
                before = count(scene, "acceptedFrameCount")
                open(it); check(panel(it)?.isShowing == true)
                assertEquals("The original dimmed background remains visible", originalBackdropAlpha, backdrop!!.alpha, 0f)
                assertSame(originalViewer, field(scene, "modelViewer").get(scene))
                assertTrue((field(scene, "renderSurface").get(scene) as TextureView).isAvailable)
            }
            Thread.sleep(300)
            scenario.onActivity { panel(it)!!.dismiss() }
            Thread.sleep(500)
            scenario.onActivity {
                val scene = field(it, "scene").get(it) as PremiumCabinView
                assertEquals("Unchanged panel must reuse its already presented texture", before, count(scene, "acceptedFrameCount"))
                assertEquals("Closing restores the exact original alpha", originalBackdropAlpha, backdrop!!.alpha, 0f)
                assertSame(originalViewer, field(scene, "modelViewer").get(scene))
                open(it)
                assertEquals(originalBackdropAlpha, backdrop!!.alpha, 0f)
            }
            val probe = original!!.copy(fan = if (original!!.fan == 7) 1 else 7, powerOn = true, connected = true)
            var beforeTexture = 0L
            var thermalWritesBeforeFan = 0L
            scenario.onActivity {
                val scene = field(it, "scene").get(it) as PremiumCabinView
                beforeTexture = count(scene, "textureUpdateCount")
                thermalWritesBeforeFan = count(scene, "thermalUniformWriteCount")
                scene.update(probe)
                assertTrue(field(scene, "deferredPanelFrames").getInt(scene) > 0)
            }
            Thread.sleep(300)
            scenario.onActivity {
                val scene = field(it, "scene").get(it) as PremiumCabinView
                assertEquals("No hidden native submissions behind the panel", before, count(scene, "acceptedFrameCount"))
                panel(it)!!.dismiss()
            }
            await("Deferred state never reached actual texture") { _, scene ->
                field(scene, "appliedState").get(scene) == probe && count(scene, "acceptedFrameCount") > before &&
                    count(scene, "textureUpdateCount") > beforeTexture
            }
            scenario.onActivity {
                assertEquals(originalBackdropAlpha, backdrop!!.alpha, 0f)
                assertSame(originalViewer, field(field(it, "scene").get(it)!!, "modelViewer").get(field(it, "scene").get(it)))
                assertEquals("A fan-only scene change must not rewrite unchanged thermal uniforms",
                    thermalWritesBeforeFan, count(field(it, "scene").get(it) as PremiumCabinView, "thermalUniformWriteCount"))
            }
            scenario.onActivity { (field(it, "scene").get(it) as PremiumCabinView).update(original!!) }
            await("Original scene-input state not restored") { _, scene -> field(scene, "appliedState").get(scene) == original }
            File(context.filesDir, "panel-render-ownership-result.json").writeText(JSONObject()
                .put("unchangedCloseReusedPresentedTexture", true).put("hiddenNativeSubmissionsSuppressed", true)
                .put("deferredSceneStateReachedActualTexture", true).put("sceneInputRestored", true)
                .put("fanChangeAvoidedThermalUniformWrites", true)
                .put("settingsPanelPreservedTextureWithoutDetach", true)
                .put("backgroundAlphaExactlyRestored", true).put("sameNativeViewerPreserved", true)
                .put("vehicleCommandsIssued", false).put("actualSdkThermalReadbackVerified", false).toString(2))
        } finally {
            scenario.onActivity {
                panel(it)?.dismiss()
                original?.let { value -> (field(it, "scene").get(it) as PremiumCabinView).update(value) }
            }
            scenario.closeAndAwaitNativeRelease()
        }
    }
}
