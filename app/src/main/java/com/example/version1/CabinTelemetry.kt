package com.example.version1

/**
 * Readback values from the vehicle, never populated from the requested recovery plan.
 * Null means unsupported, unavailable or timed out; a drawing must not replace it with a target.
 * This is a best-effort snapshot of independent SDK properties, not an atomic hardware sample.
 */
data class CabinTelemetry(
    val driverTemperature: Float?,
    val fan: Int?,
    /** Raw HVAC_SEAT_TEMPERATURE: positive heating, negative cooling; not ventilation. */
    val heat: Int?,
    val ventilation: Int?,
    val powerOn: Boolean?,
    val vehicleName: String? = null,
    val readAtEpochMillis: Long = System.currentTimeMillis(),
    val measuredCabinTemperature: Float? = null,
    val steeringHeat: Int? = null,
    val steeringHeatMax: Int = 3,
    val airDirection: CabinAirDirection? = null
) {
    val seatHeat: Int? get() = heat?.takeIf { it != Int.MIN_VALUE }?.coerceAtLeast(0)
    val seatCooling: Int? get() = heat?.takeIf { it != Int.MIN_VALUE }?.let { (-it).coerceAtLeast(0) }
    val connected: Boolean
        get() = driverTemperature != null || fan != null || heat != null ||
            ventilation != null || powerOn != null
}
