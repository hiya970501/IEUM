package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class SeatTemperatureTelemetryTest {
    private fun sample(value: Int?) = CabinTelemetry(22f, 3, value, 0, true)
    @Test fun coolingPreservesRawValueAndDoesNotInventVentilation() {
        val actual = sample(-3)
        assertEquals(-3, actual.heat)
        assertEquals(0, actual.seatHeat)
        assertEquals(3, actual.seatCooling)
        assertEquals(0, actual.ventilation)
        // Signed learning preserves cooling explicitly; it must never become OFF or ventilation.
        val learned=LearnedSetting(22f,3,actual.heat,actual.ventilation)
        assertTrue(learned.valid())
        assertEquals(3,learned.asPlan().seatCooling)
        assertEquals(0,learned.asPlan().heat)
        assertEquals(0,learned.asPlan().ventilation)
    }
    @Test fun heatingAndOffRemainKnownSeparateFromCooling() {
        assertEquals(3, sample(3).seatHeat)
        assertEquals(0, sample(3).seatCooling)
        assertEquals(0, sample(0).seatHeat)
        assertEquals(0, sample(0).seatCooling)
    }
    @Test fun missingOrOverflowSentinelCannotBecomeKnownOff() {
        for (raw in listOf(null, Int.MIN_VALUE)) {
            assertNull(sample(raw).seatHeat)
            assertNull(sample(raw).seatCooling)
        }
    }
}
