package com.example.version1

import android.app.AlertDialog
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 30)
class DialogImeDeviceTest {
    @Test fun inputDialogResizesAndReadOnlyDialogExcludesImeWithoutChangingProfiles() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("imeFixture") == "emulator")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val before = DriverProfileStore(context).load()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val modal = MainActivity::class.java.getDeclaredField("modal").apply { isAccessible = true }
        fun call(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredMethod(name)
            .apply { isAccessible = true }.invoke(activity)
        fun editor(view: View): EditText? {
            if (view is EditText) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) editor(view.getChildAt(i))?.let { return it }
            return null
        }
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 60000
            while (SystemClock.elapsedRealtime() < deadline) { if (condition()) return; Thread.sleep(100) }
            error("IME/window lifecycle condition unavailable")
        }
        try {
            await {
                var ready = false
                scenario.onActivity { ready = call(it, "canChange") == true }
                ready
            }
            var originalAccessibility = 0
            var originalFocusability = 0
            scenario.onActivity { activity ->
                val background = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
                originalAccessibility = background.importantForAccessibility
                originalFocusability = background.descendantFocusability
                assertNotEquals("Read-only home must not connect to the IME", 0,
                    activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                call(activity, "showSettings")
                val dialog = modal.get(activity) as UiPanel
                assertFalse(DialogImePolicy.containsEditor(dialog.contentView))
                assertNull("Public read-only menu shares the activity window", dialog.nativeWindow)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, background.importantForAccessibility)
                assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, background.descendantFocusability)
            }
            fun readOnlyLaidOutWithoutIme(): Boolean {
                var ready = false
                scenario.onActivity { activity ->
                    val panel = modal.get(activity) as? UiPanel
                    val insets = activity.window.decorView.rootWindowInsets
                    ready = panel != null && panel.isShowing && panel.contentView.isLaidOut &&
                        panel.contentView.width > 0 && insets != null && !insets.isVisible(WindowInsets.Type.ime())
                }
                return ready
            }
            await(::readOnlyLaidOutWithoutIme)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await {
                var restored = false
                scenario.onActivity { activity ->
                    val background = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
                    restored = modal.get(activity) == null && !activity.isFinishing &&
                        background.importantForAccessibility == originalAccessibility &&
                        background.descendantFocusability == originalFocusability
                }
                restored
            }
            // Harmless fixture exercises the production sensitive branch without opening credentials.
            scenario.onActivity { activity ->
                val method = MainActivity::class.java.declaredMethods.single { it.name == "dialog" && it.parameterCount == 7 }
                    .apply { isAccessible = true }
                val panel = method.invoke(activity, "보안 창 검증", TextView(activity).apply { text = "보안 플래그 검증" },
                    "닫기", null, null, true, null) as UiPanel
                val window = checkNotNull(panel.nativeWindow)
                assertNotEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
                assertNotEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                panel.dismiss()
                call(activity, "showAddDriverProfile")
            }
            instrumentation.waitForIdleSync()
            await {
                var laidOut = false
                scenario.onActivity { activity ->
                    val window = (modal.get(activity) as UiPanel).nativeWindow ?: error("Editor needs a real IME window")
                    val input = editor(window.decorView)
                    laidOut = input != null && input.isLaidOut && input.width > 0 && window.decorView.hasWindowFocus()
                }
                laidOut
            }
            var point = floatArrayOf()
            scenario.onActivity { activity ->
                val dialog = modal.get(activity) as UiPanel
                val window = dialog.nativeWindow ?: error("Editor needs a real IME window")
                assertEquals("Native editor must remain connected to the IME", 0,
                    window.attributes.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                assertNotEquals(0, window.attributes.fitInsetsTypes and WindowInsets.Type.ime())
                assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                    window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                val input = requireNotNull(editor(dialog.contentView))
                val location = IntArray(2); input.getLocationOnScreen(location)
                point = floatArrayOf(location[0] + input.width / 2f, location[1] + input.height / 2f)
            }
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point[0], point[1], 0)
                    .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                if (action == MotionEvent.ACTION_DOWN) Thread.sleep(16)
            }
            var visibleInput = false
            await {
                var visibleIme = false
                scenario.onActivity { activity ->
                    val window = (modal.get(activity) as UiPanel).nativeWindow ?: error("Editor needs a real IME window")
                    visibleIme = window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
                    if (visibleIme) {
                        val input = requireNotNull(editor(window.decorView))
                        val area = Rect(); val viewport = Rect()
                        input.getWindowVisibleDisplayFrame(viewport)
                        visibleInput = input.getGlobalVisibleRect(area) && area.height() >= input.height - 1 && area.bottom <= viewport.bottom
                    }
                }
                visibleIme && visibleInput
            }
            scenario.onActivity { activity -> checkNotNull((modal.get(activity) as UiPanel).getButton(AlertDialog.BUTTON_NEGATIVE)).performClick() }
            await {
                var closed = false
                scenario.onActivity { closed = modal.get(it) == null }
                closed
            }
            scenario.onActivity { call(it, "showSettings") }
            await(::readOnlyLaidOutWithoutIme)
            scenario.onActivity { (modal.get(it) as UiPanel).dismiss() }
            assertEquals(before, DriverProfileStore(context).load())
            File(context.filesDir, "dialog-ime-result.json").writeText(JSONObject()
                .put("readOnlyDialogExcludesIme", true).put("inputDialogAdjustResize", true)
                .put("actualTouchOpenedIme", true).put("inputVisibleAboveIme", visibleInput)
                .put("readOnlyLaidOutWithImeHiddenBeforeAndAfterEditor", true)
                .put("backClosedInlinePanelWithoutFinishing", true).put("backgroundAccessibilityAndFocusRestored", true)
                .put("sensitivePanelUsesSecureNativeWindow", true)
                .put("driverProfilesUnchanged", true).put("vehicleCommandsIssued", false).toString(2))
        } finally { scenario.closeAndAwaitNativeRelease() }
    }
}
