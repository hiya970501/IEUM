package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class OccupancyStateTest {
    private val frontLeft = OccupancySeat.ROW_1_LEFT
    private val frontRight = OccupancySeat.ROW_1_RIGHT
    private fun OccupancySnapshot.at(seat: OccupancySeat) = seats.first { it.seat == seat }

    @Test fun initialStateDoesNotInventDriverOrPassengers() {
        val state = OccupancyStateStore().snapshot
        assertEquals(0, state.occupiedCount)
        assertEquals(5, state.unknownCount)
        assertTrue(state.seats.all { it.source == OccupancySource.UNKNOWN })
    }

    @Test fun falseSensorIsVacantWhileMissingAndNullRemainUnknown() {
        val store = OccupancyStateStore()
        val state = store.applySensorUpdate(OccupancySensorUpdate(
            mapOf(frontLeft to false, frontRight to null), OccupancySensorStatus.AVAILABLE))
        assertEquals(OccupancyPresence.VACANT, state.at(frontLeft).presence)
        assertEquals(OccupancySource.SENSOR, state.at(frontLeft).source)
        assertEquals(4, state.unknownCount)
    }

    @Test fun repeatedSensorPositiveCannotUndoManualFalseDetectionCorrection() {
        val store = OccupancyStateStore()
        val update = OccupancySensorUpdate(mapOf(frontLeft to true), OccupancySensorStatus.AVAILABLE)
        assertTrue(store.applySensorUpdate(update).at(frontLeft).occupied)
        assertFalse(store.remove(frontLeft).at(frontLeft).occupied)
        repeat(3) {
            val corrected = store.applySensorUpdate(update).at(frontLeft)
            assertEquals(OccupancySource.MANUAL_REMOVED, corrected.source)
            assertFalse(corrected.occupied)
            assertEquals(true, corrected.sensorOccupied)
        }
        val reset = store.resetOverrides(frontLeft).at(frontLeft)
        assertTrue(reset.occupied)
        assertEquals(OccupancySource.SENSOR, reset.source)
    }

    @Test fun manualAdditionSurvivesMissingSensorAndSensorVacancy() {
        val store = OccupancyStateStore()
        store.add(frontRight)
        val state = store.applySensorUpdate(OccupancySensorUpdate(
            mapOf(frontRight to false), OccupancySensorStatus.AVAILABLE))
        assertTrue(state.at(frontRight).occupied)
        assertEquals(OccupancySource.MANUAL_ADDED, state.at(frontRight).source)
        assertFalse(store.resetOverrides().at(frontRight).occupied)
    }

    @Test fun unavailableSensorClearsStaleAutomaticReadingsButPreservesCorrections() {
        val store = OccupancyStateStore()
        store.applySensorUpdate(OccupancySensorUpdate(mapOf(frontLeft to true), OccupancySensorStatus.AVAILABLE))
        store.add(frontRight)
        val state = store.applySensorUpdate(OccupancySensorUpdate(emptyMap(), OccupancySensorStatus.UNAVAILABLE))
        assertEquals(OccupancyPresence.UNKNOWN, state.at(frontLeft).presence)
        assertTrue(state.at(frontRight).occupied)
        assertEquals(1, state.occupiedCount)
    }

    @Test fun sameVehicleKeepsOverridesWhileVehicleOrLayoutChangeClearsThem() {
        val store = OccupancyStateStore(initialVehicleKey = "car-a")
        store.add(frontLeft)
        assertTrue(store.configure(OccupancyLayout.FIVE, "car-a").hasManualOverrides)
        val nextVehicle = store.configure(OccupancyLayout.FIVE, "car-b")
        assertFalse(nextVehicle.hasManualOverrides)
        assertEquals(5, nextVehicle.unknownCount)
        store.add(frontLeft)
        val nextLayout = store.configure(OccupancyLayout.SIX, "car-b")
        assertFalse(nextLayout.hasManualOverrides)
        assertEquals(6, nextLayout.unknownCount)
    }

    @Test fun unsupportedSeatCannotCreateHiddenPassengerOrConsumeGroupReading() {
        val store = OccupancyStateStore(OccupancyLayout.FOUR)
        store.add(OccupancySeat.ROW_3_LEFT)
        val state = store.applySensorUpdate(OccupancySensorUpdate(mapOf(
            OccupancySeat.ROW_3_LEFT to true, OccupancySeat.ROW_2_CENTER to true),
            OccupancySensorStatus.AVAILABLE))
        assertEquals(0, state.occupiedCount)
        assertEquals(4, state.unknownCount)
        assertFalse(state.hasManualOverrides)
    }

    @Test fun eachSupportedLayoutHasUniquePhysicalSeatsAndCorrectRowCount() {
        assertEquals(listOf(4, 5, 6, 7), OccupancyLayout.entries.map { it.seats.size })
        OccupancyLayout.entries.forEach { layout ->
            assertEquals(layout.seats.size, layout.seats.toSet().size)
            assertEquals(2, layout.seats.count { it.row == 1 })
        }
    }
}
