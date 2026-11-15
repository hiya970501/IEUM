package com.example.version1

import android.car.VehicleAreaSeat
import org.junit.Assert.*
import org.junit.Test

class DriverSeatTest {
    private val left = DriverSeatPosition.ROW_1_LEFT
    private val right = DriverSeatPosition.ROW_1_RIGHT
    private data class Zone(val id: String, val seats: Set<String>)
    private fun zone(id: String, vararg positions: DriverSeatPosition) = Zone(id, positions.map { it.name }.toSet())
    private fun choose(zones: List<Zone>, position: DriverSeatPosition, seatOnly: Boolean = false) =
        selectDriverArea(zones, position, seatOnly) { it.seats }

    @Test fun actualSeatValuesDoNotAcceptCombinedUnknownOrInvalidPositions() {
        val masks = listOf(VehicleAreaSeat.SEAT_ROW_1_LEFT, VehicleAreaSeat.SEAT_ROW_1_CENTER,
            VehicleAreaSeat.SEAT_ROW_1_RIGHT, VehicleAreaSeat.SEAT_ROW_2_LEFT, VehicleAreaSeat.SEAT_ROW_2_CENTER,
            VehicleAreaSeat.SEAT_ROW_2_RIGHT, VehicleAreaSeat.SEAT_ROW_3_LEFT, VehicleAreaSeat.SEAT_ROW_3_CENTER,
            VehicleAreaSeat.SEAT_ROW_3_RIGHT)
        assertEquals(masks, DriverSeatPosition.entries.map { it.areaMask })
        masks.forEach { assertNotNull(DriverSeatPosition.fromAreaMask(it)) }
        listOf(0, -1, 3, 5, 7, 8, 2048, Int.MAX_VALUE).forEach { assertNull(DriverSeatPosition.fromAreaMask(it)) }
    }
    @Test fun rightHandVehicleUsesRightPhysicalZoneRegardlessOfCollectionOrder() {
        val l = zone("left", left); val r = zone("right", right)
        assertEquals(r, choose(listOf(l, r), right)); assertEquals(r, choose(listOf(r, l), right))
        assertEquals(l, choose(listOf(r, l), left))
    }
    @Test fun unsupportedDriverDoesNotFallBackToAnotherSeat() {
        assertNull(choose(listOf(zone("left", left)), right))
        assertNull(choose(emptyList(), left))
    }
    @Test fun exactIndividualZoneWinsOverSharedZones() {
        val individual = zone("right", right)
        val shared = zone("shared", left, right)
        assertEquals(individual, choose(listOf(shared, individual), right))
        assertEquals(individual, choose(listOf(individual, shared), right))
    }
    @Test fun commonBlowerMayUseTheSmallestSupportedSharedZone() {
        val front = zone("front", left, right)
        val cabin = zone("cabin", left, right, DriverSeatPosition.ROW_2_LEFT)
        assertEquals(front, choose(listOf(cabin, front), right))
    }
    @Test fun heatAndVentilationNeverSelectSharedPassengerZones() {
        assertNull(choose(listOf(zone("shared", left, right)), right, seatOnly = true))
        val individual = zone("right", right)
        assertEquals(individual, choose(listOf(zone("shared", left, right), individual), right, seatOnly = true))
    }
    @Test fun equallySmallOverlappingZonesAreNotResolvedByArbitraryOrder() {
        val one = zone("zone1", right, left)
        val two = zone("zone2", right, DriverSeatPosition.ROW_2_RIGHT)
        assertNull(choose(listOf(one, two), right)); assertNull(choose(listOf(two, one), right))
    }
    @Test fun driverCanBeInTheCenterWithoutInventingLeftOrRight() {
        val center = DriverSeatPosition.ROW_1_CENTER
        val zone = zone("center", center)
        assertEquals(zone, choose(listOf(zone("left", left), zone), center, seatOnly = true))
    }
}
