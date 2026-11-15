package com.example.version1

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Supplementary boundary diagnostic. Retains the renderer and original twelve operations.
 * Does not replace the legacy injection-to-content result or prove physical scanout.
 */
@RunWith(AndroidJUnit4::class)
class GuidelineOsInputDeviceTest {
    private data class Touch(val action: Int, val downAt: Long, val eventAt: Long,
        val dispatchedAt: Long, val source: Int)

    @Test fun osTouchDownToSameAccessibleContent() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("osInputFixture") == "emulator")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val context = instrumentation.targetContext
        val touches = LinkedBlockingQueue<Touch>()
        fun walk(node: AccessibilityNodeInfo?, text: String, enabled: Boolean): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.packageName?.toString() == context.packageName && node.isVisibleToUser &&
                node.text?.toString() == text && (!enabled || (node.isEnabled && node.isClickable))) return node
            for (i in 0 until node.childCount) walk(node.getChild(i), text, enabled)?.let { return it }
            return null
        }
        fun waitFor(text: String, enabled: Boolean = false): AccessibilityNodeInfo {
            val deadline = SystemClock.uptimeMillis() + 15000
            while (SystemClock.uptimeMillis() < deadline) {
                walk(automation.rootInActiveWindow, text, enabled)?.let { return it }
                SystemClock.sleep(10)
            }
            error("Expected visible UI missing: $text")
        }
        val samples = JSONArray()
        val diagnostics = JSONArray()
        val diagnosticFile = File(context.filesDir, "guideline-os-input-diagnostic.json")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var original: Window.Callback? = null
        var observer: Window.Callback? = null
        try {
            fun previewReady(): Boolean {
                var ready = false
                scenario.onActivity { activity ->
                    val scene = MainActivity::class.java.getDeclaredField("scene").apply { isAccessible = true }.get(activity) as PremiumCabinView
                    check(scene.parent != null && scene.isAttachedToWindow)
                    val gate = PremiumCabinView::class.java.getDeclaredField("presentation").apply { isAccessible = true }.get(scene) as PreviewPresentationGate
                    check(!gate.failed)
                    ready = !gate.covered
                }
                return ready
            }
            scenario.onActivity { activity ->
                val callback = activity.window.callback
                original = callback
                val wrapped = object : Window.Callback by callback {
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
                            touches.offer(Touch(event.actionMasked, event.downTime, event.eventTime,
                                SystemClock.uptimeMillis(), event.source))
                        }
                        return callback.dispatchTouchEvent(event)
                    }
                }
                observer = wrapped
                activity.window.callback = wrapped
            }
            fun measure(trial: Int, operation: String, source: String, target: String) {
                val bounds = Rect()
                waitFor(source, true).getBoundsInScreen(bounds)
                check(!bounds.isEmpty)
                check(touches.isEmpty()) { "Unexpected unconsumed physical touch" }
                val previewBeforeInput = previewReady()
                var inputDisplay = -1
                scenario.onActivity { activity ->
                    check(activity.window.callback === observer) { "Window callback observer was replaced" }
                    inputDisplay = activity.window.decorView.display.displayId
                }
                check(inputDisplay >= 0)
                val diagnostic = JSONObject().put("trial", trial).put("operation", operation)
                    .put("displayId", inputDisplay).put("bounds", bounds.toShortString())
                diagnostics.put(diagnostic)
                diagnosticFile.writeText(diagnostics.toString(2))
                val requestedAt = SystemClock.uptimeMillis()
                // Public shell input creates its own event timestamps through the OS.
                // A stationary 16ms swipe is the same DOWN/hold/UP gesture, not onClick.
                val pipe = automation.executeShellCommand(
                    "input touchscreen -d $inputDisplay swipe ${bounds.centerX()} ${bounds.centerY()} ${bounds.centerX()} ${bounds.centerY()} 16")
                val callReturnedAt = SystemClock.uptimeMillis()
                try {
                    val observedDown = touches.poll(15, TimeUnit.SECONDS)
                    if (observedDown == null) {
                        val output = ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes().toString(Charsets.UTF_8) }
                        diagnostic.put("actualDownObserved", false).put("shellOutput", output.take(2048))
                        diagnosticFile.writeText(diagnostics.toString(2))
                        error("Actual DOWN not observed; see diagnostic receipt")
                    }
                    val down = observedDown
                    diagnostic.put("actualDownObserved", true)
                    diagnosticFile.writeText(diagnostics.toString(2))
                    check(down.action == MotionEvent.ACTION_DOWN)
                    check(down.source and InputDevice.SOURCE_TOUCHSCREEN == InputDevice.SOURCE_TOUCHSCREEN)
                    waitFor(target, operation == "closeSettings")
                    val readyAt = SystemClock.uptimeMillis()
                    val up = checkNotNull(touches.poll(15, TimeUnit.SECONDS)) { "Actual UP not observed" }
                    check(up.action == MotionEvent.ACTION_UP && up.downAt == down.downAt)
                    check(up.eventAt - down.eventAt >= 16) { "Requested hold duration not delivered" }
                    check(readyAt >= up.eventAt && down.eventAt >= requestedAt)
                    val elapsed = readyAt - down.eventAt
                    samples.put(JSONObject().put("trial", trial).put("operation", operation)
                        .put("previewPresentedBeforeInput", previewBeforeInput)
                        .put("actualOsDownToAccessibleContentMs", elapsed).put("within250", elapsed <= 250)
                        .put("actualHoldMs", up.eventAt - down.eventAt)
                        .put("downDispatchQueueMs", down.dispatchedAt - down.eventAt)
                        .put("shellCallReturnedMs", callReturnedAt - requestedAt)
                        .put("requestToActualDownMs", down.eventAt - requestedAt)
                        .put("requestToAccessibleContentMs", readyAt - requestedAt))
                    // Drain only after measurement. No subtraction of API/handler time.
                    ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes() }
                } finally { pipe.close() }
            }
            repeat(6) { trial ->
                measure(trial, "openSettings", "설정", "차량 · 미리보기 설정")
                SystemClock.sleep(300)
                measure(trial, "closeSettings", "닫기", "설정")
                SystemClock.sleep(500)
            }
            check(touches.isEmpty())
            check(samples.length() == 12)
            val previewDeadline = SystemClock.uptimeMillis() + 60000
            while (!previewReady() && SystemClock.uptimeMillis() < previewDeadline) SystemClock.sleep(100)
            check(previewReady()) { "Actual full renderer did not present after interactions" }
            File(context.filesDir, "guideline-os-input-result.json").writeText(JSONObject()
                .put("scope", "Supplementary actual OS DOWN to same accessible UI content, full MainActivity renderer retained; not physical scanout, not replacement of legacy test")
                .put("samples", samples).put("originalLegacyGateUnchanged", true)
                .put("actual3dPresentedAfterInteractions", true)
                .put("vehicleCommandsIssuedByTest", false).toString(2))
        } finally {
            try { scenario.onActivity { activity -> original?.let { activity.window.callback = it } } }
            finally { scenario.closeAndAwaitNativeRelease() }
        }
    }
}
