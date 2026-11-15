package com.example.version1

import android.content.ComponentName
import android.content.Context
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/** One opted-in resume per activation. No activity handoff, health data, playlist reset or stop. */
internal class AutoMusicResume(
    private val context: Context,
    private val component: ComponentName,
    private val stillAllowed: () -> Boolean,
    private val finished: (String) -> Unit
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private var client: MusicSessionBrowser? = null
    private var closed = false
    private var issued = false
    private val timeout = Runnable { finish("음악 앱의 재생 확인 시간이 지났어요. 앱에서 확인해 주세요.") }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(client == null && !closed)
        if (!stillAllowed()) { close(); return }
        client = MusicSessionBrowser(context, component, browseCatalog = false) { state ->
            if (closed) return@MusicSessionBrowser
            if (!stillAllowed()) { close(); return@MusicSessionBrowser }
            if (state.terminalError) { finish(state.message); return@MusicSessionBrowser }
            if (!state.connected) return@MusicSessionBrowser
            if (issued) {
                when (state.state) {
                    PlaybackState.STATE_PLAYING -> finish("음악 앱이 재생 중임을 확인했어요.")
                    PlaybackState.STATE_ERROR, PlaybackState.STATE_STOPPED -> finish("음악 앱에서 재생을 확인해 주세요.")
                }
            } else when (AutoMusicResumePolicy.decide(state.state, state.actions, state.title.isNotBlank())) {
                AutoMusicDecision.KEEP_PLAYING -> finish("이미 재생 중인 음악을 유지해요.")
                AutoMusicDecision.NEED_SELECTION -> finish("음악 앱에서 먼저 곡을 선택해 주세요.")
                AutoMusicDecision.RESUME_PAUSED -> {
                    issued = true
                    // Set before dispatch: a provider can report state immediately.
                    if (client?.resume() != true) finish("음악 앱이 자동 재개를 지원하지 않아요.")
                }
            }
        }
        handler.postDelayed(timeout, 8_000)
        runCatching { client!!.connect() }.onFailure { finish("음악 앱에 연결하지 못했어요.") }
    }

    private fun finish(message: String) {
        if (closed) return
        close()
        finished(message)
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        client?.close(); client = null
        // Detach only. The player owns the resulting playback after activation ends.
    }
}
