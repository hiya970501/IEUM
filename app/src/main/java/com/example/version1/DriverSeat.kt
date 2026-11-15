package com.example.version1

/** AAOS VehicleAreaSeat values are physical positions, independent of a preview. */
enum class DriverSeatPosition(val areaMask: Int, val label: String) {
    ROW_1_LEFT(1, "앞좌석 좌측"), ROW_1_CENTER(2, "앞좌석 중앙"), ROW_1_RIGHT(4, "앞좌석 우측"),
    ROW_2_LEFT(16, "2열 좌측"), ROW_2_CENTER(32, "2열 중앙"), ROW_2_RIGHT(64, "2열 우측"),
    ROW_3_LEFT(256, "3열 좌측"), ROW_3_CENTER(512, "3열 중앙"), ROW_3_RIGHT(1024, "3열 우측");
    companion object { fun fromAreaMask(value: Int): DriverSeatPosition? = entries.singleOrNull { it.areaMask == value } }
}

/** Referential identity binds a preparation to this connection's seat observation. */
class DriverSeatBinding(val position: DriverSeatPosition, val manual: Boolean = false)
sealed interface DriverSeatState {
    data object Loading : DriverSeatState
    data class Available(val binding: DriverSeatBinding) : DriverSeatState
    data class Unavailable(val reason: String) : DriverSeatState
}

internal fun <T> selectDriverArea(areas: Collection<T>, position: DriverSeatPosition,
    singleSeatOnly: Boolean, names: (T) -> Set<String>): T? {
    val candidates = areas.map { it to names(it) }.filter { (_, seats) ->
        position.name in seats && (!singleSeatOnly || seats.size == 1)
    }
    val smallest = candidates.minOfOrNull { it.second.size } ?: return null
    // Two equally small overlapping zones are ambiguous. Collection order is not authority.
    return candidates.filter { it.second.size == smallest }.singleOrNull()?.first
}
