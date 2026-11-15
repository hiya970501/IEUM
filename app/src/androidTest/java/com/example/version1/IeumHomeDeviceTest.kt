package com.example.version1

import android.view.View
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real mode buttons with isolated preference writes; restricted-state fixture is synthetic, not a drive. */
@RunWith(AndroidJUnit4::class)
class IeumHomeDeviceTest {
    @Test fun unknownStartupNoticeIsCenteredAndSingleLine() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ieumFixture")=="emulator")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }
        try {
            val deadline=android.os.SystemClock.uptimeMillis()+60000
            var ready=false
            while(!ready && android.os.SystemClock.uptimeMillis()<deadline) {
                scenario.onActivity {
                    val scene=field("scene").get(it) as PremiumCabinView
                    val gate=scene.javaClass.getDeclaredField("presentation").apply { isAccessible=true }.get(scene) as PreviewPresentationGate
                    ready=field("parked").getBoolean(it) && !gate.covered
                }
                if(!ready) Thread.sleep(100)
            }
            assertTrue(ready)
            scenario.onActivity {
                (field("poll").get(it) as? kotlinx.coroutines.Job)?.cancel()
                field("lastKnownGear").set(it,null);field("parked").setBoolean(it,false)
                MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible=true }.invoke(it)
            }
            instrumentation.waitForIdleSync();Thread.sleep(100)
            scenario.onActivity {
                val notice=field("drivingNotice").get(it) as android.widget.TextView
                val shade=field("drivingShade").get(it) as android.widget.FrameLayout
                val group=shade.getChildAt(0)
                assertTrue(shade.isShown);assertEquals("차량 상태를 확인하고 있어요.",notice.text.toString())
                assertEquals(1,notice.lineCount)
                assertEquals(shade.width/2f,group.x+group.width/2f,2f)
                assertEquals(shade.height/2f,group.y+group.height/2f,2f)
            }
            // Capture the actual app Window, including its composed TextureView.
            // UiAutomation's display capture can dereference an unavailable platform
            // ScreenshotHardwareBuffer. A missing capture must still fail this test.
            val copied=java.util.concurrent.CountDownLatch(1)
            var status=-1
            var image:android.graphics.Bitmap?=null
            try {
                scenario.onActivity { activity ->
                    val decor=activity.window.decorView
                    assertTrue(decor.width>0 && decor.height>0)
                    val bitmap=android.graphics.Bitmap.createBitmap(decor.width,decor.height,
                        android.graphics.Bitmap.Config.ARGB_8888)
                    image=bitmap
                    android.view.PixelCopy.request(activity.window,bitmap,{
                        status=it;copied.countDown()
                    },android.os.Handler(android.os.Looper.getMainLooper()))
                }
                assertTrue("Actual notice Window capture timed out",copied.await(10,java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(android.view.PixelCopy.SUCCESS,status)
                val bitmap=checkNotNull(image)
                java.io.File(instrumentation.targetContext.filesDir,"ieum-centered-notice.png").outputStream().use {
                    assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))
                }
            } finally { image?.recycle() }
        } finally { scenario.closeAndAwaitNativeRelease() }
    }
    @Test fun directSelectorsPreserveConsentAndRestrictionInterlocks() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ieumFixture")=="emulator")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("IEUM",context.getString(R.string.app_name))
        val prefs=ProtectedPreferences.get(context,"comfort_preferences")
        val original=prefs.all.toMap()
        val namespace="qa_ieum_${java.util.UUID.randomUUID()}"
        val owned=ProtectedPreferences.get(context,namespace)
        fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        var ready=false
        try {
            val deadline=android.os.SystemClock.uptimeMillis()+60000
            while(!ready && android.os.SystemClock.uptimeMillis()<deadline) {
                scenario.onActivity { activity ->
                    val safe=MainActivity::class.java.getDeclaredMethod("safeToAct").apply { isAccessible=true }.invoke(activity)==true
                    val scene=field("scene").get(activity) as PremiumCabinView
                    val gate=scene.javaClass.getDeclaredField("presentation").apply { isAccessible=true }.get(scene) as PreviewPresentationGate
                    if(safe && !gate.covered && field("foreground").getBoolean(activity)) ready=true
                }
                if(!ready) Thread.sleep(100)
            }
            assertTrue("Actual parked scene must be ready",ready)
            scenario.onActivity { activity ->
                val preferencesField=field("prefs\$delegate")
                val previous=preferencesField.get(activity)
                val previousMode=field("comfortMode").get(activity)
                val consent=field("learningConsent").getBoolean(activity)
                val render=MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible=true }
                try {
                    preferencesField.set(activity,lazy { owned })
                    field("comfortMode").set(activity,ComfortMode.AUTOMATIC)
                    field("learningConsent").setBoolean(activity,false);render.invoke(activity)
                    @Suppress("UNCHECKED_CAST")
                    val controls=field("comfortModeButtons").get(activity) as Map<ComfortMode,Button>
                    assertFalse(controls.getValue(ComfortMode.AUTOMATIC).isSelected)
                    assertFalse(controls.getValue(ComfortMode.LEARNING).isSelected)
                    assertTrue(controls.getValue(ComfortMode.LEARNING).performClick())
                    assertEquals(ComfortMode.AUTOMATIC,field("comfortMode").get(activity))
                    (field("modal").get(activity) as? UiPanel)?.dismiss()
                    // State-paint fixture only; actual SDK execution is a separate owned test.
                    field("active").setBoolean(activity,true)
                    field("comfortMode").set(activity,ComfortMode.LEARNING);render.invoke(activity)
                    assertTrue(controls.getValue(ComfortMode.LEARNING).isSelected)
                    assertFalse(controls.getValue(ComfortMode.AUTOMATIC).isSelected)
                    field("comfortMode").set(activity,ComfortMode.AUTOMATIC);render.invoke(activity)
                    assertTrue(controls.getValue(ComfortMode.AUTOMATIC).isSelected)
                    val telemetry=field("telemetryLabel").get(activity) as android.widget.TextView
                    assertFalse(telemetry.text.contains('\n'));assertEquals(1,telemetry.maxLines)
                    val buttons=controls.values.toList()+listOf(field("connectButton").get(activity) as Button,field("settingsButton").get(activity) as Button)
                    val positions=buttons.map { button -> IntArray(2).also { button.getLocationOnScreen(it) }[1] }
                    assertEquals("All four controls must start at the same height",1,positions.toSet().size)
                    assertEquals("All four controls must share one height",1,buttons.map { it.height }.toSet().size)
                    fun switches(view:View):Int=if(view is android.widget.Switch) 1 else if(view is android.view.ViewGroup) (0 until view.childCount).sumOf { switches(view.getChildAt(it)) } else 0
                    assertEquals("Home has no duplicate recovery switch",0,switches(field("homeContent").get(activity) as View))
                    assertEquals(View.GONE,(field("sceneStatus").get(activity) as View).visibility)
                    field("parked").setBoolean(activity,false);render.invoke(activity)
                    val shade=field("drivingShade").get(activity) as View
                    assertEquals(View.VISIBLE,shade.visibility);assertTrue(shade.isClickable)
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,(field("homeContent").get(activity) as View).importantForAccessibility)
                    assertFalse(controls.getValue(ComfortMode.LEARNING).isEnabled)
                } finally {
                    field("active").setBoolean(activity,false)
                    field("parked").setBoolean(activity,true)
                    field("comfortMode").set(activity,previousMode);field("learningConsent").setBoolean(activity,consent)
                    preferencesField.set(activity,previous);render.invoke(activity)
                }
            }
        } finally {
            scenario.closeAndAwaitNativeRelease()
            owned.edit().clear().commit();context.deleteSharedPreferences(namespace)
        }
        assertEquals("User preferences must be unchanged",original,prefs.all)
    }
}
