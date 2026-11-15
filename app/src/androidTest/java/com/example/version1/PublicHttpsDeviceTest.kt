package com.example.version1

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Fixed public city, real platform HTTPS. Never requests or reads a device location. */
@RunWith(AndroidJUnit4::class)
class PublicHttpsDeviceTest {
    @Test fun fixedPublicCityCompletesPlatformHttpsWithoutLocationAccess() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("guidelineUi")=="emulator")
        assumeTrue(android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val weather=runBlocking {
            val places=ComfortWeatherClient.search("Seoul")
            assertTrue("Public city lookup must return a bounded list",places.size in 1..5)
            assertTrue(places.none { it.fromDeviceLocation })
            ComfortWeatherClient.fetch(places.first())
        }
        assertTrue(weather.temperature.isFinite() && weather.temperature in -60f..65f)
        assertTrue(weather.humidity.isFinite() && weather.humidity in 0f..100f)
        assertTrue(weather.observedAt>0 && weather.fetchedAt>0)
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"public-https-result.json").writeText(JSONObject()
            .put("passed",true).put("platformHttpsCompleted",true).put("measurementsValidated",true)
            .put("query","fixed public Seoul city").put("deviceLocationRead",false)
            .put("locationPermissionRequested",false).put("vehicleCommandsIssued",false)
            .put("physicalGpsVerified",false).put("certificatePinningVerified",false)
            .put("scope","Live fixed-provider HTTPS geocoding and forecast through production client; not GPS, publisher-update or physical-phone proof")
            .toString(2))
    }
}
