package com.example.version1

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.VectorDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.TextView
import android.widget.ImageView
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** QA only. Samples drawable backgrounds, never glyphs, private text or screenshots.
 * Three points are engineering samples, not exhaustive gradient/overlay/panel certification.
 * Unsupported composition remains unknown instead of assuming a white background. */
internal object VisibleTextContrastAudit {
    fun inspect(view: TextView): JSONObject = background(view, view.currentTextColor,
        view.totalPaddingLeft, view.totalPaddingRight)

    fun icon(view: ImageView): JSONObject {
        val result = JSONObject().put("scope", "solid-vector-tint/background-proxy; not per-pixel or 3D certification")
        val tint = view.imageTintList ?: return result.put("known", false).put("reason", "no-solid-image-tint")
        if (view.drawable !is VectorDrawable || view.drawable.alpha != 255 ||
            (view.imageTintMode != null && view.imageTintMode != PorterDuff.Mode.SRC_IN))
            return result.put("known", false).put("reason", "unsupported-image-composition")
        return background(view, tint.getColorForState(view.drawableState, tint.defaultColor),
            view.paddingLeft, view.paddingRight)
            .put("scope", "solid-vector-tint/background-proxy; not per-pixel or 3D certification")
            .put("drawablePixelAlphaVerified", false)
    }

    private fun background(view: View, foregroundColor: Int, paddingLeft: Int, paddingRight: Int): JSONObject {
        val result = JSONObject().put("scope", "three-background-drawable-samples")
            .put("enabled", view.isEnabled).put("selected", view.isSelected)
        val chain = generateSequence(view as View?) { it.parent as? View }.toList().asReversed()
        if (chain.any { it.alpha != 1f || it.foreground != null })
            return result.put("known", false).put("reason", "alpha-or-foreground")
        if (chain.any { node -> node.background?.let {
                it !is ColorDrawable && it !is GradientDrawable && it !is RippleDrawable &&
                    !(it is StateListDrawable && (it.current is ColorDrawable || it.current is GradientDrawable))
            } == true }) return result.put("known", false).put("reason", "unsupported-drawable")
        val visible = Rect()
        if (!view.getLocalVisibleRect(visible) || visible.isEmpty)
            return result.put("known", false).put("reason", "not-visible")
        val origin = IntArray(2); view.getLocationOnScreen(origin)
        // GlobalVisibleRect is in the root's coordinate space, which can differ
        // from screen coordinates in the OEM embedded application window.
        visible.offset(origin[0], origin[1])
        // Probe inside the laid-out text area, away from rounded card corners.
        val left = max(visible.left, origin[0] + paddingLeft)
        val right = min(visible.right, origin[0] + view.width - paddingRight)
        val y = visible.centerY()
        if (right <= left) return result.put("known", false).put("reason", "no-text-width")
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            var minimum = Double.POSITIVE_INFINITY
            for (fraction in listOf(.25f, .5f, .75f)) {
                val x = left + (right - left) * fraction
                var background = Color.TRANSPARENT
                for (node in chain) {
                    val drawable = node.background ?: continue
                    if (drawable.bounds.isEmpty)
                        return result.put("known", false).put("reason", "unlaid-background")
                    val location = IntArray(2); node.getLocationOnScreen(location)
                    bitmap.eraseColor(Color.TRANSPARENT)
                    canvas.save()
                    canvas.translate(location[0] - x, location[1].toFloat() - y)
                    drawable.draw(canvas)
                    canvas.restore()
                    background = over(bitmap.getPixel(0, 0), background)
                }
                if (Color.alpha(background) != 255)
                    return result.put("known", false).put("reason", "nonopaque-scene-background")
                val foreground = over(foregroundColor, background)
                val a = luminance(foreground); val b = luminance(background)
                minimum = min(minimum, (max(a, b) + .05) / (min(a, b) + .05))
            }
            return result.put("known", true).put("samples", 3).put("minimumRatio", minimum)
                .put("belowInternal45Floor", minimum < 4.5)
        } finally { bitmap.recycle() }
    }

    private fun over(front: Int, back: Int): Int {
        val a = Color.alpha(front) / 255.0; val b = Color.alpha(back) / 255.0
        val combined = a + b * (1 - a)
        if (combined == 0.0) return Color.TRANSPARENT
        fun channel(f: Int, r: Int) = ((f * a + r * b * (1 - a)) / combined).toInt().coerceIn(0, 255)
        return Color.argb((combined * 255).toInt().coerceIn(0, 255),
            channel(Color.red(front), Color.red(back)), channel(Color.green(front), Color.green(back)),
            channel(Color.blue(front), Color.blue(back)))
    }

    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= .04045) c / 12.92 else ((c + .055) / 1.055).pow(2.4)
        }
        return .2126 * channel(Color.red(color)) + .7152 * channel(Color.green(color)) +
            .0722 * channel(Color.blue(color))
    }
}
