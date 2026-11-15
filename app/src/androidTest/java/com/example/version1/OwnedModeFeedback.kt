package com.example.version1

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Observe real mode feedback, never synthesize an app click or bypass the controller.
 * No accessibility polling or scenario.onActivity runs inside the timed interval. */
@android.annotation.TargetApi(29)
internal class OwnedModeFeedback(
    private val scenario: ActivityScenario<MainActivity>, private val context: Context
) : AutoCloseable {
    private val fields = listOf("comfortModeButtons", "active", "busy", "comfortMode", "resultLabel", "modal",
        "debugButtonEntered", "debugButtonHapticDone", "debugButtonFinished",
        "debugModeSoundStarted", "debugModeSoundFinished", "debugModeRenderStarted", "debugModeRenderFinished", "debugModePreparation")
        .associateWith { MainActivity::class.java.getDeclaredField(it).apply { isAccessible = true } }
    private fun field(activity: MainActivity, name: String) = fields.getValue(name).get(activity)
    private class Gesture(val operation: String, val screenBounds: Rect) {
        val down = AtomicLong(); val up = AtomicLong(); val streamDown = AtomicLong()
        val deliveredAt = AtomicLong(); val sourceValid = AtomicBoolean(true)
        val upDispatch = AtomicLong(); val upHandled = AtomicLong()
        val upArrived = CountDownLatch(1); val frameArrived = CountDownLatch(1)
        val committed = AtomicBoolean(); var row: JSONObject? = null
    }
    private val pending = AtomicReference<Gesture?>()
    private val samples = JSONArray()
    private val listeners = mutableListOf<Pair<View, ViewTreeObserver.OnPreDrawListener>>()
    private var sequence = 0
    private lateinit var window: Window
    private lateinit var previous: Window.Callback
    private lateinit var observer: Window.Callback
    private var callbackRestored = false

    init {
        check(android.os.Build.VERSION.SDK_INT >= 29)
        scenario.onActivity { activity ->
            window = activity.window; previous = window.callback
            observer = object : Window.Callback by previous {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    val gesture = pending.get()
                    if (gesture != null && event.actionMasked == MotionEvent.ACTION_DOWN) {
                        gesture.down.set(event.eventTime); gesture.streamDown.set(event.downTime)
                        gesture.deliveredAt.set(SystemClock.uptimeMillis())
                        gesture.sourceValid.set(event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) &&
                            gesture.screenBounds.contains(event.rawX.toInt(), event.rawY.toInt()))
                    }
                    if (gesture != null && event.actionMasked == MotionEvent.ACTION_UP)
                        gesture.upDispatch.set(SystemClock.uptimeMillis())
                    return try { previous.dispatchTouchEvent(event) } finally {
                        if (gesture != null && event.actionMasked == MotionEvent.ACTION_UP) {
                            gesture.upHandled.set(SystemClock.uptimeMillis())
                            gesture.sourceValid.set(gesture.sourceValid.get() &&
                                event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) &&
                                event.downTime == gesture.streamDown.get() &&
                                gesture.screenBounds.contains(event.rawX.toInt(), event.rawY.toInt()))
                            gesture.up.set(event.eventTime); gesture.upArrived.countDown()
                        }
                    }
                }
            }
            window.callback = observer
        }
    }
    private fun fullyVisible(view: View): Boolean {
        val bounds = Rect()
        return view.isShown && view.alpha > 0f && view.width > 0 && view.height > 0 &&
            view.getGlobalVisibleRect(bounds) && bounds.width() >= view.width - 1 && bounds.height() >= view.height - 1
    }
    fun click(mode: ComfortMode) {
        lateinit var gesture: Gesture
        var display = -1
        scenario.onActivity { activity ->
            @Suppress("UNCHECKED_CAST")
            val button = (field(activity, "comfortModeButtons") as Map<ComfortMode, Button>).getValue(mode)
            check(button.isEnabled && fullyVisible(button) && activity.window.decorView.hasWindowFocus())
            val position = IntArray(2); button.getLocationOnScreen(position)
            val bounds = Rect(position[0], position[1], position[0] + button.width, position[1] + button.height)
            val beforeActive = field(activity, "active") as Boolean
            val beforeMode = field(activity, "comfortMode") as ComfortMode
            val expectedActive = !(beforeActive && beforeMode == mode)
            val operation = if (!beforeActive) "start_${mode.name}" else if (beforeMode == mode)
                "stop_${mode.name}" else "switch_${beforeMode.name}_to_${mode.name}"
            gesture = Gesture(operation, bounds)
            check(pending.compareAndSet(null, gesture))
            val root = activity.window.decorView
            display = root.display.displayId
            val listener = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (pending.get() !== gesture || gesture.down.get() <= 0) return true
                    val busy = field(activity, "busy") as Boolean
                    val result = field(activity, "resultLabel") as TextView
                    val busyNotice = busy && result.text.toString() == "차량의 실제 응답을 확인하고 있어요." && fullyVisible(result)
                    val confirmed = !busy && field(activity, "active") == expectedActive &&
                        field(activity, "comfortMode") == mode && button.isSelected == expectedActive && fullyVisible(button)
                    if (field(activity, "modal") == null && (busyNotice || confirmed)) {
                        val preDrawAt = SystemClock.uptimeMillis()
                        root.viewTreeObserver.removeOnPreDrawListener(this)
                        val kind = if (busyNotice) "visibleBusyNotice" else "confirmedModeState"
                        root.viewTreeObserver.registerFrameCommitCallback {
                            if (pending.get() === gesture && gesture.committed.compareAndSet(false, true)) {
                                val committed = SystemClock.uptimeMillis(); val down = gesture.down.get()
                                gesture.row = JSONObject().put("operation", operation).put("feedbackKind", kind)
                                    .put("actualDownUptimeMs", down).put("feedbackCommitUptimeMs", committed)
                                    .put("downToFeedbackCommitMs", committed - down)
                                    .put("upHandlerMs", gesture.upHandled.get() - gesture.upDispatch.get())
                                    .put("upReturnToEligiblePreDrawMs", preDrawAt - gesture.upHandled.get())
                                    .put("eligiblePreDrawToCommitCallbackMs", committed - preDrawAt)
                                    .put("upToPostedClickMs", (field(activity, "debugButtonEntered") as Long) - gesture.upHandled.get())
                                    .put("clickHapticMs", (field(activity, "debugButtonHapticDone") as Long) - (field(activity, "debugButtonEntered") as Long))
                                    .put("clickHandlerMs", (field(activity, "debugButtonFinished") as Long) - (field(activity, "debugButtonHapticDone") as Long))
                                    .put("modeSoundStopMs", (field(activity, "debugModeSoundFinished") as Long) - (field(activity, "debugModeSoundStarted") as Long))
                                    .put("modeRenderMs", (field(activity, "debugModeRenderFinished") as Long) - (field(activity, "debugModeRenderStarted") as Long))
                                    .put("modePreferenceMs", (field(activity,"debugModePreparation") as LongArray)[0])
                                    .put("modeSessionMs", (field(activity,"debugModePreparation") as LongArray)[1])
                                    .put("modeRecommendationMs", (field(activity,"debugModePreparation") as LongArray)[2])
                                    .put("modeInitialRenderMs", (field(activity,"debugModePreparation") as LongArray)[3])
                                    .put("within250", committed - down in 0..250)
                                gesture.frameArrived.countDown()
                            }
                        }
                    }
                    return true
                }
            }
            listeners.add(root to listener); root.viewTreeObserver.addOnPreDrawListener(listener)
        }
        check(display == 0)
        val requested = SystemClock.uptimeMillis()
        File(context.filesDir, "mode-host-touch-request.json").writeText(JSONObject()
            .put("sequence", ++sequence).put("displayId", display)
            .put("x", gesture.screenBounds.centerX()).put("y", gesture.screenBounds.centerY())
            .put("holdMillis", 16).put("requestedAt", requested).toString())
        check(gesture.upArrived.await(20, TimeUnit.SECONDS)) { "Actual host mode gesture not delivered" }
        check(gesture.sourceValid.get() && gesture.down.get() >= requested &&
            gesture.up.get() - gesture.down.get() >= 16) { "Actual mode input provenance invalid" }
        check(gesture.frameArrived.await(10, TimeUnit.SECONDS)) { "Visible mode feedback frame missing" }
        val row = checkNotNull(gesture.row).put("actualHoldMs", gesture.up.get() - gesture.down.get())
            .put("downDeliveryMs", gesture.deliveredAt.get() - gesture.down.get()).put("actualInputObserved", true)
        samples.put(row); check(pending.compareAndSet(gesture, null))
    }
    fun receipt(functionalCycleCompleted: Boolean, originalPreferencesPreserved: Boolean) {
        File(context.filesDir, "mode-feedback-result.json").writeText(JSONObject()
            .put("scope", "Real OS DOWN/16ms hold to a natural committed visible busy/result frame; actual SDK cycle and owned learning storage; not accessible-completion or physical scanout certification")
            .put("samples", samples).put("functionalCycleCompleted", functionalCycleCompleted)
            .put("originalPreferencesPreserved", originalPreferencesPreserved)
            .put("callbackRestored", callbackRestored).put("syntheticAppClicks", false)
            .put("physicalVehicle", false).toString(2))
    }
    override fun close() {
        pending.set(null)
        scenario.onActivity {
            listeners.forEach { (root, listener) ->
                if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(listener)
            }
            listeners.clear()
            callbackRestored = window.callback === observer
            if (callbackRestored) window.callback = previous
        }
        receipt(false, false) // Partial receipt survives an assertion/cleanup failure.
    }
}
