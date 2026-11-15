package com.example.version1

import android.os.SystemClock
import android.os.Trace
import android.util.Log

/** Fixed stage names and timings only. No health, pairing, profile or request data. */
internal object PerformanceMarks {
    /** Whole-process figures only, at import boundaries; never called for each frame. */
    fun memory(stage: String, snapshot: ProcessMemorySnapshot?, futureBytes: Long = 0) {
        Log.i("AfterglowPerf", "memory=$stage rssBytes=${snapshot?.rssBytes} pssBytes=${snapshot?.pssBytes} " +
            "hwmBytes=${snapshot?.peakRssBytes} futureBytes=$futureBytes elapsedMs=${SystemClock.elapsedRealtime()}")
    }
    fun event(stage: String) {
        Log.i("AfterglowPerf", "event=$stage elapsedMs=${SystemClock.elapsedRealtime()}")
    }
    /** Debug caller only. eventTime/uptime includes input queueing and touch hold;
     * a committed content frame is not a physical scanout measurement. */
    fun inputContent(stage: String, downUptimeMs: Long, committedUptimeMs: Long) {
        if (downUptimeMs <= 0 || committedUptimeMs < downUptimeMs) return
        val millis = committedUptimeMs - downUptimeMs
        Log.i("AfterglowPerf", "inputContent=$stage downToCommitMs=$millis " +
            "downUptimeMs=$downUptimeMs committedUptimeMs=$committedUptimeMs " +
            "within250=${millis <= 250} notPhysicalScanout=true")
    }
    fun elapsed(stage: String, startedElapsedMs: Long) {
        val ended = SystemClock.elapsedRealtime()
        Log.i("AfterglowPerf", "stage=$stage durationMs=${ended - startedElapsedMs} endElapsedMs=$ended")
    }

    fun <T> measure(stage: String, block: () -> T): T {
        val started = SystemClock.elapsedRealtime()
        Trace.beginSection("afterglow.$stage")
        try { return block() }
        finally {
            Trace.endSection()
            val ended = SystemClock.elapsedRealtime()
            Log.i("AfterglowPerf", "stage=$stage durationMs=${ended - started} endElapsedMs=$ended")
        }
    }
}
