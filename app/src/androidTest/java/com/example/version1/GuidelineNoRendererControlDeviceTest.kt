package com.example.version1

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Diagnostic control ONLY: actual Main UI with its renderer detached. Never replaces full-app latency gate. */
@RunWith(AndroidJUnit4::class)
class GuidelineNoRendererControlDeviceTest {
    @Test fun sameMenuWithoutRendererDiagnostic() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rendererControlFixture") == "emulator")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val context = instrumentation.targetContext
        fun walk(node: AccessibilityNodeInfo?, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.packageName?.toString() == context.packageName && node.isVisibleToUser && match(node)) return node
            for (index in 0 until node.childCount) walk(node.getChild(index), match)?.let { return it }
            return null
        }
        fun waitFor(text: String, enabled: Boolean = false): AccessibilityNodeInfo {
            val deadline = SystemClock.uptimeMillis() + 15000
            while (SystemClock.uptimeMillis() < deadline) {
                walk(automation.rootInActiveWindow) { it.text?.toString() == text &&
                    (!enabled || (it.isEnabled && it.isClickable)) }?.let { return it }
                SystemClock.sleep(10)
            }
            error("Expected visible UI missing: $text")
        }
        fun touch(node: AccessibilityNodeInfo): Long {
            val bounds = Rect(); check(node.getBoundsInScreen(bounds).let { !bounds.isEmpty })
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { check(automation.injectInputEvent(event, false)) } finally { event.recycle() }
                if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(16)
            }
            return down
        }
        val samples = JSONArray()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            // Retain the same layout bounds and real MainActivity menus; remove only
            // 3D in this QA-owned Activity. Production source is unchanged.
            scenario.onActivity { activity ->
                val scene = MainActivity::class.java.getDeclaredField("scene").apply { isAccessible = true }.get(activity) as android.view.View
                val parent = scene.parent as android.view.ViewGroup
                val position = parent.indexOfChild(scene)
                val parameters = scene.layoutParams
                val placeholder = android.view.View(activity).apply {
                    id = scene.id
                    setBackgroundColor(android.graphics.Color.BLACK)
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                parent.removeView(scene) // Real native lifecycle teardown, not a fake acknowledgement.
                parent.addView(placeholder, position, parameters)
            }
            val cleanupDeadline = SystemClock.elapsedRealtime() + 30000
            while (!NativeRenderLifecycle.nativeReady && SystemClock.elapsedRealtime() < cleanupDeadline) SystemClock.sleep(50)
            check(NativeRenderLifecycle.nativeReady) { "Actual renderer cleanup not complete; no calibration" }
            repeat(6) { trial ->
                val settings = waitFor("설정", enabled = true)
                val start = touch(settings)
                waitFor("차량 · 미리보기 설정")
                val ready = SystemClock.uptimeMillis() - start
                samples.put(JSONObject().put("trial", trial).put("operation", "openSettings")
                    .put("touchToAccessibleContentMs", ready).put("within250", ready <= 250))
                SystemClock.sleep(300)
                val closeStart = touch(waitFor("닫기", enabled = true))
                waitFor("설정", enabled = true)
                val closed = SystemClock.uptimeMillis() - closeStart
                samples.put(JSONObject().put("trial", trial).put("operation", "closeSettings")
                    .put("touchToAccessibleContentMs", closed).put("within250", closed <= 250))
                SystemClock.sleep(500)
            }
            File(context.filesDir, "no-renderer-latency-control.json").writeText(JSONObject()
                .put("metric", "Real touch DOWN to accessible target window content, includes 16ms touch hold and polling overhead; not physical scanout")
                .put("diagnosticOnly", true).put("rendererRemovedFromQaActivity", true).put("actualNativeCleanupBeforeMeasurement", NativeRenderLifecycle.nativeReady).put("fullApplication250GateProven", false).put("samples", samples).put("vehicleCommandsIssuedByTest", false).toString(2))
        } finally { scenario.closeAndAwaitNativeRelease() }
    }
}
