package com.example.version1

/** Work is completed by submitted frames, not by display callbacks skipped by the GPU. */
internal class RenderFrameBudget(private val stallMillis: Long = 15_000L) {
    var remaining: Int = 0
        private set
    private var progressAt = 0L

    fun request(count: Int, now: Long) {
        require(count in 0..96)
        if (count == 0) return
        if (remaining == 0) progressAt = now
        remaining = maxOf(remaining, count)
    }

    fun submitted(accepted: Boolean, now: Long) {
        if (!accepted) return
        remaining = (remaining - 1).coerceAtLeast(0)
        progressAt = now
    }

    fun stalled(now: Long): Boolean = remaining > 0 && now - progressAt > stallMillis
    fun clear() { remaining = 0; progressAt = 0L }
}
