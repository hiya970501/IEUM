package com.example.version1

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import kotlin.math.min

/**
 * Native plan-view seat map: a handful of small Views and paths, no bitmap,
 * animation loop, extra GL context or 3D model. Every entire seat is an action
 * target (80dp minimum at the supported 350dp map width). For occupied seats
 * the X marks that same large removal target, rather than a tiny independent
 * button overlapping the next seat. Native child Views expose each action to
 * accessibility services, keyboard and rotary focus.
 */
class SeatMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {
    var onAdd: ((OccupancySeat) -> Unit)? = null
    var onRemove: ((OccupancySeat) -> Unit)? = null

    private var state = OccupancyStateStore().snapshot
    private var rightHandDrive = false
    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shellRect = RectF()
    private val frontPath = Path()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = PleosDesign.typeface(context, 500)
        textAlign = Paint.Align.CENTER
    }
    private val cells = mutableListOf<SeatCell>()
    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    init {
        setWillNotDraw(false)
        clipChildren = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        rebuildCells()
    }

    fun submit(snapshot: OccupancySnapshot, rightHandDrive: Boolean = false) {
        if (state == snapshot && this.rightHandDrive == rightHandDrive) return
        val layoutChanged = state.layout != snapshot.layout
        state = snapshot
        this.rightHandDrive = rightHandDrive
        if (layoutChanged) rebuildCells()
        else cells.forEachIndexed { index, cell -> cell.bind(state.seats[index]) }
        if (layoutChanged) requestLayout()
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        // View construction can invoke this before the Kotlin fields are ready;
        // walking native children also avoids retaining a second interaction list.
        for (index in 0 until childCount) {
            getChildAt(index).apply {
                isEnabled = enabled
                isClickable = enabled
                isFocusable = enabled
            }
        }
    }

    private fun rebuildCells() {
        removeAllViews()
        cells.clear()
        state.seats.forEach { item ->
            val cell = SeatCell(context).apply {
                bind(item)
                isEnabled = this@SeatMapView.isEnabled
                isClickable = this@SeatMapView.isEnabled
                isFocusable = this@SeatMapView.isEnabled
            }
            cells += cell
            addView(cell)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rowCount = state.layout.seats.maxOf { it.row }
        val desiredHeight = dp(64 + rowCount * 116)
        val measuredWidth = resolveSize(dp(350), widthMeasureSpec)
        val measuredHeight = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)
        val contentWidth = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0)
        val contentHeight = (measuredHeight - paddingTop - paddingBottom - dp(56)).coerceAtLeast(0)
        val seatWidth = min(contentWidth * .274f, dp(108).toFloat()).toInt()
        val seatHeight = min(contentHeight / rowCount, dp(116)).coerceAtLeast(0)
        cells.forEach { it.measure(MeasureSpec.makeMeasureSpec(seatWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(seatHeight, MeasureSpec.EXACTLY)) }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val contentWidth = width - paddingLeft - paddingRight
        val contentHeight = height - paddingTop - paddingBottom - dp(56)
        val rowCount = state.layout.seats.maxOf { it.row }
        val rowHeight = contentHeight.toFloat() / rowCount
        cells.forEach { cell ->
            val item = cell.item
            val normalizedX = when (item.seat.column) { 0 -> .19f; 1 -> .5f; else -> .81f }
            val centerX = paddingLeft + contentWidth * normalizedX
            val centerY = paddingTop + dp(48) + rowHeight * (item.seat.row - .5f)
            val cellLeft = (centerX - cell.measuredWidth / 2f).toInt()
            val cellTop = (centerY - cell.measuredHeight / 2f).toInt()
            cell.layout(cellLeft, cellTop, cellLeft + cell.measuredWidth, cellTop + cell.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val edge = dp(5).toFloat()
        shellRect.set(paddingLeft + edge, paddingTop + dp(20).toFloat(),
            width - paddingRight - edge, height - paddingBottom - edge)
        shellPaint.style = Paint.Style.FILL
        shellPaint.color = 0xFF20252A.toInt()
        canvas.drawRoundRect(shellRect, dp(54).toFloat(), dp(54).toFloat(), shellPaint)
        shellPaint.style = Paint.Style.STROKE
        shellPaint.strokeWidth = dp(1).toFloat()
        shellPaint.color = 0xFF53605F.toInt()
        canvas.drawRoundRect(shellRect, dp(54).toFloat(), dp(54).toFloat(), shellPaint)
        val cx = shellRect.centerX()
        val frontY = shellRect.top + dp(12)
        frontPath.reset()
        frontPath.moveTo(shellRect.left + dp(45), frontY + dp(19))
        frontPath.quadTo(cx, frontY - dp(7), shellRect.right - dp(45), frontY + dp(19))
        canvas.drawPath(frontPath, shellPaint)
        textPaint.color = PleosDesign.muted
        textPaint.textSize = 14 * scaledDensity
        canvas.drawText("FRONT", cx, frontY + dp(18), textPaint)
        // This wheel is a preview marker; physical seat labels/identities never move.
        val driverX = paddingLeft + (width - paddingLeft - paddingRight) *
            if (rightHandDrive) .81f else .19f
        val wheelY = paddingTop + dp(42).toFloat()
        shellPaint.color = PleosDesign.secondary
        shellPaint.strokeWidth = dp(2).toFloat()
        canvas.drawCircle(driverX, wheelY, dp(9).toFloat(), shellPaint)
        canvas.drawLine(driverX - dp(7), wheelY - dp(1), driverX + dp(7), wheelY - dp(1), shellPaint)
        canvas.drawLine(driverX, wheelY, driverX, wheelY + dp(7), shellPaint)
    }

    private fun seatLabel(seat: OccupancySeat): String = when (seat) {
        OccupancySeat.ROW_1_LEFT -> "앞좌석 좌측"
        OccupancySeat.ROW_1_RIGHT -> "앞좌석 우측"
        OccupancySeat.ROW_2_LEFT -> "2열 좌측"
        OccupancySeat.ROW_2_CENTER -> "2열 중앙"
        OccupancySeat.ROW_2_RIGHT -> "2열 우측"
        OccupancySeat.ROW_3_LEFT -> "3열 좌측"
        OccupancySeat.ROW_3_RIGHT -> "3열 우측"
    }

    private fun dp(value: Int): Int = (value * density + .5f).toInt()

    private inner class SeatCell(context: Context) : View(context) {
        lateinit var item: OccupancySeatState
            private set
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = PleosDesign.typeface(context, 500)
            textAlign = Paint.Align.CENTER
        }

        init {
            isClickable = true
            isFocusable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            val typed = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = typed.getDrawable(0)
            typed.recycle()
            setOnClickListener {
                if (item.occupied) onRemove?.invoke(item.seat) else onAdd?.invoke(item.seat)
            }
        }

        fun bind(next: OccupancySeatState) {
            item = next
            contentDescription = "${seatLabel(next.seat)}, ${next.sourceLabel}, " +
                if (next.occupied) "탑승자 제외" else "탑승자 수동 추가"
            invalidate()
            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val seatBottom = height - dp(35).toFloat()
            val occupied = item.occupied
            val base = if (occupied) 0xFF365448.toInt() else PleosDesign.raised
            paint.style = Paint.Style.FILL
            paint.color = base
            rect.set(cx - dp(19), dp(4).toFloat(), cx + dp(19), dp(19).toFloat())
            canvas.drawRoundRect(rect, dp(5).toFloat(), dp(5).toFloat(), paint)
            rect.set(dp(8).toFloat(), dp(16).toFloat(), width - dp(8).toFloat(), seatBottom)
            canvas.drawRoundRect(rect, dp(13).toFloat(), dp(13).toFloat(), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = if (occupied) PleosDesign.accent else PleosDesign.line
            canvas.drawRoundRect(rect, dp(13).toFloat(), dp(13).toFloat(), paint)
            paint.color = if (occupied) 0xFF6F9C86.toInt() else 0xFF53595F.toInt()
            canvas.drawLine(rect.left + dp(10), seatBottom - dp(13), rect.right - dp(10), seatBottom - dp(13), paint)
            val iconY = (dp(20) + seatBottom) / 2f - dp(4)
            if (occupied) {
                paint.style = Paint.Style.FILL
                paint.color = PleosDesign.accent
                canvas.drawCircle(cx, iconY - dp(12), dp(8).toFloat(), paint)
                rect.set(cx - dp(13), iconY, cx + dp(13), iconY + dp(19))
                canvas.drawRoundRect(rect, dp(10).toFloat(), dp(10).toFloat(), paint)
                // X is visibly attached to the avatar's top-right corner.
                val badgeX = width - dp(14).toFloat()
                val badgeY = dp(18).toFloat()
                paint.color = PleosDesign.text
                canvas.drawCircle(badgeX, badgeY, dp(12).toFloat(), paint)
                paint.color = PleosDesign.accentInk
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2).toFloat()
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(badgeX - dp(4), badgeY - dp(4), badgeX + dp(4), badgeY + dp(4), paint)
                canvas.drawLine(badgeX + dp(4), badgeY - dp(4), badgeX - dp(4), badgeY + dp(4), paint)
            } else {
                paint.color = PleosDesign.secondary
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2).toFloat()
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(cx - dp(10), iconY, cx + dp(10), iconY, paint)
                canvas.drawLine(cx, iconY - dp(10), cx, iconY + dp(10), paint)
            }
            labelPaint.color = PleosDesign.text
            labelPaint.textSize = 16 * scaledDensity
            canvas.drawText(seatLabel(item.seat), cx, height - dp(18).toFloat(), labelPaint)
            labelPaint.color = when (item.source) {
                OccupancySource.MANUAL_ADDED, OccupancySource.MANUAL_REMOVED -> PleosDesign.accent
                else -> PleosDesign.muted
            }
            labelPaint.textSize = 13 * scaledDensity
            canvas.drawText(item.sourceLabel, cx, height - dp(1).toFloat(), labelPaint)
            if (isFocused) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2).toFloat()
                paint.color = PleosDesign.accent
                rect.set(dp(1).toFloat(), dp(1).toFloat(), width - dp(1).toFloat(), height - dp(1).toFloat())
                canvas.drawRoundRect(rect, dp(13).toFloat(), dp(13).toFloat(), paint)
            }
        }
    }
}
