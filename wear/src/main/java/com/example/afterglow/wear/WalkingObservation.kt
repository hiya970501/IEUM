package com.example.afterglow.wear

import com.example.afterglow.net.WatchActivityRecord
import java.util.UUID

/** Foreground session only. Counter baseline is an observation, never today's Samsung total. */
internal class WalkingObservation(private val wallStart: Long, private val elapsedStart: Long) {
    private val id = UUID.randomUUID().toString()
    private var baseline: Long? = null
    private var baselineAt: Long? = null
    private var lastCounter: Long? = null
    private var measuredAt: Long? = null
    private var lastSensorNanos = 0L
    private var endedAt: Long? = null
    private var invalid = false
    fun counter(value: Float, timestampNanos: Long, elapsedNanos: Long, wallNow: Long) {
        if (endedAt != null || invalid || !value.isFinite() || value < 0 || value > 16_000_000 || value % 1f != 0f ||
            timestampNanos <= lastSensorNanos || timestampNanos < elapsedStart * 1_000_000 || elapsedNanos < timestampNanos ||
            elapsedNanos - timestampNanos >= 30_000_000_000L) return
        val at = wallNow - (elapsedNanos - timestampNanos) / 1_000_000
        if (at < wallStart) return
        val count = value.toLong()
        if (lastCounter?.let { count < it } == true) { invalid = true; return }
        if (baseline == null) { baseline = count; baselineAt = at }
        if (count - baseline!! > 200_000) { invalid = true; return }
        lastCounter = count; measuredAt = at; lastSensorNanos = timestampNanos
    }
    fun snapshot(wallNow: Long, elapsedNow: Long, end: Boolean = false): WatchActivityRecord {
        check(!invalid) { "걸음 센서가 초기화됐어요. 새 측정을 시작하세요" }
        val elapsed = elapsedNow - elapsedStart
        check(elapsed in 0..86_400_000 && kotlin.math.abs((wallNow - wallStart) - elapsed) <= 2000) { "시각이 변경됐어요. 새 측정을 시작하세요" }
        if (end && endedAt == null) { check(elapsed > 0); endedAt = wallNow }
        return WatchActivityRecord(id, wallStart, wallNow, endedAt,
            baseline?.let { (lastCounter!! - it).toInt() }, baselineAt, measuredAt).validate(wallNow)
    }
}
