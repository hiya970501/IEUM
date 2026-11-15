package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class WeatherLocationTest {
    @Test fun sourceFixTimeIsPreservedRatherThanMadeFreshAtReceipt() {
        val position=WeatherLocation.position(37.5,127.0,1_000_000_000,300_000_000_000,10f,false)
        assertEquals(1000L,position.elapsedRealtimeMs)
        assertTrue(WeatherRegionPolicy.positionFresh(301_000,position.elapsedRealtimeMs))
        assertFalse(WeatherRegionPolicy.positionFresh(301_001,position.elapsedRealtimeMs))
        assertTrue(runCatching { WeatherLocation.position(37.5,127.0,1_001,1000,10f,false) }.isFailure)
        assertTrue(runCatching { WeatherLocation.position(37.5,127.0,0,1000,10f,false) }.isFailure)
    }
    @Test fun approximatePermissionUsesAdvertisedFusedOrNetworkButNeverGps() {
        assertEquals(listOf("fused","network"),WeatherLocation.providers(false,listOf("gps","fused","network")))
        assertEquals(listOf("gps"),WeatherLocation.providers(true,listOf("gps")))
        assertTrue(WeatherLocation.providers(false,listOf("gps")).isEmpty())
    }
    @Test fun onlyRoundedRegionLeavesLocationBoundary() {
        val place=WeatherLocation.place(37.566535,126.977969,1000,100f,false)
        assertEquals(37.57,place.latitude,0.000001)
        assertEquals(126.98,place.longitude,0.000001)
        assertTrue(place.fromDeviceLocation)
        assertFalse(place.name.contains("37."))
        assertTrue(WeatherLocation.place(37.56,126.97,0,10f,true).name.contains("테스트"))
    }
    @Test fun staleInvalidAndUnboundedFixesAreRejected() {
        for(args in listOf(Triple(300001L,20f,37.5),Triple(-1L,20f,37.5),Triple(0L,Float.NaN,37.5),
            Triple(0L,20001f,37.5),Triple(0L,10f,Double.NaN))) {
            assertTrue(runCatching { WeatherLocation.place(args.third,127.0,args.first,args.second,false) }.isFailure)
        }
    }
}
