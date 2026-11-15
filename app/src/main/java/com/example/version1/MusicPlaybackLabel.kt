package com.example.version1

/** Framework PlaybackState values; unknown, loading and command acknowledgements are not playback. */
internal fun musicPlaybackLabel(state: Int): String = when (state) {
    3 -> "재생 중"
    2 -> "일시정지됨"
    1 -> "재생 중지됨"
    6, 8 -> "재생 준비 중"
    7 -> "재생 오류 · 음악 앱에서 확인하세요."
    else -> "재생 확인 대기"
}
