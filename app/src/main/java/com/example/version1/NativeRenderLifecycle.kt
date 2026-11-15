package com.example.version1

import android.os.Looper

/** Process-wide barrier: foreground/background acknowledgements include real engine teardown. */
internal object NativeRenderLifecycle {
    private val gate = NativeReleaseGate { PerformanceMarks.event("resources.stop.done") }
    val nativeReady: Boolean get() = gate.nativeReady
    private fun checkOwner() { check(Looper.myLooper() == Looper.getMainLooper()) }
    fun foreground(owner: Any) { checkOwner(); gate.foreground(owner) }
    fun background(owner: Any) { checkOwner(); gate.background(owner) }
    fun begin(): NativeReleaseGate.Ticket { checkOwner(); return gate.begin() }
    fun complete(ticket: NativeReleaseGate.Ticket) { checkOwner(); gate.complete(ticket) }
    fun fail(ticket: NativeReleaseGate.Ticket) { checkOwner(); gate.fail(ticket) }
    fun whenReady(owner: Any, ready: () -> Unit, blocked: () -> Unit) {
        checkOwner(); gate.whenReady(owner, ready, blocked)
    }
    fun cancel(owner: Any) { checkOwner(); gate.cancel(owner) }
}
