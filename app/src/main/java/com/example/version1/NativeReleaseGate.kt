package com.example.version1

/** Owner-thread state. A release request never counts as a completed native release. */
internal class NativeReleaseGate(private val stopped: () -> Unit) {
    class Ticket internal constructor()
    private val foreground = mutableSetOf<Any>()
    private val pending = mutableSetOf<Ticket>()
    private val waiting = linkedMapOf<Any, Pair<() -> Unit, () -> Unit>>()
    private var reportedIdle = false
    @Volatile var nativeReady = true
        private set
    @Volatile var failed = false
        private set
    fun foreground(owner: Any) { foreground.add(owner); reportedIdle = false }
    fun background(owner: Any) { foreground.remove(owner); dispatch() }
    fun begin(): Ticket = Ticket().also { pending.add(it); nativeReady = false; reportedIdle = false }
    fun complete(ticket: Ticket) {
        if (failed || !pending.remove(ticket)) return
        nativeReady = pending.isEmpty()
        dispatch()
    }
    fun fail(ticket: Ticket) {
        if (ticket !in pending) return
        failed = true; nativeReady = false
        val callbacks = waiting.values.toList(); waiting.clear()
        callbacks.forEach { it.second() }
    }
    fun whenReady(owner: Any, ready: () -> Unit, blocked: () -> Unit) {
        when { failed -> blocked(); nativeReady -> ready(); else -> waiting[owner] = ready to blocked }
    }
    fun cancel(owner: Any) { waiting.remove(owner) }
    private fun dispatch() {
        if (!nativeReady || failed) return
        if (foreground.isEmpty() && !reportedIdle) { reportedIdle = true; stopped() }
        val callbacks = waiting.toMap(); waiting.clear()
        callbacks.forEach { (owner, callback) ->
            if (nativeReady) callback.first() else waiting[owner] = callback
        }
    }
}
