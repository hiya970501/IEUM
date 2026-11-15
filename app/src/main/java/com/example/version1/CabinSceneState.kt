package com.example.version1

/** Read-back settings for a concept cabin; these are not measured flow or body temperatures. */
data class CabinSceneState(
    val driverTemperature: Float? = null,
    val fan: Int = 0,
    val heat: Int = 0,
    val ventilation: Int = 0,
    val powerOn: Boolean = false,
    val active: Boolean = false,
    val connected: Boolean = false,
    val steeringHeat: Int? = null,
    val steeringHeatMax: Int = 3,
    val seatCooling: Int = 0,
    val airDirection: CabinAirDirection? = null,
)
