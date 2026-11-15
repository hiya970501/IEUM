package com.example.afterglow.wear

import com.example.afterglow.net.SecureRelayEndpoint

internal class WatchDestination(address: String, val token: String) {
    val endpoint=SecureRelayEndpoint(address)
    val host get()=endpoint.host
    init { require(token.matches(Regex("[0-9a-f]{32}"))) }
    override fun toString() = "WatchDestination(redacted)"
}

/** Permission intent may survive its dialog, but never a stop or a new pairing.
 * Each sensor registration and transfer belongs to one foreground epoch. */
internal class MeasurementSession {
    class Authorization internal constructor(internal val revision: Long, internal val target: WatchDestination)
    class Measurement internal constructor(internal val revision: Long, internal val epoch: Long, val target: WatchDestination)
    class Transfer internal constructor(internal val measurement: Measurement)
    private var target: WatchDestination? = null
    private var revision = 0L
    private var epoch = 0L
    private var foreground = false
    private var permission: Authorization? = null
    private var ready: Authorization? = null
    private var measurement: Measurement? = null
    private var transfer: Transfer? = null
    private var lastAttempt: Long? = null

    @Synchronized fun configure(value: WatchDestination) { stop(); target = value }
    @Synchronized fun foreground(value: Boolean) {
        if (foreground == value) return
        foreground = value; epoch++
        if (!value) { measurement = null; transfer = null; lastAttempt = null }
    }
    @Synchronized fun requestStart(granted: Boolean): Authorization? {
        stop()
        val destination = target?.takeIf { foreground } ?: return null
        return Authorization(revision, destination).also { if (granted) ready = it else permission = it }
    }
    @Synchronized fun permissionResult(ticket: Authorization, granted: Boolean): Boolean {
        if (permission !== ticket || ticket.revision != revision || ticket.target !== target) return false
        permission = null
        if (granted) ready = ticket
        return true
    }
    @Synchronized fun takeStart(): Measurement? {
        val authorization = ready ?: return null
        if (!foreground || authorization.revision != revision || authorization.target !== target) return null
        ready = null
        return Measurement(revision, epoch, authorization.target).also { measurement = it }
    }
    @Synchronized fun isCurrent(ticket: Measurement): Boolean = foreground && measurement === ticket &&
        ticket.revision == revision && ticket.epoch == epoch && ticket.target === target
    @Synchronized fun isCurrent(ticket: Transfer): Boolean = transfer === ticket && isCurrent(ticket.measurement)
    @Synchronized fun claim(ticket: Measurement, nowMillis: Long): Transfer? {
        if (!isCurrent(ticket) || transfer != null || nowMillis < 0) return null
        val last = lastAttempt
        if (last != null && (nowMillis < last || nowMillis - last < 5000)) return null
        lastAttempt = nowMillis
        return Transfer(ticket).also { transfer = it }
    }
    /** A late completion cannot unlock or update a newer transfer. */
    @Synchronized fun complete(ticket: Transfer): Boolean {
        if (!isCurrent(ticket)) return false
        transfer = null
        return true
    }
    @Synchronized fun stop() {
        revision++; permission = null; ready = null; measurement = null; transfer = null; lastAttempt = null
    }
}

internal data class LiveHeartRate(val bpm: Int, val measuredAt: Long, val sensorTimestampNanos: Long) {
    companion object {
        fun from(value: Float, sensorTimestampNanos: Long, elapsedNanos: Long, wallMillis: Long): LiveHeartRate? {
            if (!value.isFinite() || value < 40f || value > 220f || sensorTimestampNanos <= 0 ||
                elapsedNanos < sensorTimestampNanos || wallMillis <= 0) return null
            val age = elapsedNanos - sensorTimestampNanos
            if (age >= 30_000_000_000L) return null
            val measured = wallMillis - age / 1_000_000
            if (measured <= 0) return null
            return LiveHeartRate(value.toInt(), measured, sensorTimestampNanos)
        }
    }
    fun isFresh(elapsedNanos: Long): Boolean = elapsedNanos >= sensorTimestampNanos &&
        elapsedNanos - sensorTimestampNanos < 30_000_000_000L
}
