package com.example.version1

/** Main-thread presentation ownership. Import completion is not a displayed frame.
 * Pacer timestamps conservatively bound submitted EGL buffers on one surface.
 * Each new surface gets an epoch; timestamps never cross surface boundaries. */
internal class PreviewPresentationGate {
    class Ticket internal constructor(val surface: Long, val revision: Long)
    private var surface = 0L
    private var revision = 0L
    private var latestUpperBound = 0L
    private var oldFrameBound = 0L
    private var target: Long? = null
    private var ready = false
    var current: Ticket? = null
        private set
    var covered = true
        private set
    var failed = false
        private set
    val awaitingTexture: Boolean get() = covered && ready && !failed
    val requiredTimestamp: Long? get() = target
    fun attach(): Long { surface++; latestUpperBound = 0; begin(); return surface }
    fun begin(): Ticket {
        ready = false; target = null; covered = true; failed = false
        return Ticket(surface, ++revision).also { current = it }
    }
    fun markReady(ticket: Ticket) {
        if (ticket !== current || failed || ready) return
        ready = true; oldFrameBound = latestUpperBound
    }
    fun submitted(ticket: Ticket, accepted: Boolean, expectedPresentationNanos: Long) {
        if (ticket !== current || failed || !accepted || expectedPresentationNanos !in 1 until Long.MAX_VALUE) return
        latestUpperBound = maxOf(latestUpperBound, expectedPresentationNanos)
        if (covered && ready && target == null) target = maxOf(oldFrameBound + 1, expectedPresentationNanos)
    }
    fun updated(surfaceEpoch: Long, timestamp: Long): Boolean {
        val threshold = target ?: return false
        if (surfaceEpoch != surface || failed || !covered || !ready || timestamp !in 1 until Long.MAX_VALUE ||
            timestamp < threshold || timestamp <= oldFrameBound) return false
        covered = false
        return true
    }
    fun fail() { failed = true; covered = true; ready = false; target = null }
    fun detach() { surface++; current = null; ready = false; target = null; covered = true; failed = false }
}
