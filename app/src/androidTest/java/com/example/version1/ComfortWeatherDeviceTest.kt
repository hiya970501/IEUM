package com.example.version1

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real network smoke with a public test city; never writes the user's selected region. */
@RunWith(AndroidJUnit4::class)
class ComfortWeatherDeviceTest {
    @Test fun searchesPublicTestCityAndReadsCurrentWeather() = runBlocking {
        val results=ComfortWeatherClient.search("Seoul")
        assertTrue(results.isNotEmpty())
        val w=ComfortWeatherClient.fetch(results.first())
        assertTrue(w.temperature.isFinite());assertTrue(w.humidity in 0f..100f)
        assertTrue(w.observedAt>0);assertTrue(w.fetchedAt>0)
        assertEquals(results.first(),w.place)
    }
}
