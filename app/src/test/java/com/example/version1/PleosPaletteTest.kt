package com.example.version1

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class PleosPaletteTest {
    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((color ushr shift) and 255) / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }
    private fun contrast(a: Int, b: Int): Double {
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
    }
    @Test fun allAppTextAndSelectedLabelsMeetContrastInBothModes() {
        for (night in listOf(false, true)) {
            val p = PleosPalette.forNight(night)
            for (surface in listOf(p.background, p.surface, p.raised)) {
                for (text in listOf(p.text, p.secondary, p.accent, p.warning)) {
                    assertTrue("night=$night contrast=${contrast(text, surface)}", contrast(text, surface) >= 4.5)
                }
            }
            assertTrue(contrast(p.accentInk, p.accent) >= 4.5)
        }
    }
}
