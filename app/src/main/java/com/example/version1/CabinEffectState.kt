package com.example.version1

/** The authored 3D effects' exact inputs, independent of labels and request state. */
internal data class CabinEffectState(
    val seat: TintLevels, val wheel: TintLevels,
    val airflow: Boolean, val warmAirflow: Boolean,
    val heatVisible: Boolean, val ventilationVisible: Boolean,
    val fanLevel: Int,
    val airDirection: CabinAirDirection?
) {
    data class TintLevels(val heat: Int, val ventilation: Int, val maximum: Int)

    fun visible(name: String): Boolean = when {
        name.startsWith("afterglow_airflow") || name.startsWith("afterglow_particle") -> airflow &&
            (when {
                name.contains("_floor_") -> airDirection?.floor == true
                name.contains("_defrost_") -> airDirection?.defrost == true
                else -> airDirection?.face == true
            }) &&
            (!name.contains("_warm_") || warmAirflow) && (!name.contains("_cool_") || !warmAirflow) &&
            (name.substringAfterLast('_').toIntOrNull()?.let { it in 0 until fanLevel } ?: true)
        name.startsWith("afterglow_heat") -> heatVisible
        name.startsWith("afterglow_vent") -> ventilationVisible
        else -> true
    }

    companion object {
        fun from(state: CabinSceneState): CabinEffectState = CabinEffectState(
            TintLevels(if (state.connected) state.heat else 0,
                if (state.connected) maxOf(state.ventilation, state.seatCooling) else 0, 3),
            TintLevels(if (state.connected) state.steeringHeat?.coerceAtLeast(0) ?: 0 else 0,
                0, state.steeringHeatMax.coerceAtLeast(1)),
            state.connected && state.powerOn && state.fan > 0,
            state.driverTemperature?.let { it >= 25f } == true,
            state.connected && state.heat > 0,
            state.connected && state.ventilation > 0,
            if (state.connected && state.powerOn) state.fan.coerceIn(0, 8) else 0,
            state.airDirection.takeIf { state.connected }
        )
    }
}
