package com.example.version1

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.min

/**
 * A schematic setting preview, not a CFD result or a measured airflow field.
 * All paths and direction markers are cached when settings change. The artwork is deliberately
 * static: it remains legible in screenshots and never starts an animation or a frame callback.
 * Labels and meaningful vehicle settings belong to the accessible parent UI.
 */
class CabinAirflowView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var fan = 2
    private var heat = 0
    private var ventilation = 0
    private var temperature = 24f
    private var active = false
    private var wet = false
    private var dark = true

    private data class Arrow(val x: Float, val y: Float, val dx: Float, val dy: Float, val size: Float)
    private data class Stream(val path: Path, val width: Float, val alpha: Int, val arrows: List<Arrow>)
    private var streams: List<Stream> = emptyList()
    private var seatStreams: List<Stream> = emptyList()
    private var warmStreams: List<Stream> = emptyList()
    private var jetShader: Shader? = null
    private var envelopeShader: Shader? = null
    private var seatJetShader: Shader? = null
    private var seatHeatShader: Shader? = null
    private var ambientShader: Shader? = null
    private val seatHalo = RectF(316f, 91f, 440f, 224f)
    private val ventBounds = RectF(143f, 129f, 166f, 143f)
    private val headrestBounds = RectF(397f, 82f, 416f, 103f)
    private val sourceHaloBounds = RectF(141f, 114f, 186f, 160f)
    private val airflowEnvelope = shape {
        moveTo(163f, 131f)
        cubicTo(223f, 111f, 297f, 83f, 356f, 106f)
        cubicTo(393f, 119f, 407f, 136f, 432f, 151f)
        cubicTo(397f, 175f, 358f, 189f, 318f, 174f)
        cubicTo(265f, 154f, 211f, 150f, 163f, 142f)
        close()
    }
    private val cabinOutline = shape {
        moveTo(52f, 125f)
        cubicTo(74f, 91f, 89f, 61f, 118f, 46f)
        cubicTo(226f, 27f, 385f, 28f, 469f, 45f)
        cubicTo(496f, 51f, 512f, 78f, 515f, 107f)
        lineTo(520f, 202f)
    }
    private val windowInset = shape {
        moveTo(83f, 111f)
        lineTo(124f, 59f)
        cubicTo(230f, 43f, 388f, 43f, 461f, 57f)
        cubicTo(484f, 63f, 496f, 82f, 500f, 105f)
    }
    private val dashboard = shape {
        moveTo(40f, 134f)
        cubicTo(68f, 121f, 111f, 121f, 142f, 127f)
        lineTo(167f, 144f)
        lineTo(163f, 173f)
        lineTo(134f, 188f)
        lineTo(59f, 188f)
    }
    private val dashboardInset = shape {
        moveTo(58f, 142f)
        cubicTo(90f, 134f, 115f, 135f, 133f, 141f)
        lineTo(145f, 154f)
        lineTo(143f, 168f)
    }
    private val seat = shape {
        moveTo(397f, 107f)
        cubicTo(393f, 105f, 388f, 109f, 386f, 116f)
        lineTo(371f, 174f)
        cubicTo(370f, 180f, 374f, 185f, 382f, 187f)
        lineTo(391f, 190f)
        lineTo(346f, 190f)
        cubicTo(337f, 190f, 331f, 195f, 330f, 203f)
        cubicTo(348f, 211f, 381f, 214f, 399f, 210f)
        cubicTo(411f, 206f, 417f, 195f, 414f, 184f)
        lineTo(405f, 176f)
        lineTo(417f, 118f)
        cubicTo(419f, 111f, 413f, 108f, 407f, 107f)
        close()
    }
    private val seatInner = shape {
        moveTo(402f, 117f)
        lineTo(389f, 170f)
        cubicTo(387f, 179f, 392f, 184f, 401f, 187f)
        moveTo(344f, 200f)
        cubicTo(359f, 205f, 382f, 207f, 398f, 202f)
    }
    private val heatedSeatFace = shape {
        moveTo(391f, 113f)
        cubicTo(396f, 109f, 405f, 110f, 410f, 114f)
        lineTo(395f, 173f)
        cubicTo(390f, 185f, 395f, 189f, 407f, 194f)
        cubicTo(394f, 208f, 360f, 208f, 337f, 201f)
        cubicTo(339f, 195f, 344f, 194f, 355f, 194f)
        lineTo(388f, 194f)
        cubicTo(374f, 187f, 374f, 179f, 378f, 165f)
        close()
    }
    private val occupant = shape {
        moveTo(349f, 109f)
        cubicTo(345f, 123f, 340f, 145f, 344f, 163f)
        cubicTo(346f, 176f, 359f, 184f, 371f, 187f)
        lineTo(325f, 190f)
        cubicTo(316f, 191f, 310f, 199f, 309f, 208f)
        lineTo(308f, 219f)
        moveTo(357f, 113f)
        lineTo(331f, 142f)
        lineTo(304f, 135f)
    }
    private val floor = shape {
        moveTo(48f, 225f); lineTo(510f, 225f)
        moveTo(369f, 214f); lineTo(369f, 224f)
        moveTo(398f, 214f); lineTo(398f, 224f)
    }
    private val returnContours = listOf(
        shape { moveTo(433f, 179f); cubicTo(410f, 217f, 269f, 229f, 204f, 202f); cubicTo(182f, 193f, 176f, 174f, 198f, 164f) },
        shape { moveTo(447f, 169f); cubicTo(449f, 216f, 258f, 241f, 190f, 207f) }
    )

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = null
        isClickable = false
        isFocusable = false
        rebuildFlow()
    }

    /** Values alter schematic appearance only; no measured velocity or temperature is inferred. */
    fun update(fan: Int, heat: Int, ventilation: Int, temperature: Float, active: Boolean, wet: Boolean, dark: Boolean) {
        val newFan = fan.coerceIn(0, 7)
        val newHeat = heat.coerceIn(0, 3)
        val newVentilation = ventilation.coerceIn(0, 3)
        val newTemperature = if (temperature.isFinite()) temperature.coerceIn(16f, 32f) else 24f
        if (this.fan == newFan && this.heat == newHeat && this.ventilation == newVentilation &&
            this.temperature == newTemperature && this.active == active && this.wet == wet && this.dark == dark) return
        this.fan = newFan
        this.heat = newHeat
        this.ventilation = newVentilation
        this.temperature = newTemperature
        this.active = active
        this.wet = wet
        this.dark = dark
        rebuildFlow()
        invalidate()
    }

    private fun rebuildFlow() {
        val cyan = if (dark) Color.rgb(105, 227, 227) else Color.rgb(23, 116, 130)
        val mint = if (dark) Color.rgb(187, 238, 208) else Color.rgb(36, 126, 100)
        // Temperature changes a subtle visual tint, never an asserted vent-air temperature.
        val tail = if (temperature >= 26f) Color.rgb(220, 217, 171) else mint
        jetShader = LinearGradient(159f, 135f, 430f, 147f,
            intArrayOf(alpha(cyan, 255), alpha(cyan, 238), alpha(tail, 170), alpha(tail, 45)),
            floatArrayOf(0f, .30f, .72f, 1f), Shader.TileMode.CLAMP)
        envelopeShader = LinearGradient(163f, 135f, 432f, 150f,
            intArrayOf(alpha(cyan, if (active) 33 else 25), alpha(mint, 14), Color.TRANSPARENT),
            floatArrayOf(0f, .57f, 1f), Shader.TileMode.CLAMP)
        seatJetShader = LinearGradient(366f, 201f, 353f, 123f,
            intArrayOf(alpha(cyan, 210), alpha(cyan, 130), alpha(cyan, 5)),
            floatArrayOf(0f, .65f, 1f), Shader.TileMode.CLAMP)
        seatHeatShader = LinearGradient(400f, 116f, 344f, 204f,
            intArrayOf(alpha(Color.rgb(255, 191, 117), 116 + heat * 20), alpha(Color.rgb(244, 158, 91), 60 + heat * 15)),
            null, Shader.TileMode.CLAMP)
        ambientShader = RadialGradient(384f, 166f, 94f,
            intArrayOf(alpha(Color.rgb(246, 172, 100), 11 + heat * 8), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)

        val count = if (fan == 0) 0 else 5 + fan.coerceAtMost(5)
        streams = (0 until count).map { index ->
            val t = if (count <= 1) 0f else (index.toFloat() / (count - 1) - .5f) * 2f
            val stream = shape {
                moveTo(164f, 136f + t * 4f)
                cubicTo(217f, 130f + t * 14f, 276f, 119f + t * 29f, 329f, 130f + t * 37f)
                cubicTo(365f, 135f + t * 38f, 391f, 145f + t * 30f, 429f, 152f + t * 22f)
            }
            val emphasis = index % 3 == 1
            Stream(stream, if (emphasis) 2.04f + fan * .072f else 1.14f + fan * .042f,
                if (emphasis) (if (active) 255 else 245) else (if (active) 190 else 155),
                arrows(stream, if (emphasis) floatArrayOf(.33f, .77f) else floatArrayOf(.56f), if (emphasis) 4.5f else 3.4f))
        }
        seatStreams = if (ventilation == 0) emptyList() else (0 until 3 + ventilation).map { index ->
            val startX = 343f + index * 9f
            val flow = shape {
                moveTo(startX, 197f + index * .6f)
                cubicTo(startX - 3f, 181f, startX - 5f, 155f, startX - 16f, 136f)
                cubicTo(startX - 23f, 126f, startX - 24f, 120f, startX - 23f, 115f)
            }
            Stream(flow, 1.4f + ventilation * .13f, 165 + ventilation * 20, arrows(flow, floatArrayOf(.38f, .72f), 3.6f))
        }
        warmStreams = if (heat == 0) emptyList() else (0 until 3 + heat).map { index ->
            val startX = 395f + index * 6f
            val flow = shape {
                moveTo(startX, 180f - index * 7f)
                cubicTo(startX + 13f, 157f - index * 7f, startX - 8f, 137f - index * 5f, startX + 6f, 111f - index * 4f)
            }
            Stream(flow, 1.2f, 110 + heat * 22, arrows(flow, floatArrayOf(.58f), 3.2f))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val scale = min(width / 560f, height / 252f)
        canvas.save()
        canvas.translate((width - 560f * scale) / 2f, (height - 252f * scale) / 2f)
        canvas.scale(scale, scale)
        val line = if (dark) Color.rgb(183, 213, 202) else Color.rgb(57, 98, 88)
        val cyan = if (dark) Color.rgb(134, 232, 227) else Color.rgb(23, 116, 130)
        val amber = if (dark) Color.rgb(247, 188, 124) else Color.rgb(165, 100, 30)

        // Fine structural outlines establish the car interior without a heavy illustration.
        stroke(line, 80, 1.3f)
        canvas.drawPath(cabinOutline, paint)
        stroke(line, 35, .8f)
        canvas.drawPath(windowInset, paint)
        canvas.drawLine(472f, 69f, 480f, 178f, paint)
        canvas.drawPath(floor, paint)
        stroke(line, 102, 1.35f)
        canvas.drawPath(dashboard, paint)
        stroke(line, 40, .85f)
        canvas.drawPath(dashboardInset, paint)
        canvas.drawCircle(293f, 137f, 18f, paint)
        canvas.drawLine(293f, 119f, 293f, 154f, paint)

        if (heat > 0) {
            fill(ambientShader)
            canvas.drawOval(seatHalo, paint)
        }
        if (fan > 0) {
            fill(envelopeShader)
            canvas.drawPath(airflowEnvelope, paint)
            stroke(cyan, if (active) 39 else 27, .85f)
            returnContours.forEach { canvas.drawPath(it, paint) }
            streams.forEach { drawStream(canvas, it, jetShader, cyan) }
        }

        // Translucent seat panels retain depth while allowing the airflow field to read.
        fill(null)
        paint.color = alpha(if (dark) Color.rgb(28, 65, 55) else Color.rgb(223, 237, 229), 205)
        canvas.drawPath(seat, paint)
        if (heat > 0) {
            fill(seatHeatShader)
            canvas.drawPath(heatedSeatFace, paint)
        }
        stroke(if (heat > 0) amber else line, if (heat > 0) 205 else 126, 1.6f)
        canvas.drawPath(seat, paint)
        canvas.drawRoundRect(headrestBounds, 5f, 5f, paint)
        stroke(if (heat > 0) amber else line, if (heat > 0) 133 else 57, 1f)
        canvas.drawPath(seatInner, paint)

        // A restrained occupant silhouette provides direction and scale, without health claims.
        stroke(line, 104, 1.6f)
        canvas.drawCircle(354f, 91f, 13.5f, paint)
        canvas.drawPath(occupant, paint)

        if (ventilation > 0) seatStreams.forEach { drawStream(canvas, it, seatJetShader, cyan) }
        if (heat > 0) warmStreams.forEach { drawStream(canvas, it, null, amber) }

        // Source detail and inlet ticks are drawn last, giving the airflow a clear origin.
        if (fan > 0) {
            fill(null)
            paint.color = alpha(cyan, if (active) 17 else 10)
            canvas.drawOval(sourceHaloBounds, paint)
        }
        stroke(cyan, if (fan > 0) 230 else 65, 1.6f)
        canvas.drawRoundRect(ventBounds, 3f, 3f, paint)
        for (i in 0..3) {
            val x = 148f + i * 4f
            canvas.drawLine(x, 133f, x, 139f, paint)
        }
        if (wet && fan > 0) {
            // A faint second inlet line only emphasizes the chosen dehumidifying preset.
            stroke(cyan, 86, .9f)
            canvas.drawLine(126f, 132f, 136f, 132f, paint)
            canvas.drawLine(120f, 140f, 136f, 140f, paint)
        }
        canvas.restore()
    }

    private fun drawStream(canvas: Canvas, stream: Stream, shader: Shader?, color: Int) {
        stroke(color, stream.alpha, stream.width)
        paint.shader = shader
        canvas.drawPath(stream.path, paint)
        paint.shader = null
        // Small, cached chevrons remain directional even with all animation disabled.
        stroke(color, (stream.alpha * .85f).toInt(), stream.width.coerceAtLeast(1.15f))
        stream.arrows.forEach { arrow ->
            val backX = arrow.x - arrow.dx * arrow.size
            val backY = arrow.y - arrow.dy * arrow.size
            val wing = arrow.size * .46f
            canvas.drawLine(backX - arrow.dy * wing, backY + arrow.dx * wing, arrow.x, arrow.y, paint)
            canvas.drawLine(backX + arrow.dy * wing, backY - arrow.dx * wing, arrow.x, arrow.y, paint)
        }
    }

    private fun arrows(path: Path, fractions: FloatArray, size: Float): List<Arrow> {
        val measure = PathMeasure(path, false)
        val point = FloatArray(2)
        val tangent = FloatArray(2)
        return fractions.map { fraction ->
            measure.getPosTan(measure.length * fraction, point, tangent)
            Arrow(point[0], point[1], tangent[0], tangent[1], size)
        }
    }

    private fun stroke(color: Int, opacity: Int, width: Float) {
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.color = alpha(color, opacity)
    }

    private fun fill(shader: Shader?) {
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.shader = shader
    }

    private fun alpha(color: Int, value: Int): Int = (color and 0x00ffffff) or (value.coerceIn(0, 255) shl 24)
    private fun shape(build: Path.() -> Unit) = Path().apply(build)
}
