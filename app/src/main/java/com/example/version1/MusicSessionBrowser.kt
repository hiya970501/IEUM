package com.example.version1

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Future

/** Selected-provider access only; no notification-listener permission or global session access. */
class MusicSessionBrowser(
    private val context: Context,
    private val component: ComponentName,
    private val browseCatalog: Boolean = true,
    private val changed: (Snapshot) -> Unit
) : AutoCloseable {
    data class Entry(val id: String, val title: String, val browsable: Boolean, val playable: Boolean)
    data class Snapshot(
        val message: String, val entries: List<Entry> = emptyList(),
        val title: String = "", val state: Int = PlaybackState.STATE_NONE,
        val actions: Long = 0, val connected: Boolean = false, val canBack: Boolean = false,
        val positionMillis: Long = 0, val terminalError: Boolean = false
    )
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var failed = false
    private var verified = false
    private var loadGeneration = 0L
    private var stateRevision = 0L
    private var titleRevision = 0L
    private var initialRead: Future<*>? = null
    private var browser: MediaBrowser? = null
    private var controller: MediaController? = null
    private var subscription: String? = null
    private val parents = mutableListOf<String>()
    private var snapshot = Snapshot("음악 앱에 연결 중…")
    private val timeout = Runnable { fail("음악 앱의 응답이 없어요. 앱에서 로그인·초기 설정을 확인하세요.") }
    private fun publish(value: Snapshot) { if (!closed) { snapshot = value; changed(value) } }
    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (closed || failed) return
            stateRevision++
            snapshot = withState(snapshot, state)
            if (verified) publish(snapshot)
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            if (closed || failed) return
            titleRevision++
            snapshot = snapshot.copy(title = titleOf(metadata))
            if (verified) publish(snapshot)
        }
        override fun onSessionDestroyed() { fail("음악 재생 연결이 종료됐어요.") }
    }
    fun connect() {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(browser == null && !closed)
        publish(snapshot)
        browser = MediaBrowser(context, component, object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                if (closed || failed) return
                val client = browser ?: return
                runCatching {
                    val media = MediaController(context, client.sessionToken)
                    controller = media
                    media.registerCallback(callback, handler)
                    val root = client.root
                    check(MusicText.validId(root))
                    val oldStateRevision = stateRevision
                    val oldTitleRevision = titleRevision
                    initialRead = BoundedMediaRead.shared.submit({
                        check(media.packageName == component.packageName)
                        media.playbackState to titleOf(media.metadata)
                    }) { result -> handler.post {
                        if (closed || failed || controller !== media) return@post
                        result.fold(onSuccess = { (state, title) ->
                            verified = true
                            if (stateRevision == oldStateRevision) snapshot = withState(snapshot, state)
                            if (titleRevision == oldTitleRevision) snapshot = snapshot.copy(title = title)
                            if (browseCatalog) { parents.add(root); load(root) }
                            else {
                                handler.removeCallbacks(timeout)
                                publish(snapshot.copy(message = "음악 앱의 재생 상태", connected = true))
                            }
                        }, onFailure = { fail("음악 앱의 재생 연결을 확인하지 못했어요.") })
                    } }
                    if (initialRead == null) fail("이전 음악 앱의 응답을 기다리고 있어요. 잠시 후 다시 연결하세요.")
                }.onFailure { fail("음악 앱의 재생 연결을 읽지 못했어요.") }
            }
            override fun onConnectionFailed() { fail("음악 앱이 곡 목록 연결을 허용하지 않았어요. 앱에서 재생을 선택하세요.") }
            override fun onConnectionSuspended() { fail("음악 앱 연결이 끊겼어요. 다시 연결해 주세요.") }
        }, null)
        handler.postDelayed(timeout, 8_000)
        runCatching { browser?.connect() }.onFailure { fail("음악 앱에 연결하지 못했어요.") }
    }
    private fun titleOf(metadata: MediaMetadata?): String = if (metadata == null) "" else
        MusicText.sessionTitle(metadata.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: metadata.getText(MediaMetadata.METADATA_KEY_TITLE))
    private fun withState(value: Snapshot, state: PlaybackState?) = value.copy(
        state = state?.state ?: PlaybackState.STATE_NONE, actions = state?.actions ?: 0,
        positionMillis = state?.position?.coerceAtLeast(0) ?: 0)
    private fun load(id: String) {
        if (closed || failed || !verified || !MusicText.validId(id)) return
        val client = browser?.takeIf { it.isConnected } ?: return
        val generation = ++loadGeneration
        subscription?.let { client.unsubscribe(it) }
        subscription = id
        publish(snapshot.copy(message = "곡 목록을 읽는 중…", entries = emptyList(), connected = true, canBack = parents.size > 1))
        handler.removeCallbacks(timeout); handler.postDelayed(timeout, 8_000)
        val options = Bundle().apply { putInt(MediaBrowser.EXTRA_PAGE, 0); putInt(MediaBrowser.EXTRA_PAGE_SIZE, 100) }
        runCatching { client.subscribe(id, options, object : MediaBrowser.SubscriptionCallback() {
            override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>, options: Bundle) {
                onChildrenLoaded(parentId, children)
            }
            override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
                if (closed || failed || generation != loadGeneration || subscription != parentId) return
                handler.removeCallbacks(timeout)
                val entries = children.take(100).mapNotNull { item -> item.mediaId?.takeIf { MusicText.validId(it) }?.let {
                    Entry(it, MusicText.title(item.description.title), item.isBrowsable, item.isPlayable)
                } }
                publish(snapshot.copy(message = if (entries.isEmpty()) "곡이 없어요. 음악 앱에서 파일·로그인을 설정하세요."
                    else if (children.size > 100) "처음 100개 항목 · 전체 목록은 음악 앱에서 확인하세요." else "음악 앱의 곡 목록",
                    entries = entries))
            }
            override fun onError(parentId: String, options: Bundle) { onError(parentId) }
            override fun onError(parentId: String) {
                if (!closed && !failed && generation == loadGeneration && subscription == parentId)
                    fail("곡 목록을 읽지 못했어요. 음악 앱에서 확인하세요.")
            }
        }) }.onFailure { fail("곡 목록을 읽지 못했어요.") }
    }
    fun browse(entry: Entry) {
        if (closed || !entry.browsable || entry !in snapshot.entries || parents.size >= 16) return
        parents.add(entry.id); load(entry.id)
    }
    fun back() { if (!closed && parents.size > 1) { parents.removeAt(parents.lastIndex); load(parents.last()) } }
    fun play(entry: Entry): Boolean = command(PlaybackState.ACTION_PLAY_FROM_MEDIA_ID) {
        if (!entry.playable || entry !in snapshot.entries) error("Stale music entry")
        it.playFromMediaId(entry.id, null)
    }
    fun resume(): Boolean = command(PlaybackState.ACTION_PLAY) { it.play() }
    fun pause(): Boolean = command(PlaybackState.ACTION_PAUSE) { it.pause() }
    private fun command(action: Long, send: (MediaController.TransportControls) -> Unit): Boolean {
        if (closed || failed || !verified || !snapshot.connected || snapshot.actions and action == 0L) return false
        return runCatching { send(controller?.transportControls ?: return false); true }.getOrDefault(false)
    }
    private fun fail(message: String) {
        if (closed || failed) return
        failed = true; verified = false; loadGeneration++
        handler.removeCallbacks(timeout)
        initialRead?.cancel(true); initialRead = null
        runCatching { controller?.unregisterCallback(callback) }; controller = null
        runCatching { browser?.disconnect() }
        publish(Snapshot(message, terminalError = true))
    }
    override fun close() {
        if (closed) return
        closed = true; verified = false; loadGeneration++; handler.removeCallbacksAndMessages(null)
        initialRead?.cancel(true); initialRead = null
        runCatching { controller?.unregisterCallback(callback) }; controller = null
        runCatching { browser?.disconnect() }; browser = null
        parents.clear(); subscription = null
        snapshot = Snapshot("")
        // Detach observation only: never pause or stop another app on dialog/background cleanup.
    }
}
