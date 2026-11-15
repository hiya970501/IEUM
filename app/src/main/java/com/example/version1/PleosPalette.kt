package com.example.version1

/** App surfaces follow the vehicle's uiMode; the 3D scene retains its own lighting. */
data class PleosPalette(
    val background: Int, val surface: Int, val raised: Int, val text: Int,
    val secondary: Int, val accent: Int, val accentInk: Int, val warning: Int
) {
    companion object {
        fun forNight(night: Boolean) = if (night) PleosPalette(
            0xFF131417.toInt(), 0xFF1D1E21.toInt(), 0xFF313236.toInt(),
            0xFFF7F8FA.toInt(), 0xFFC7C9CE.toInt(), 0xFFA9EDD3.toInt(),
            0xFF0D2820.toInt(), 0xFFF8C78B.toInt()
        ) else PleosPalette(
            0xFFFFFFFF.toInt(), 0xFFF7F8FA.toInt(), 0xFFEDEEF2.toInt(),
            0xFF1D1E21.toInt(), 0xFF44464E.toInt(), 0xFF146343.toInt(),
            0xFFFFFFFF.toInt(), 0xFF795000.toInt()
        )
    }
}
