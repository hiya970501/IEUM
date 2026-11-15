package com.example.version1

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.Switch
import kotlin.math.min

/** Native drag/tap switch with a pill track and circular thumb. */
object PleosSwitchStyle {
    fun apply(control: Switch, large: Boolean = false) {
        control.thumbTintList = null
        control.trackTintList = null
        control.trackDrawable = SwitchPart(control.resources, false, large)
        control.thumbDrawable = SwitchPart(control.resources, true, large)
        control.switchMinWidth = ((if (large) 112 else 80) * control.resources.displayMetrics.density).toInt()
        control.splitTrack = false
        control.showText = false
        control.layoutDirection = View.LAYOUT_DIRECTION_LTR
    }

    private class SwitchPart(private val resources: Resources, private val knob: Boolean,
                             private val large: Boolean) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var overallAlpha = 255
        private var filter: ColorFilter? = null
        private fun dp(value: Int) = value * resources.displayMetrics.density

        override fun draw(canvas: Canvas) {
            val enabled = android.R.attr.state_enabled in state
            val checked = android.R.attr.state_checked in state
            val pressed = android.R.attr.state_pressed in state
            val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            val palette = PleosPalette.forNight(night)
            // Do not turn an OFF switch white-on-white in the day theme, or erase a
            // disabled checked state. Thumb position and accessibility preserve state.
            val color = if (knob) {
                if (checked) palette.accentInk else palette.background
            } else if (checked) palette.accent else palette.secondary
            paint.color = color
            paint.alpha = Color.alpha(color) * overallAlpha / 255
            paint.colorFilter = filter
            rect.set(bounds)
            if (knob) {
                val side = min(min(rect.width(), rect.height()), dp(if (large) 56 else 40))
                rect.set(rect.centerX() - side / 2, rect.centerY() - side / 2,
                    rect.centerX() + side / 2, rect.centerY() + side / 2)
            }
            canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
            if (pressed && enabled && !knob) {
                paint.color = 0x1AFFFFFF
                paint.alpha = 26 * overallAlpha / 255
                canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
            }
        }

        override fun getIntrinsicWidth() = dp(if (knob) { if (large) 56 else 40 } else { if (large) 112 else 80 }).toInt()
        override fun getIntrinsicHeight() = dp(if (knob) { if (large) 56 else 40 } else { if (large) 64 else 48 }).toInt()
        override fun getPadding(padding: Rect): Boolean {
            val inset = if (knob) 0 else dp(4).toInt()
            padding.set(inset, inset, inset, inset)
            return !knob
        }
        override fun isStateful() = true
        override fun onStateChange(state: IntArray): Boolean { invalidateSelf(); return true }
        override fun setAlpha(alpha: Int) { overallAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
        override fun getAlpha() = overallAlpha
        override fun setColorFilter(colorFilter: ColorFilter?) { filter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
