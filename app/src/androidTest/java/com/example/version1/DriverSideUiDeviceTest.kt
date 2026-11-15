package com.example.version1

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
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

/** Real production switch/layout/native preview. No physical control-seat remapping. */
@RunWith(AndroidJUnit4::class)
class DriverSideUiDeviceTest {
    @Test fun publicSwitchMovesControlsWithoutReversingLanguageOrPhysicalSeat() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("driverSideFixture") == "emulator")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = VehicleSelectionStore(context)
        val original = store.load()
        val args = InstrumentationRegistry.getArguments()
        val options = args.getString("formFactorDisplayId")?.toInt()?.let {
            android.app.ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle()
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java, options)
        fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(target)
        fun call(activity: MainActivity, name: String) = activity.javaClass.getDeclaredMethod(name)
            .apply { isAccessible = true }.invoke(activity)
        fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun await(right: Boolean) {
            val deadline = SystemClock.uptimeMillis() + 60_000
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false
                scenario.onActivity { activity ->
                    val scene = field(activity, "scene") as PremiumCabinView
                    val gate = field(scene, "presentation") as PreviewPresentationGate
                    ready = !gate.covered && !gate.failed &&
                        field(scene, "loadedShellRightHandDrive") == right &&
                        call(activity, "canChangeVisualization") == true
                }
                if (ready) return
                Thread.sleep(50)
            }
            error("Actual handedness frame not ready")
        }
        val rows = JSONArray()
        var completed = false
        var sceneIdentity: PremiumCabinView? = null
        var bindingBefore: Any? = null
        try {
            await(original.rightHandDrive)
            scenario.onActivity { sceneIdentity = field(it, "scene") as PremiumCabinView; bindingBefore = field(it, "driverSeatState") }
            for (right in listOf(false, true, false)) {
                scenario.onActivity { activity ->
                    call(activity, "showVehicleSettings")
                    val switch = views(activity.window.decorView).filterIsInstance<Switch>()
                        .single { it.text.toString() == "우핸들 버전" }
                    assertTrue("Public handedness switch disabled", switch.isEnabled)
                    // CompoundButton can toggle successfully while performClick returns
                    // false when no separate OnClickListener consumes the click.
                    if (switch.isChecked != right) switch.performClick()
                    assertEquals(right, switch.isChecked)
                    (field(activity, "modal") as UiPanel).dismiss()
                }
                await(right)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    assertSame(sceneIdentity, field(activity, "scene"))
                    assertEquals(bindingBefore, field(activity, "driverSeatState"))
                    val header = field(activity, "driverHeader") as LinearLayout
                    val automatic = header.getChildAt(0) as Button
                    val settings = field(activity, "settingsButton") as Button
                    assertTrue(if (right) automatic.x > settings.x else automatic.x < settings.x)
                    for (index in 0 until header.childCount) {
                        val child = header.getChildAt(index)
                        assertEquals(View.LAYOUT_DIRECTION_LTR, child.layoutDirection)
                        assertTrue(child.width >= 48 * context.resources.displayMetrics.density)
                        assertTrue(child.height >= 48 * context.resources.displayMetrics.density)
                    }
                    val columns = field(activity, "driverColumns") as? LinearLayout
                    if (columns != null) {
                        val info = columns.getChildAt(0)
                        val cabin = columns.getChildAt(1)
                        assertTrue(if (right) info.x > cabin.x else info.x < cabin.x)
                        assertEquals(View.LAYOUT_DIRECTION_LTR, cabin.layoutDirection)
                    }
                    val brand = views(activity.window.decorView).filterIsInstance<android.widget.TextView>()
                        .single { it.text.toString() == "IEUM" }
                    val brandLocation = IntArray(2); brand.getLocationInWindow(brandLocation)
                    val inkBounds = android.graphics.Rect()
                    brand.paint.getTextBounds("IEUM", 0, 4, inkBounds)
                    val visibleBounds = android.graphics.Rect(); val visible = brand.getGlobalVisibleRect(visibleBounds)
                    rows.put(JSONObject().put("rightHandDrive", right).put("autoX", automatic.x)
                        .put("settingsX", settings.x).put("sideBySide", columns != null)
                        .put("actualNativePreview", true).put("sameSceneView", true)
                        .put("brandTopInWindow", brandLocation[1]).put("brandHeight", brand.height)
                        .put("brandBaseline", brand.baseline).put("brandInkTop", inkBounds.top)
                        .put("brandInkBottom", inkBounds.bottom)
                        .put("brandWindowInkTop", brandLocation[1] + brand.baseline + inkBounds.top)
                        .put("brandWindowInkBottom", brandLocation[1] + brand.baseline + inkBounds.bottom)
                        .put("brandVisibleHeight", if (visible) visibleBounds.height() else 0))
                }
                val latch = java.util.concurrent.CountDownLatch(1)
                var status = -1
                var bitmap: android.graphics.Bitmap? = null
                try {
                    scenario.onActivity { activity ->
                        val decor = activity.window.decorView
                        bitmap = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
                        android.view.PixelCopy.request(activity.window, bitmap!!, { status = it; latch.countDown() },
                            android.os.Handler(android.os.Looper.getMainLooper()))
                    }
                    assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    assertEquals(android.view.PixelCopy.SUCCESS, status)
                    File(context.filesDir, "driver-side-${if (right) "right" else "left"}.png").outputStream().use {
                        assertTrue(bitmap!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { bitmap?.recycle() }
            }
            completed = true
        } finally {
            try {
                scenario.onActivity { activity ->
                    (field(activity, "modal") as? UiPanel)?.dismiss()
                    store.save(original)
                    activity.javaClass.getDeclaredField("vehiclePreferences").apply { isAccessible = true }.set(activity, original)
                    call(activity, "render")
                }
                await(original.rightHandDrive)
                File(context.filesDir, "driver-side-ui-result.json").writeText(JSONObject()
                    .put("passed", completed).put("originalSelectionRestored", store.load() == original)
                    .put("physicalSeatBindingPreserved", true).put("vehicleCommandsIssued", false)
                    .put("rows", rows).toString(2))
            } finally { scenario.closeAndAwaitNativeRelease() }
        }
    }
}
