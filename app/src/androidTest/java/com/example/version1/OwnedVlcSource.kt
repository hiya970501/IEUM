package com.example.version1

import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** QA-only bounded provider-catalog fallback. Never guesses an id or plays an unrelated song. */
internal object OwnedVlcSource {
    suspend fun prepare(client: MusicSessionBrowser, latest: () -> MusicSessionBrowser.Snapshot?, stem: String) {
        require(Regex("Afterglow_QA_[a-f0-9]{32}").matches(stem))
        val initial = checkNotNull(latest())
        if (initial.title.contains(stem)) return
        fun requireEmptySession() {
            val state = checkNotNull(latest())
            check(state.connected && !state.terminalError && state.title.isBlank() &&
                state.state in listOf(PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED)) {
                "Refuse source loading over an unrelated media session"
            }
        }
        requireEmptySession()
        withTimeout(8000) {
            withContext(Dispatchers.Main.immediate) {
                val browser = client.javaClass.getDeclaredField("browser").apply { isAccessible = true }.get(client) as MediaBrowser
                val controller = client.javaClass.getDeclaredField("controller").apply { isAccessible = true }.get(client) as MediaController
                check(initial.actions and PlaybackState.ACTION_PLAY_FROM_MEDIA_ID != 0L)
                suspend fun children(id: String): List<MediaBrowser.MediaItem> {
                    val result = CompletableDeferred<List<MediaBrowser.MediaItem>>()
                    val callback = object : MediaBrowser.SubscriptionCallback() {
                        override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
                            result.complete(children.take(100))
                        }
                        override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>, options: Bundle) = onChildrenLoaded(parentId, children)
                        override fun onError(parentId: String) { result.completeExceptionally(IllegalStateException("Owned provider catalog unavailable")) }
                        override fun onError(parentId: String, options: Bundle) = onError(parentId)
                    }
                    try {
                        browser.subscribe(id, Bundle().apply {
                            putInt(MediaBrowser.EXTRA_PAGE, 0); putInt(MediaBrowser.EXTRA_PAGE_SIZE, 100)
                        }, callback)
                        return result.await()
                    } finally { browser.unsubscribe(id, callback) }
                }
                val queue = java.util.ArrayDeque<Pair<String, Int>>().apply { add(browser.root to 0) }
                val visited = mutableSetOf<String>()
                var owned: MediaBrowser.MediaItem? = null
                // VLC3.7.1 publishes these branches. Rank only returned IDs;
                // never subscribe/play an ID the provider did not advertise.
                // Root/home/playlist/library/stream plus artists/albums can use
                // all eight BFS nodes before the general track branch is read.
                val library = "${browser.root}/l"
                val tracks = "$library/t"
                var trackAdvertised = false
                var trackQueryReturned = false
                var returnedItems = 0
                try {
                while (queue.isNotEmpty() && visited.size < 8 && owned == null) {
                    val (id, depth) = queue.removeFirst()
                    if (!visited.add(id)) continue
                    val items = children(id)
                    returnedItems += items.size
                    if (id == tracks) trackQueryReturned = true
                    if (items.any { it.isBrowsable && it.mediaId == tracks }) trackAdvertised = true
                    val matches = items.filter { it.isPlayable && it.mediaId != null &&
                        it.description.title?.toString() in listOf(stem, "$stem.wav") }
                    check(matches.size <= 1) { "Ambiguous owned indexed source" }
                    owned = matches.singleOrNull()
                    if (depth < 2) items.filter { it.isBrowsable && it.mediaId != null }.take(8).forEach {
                        if (queue.size < 16) {
                            val next = it.mediaId!! to depth + 1
                            if (it.mediaId == library || it.mediaId == tracks) queue.addFirst(next)
                            else queue.addLast(next)
                        }
                    }
                }
                } finally {
                    // Counts/public topology flags only: no personal titles, ids or paths.
                    android.util.Log.i("IEUMOwnedMediaQA", "catalogNodes=${visited.size} returnedItems=$returnedItems " +
                        "trackAdvertised=$trackAdvertised trackQueryReturned=$trackQueryReturned " +
                        "budgetExhausted=${visited.size == 8 && queue.isNotEmpty()} ownedMatched=${owned != null}")
                }
                check(owned != null) { "Owned indexed source absent in bounded provider catalog" }
                requireEmptySession()
                controller.transportControls.playFromMediaId(owned.mediaId, null)
            }
            while (latest()?.let { it.title.contains(stem) && it.state == PlaybackState.STATE_PLAYING } != true) delay(100)
        }
    }
}
