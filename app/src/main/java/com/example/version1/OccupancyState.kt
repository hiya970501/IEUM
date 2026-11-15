package com.example.version1

/** Physical positions are independent of the steering-wheel preference. */
enum class OccupancySeat(val row: Int, val column: Int, val positionLabel: String) {
    ROW_1_LEFT(1, 0, "앞좌석 왼쪽"),
    ROW_1_RIGHT(1, 2, "앞좌석 오른쪽"),
    ROW_2_LEFT(2, 0, "2열 왼쪽"),
    ROW_2_CENTER(2, 1, "2열 가운데"),
    ROW_2_RIGHT(2, 2, "2열 오른쪽"),
    ROW_3_LEFT(3, 0, "3열 왼쪽"),
    ROW_3_RIGHT(3, 2, "3열 오른쪽")
}

/** A seating arrangement selected from verified model configuration, never from occupancy. */
enum class OccupancyLayout(val seats: List<OccupancySeat>, val label: String) {
    FOUR(listOf(OccupancySeat.ROW_1_LEFT, OccupancySeat.ROW_1_RIGHT,
        OccupancySeat.ROW_2_LEFT, OccupancySeat.ROW_2_RIGHT), "4인승 · 2+2"),
    FIVE(listOf(OccupancySeat.ROW_1_LEFT, OccupancySeat.ROW_1_RIGHT,
        OccupancySeat.ROW_2_LEFT, OccupancySeat.ROW_2_CENTER, OccupancySeat.ROW_2_RIGHT), "5인승 · 2+3"),
    SIX(listOf(OccupancySeat.ROW_1_LEFT, OccupancySeat.ROW_1_RIGHT,
        OccupancySeat.ROW_2_LEFT, OccupancySeat.ROW_2_RIGHT,
        OccupancySeat.ROW_3_LEFT, OccupancySeat.ROW_3_RIGHT), "6인승 · 2+2+2"),
    SEVEN(listOf(OccupancySeat.ROW_1_LEFT, OccupancySeat.ROW_1_RIGHT,
        OccupancySeat.ROW_2_LEFT, OccupancySeat.ROW_2_CENTER, OccupancySeat.ROW_2_RIGHT,
        OccupancySeat.ROW_3_LEFT, OccupancySeat.ROW_3_RIGHT), "7인승 · 2+3+2")
}

enum class OccupancyPresence { UNKNOWN, VACANT, OCCUPIED }
enum class OccupancySource { UNKNOWN, SENSOR, MANUAL_ADDED, MANUAL_REMOVED }
enum class OccupancySensorStatus { NOT_REQUESTED, LOADING, AVAILABLE, UNAVAILABLE, PERMISSION_DENIED }

data class OccupancySensorUpdate(
    val readings: Map<OccupancySeat, Boolean?>,
    val status: OccupancySensorStatus,
    val message: String? = null
)

data class OccupancySeatState(
    val seat: OccupancySeat,
    val presence: OccupancyPresence,
    val source: OccupancySource,
    val sensorOccupied: Boolean?
) {
    val occupied: Boolean get() = presence == OccupancyPresence.OCCUPIED
    val sourceLabel: String get() = when (source) {
        OccupancySource.UNKNOWN -> "확인 안 됨"
        OccupancySource.SENSOR -> if (occupied) "차량 감지" else "비어 있음"
        OccupancySource.MANUAL_ADDED -> "수동 추가"
        OccupancySource.MANUAL_REMOVED -> "수동 제외"
    }
}

data class OccupancySnapshot(
    val layout: OccupancyLayout,
    val seats: List<OccupancySeatState>,
    val sensorStatus: OccupancySensorStatus,
    val sensorMessage: String? = null
) {
    val occupiedCount: Int get() = seats.count { it.occupied }
    val unknownCount: Int get() = seats.count { it.presence == OccupancyPresence.UNKNOWN }
    val hasManualOverrides: Boolean get() = seats.any {
        it.source == OccupancySource.MANUAL_ADDED || it.source == OccupancySource.MANUAL_REMOVED
    }
    val summary: String get() = buildString {
        append("탑승 ${occupiedCount}명")
        if (unknownCount > 0) append(" · 미확인 ${unknownCount}석")
    }
}

/**
 * Small UI-thread state holder. A missing/failed sensor reading remains unknown.
 * Manual corrections are explicit overrides: a repeated positive sensor event cannot
 * re-add a person whom the user removed. Resetting an override restores the latest
 * genuine sensor observation, and changing vehicle context clears all observations.
 * This is comfort UI state only, never an input to airbags or other safety systems.
 */
class OccupancyStateStore(
    initialLayout: OccupancyLayout = OccupancyLayout.FIVE,
    initialVehicleKey: String = ""
) {
    private var layout = initialLayout
    private var vehicleKey = initialVehicleKey
    private var sensorStatus = OccupancySensorStatus.NOT_REQUESTED
    private var sensorMessage: String? = null
    private val sensorReadings = mutableMapOf<OccupancySeat, Boolean?>()
    private val overrides = mutableMapOf<OccupancySeat, Boolean>()

    val snapshot: OccupancySnapshot get() = OccupancySnapshot(layout, layout.seats.map { seat ->
        val raw = sensorReadings[seat]
        val manual = overrides[seat]
        OccupancySeatState(seat, when (manual ?: raw) {
            true -> OccupancyPresence.OCCUPIED
            false -> OccupancyPresence.VACANT
            null -> OccupancyPresence.UNKNOWN
        }, when {
            manual == true -> OccupancySource.MANUAL_ADDED
            manual == false -> OccupancySource.MANUAL_REMOVED
            raw != null -> OccupancySource.SENSOR
            else -> OccupancySource.UNKNOWN
        }, raw)
    }, sensorStatus, sensorMessage)

    /** A key must identify the selected vehicle/version, not the LHD/RHD view preference. */
    fun configure(nextLayout: OccupancyLayout, nextVehicleKey: String): OccupancySnapshot {
        if (vehicleKey != nextVehicleKey || layout != nextLayout) {
            sensorReadings.clear()
            overrides.clear()
            sensorStatus = OccupancySensorStatus.NOT_REQUESTED
            sensorMessage = null
        }
        layout = nextLayout
        vehicleKey = nextVehicleKey
        return snapshot
    }

    /** An update is a complete snapshot. Unsupported or missing seats become unknown. */
    fun applySensorUpdate(update: OccupancySensorUpdate): OccupancySnapshot {
        sensorReadings.clear()
        if (update.status == OccupancySensorStatus.AVAILABLE) {
            update.readings.filterKeys { it in layout.seats }.forEach { (seat, occupied) ->
                sensorReadings[seat] = occupied
            }
        }
        sensorStatus = update.status
        sensorMessage = update.message
        return snapshot
    }

    fun add(seat: OccupancySeat): OccupancySnapshot {
        if (seat in layout.seats) overrides[seat] = true
        return snapshot
    }

    fun remove(seat: OccupancySeat): OccupancySnapshot {
        if (seat in layout.seats) overrides[seat] = false
        return snapshot
    }

    fun resetOverrides(seat: OccupancySeat? = null): OccupancySnapshot {
        if (seat == null) overrides.clear() else overrides.remove(seat)
        return snapshot
    }
}
