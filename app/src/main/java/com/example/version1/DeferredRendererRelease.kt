package com.example.version1

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.Fence

/** Drain the private, no-longer-rendering engine before destructive joins, on its original thread.
 * Filament 1.75 Engine::shutdown explicitly requires the creating thread. Do not move destroy to IO.
 * Backend-idle polling follows the public Fence API; no forced timeout destroys a busy engine.
 * References: google/filament v1.75.0 Engine.cpp; sceneview/sceneview Engine.kt backend-idle teardown.
 */
internal object DeferredRendererRelease {
    // Cleanup must retain the engine's creating thread, but must not wait behind
    // a display traversal's synchronization barrier while that surface detaches.
    // Public since API28, which is this app's minimum; the native fence still
    // decides readiness and no timeout is treated as permission to destroy.
    private val handler = Handler.createAsync(Looper.getMainLooper())
    // If a driver fails, retain ownership rather than let a finalizer destroy it on another thread.
    private val failedOwners = mutableListOf<Pair<Engine, () -> Unit>>()
    fun drainWithin(engine: Engine, timeoutNanos: Long): Boolean {
        val fence = engine.createFence()
        return try { fence.wait(Fence.Mode.FLUSH, timeoutNanos) == Fence.FenceStatus.CONDITION_SATISFIED }
        finally { engine.destroyFence(fence) }
    }
    fun schedule(engine: Engine, destroy: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val ticket = NativeRenderLifecycle.begin()
        val started = SystemClock.elapsedRealtime()
        PerformanceMarks.event("scene.native.release.queued")
        var fence: Fence? = null
        var reportedSlow = false
        var observedFirstProbe = false
        val poll = object : Runnable {
            override fun run() {
                try {
                    if (!observedFirstProbe) {
                        observedFirstProbe = true
                        PerformanceMarks.event("scene.native.release.probe")
                    }
                    // First probe is posted after Android's surface-detach callback unwinds.
                    val probe = fence ?: engine.createFence().also { fence = it }
                    when (probe.wait(Fence.Mode.FLUSH, 0L)) {
                        Fence.FenceStatus.TIMEOUT_EXPIRED -> {
                            val slow = SystemClock.elapsedRealtime() - started > 5000
                            if (slow && !reportedSlow) { reportedSlow = true; Log.w("AfterglowRender", "Native release still draining; new engine blocked") }
                            handler.postDelayed(this, if (slow) 250L else 32L)
                        }
                        Fence.FenceStatus.CONDITION_SATISFIED -> {
                            PerformanceMarks.event("scene.native.release.fence.ready")
                            engine.destroyFence(probe); fence = null
                            PerformanceMarks.event("scene.native.release.destroy.begin")
                            destroy()
                            NativeRenderLifecycle.complete(ticket)
                            PerformanceMarks.elapsed("scene.native.release.done", started)
                        }
                        else -> fail()
                    }
                } catch (_: Exception) { fail() }
            }
            private fun fail() {
                failedOwners.add(engine to destroy)
                NativeRenderLifecycle.fail(ticket)
                Log.e("AfterglowRender", "Native release not confirmed; ownership retained")
            }
        }
        if (!handler.post(poll)) {
            failedOwners.add(engine to destroy); NativeRenderLifecycle.fail(ticket)
        }
    }
}
