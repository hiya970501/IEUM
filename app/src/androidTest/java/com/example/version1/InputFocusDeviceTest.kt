package com.example.version1

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual OEM multi-window focus handoff, not a synthetic lifecycle callback. */
@RunWith(AndroidJUnit4::class)
class InputFocusDeviceTest {
    @Test fun visibleUnfocusedAppExplainsPauseAndPublicLogoTapRestoresActualPreview() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inputFocusFixture") == "emulator")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = ProtectedPreferences.get(context, "comfort_preferences")
        val original = prefs.all.toMap()
        val automation = instrumentation.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var stage = "initial-preview"
        val notice = "이 화면을 터치해 차량 연결을 재개하세요"
        fun osTap(x: Float, y: Float) {
            val down = SystemClock.uptimeMillis()
            fun inject(action: Int) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(automation.injectInputEvent(event, true)) }
                finally { event.recycle() }
            }
            inject(MotionEvent.ACTION_DOWN); Thread.sleep(16); inject(MotionEvent.ACTION_UP)
        }
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun await(check: (MainActivity) -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 60000
            while (SystemClock.elapsedRealtime() < deadline) {
                var ready = false
                scenario.onActivity { ready = check(it) }
                if (ready) return
                Thread.sleep(100)
            }
            val diagnostic = JSONObject().put("stage", stage)
            scenario.onActivity { activity ->
                val gate = field("foregroundGate").get(activity) as ForegroundResourceGate
                diagnostic.put("foreground", field("foreground").getBoolean(activity))
                    .put("awaitingInputFocus", gate.awaitingInputFocus).put("allowed", gate.allowed)
                    .put("windowFocus", activity.hasWindowFocus()).put("multiWindow", activity.isInMultiWindowMode)
                    .put("hintMatches", (field("footerLabel").get(activity) as TextView).text.toString() == notice)
                for (name in listOf("started", "resumed", "top", "interactive"))
                    diagnostic.put(name, gate.javaClass.getDeclaredField(name).apply { isAccessible = true }.getBoolean(gate))
            }
            File(context.filesDir, "input-focus-diagnostic.json").writeText(diagnostic.toString(2))
            error("Actual focus/preview condition not confirmed at $stage")
        }
        fun presented(activity: MainActivity): Boolean {
            val scene = field("scene").get(activity) as PremiumCabinView
            val gate = scene.javaClass.getDeclaredField("presentation").apply { isAccessible = true }
                .get(scene) as PreviewPresentationGate
            check(!gate.failed)
            return field("foreground").getBoolean(activity) && !gate.covered
        }
        try {
            await(::presented)
            scenario.onActivity {
                assertFalse(field("active").getBoolean(it))
                assertFalse(field("busy").getBoolean(it))
                assertTrue(field("parked").getBoolean(it))
                assertEquals(0, it.windowManager.defaultDisplay.displayId)
            }
            // The cluster Activity has an OEM launch permission. Do not request
            // or bypass it: focus its already-visible, noninteractive speed label.
            fun speedLabel(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.packageName?.toString() == "ai.umos.drivingview" && node.isVisibleToUser &&
                    node.isEnabled && node.text?.toString()?.trim()?.matches(Regex("^(?:0\\s*)?km\\s*/\\s*h$")) == true &&
                    generateSequence(node) { it.parent }.none { it.isClickable || it.isLongClickable }) return node
                for (index in 0 until node.childCount) speedLabel(node.getChild(index))?.let { return it }
                return null
            }
            // On this owned 2560x1440 emulator the speed text is an overlay and
            // does not take input focus. Use the blank cluster body directly
            // observed in iteration491/normal-window.png (not a vehicle control).
            // Never reuse that observed point on a different layout/display.
            val size = android.graphics.Point()
            scenario.onActivity { it.windowManager.defaultDisplay.getRealSize(size) }
            check(size.x == 2560 && size.y == 1440)
            val cluster = automation.windowsOnAllDisplays[0].orEmpty().asSequence().mapNotNull { it.root }
                .filter { it.packageName?.toString() == "ai.umos.drivingview" && it.isVisibleToUser }
                .firstOrNull { node -> Rect().also { node.getBoundsInScreen(it) }.contains(100, 300) }
                ?: error("Observed OEM blank-body point is not in the visible cluster; no touch issued")
            check(speedLabel(cluster) != null) { "Observed cluster structure no longer matches; no touch issued" }
            osTap(100f, 300f)
            stage = "after-observed-oem-blank-body-tap"
            await { activity ->
                val gate = field("foregroundGate").get(activity) as ForegroundResourceGate
                val label = field("footerLabel").get(activity) as TextView
                val bounds = Rect()
                return@await !field("foreground").getBoolean(activity) && gate.awaitingInputFocus &&
                    label.isShown && label.getGlobalVisibleRect(bounds) && label.text.toString() == notice
            }
            scenario.onActivity {
                // A late ordinary render must not replace the focus hint with a
                // misleading P/connection/loading state. This invocation is a QA fixture.
                MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(it)
                assertEquals(notice, (field("footerLabel").get(it) as TextView).text.toString())
                assertTrue((field("footerLabel").get(it) as TextView).isShown)
                assertFalse(field("foreground").getBoolean(it))
            }
            var point: Pair<Float, Float>? = null
            scenario.onActivity { activity ->
                fun find(view: View): TextView? {
                    if (view is TextView && view.text.toString() == "IEUM" && view.isShown) return view
                    if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                    return null
                }
                val logo = checkNotNull(find(activity.window.decorView))
                assertFalse(logo.isClickable)
                val rect = Rect(); assertTrue(logo.getLocalVisibleRect(rect)); assertFalse(rect.isEmpty)
                val origin = IntArray(2); logo.getLocationOnScreen(origin)
                point = origin[0] + rect.exactCenterX() to origin[1] + rect.exactCenterY()
            }
            val (x, y) = checkNotNull(point)
            osTap(x, y)
            stage = "after-public-logo-tap"
            await(::presented)
            scenario.onActivity {
                assertFalse((field("foregroundGate").get(it) as ForegroundResourceGate).awaitingInputFocus)
                val label = field("footerLabel").get(it) as TextView
                assertTrue("Focus hint must no longer be displayed", !label.isShown || label.text.toString() != notice)
                assertFalse(field("active").getBoolean(it))
            }
            assertEquals(original, prefs.all)
            File(context.filesDir, "input-focus-result.json").writeText(JSONObject()
                .put("passed", true).put("actualOemFocusHandoff", true).put("lateRenderHintPreserved", true)
                .put("publicLogoOsTap", true).put("actualPreviewRestored", true)
                .put("observedOemBlankBodyTap", true).put("hintActuallyVisible", true)
                .put("preferencesPreserved", true).put("vehicleCommandsIssued", false)
                .put("physicalScanoutVerified", false).toString(2))
        } finally {
            try { scenario.closeAndAwaitNativeRelease() }
            finally { automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags } }
        }
    }
}
