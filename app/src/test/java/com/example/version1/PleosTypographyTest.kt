package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class PleosTypographyTest {
    @Test fun millimeterTargetsRoundUpAtBothSupportedDensities() {
        assertEquals(32,PleosTypography.requiredInkPixels(5f,160f,1f))
        assertEquals(40,PleosTypography.requiredInkPixels(5f,200f,1.25f))
        assertEquals(19,PleosTypography.requiredInkPixels(3f,160f,1f))
        assertEquals(24,PleosTypography.requiredInkPixels(3f,200f,1.25f))
    }
    @Test fun malformedDpiFallsBackWithoutTreatingMissingDensityAsProof() {
        assertEquals(40,PleosTypography.requiredInkPixels(5f,Float.NaN,1.25f))
        assertThrows(IllegalArgumentException::class.java) { PleosTypography.requiredInkPixels(5f,160f,0f) }
        assertThrows(IllegalArgumentException::class.java) { PleosTypography.requiredInkPixels(Float.NaN,160f,1f) }
    }
}
