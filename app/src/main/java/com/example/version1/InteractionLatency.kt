package com.example.version1

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.Window
import java.util.ArrayDeque

/** Debug-only input-to-frame completion observation; not a physical display latency claim.
 * Event timestamps include time waiting in the main queue. No coordinates or content.
 */
internal class InteractionLatency(private val window: Window, enabled: Boolean) {
    private data class Input(val kind: String, val nanos: Long, var handledNanos: Long = Long.MAX_VALUE)
    private val pending = ArrayDeque<Input>()
    private var current: Input? = null
    private val listener = if (enabled) Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
        val start = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        if (start > 0 && total >= 0) {
            // A frame already in flight when the touch arrived is not its UI response.
            while (pending.isNotEmpty() && pending.first.handledNanos <= start) {
                val input = pending.removeFirst()
                val millis = (start + total - input.nanos) / 1_000_000.0
                Log.i("AfterglowLatency", "input=${input.kind} frameCompletionMs=$millis within250=${millis <= 250} droppedReports=$dropped")
            }
        }
    } else null
    init { listener?.let { window.addOnFrameMetricsAvailableListener(it, Handler(Looper.getMainLooper())) } }
    fun input(event: MotionEvent, observeMainFrame: Boolean = true) {
        if (listener == null) return
        val kind = when (event.actionMasked) { MotionEvent.ACTION_DOWN -> "down"; MotionEvent.ACTION_UP -> "up"; else -> return }
        if (pending.size >= 16) pending.removeFirst()
        val eventNanos = System.nanoTime() - (SystemClock.uptimeMillis() - event.eventTime) * 1_000_000
        current = Input(kind, eventNanos).also { if (observeMainFrame) pending.addLast(it) }
    }
    fun handled() { current?.handledNanos = System.nanoTime() }
    fun observeDialog(dialog: Window) {
        val input = current ?: return
        if (listener == null) return
        // A dialog shown by a later async response cannot be attributed to an old tap.
        if (System.nanoTime() - input.nanos > 1_000_000_000L) return
        var firstFrame: Window.OnFrameMetricsAvailableListener? = null
        var reported = false
        firstFrame = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            // Reports already queued by RenderThread can arrive after unregistration.
            if (reported) return@OnFrameMetricsAvailableListener
            val start = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (start > 0 && total >= 0) {
                reported = true
                val millis = (start + total - input.nanos) / 1_000_000.0
                Log.i("AfterglowLatency", "input=dialogFirstFrame frameCompletionMs=$millis within250=${millis <= 250} droppedReports=$dropped")
                firstFrame?.let { runCatching { dialog.removeOnFrameMetricsAvailableListener(it) } }
                firstFrame = null
            }
        }
        firstFrame?.let { dialog.addOnFrameMetricsAvailableListener(it, Handler(Looper.getMainLooper())) }
    }
    fun observeInlinePanel(root: android.view.View) {
        val input = current ?: return
        if (listener == null || System.nanoTime() - input.nanos > 1_000_000_000L) return
        if (android.os.Build.VERSION.SDK_INT >= 29 && root.isHardwareAccelerated) {
            root.viewTreeObserver.registerFrameCommitCallback {
                val millis = (System.nanoTime() - input.nanos) / 1_000_000.0
                Log.i("AfterglowLatency", "input=inlinePanelCommitted inputToCommitCallbackMs=$millis within250=${millis <= 250}; notPhysicalScanout=true")
            }
        }
    }
    fun close() { listener?.let(window::removeOnFrameMetricsAvailableListener); pending.clear() }
}
