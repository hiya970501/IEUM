package com.example.version1

import android.content.Context
import android.graphics.Typeface

/**
 * Native Android mapping of the public Pleos Connect design foundations.
 *
 * Grayscale values come from the official Colors sheet; Asta Sans is the font
 * linked by the official Typography page. The accent remains Afterglow's own
 * brand color. Mapping the published hierarchy to sp is an app design choice.
 */
object PleosDesign {
    val background: Int = 0xFF131417.toInt() // Dark basic_00
    val surface: Int = 0xFF1D1E21.toInt() // Dark basic_50
    val raised: Int = 0xFF313236.toInt() // Dark basic_100
    val line: Int = 0xFF44464E.toInt() // Dark basic_200
    val text: Int = 0xFFF7F8FA.toInt() // Dark basic_800
    val secondary: Int = 0xFFC7C9CE.toInt() // Dark basic_500
    val muted: Int = 0xFFA4A8AE.toInt() // Dark basic_400
    val active: Int = 0xFF00E074.toInt() // Official dark informative active
    val info: Int = 0xFF0082FF.toInt() // Official dark informative positive
    val negative: Int = 0xFFFF4C28.toInt() // Official dark informative negative

    // Afterglow brand accents are intentionally separate from official tokens.
    val accent: Int = 0xFFA9EDD3.toInt()
    val accentInk: Int = 0xFF0D2820.toInt()

    const val fontName = "Asta Sans"
    const val titleLargeSp = 40f
    const val titleMediumSp = 36f
    const val titleSmallSp = 32f
    const val bodyLargeSp = 30f
    const val bodyMediumSp = 28f
    const val bodySmallSp = 26f
    const val labelMediumSp = 24f
    const val labelSmallSp = 20f

    // Extracted from the official .fig form_factor/connect-s variables.
    // These map Figma design units to dp for the current Connect-S demo.
    const val buttonMediumHeightDp = 80
    const val buttonMediumRadiusDp = 24
    const val buttonPaddingHorizontalDp = 32
    const val buttonPaddingVerticalDp = 16
    const val buttonMediumGapDp = 14
    const val buttonMediumMinWidthDp = 120
    const val buttonLargeHeightDp = 100
    const val buttonLargeRadiusDp = 32
    const val switchWidthDp = 80
    const val switchHeightDp = 48
    const val switchKnobDp = 32
    const val switchPaddingDp = 8
    const val switchRadiusDp = 16
    const val switchKnobRadiusDp = 8
    val switchOnTrack: Int = 0xFF00E074.toInt()
    val switchOffTrack: Int = 0x1AFFFFFF
    val switchDisabledTrack: Int = 0x1AFFFFFF
    val switchOnKnob: Int = 0xFFFFFFFF.toInt()
    val switchOffKnob: Int = 0xFFA4A8AE.toInt()
    val switchDisabledKnob: Int = 0x33FFFFFF
    val switchPressedOverlay: Int = 0x1AFFFFFF

    private val fonts = mutableMapOf<Int, Typeface>()

    /** The bundled variable font covers Korean and Latin, with weights 300–800. */
    fun typeface(context: Context, weight: Int = 400): Typeface = synchronized(fonts) {
        val supportedWeight = weight.coerceIn(300, 800)
        fonts.getOrPut(supportedWeight) {
            Typeface.create(context.resources.getFont(R.font.asta_sans_variable), supportedWeight, false)
        }
    }
}
