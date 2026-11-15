package com.example.version1

/** Multi-resume can leave an obscured Activity STARTED and RESUMED indefinitely. */
internal class ForegroundResourceGate(private val requiresTopResumed: Boolean) {
    private var started = false
    private var resumed = false
    private var top = false
    private var interactive = true
    val allowed: Boolean get() = interactive && started && resumed && (!requiresTopResumed || top)
    val awaitingInputFocus: Boolean get() = interactive && started && resumed && requiresTopResumed && !top
    fun interactive(value: Boolean) { interactive = value }
    fun start() { started = true; resumed = false; top = false }
    fun resume() { if (started) resumed = true }
    fun top(value: Boolean) { top = value && started && resumed }
    fun pause() { resumed = false; top = false }
    fun stop() { started = false; pause() }
}
