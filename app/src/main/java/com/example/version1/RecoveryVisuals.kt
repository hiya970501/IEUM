package com.example.version1

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A consistent 48-unit line icon family. Callers may size the drawable to any dp bounds. */
class RecoveryGlyphDrawable(private val kind: String, private val color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.1f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var drawableAlpha = 255
    private var filter: ColorFilter? = null

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val side = min(bounds.width(), bounds.height()).toFloat()
        canvas.save()
        canvas.translate(bounds.left + (bounds.width() - side) / 2f, bounds.top + (bounds.height() - side) / 2f)
        canvas.scale(side / 48f, side / 48f)
        paint.color = color
        paint.alpha = Color.alpha(color) * drawableAlpha / 255
        paint.colorFilter = filter
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3.1f
        when (kind) {
            "run" -> {
                canvas.drawCircle(28f, 10f, 3f, paint)
                path(canvas) { moveTo(14f, 21f); lineTo(21f, 16f); lineTo(29f, 20f); lineTo(36f, 21f) }
                path(canvas) { moveTo(27f, 19f); lineTo(23f, 29f); lineTo(31f, 34f); lineTo(35f, 41f) }
                path(canvas) { moveTo(23f, 29f); lineTo(17f, 36f); lineTo(9f, 37f) }
            }
            "water" -> {
                path(canvas) { moveTo(24f, 6f); cubicTo(20f, 12f, 16f, 16f, 16f, 21f); cubicTo(16f, 31f, 32f, 31f, 32f, 21f); cubicTo(32f, 16f, 28f, 12f, 24f, 6f); close() }
                wave(canvas, 7f, 35f, 34f, 3f)
                wave(canvas, 7f, 42f, 34f, 3f)
            }
            "hike" -> {
                path(canvas) { moveTo(6f, 38f); lineTo(21f, 12f); lineTo(35f, 38f); close() }
                path(canvas) { moveTo(29f, 27f); lineTo(34f, 19f); lineTo(43f, 38f); lineTo(35f, 38f) }
                path(canvas) { moveTo(16f, 21f); lineTo(20f, 24f); lineTo(25f, 20f) }
            }
            "heat" -> {
                for (x in floatArrayOf(13f, 24f, 35f)) path(canvas) {
                    moveTo(x, 39f); cubicTo(x - 7f, 32f, x + 7f, 26f, x, 19f); cubicTo(x - 3f, 16f, x - 3f, 13f, x, 9f)
                }
            }
            "fan" -> {
                for (rotation in floatArrayOf(0f, 120f, 240f)) {
                    canvas.save()
                    canvas.rotate(rotation, 24f, 24f)
                    path(canvas) {
                        moveTo(24f, 20f); cubicTo(18f, 14f, 20f, 5f, 28f, 7f)
                        cubicTo(39f, 9f, 37f, 18f, 27f, 23f)
                    }
                    canvas.restore()
                }
                canvas.drawCircle(24f, 24f, 4f, paint)
            }
            "air" -> {
                path(canvas) { moveTo(7f, 17f); lineTo(28f, 17f); cubicTo(37f, 17f, 37f, 7f, 30f, 8f) }
                path(canvas) { moveTo(7f, 25f); lineTo(37f, 25f); cubicTo(44f, 25f, 44f, 35f, 37f, 35f) }
                path(canvas) { moveTo(7f, 33f); lineTo(22f, 33f); cubicTo(28f, 33f, 28f, 41f, 22f, 40f) }
            }
            "music" -> {
                path(canvas) { moveTo(18f, 34f); lineTo(18f, 13f); lineTo(36f, 8f); lineTo(36f, 29f) }
                canvas.drawOval(RectF(7f, 30f, 18f, 39f), paint)
                canvas.drawOval(RectF(25f, 25f, 36f, 34f), paint)
                canvas.drawLine(18f, 20f, 36f, 15f, paint)
            }
            "watch" -> {
                canvas.drawRoundRect(RectF(13f, 11f, 35f, 37f), 7f, 7f, paint)
                path(canvas) { moveTo(18f, 11f); lineTo(19f, 5f); lineTo(29f, 5f); lineTo(30f, 11f) }
                path(canvas) { moveTo(18f, 37f); lineTo(19f, 43f); lineTo(29f, 43f); lineTo(30f, 37f) }
                path(canvas) { moveTo(18f, 25f); lineTo(22f, 25f); lineTo(25f, 20f); lineTo(28f, 28f); lineTo(31f, 24f) }
                canvas.drawLine(38f, 20f, 38f, 25f, paint)
            }
            "arrow" -> {
                canvas.drawLine(9f, 24f, 38f, 24f, paint)
                path(canvas) { moveTo(28f, 14f); lineTo(38f, 24f); lineTo(28f, 34f) }
            }
            "drop" -> path(canvas) {
                moveTo(24f, 6f); cubicTo(19f, 14f, 12f, 21f, 12f, 29f)
                cubicTo(12f, 45f, 36f, 45f, 36f, 29f); cubicTo(36f, 21f, 29f, 14f, 24f, 6f); close()
            }
            "play" -> path(canvas) { moveTo(17f, 10f); lineTo(37f, 24f); lineTo(17f, 38f); close() }
            "check" -> path(canvas) { moveTo(10f, 25f); lineTo(20f, 35f); lineTo(39f, 14f) }
            "stop" -> canvas.drawRoundRect(RectF(12f, 12f, 36f, 36f), 4f, 4f, paint)
            "heart" -> path(canvas) {
                moveTo(24f, 39f); cubicTo(20f, 35f, 7f, 26f, 7f, 18f)
                cubicTo(7f, 8f, 18f, 5f, 24f, 14f); cubicTo(30f, 5f, 41f, 8f, 41f, 18f)
                cubicTo(41f, 26f, 28f, 35f, 24f, 39f); close()
            }
            "settings" -> {
                val gear = Path()
                for (i in 0 until 48) {
                    val angle = Math.toRadians((i * 7.5 - 90.0))
                    val radius = if (i % 6 in 1..3) 18f else 14.4f
                    val x = 24f + cos(angle).toFloat() * radius
                    val y = 24f + sin(angle).toFloat() * radius
                    if (i == 0) gear.moveTo(x, y) else gear.lineTo(x, y)
                }
                gear.close()
                canvas.drawPath(gear, paint)
                canvas.drawCircle(24f, 24f, 6f, paint)
            }
            else -> canvas.drawCircle(24f, 24f, 15f, paint)
        }
        canvas.restore()
    }

    private fun path(canvas: Canvas, build: Path.() -> Unit) = canvas.drawPath(Path().apply(build), paint)

    private fun wave(canvas: Canvas, x: Float, y: Float, width: Float, amplitude: Float) {
        val segment = width / 2f
        path(canvas) {
            moveTo(x, y)
            cubicTo(x + segment * .25f, y - amplitude, x + segment * .75f, y + amplitude, x + segment, y)
            cubicTo(x + segment * 1.25f, y - amplitude, x + segment * 1.75f, y + amplitude, x + width, y)
        }
    }

    override fun setAlpha(alpha: Int) { drawableAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun getAlpha(): Int = drawableAlpha
    override fun setColorFilter(colorFilter: ColorFilter?) { filter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = (48f * Resources.getSystem().displayMetrics.density).toInt()
    override fun getIntrinsicHeight(): Int = intrinsicWidth
}
