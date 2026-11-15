package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class CabinEffectStateTest {
    private val base = CabinSceneState(connected = true, powerOn = true, fan = 1,
        driverTemperature = 24f, steeringHeat = 0, airDirection = CabinAirDirection.FACE)

    @Test fun fanLevelAndModeMetadataDoNotChangeSeatOrWheelMaterials() {
        val first = CabinEffectState.from(base)
        val next = CabinEffectState.from(base.copy(fan = 7, active = true))
        assertEquals(first.seat, next.seat)
        assertEquals(first.wheel, next.wheel)
        assertTrue(first.visible("afterglow_airflow_cool_0"))
        assertFalse(first.visible("afterglow_airflow_cool_1"))
        assertTrue(next.visible("afterglow_airflow_cool_6"))
        assertFalse(next.visible("afterglow_airflow_cool_7"))
        assertTrue(first.visible("afterglow_airflow_cool_path"))
        assertFalse(CabinEffectState.from(base.copy(powerOn = false)).visible("afterglow_airflow_cool_path"))
    }
    @Test fun seatHeatAndCoolingLeaveWheelUntouchedButKeepExactSeatLevels() {
        val first = CabinEffectState.from(base)
        val warm = CabinEffectState.from(base.copy(heat = 3))
        val cool = CabinEffectState.from(base.copy(ventilation = 1, seatCooling = 3))
        assertEquals(first.wheel, warm.wheel); assertEquals(first.wheel, cool.wheel)
        assertEquals(CabinEffectState.TintLevels(3, 0, 3), warm.seat)
        assertEquals(CabinEffectState.TintLevels(0, 3, 3), cool.seat)
        assertTrue(warm.visible("afterglow_heat_driver")); assertFalse(first.visible("afterglow_heat_driver"))
        assertTrue(cool.visible("afterglow_vent_driver"))
    }
    @Test fun wheelMaximumAndLevelsAreIndependentOfSeatAndAirflow() {
        val first = CabinEffectState.from(base)
        val heated = CabinEffectState.from(base.copy(steeringHeat = 2, steeringHeatMax = 2))
        assertEquals(first.seat, heated.seat); assertEquals(first.airflow, heated.airflow)
        assertEquals(CabinEffectState.TintLevels(2, 0, 2), heated.wheel)
        val unknown = CabinEffectState.from(base.copy(connected = false, heat = 3, ventilation = 3, steeringHeat = 3))
        assertEquals(0, unknown.seat.heat); assertEquals(0, unknown.wheel.heat)
        assertFalse(unknown.airflow); assertFalse(unknown.heatVisible); assertFalse(unknown.ventilationVisible)
    }
    @Test fun warmCoolPathsSwitchOnlyAtAuthoredTemperatureBoundary() {
        for ((temperature, warm) in listOf(24.9f to false, 25f to true, 26f to true)) {
            val state = CabinEffectState.from(base.copy(driverTemperature = temperature))
            assertEquals(warm, state.visible("afterglow_particle_warm_driver"))
            assertEquals(!warm, state.visible("afterglow_airflow_cool_driver"))
            assertTrue(state.visible("afterglow_airflow_direction"))
        }
    }

    @Test fun everySdkOutletCombinationSelectsOnlyItsActualPaths() {
        for (direction in CabinAirDirection.entries) {
            val state=CabinEffectState.from(base.copy(fan=3,airDirection=direction))
            assertEquals(direction.face,state.visible("afterglow_airflow_cool_0"))
            assertEquals(direction.floor,state.visible("afterglow_airflow_floor_cool_0"))
            assertEquals(direction.defrost,state.visible("afterglow_airflow_defrost_cool_0"))
            assertFalse(state.visible("afterglow_airflow_floor_warm_0"))
            assertFalse(state.visible("afterglow_airflow_floor_cool_3"))
            assertEquals(direction,CabinAirDirection.fromSdkName(direction.name))
        }
        val unknown=CabinEffectState.from(base.copy(airDirection=null))
        assertFalse(unknown.visible("afterglow_airflow_cool_0"))
        assertFalse(unknown.visible("afterglow_airflow_floor_cool_0"))
        assertFalse(unknown.visible("afterglow_airflow_defrost_cool_0"))
        assertNull(CabinAirDirection.fromSdkName("UNKNOWN"))
        assertNull(CabinAirDirection.fromSdkName(null))
        val off=CabinEffectState.from(base.copy(powerOn=false,airDirection=CabinAirDirection.FACE_AND_FLOOR_AND_DEFROST))
        assertFalse(off.visible("afterglow_airflow_floor_cool_0"))
        assertFalse(off.visible("afterglow_airflow_defrost_cool_0"))
    }
}
