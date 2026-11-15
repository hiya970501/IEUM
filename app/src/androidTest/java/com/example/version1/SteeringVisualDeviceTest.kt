package com.example.version1

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual SDK request/readback, product durable journal and unedited window captures.
 * This fixture is emulator-only and does not claim physical steering or optical latency. */
@RunWith(AndroidJUnit4::class)
class SteeringVisualDeviceTest {
    @Test fun steeringLevelsReachSdkHudAndRestoreAllCapturedSettings() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("thermalSdkFixture")=="emulator")
        assumeTrue(android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        val rows=JSONArray()
        var controller:PleosRecoveryController?=null
        var original:Map<String,String>?=null
        var restored=false
        fun awaitReady(label:String,condition:(MainActivity)->Boolean) {
            val deadline=SystemClock.uptimeMillis()+60000
            while(SystemClock.uptimeMillis()<deadline) {
                var ready=false;scenario.onActivity { ready=condition(it) }
                if(ready)return
                Thread.sleep(100)
            }
            error(label)
        }
        fun values():Map<String,String> {
            val data=runBlocking { controller!!.captureZeroTapBaseline() }.getJSONObject("values")
            return data.keys().asSequence().associateWith { data.getString(it) }
        }
        fun capture(level:Int) {
            val done=CountDownLatch(1);var status=-1;var image:Bitmap?=null
            scenario.onActivity { activity ->
                image=Bitmap.createBitmap(activity.window.decorView.width,activity.window.decorView.height,Bitmap.Config.ARGB_8888)
                PixelCopy.request(activity.window,image!!,{status=it;done.countDown()},Handler(Looper.getMainLooper()))
            }
            try {
                assertTrue(done.await(8,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,status)
                File(context.filesDir,"steering-stage-$level.png").outputStream().use {
                    assertTrue(image!!.compress(Bitmap.CompressFormat.PNG,100,it))
                }
            } finally {image?.recycle()}
        }
        try {
            awaitReady("Actual parked preview and idle controller required") { activity ->
                val safe=MainActivity::class.java.getDeclaredMethod("safeToAct").apply { isAccessible=true }.invoke(activity)==true
                val scene=field("scene").get(activity) as PremiumCabinView
                val gate=scene.javaClass.getDeclaredField("presentation").apply { isAccessible=true }.get(scene) as PreviewPresentationGate
                safe && !gate.covered && field("driverSeatState").get(activity) is DriverSeatState.Available &&
                    !field("busy").getBoolean(activity) && !field("active").getBoolean(activity)
            }
            scenario.onActivity { controller=field("controller").get(it) as PleosRecoveryController }
            assertFalse(controller!!.hasPending)
            val baseline=runBlocking { controller!!.cabinTelemetry() }
            require(baseline.driverTemperature!=null && baseline.fan!=null && baseline.steeringHeat!=null && baseline.steeringHeatMax>=3)
            original=values()
            // Own the foreground operation while using the real controller directly.
            // Otherwise MainActivity correctly restores an orphaned pending journal.
            scenario.onActivity { field("busy").setBoolean(it,true) }
            for(level in 0..3) {
                assertTrue("Previous baseline must be restored before a new stage",values()==original)
                val plan=RecoveryPlan(baseline.driverTemperature!!,baseline.fan!!,0,0,"QA","SDK steering fixture",controlsSeat=false,steeringHeat=level)
                runBlocking { controller!!.apply(plan) }
                val applied=runBlocking { controller!!.cabinTelemetry() }
                assertEquals("Actual SDK readback",level,applied.steeringHeat)
                scenario.onActivity {
                    field("telemetry").set(it,applied)
                    MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible=true }.invoke(it)
                }
                Thread.sleep(1000)
                capture(level)
                val actual=runBlocking { controller!!.cabinTelemetry() }
                assertEquals(level,actual.steeringHeat)
                rows.put(JSONObject().put("requested",level).put("sdkReadback",actual.steeringHeat).put("screenshot","steering-stage-$level.png"))
                assertTrue(runBlocking { controller!!.restore() })
                assertFalse(controller!!.hasPending)
                assertTrue("All captured baseline settings must be restored",values()==original)
            }
            restored=true
        } finally {
            var cleanupFailure:Exception?=null
            try {
                if(controller!=null && controller!!.hasPending) runBlocking { controller!!.restore() }
                if(controller!=null && original!=null) restored=values()==original && !controller!!.hasPending
            }catch(error:Exception){restored=false;cleanupFailure=error}
            finally {
                scenario.onActivity { field("busy").setBoolean(it,false) }
                File(context.filesDir,"steering-visual-result.json").writeText(JSONObject()
                    .put("sdkRequestedAndReadback",rows.length()==4).put("physicalVehicle",false)
                    .put("capturesRequireVisualInspection",true).put("allBaselineSettingsRestored",restored)
                    .put("baselineCaptured",original!=null)
                    .put("cleanupFailureType",cleanupFailure?.javaClass?.simpleName?:JSONObject.NULL)
                    .put("stages",rows).toString(2))
                scenario.closeAndAwaitNativeRelease()
            }
        }
        assertTrue(restored);assertEquals(4,rows.length())
    }
}
