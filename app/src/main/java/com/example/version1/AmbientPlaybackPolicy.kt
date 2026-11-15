package com.example.version1

internal enum class AmbientFocusEvent { GAIN, DUCK, TRANSIENT_LOSS, LOSS }

/** Focus may resume a paused session, but can never start a session the user stopped. */
internal class AmbientPlaybackPolicy {
    private var requested = false
    private var interrupted = false
    private var ducked = false
    val shouldPlay: Boolean get() = requested && !interrupted
    val volume: Float get() = if (ducked) .06f else .25f

    fun requestStart() { requested = true; interrupted = false; ducked = false }
    fun stop() { requested = false; interrupted = false; ducked = false }
    fun focus(event: AmbientFocusEvent) {
        when (event) {
            AmbientFocusEvent.GAIN -> { interrupted = false; ducked = false }
            AmbientFocusEvent.DUCK -> ducked = true
            AmbientFocusEvent.TRANSIENT_LOSS -> interrupted = true
            AmbientFocusEvent.LOSS -> stop()
        }
    }
}
