package com.example.version1

/** Visibility/background notices can be queued across foreground reentry.
 * Running pressure is different from the background LRU severity range. */
internal object MemoryTrimPolicy {
    private const val RUNNING_LOW = 10
    private const val UI_HIDDEN = 20
    private const val BACKGROUND = 40
    fun shouldReleaseForPressure(level: Int, foreground: Boolean): Boolean =
        (!foreground && level >= BACKGROUND) || (level >= RUNNING_LOW && level < UI_HIDDEN)
}
