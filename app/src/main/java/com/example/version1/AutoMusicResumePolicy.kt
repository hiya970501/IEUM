package com.example.version1

internal enum class AutoMusicDecision { KEEP_PLAYING, RESUME_PAUSED, NEED_SELECTION }

internal object AutoMusicResumePolicy {
    // Public PlaybackState values: do not interpret NONE/STOPPED/ERROR as a paused song.
    fun decide(state: Int, actions: Long, hasTitle: Boolean): AutoMusicDecision = when {
        state == 3 -> AutoMusicDecision.KEEP_PLAYING
        state == 2 && actions and 4L != 0L && hasTitle -> AutoMusicDecision.RESUME_PAUSED
        else -> AutoMusicDecision.NEED_SELECTION
    }
}
