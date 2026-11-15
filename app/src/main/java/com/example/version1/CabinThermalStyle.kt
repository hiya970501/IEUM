package com.example.version1

/** Colors encode confirmed setting levels, not measured upholstery temperatures. */
internal object CabinThermalStyle {
    fun seatMaterial(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith("__afterglow_interior") &&
            listOf("fr_seat", "seatsoft", "seat_soft", "car_seat", "seattexture").any(n::contains) &&
            listOf("belt", "plastic", "mech", "rear", "rr_seat", "backpanel").none(n::contains)
    }
    fun wheelMaterial(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith("__afterglow_interior") &&
            listOf("steeringwheelrim", "steeringleather", "steeringsoft", "swheel_rim").any(n::contains)
    }
    fun tint(base: FloatArray, heat: Int, vent: Int, maxLevel: Int = 3): FloatArray {
        if (heat <= 0 && vent <= 0) return base.copyOf()
        val amount = ((maxOf(heat, vent).toFloat() / maxLevel.coerceAtLeast(1)).coerceIn(0f, 1f) * .55f + .25f)
        val target = if (heat > 0) floatArrayOf(1f, .045f, .018f) else floatArrayOf(.025f, .28f, 1f)
        return FloatArray(4) { i -> if (i == 3) base[3] else base[i] * (1f - amount) + target[i] * amount }
    }
}
