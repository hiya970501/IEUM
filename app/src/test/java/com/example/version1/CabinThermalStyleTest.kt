package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class CabinThermalStyleTest {
    @Test fun levelsIncreaseHeatAndVentColorAndOffRestoresOriginal() {
        val base = floatArrayOf(.2f, .2f, .2f, 1f)
        assertArrayEquals(base, CabinThermalStyle.tint(base, 0, 0), .0001f)
        val low = CabinThermalStyle.tint(base, 1, 0)
        val high = CabinThermalStyle.tint(base, 3, 0)
        assertTrue(high[0] > low[0]); assertTrue(high[1] < low[1])
        val cool = CabinThermalStyle.tint(base, 0, 3)
        assertTrue(cool[2] > cool[0]); assertEquals(1f, cool[3], 0f)
        assertArrayEquals(base, floatArrayOf(.2f, .2f, .2f, 1f), 0f)
    }
    @Test fun avoidsSeatBeltsMechanismsAndExterior() {
        assertTrue(CabinThermalStyle.seatMaterial("FR_SEAT__afterglow_interior"))
        assertFalse(CabinThermalStyle.seatMaterial("Seatbeltsoft__afterglow_interior"))
        assertFalse(CabinThermalStyle.seatMaterial("SEAT_MECH__afterglow_interior"))
        assertFalse(CabinThermalStyle.seatMaterial("FR_SEAT__afterglow_exterior"))
        assertTrue(CabinThermalStyle.wheelMaterial("SteeringLeatherA__afterglow_interior"))
    }
    @Test fun providerLinksCannotRedirectToOtherHostsOrCredentials() {
        assertTrue(MusicSource.YOUTUBE_MUSIC.acceptsLink("https://music.youtube.com/playlist?list=abc"))
        assertFalse(MusicSource.MELON.acceptsLink("https://music.youtube.com/playlist?list=abc"))
        assertFalse(MusicSource.YOUTUBE_MUSIC.acceptsLink("https://music.youtube.com.evil.test/playlist"))
        assertFalse(MusicSource.YOUTUBE_MUSIC.acceptsLink("https://user@music.youtube.com/playlist"))
        assertFalse(MusicSource.YOUTUBE_MUSIC.acceptsLink("http://music.youtube.com/playlist"))
    }
}
