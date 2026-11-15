package com.example.version1

import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Owned platform mock provider, never overwrites GPS/network or a user's position. */
@RunWith(AndroidJUnit4::class)
class WeatherTrackingDeviceTest {
    @Suppress("DEPRECATION")
    @Test fun platformListenerReceivesNewRegionAndStopsAfterClose() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("gpsFixture")=="emulator")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val provider="ieum_qa_${SystemClock.uptimeMillis()}"
        val manager=context.getSystemService(LocationManager::class.java)
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        val prefsField=MainActivity::class.java.getDeclaredField("prefs\$delegate").apply { isAccessible=true }
        val namespace="qa_gps_${java.util.UUID.randomUUID()}"
        val owned=ProtectedPreferences.get(context,namespace)
        owned.edit().putBoolean("weather_location_enabled",false).commit()
        var previousPrefs:Any?=null
        val received=LinkedBlockingQueue<WeatherPlace>()
        var tracker:WeatherLocationTracker?=null
        var added=false
        try {
            scenario.onActivity { previousPrefs=prefsField.get(it);prefsField.set(it,lazy { owned }) }
            val foregroundField=MainActivity::class.java.getDeclaredField("foreground").apply { isAccessible=true }
            val deadline=SystemClock.uptimeMillis()+15000
            var foreground=false
            while(!foreground && SystemClock.uptimeMillis()<deadline) {
                scenario.onActivity { foreground=foregroundField.getBoolean(it) }
                if(!foreground) Thread.sleep(50)
            }
            assertTrue("Position updates are a foreground feature",foreground)
            manager.addTestProvider(provider,false,false,false,false,false,false,false,1,1);added=true
            manager.setTestProviderEnabled(provider,true)
            assertTrue("Foreground location permission boundary must be effective",WeatherLocation.permitted(context))
            fun start() {
                instrumentation.runOnMainSync {
                    tracker=WeatherLocationTracker(context,{ received.offer(it.place) },{},listOf(provider))
                    assertTrue(tracker!!.start())
                }
            }
            fun send(lat:Double,lon:Double) {
                    manager.setTestProviderLocation(provider,Location(provider).apply {
                        latitude=lat;longitude=lon;accuracy=10f;time=System.currentTimeMillis();elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()
                    })
            }
            start();send(37.566535,126.977969)
            val seoul=checkNotNull(received.poll(10,TimeUnit.SECONDS)) { "Platform provider did not deliver first fix" }
            assertEquals(37.57,seoul.latitude,0.000001);assertTrue(seoul.name.contains("테스트"))
            instrumentation.runOnMainSync { tracker!!.close();tracker=null };received.clear()
            start();send(35.179554,129.075642)
            // Android can replay a fresh cached Seoul fix to a new subscriber.
            // Keep the production 60s cadence and wait for the actual new region.
            val moveDeadline=SystemClock.uptimeMillis()+75_000
            var movedRegion:WeatherPlace?=null
            while(movedRegion==null && SystemClock.uptimeMillis()<moveDeadline) {
                val candidate=received.poll(1,TimeUnit.SECONDS)
                if(candidate!=null && WeatherRegionPolicy.moved(seoul,candidate)) movedRegion=candidate
                else send(35.179554,129.075642)
            }
            val busan=checkNotNull(movedRegion) { "Platform provider did not deliver moved region at production cadence" }
            assertTrue(WeatherRegionPolicy.moved(seoul,busan))
            instrumentation.runOnMainSync { tracker!!.close();tracker=null };received.clear()
            send(33.4996,126.5312)
            assertNull("Closed listener must not deliver another region",received.poll(1500,TimeUnit.MILLISECONDS))
        } finally {
            instrumentation.runOnMainSync { tracker?.close() }
            if(added) manager.removeTestProvider(provider)
            scenario.onActivity { if(previousPrefs!=null) prefsField.set(it,previousPrefs) }
            scenario.closeAndAwaitNativeRelease()
            owned.edit().clear().commit();context.deleteSharedPreferences(namespace)
        }
    }
}
