package com.example.version1

import android.app.ActivityOptions
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs on an explicitly requested official emulator display; copies the actual app window. */
@RunWith(AndroidJUnit4::class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 30)
class FormFactorPreviewDeviceTest {
    @Test fun previewAppearsOnRequestedDisplay() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("guidelineUi") == "emulator" && args.containsKey("formFactorDisplayId"))
        val id = args.getString("formFactorDisplayId")!!.toInt()
        val expectedWidth = args.getString("formFactorWidthDp")!!.toInt()
        val expectedDpi = args.getString("formFactorDpi")!!.toInt()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()
        val scenario = ActivityScenario.launch(MainActivity::class.java, options)
        val result = JSONObject()
        try {
            var presented = false
            val deadline = SystemClock.elapsedRealtime() + 60000
            while (!presented && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { activity ->
                    assertEquals(id, activity.windowManager.defaultDisplay.displayId)
                    val scene = MainActivity::class.java.getDeclaredField("scene").apply { isAccessible = true }.get(activity)!!
                    val gate = scene.javaClass.getDeclaredField("presentation").apply { isAccessible = true }.get(scene) as PreviewPresentationGate
                    check(!gate.failed) { "Actual 3D preview presentation failed" }
                    presented = !gate.covered
                }
                if (!presented) Thread.sleep(250)
            }
            assertTrue("No actual 3D frame on requested display", presented)
            val copied = CountDownLatch(1)
            var copyStatus = -1
            lateinit var image: Bitmap
            scenario.onActivity { activity ->
                val metrics = activity.resources.displayMetrics
                val bounds = activity.windowManager.currentWindowMetrics.bounds
                val widthDp = bounds.width() / metrics.density
                assertTrue("App window width does not match requested form factor", kotlin.math.abs(widthDp - expectedWidth) < 3)
                assertEquals(expectedDpi, metrics.densityDpi)
                val decor = activity.window.decorView
                val scene = MainActivity::class.java.getDeclaredField("scene").apply { isAccessible = true }.get(activity) as android.view.View
                val visibleScene = android.graphics.Rect()
                check(scene.getGlobalVisibleRect(visibleScene))
                val visibleHeightDp = visibleScene.height() / metrics.density
                assertTrue("Afterglow's 3D first-screen area is too small (product target 160dp)", visibleHeightDp >= 160)
                @Suppress("UNCHECKED_CAST")
                val controls=MainActivity::class.java.getDeclaredField("comfortModeButtons").apply { isAccessible=true }.get(activity) as Map<ComfortMode,android.widget.Button>
                val recovery=controls.getValue(ComfortMode.AUTOMATIC)
                val visibleRecovery = android.graphics.Rect()
                assertTrue("Recovery control is not visible", recovery.getGlobalVisibleRect(visibleRecovery))
                assertTrue("Automatic mode button is clipped", visibleRecovery.height() / metrics.density >= 64 && visibleRecovery.width() / metrics.density >= 80)
                image = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                result.put("displayId", id).put("densityDpi", metrics.densityDpi)
                    .put("windowWidthDp", widthDp).put("windowHeightDp", bounds.height() / metrics.density)
                    .put("visible3dHeightDp", visibleHeightDp)
                    .put("visibleRecoveryHeightDp", visibleRecovery.height() / metrics.density)
                    .put("actual3dFramePresented", true).put("physicalMillimetersVerified", false)
                PixelCopy.request(activity.window, image, { status -> copyStatus = status; copied.countDown() }, Handler(Looper.getMainLooper()))
            }
            assertTrue("Window screenshot callback timed out", copied.await(10, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, copyStatus)
            try {
                File(context.filesDir, "form-factor-window.png").outputStream().use {
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { image.recycle() }
            if (args.getString("firstFrameDiagnostic") == "emulator") {
                fun snapshot(name: String) {
                    val done = CountDownLatch(1)
                    var status = -1
                    lateinit var bitmap: Bitmap
                    scenario.onActivity { activity ->
                        val decor = activity.window.decorView
                        bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                        PixelCopy.request(activity.window, bitmap, { value -> status = value; done.countDown() }, Handler(Looper.getMainLooper()))
                    }
                    try {
                        assertTrue(done.await(10, TimeUnit.SECONDS))
                        assertEquals(PixelCopy.SUCCESS, status)
                        File(context.filesDir, "form-factor-$name.png").outputStream().use {
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    } finally { bitmap.recycle() }
                }
                // This is explicitly a rendering diagnostic, not an input-latency
                // fixture. Do not change camera, state, source assets or production.
                Thread.sleep(2000)
                snapshot("unchanged-2s")
                var beforeAccepted = 0L
                var beforeTexture = 0L
                val rawCopied = CountDownLatch(1)
                scenario.onActivity { activity ->
                    val scene = MainActivity::class.java.getDeclaredField("scene").apply { isAccessible = true }.get(activity) as PremiumCabinView
                    val viewer = PremiumCabinView::class.java.getDeclaredField("modelViewer").apply { isAccessible = true }.get(scene)
                        as com.google.android.filament.utils.BudgetModelViewer
                    viewer.debugGetNextFrameCallback { bitmap ->
                        try {
                            File(context.filesDir, "form-factor-raw-renderer.png").outputStream().use {
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                            }
                        } finally { bitmap.recycle(); rawCopied.countDown() }
                    }
                    beforeAccepted = PremiumCabinView::class.java.getDeclaredField("acceptedFrameCount").apply { isAccessible = true }.getLong(scene)
                    beforeTexture = PremiumCabinView::class.java.getDeclaredField("lastTextureTimestamp").apply { isAccessible = true }.getLong(scene)
                    PremiumCabinView::class.java.getDeclaredMethod("requestFrames", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(scene, 1)
                }
                var extraPresented = false
                val limit = SystemClock.elapsedRealtime() + 15000
                while (!extraPresented && SystemClock.elapsedRealtime() < limit) {
                    scenario.onActivity { activity ->
                        val scene = MainActivity::class.java.getDeclaredField("scene").apply { isAccessible = true }.get(activity) as PremiumCabinView
                        val accepted = PremiumCabinView::class.java.getDeclaredField("acceptedFrameCount").apply { isAccessible = true }.getLong(scene)
                        val texture = PremiumCabinView::class.java.getDeclaredField("lastTextureTimestamp").apply { isAccessible = true }.getLong(scene)
                        extraPresented = accepted > beforeAccepted && texture > beforeTexture
                    }
                    if (!extraPresented) Thread.sleep(100)
                }
                assertTrue("Extra real submission and newer TextureView buffer required", extraPresented)
                assertTrue("Renderer readback callback did not complete", rawCopied.await(10, TimeUnit.SECONDS))
                snapshot("extra-frame")
                result.put("firstFrameDiagnosticOnly", true).put("extraActualFramePresented", true)
                    .put("rawRendererReadbackCompleted", true)
            }
            File(context.filesDir, "form-factor-result.json").writeText(result.toString(2))
        } finally { scenario.closeAndAwaitNativeRelease() }
    }
}
