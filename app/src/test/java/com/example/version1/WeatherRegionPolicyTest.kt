package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class WeatherRegionPolicyTest {
    private val seoul=WeatherPlace("region",37.57,126.98,true)
    @Test fun roadTripChangesRegionButLocalJitterDoesNot() {
        assertFalse(WeatherRegionPolicy.moved(seoul,seoul.copy(latitude=37.58)))
        val busan=WeatherPlace("region",35.18,129.08,true)
        assertTrue(WeatherRegionPolicy.moved(seoul,busan))
        assertTrue(WeatherRegionPolicy.distanceKm(seoul,busan) in 320.0..330.0)
        assertTrue(WeatherRegionPolicy.moved(null,seoul))
        assertTrue(WeatherRegionPolicy.moved(seoul.copy(fromDeviceLocation=false),seoul))
        assertEquals(0.0,WeatherRegionPolicy.distanceKm(seoul,seoul),0.00001)
    }
    @Test fun failedNewRegionRetriesWithoutHammeringWeatherServer() {
        assertTrue(WeatherRegionPolicy.due(1_000_000,0,0,true))
        assertFalse(WeatherRegionPolicy.due(1_030_000,1_000_000,0,true))
        assertTrue(WeatherRegionPolicy.due(1_060_000,1_000_000,0,true))
        assertFalse(WeatherRegionPolicy.due(1_100_000,1_000_000,1_000_000,false))
        assertTrue(WeatherRegionPolicy.due(1_900_000,1_000_000,1_000_000,false))
        assertTrue(WeatherRegionPolicy.due(500_000,1_000_000,1_000_000,false))
    }
    @Test fun lostGpsCannotKeepPreviousRegionVerifiedIndefinitely() {
        assertTrue(WeatherRegionPolicy.positionFresh(300_100,100))
        assertFalse(WeatherRegionPolicy.positionFresh(300_101,100))
        assertFalse(WeatherRegionPolicy.positionFresh(100,0))
        assertFalse(WeatherRegionPolicy.positionFresh(99,100))
    }
}
