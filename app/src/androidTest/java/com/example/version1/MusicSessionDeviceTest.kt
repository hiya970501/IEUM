package com.example.version1

import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Opt-in test against an independently installed official player and an owned synthetic WAV. */
@RunWith(AndroidJUnit4::class)
class MusicSessionDeviceTest {
    @Test fun vlcActualSessionPauseAndResume() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("musicProbe") == "vlc")
        val ownedTitle = args.getString("fixtureTitle") ?: error("Explicit owned fixture required")
        check(Regex("Afterglow_QA_[0-9a-f]{32}").matches(ownedTitle.removeSuffix(".wav")))
        val context = instrumentation.targetContext
        File(context.filesDir, "music-session-probe.json").delete()
        val observations = CopyOnWriteArrayList<MusicSessionBrowser.Snapshot>()
        val phases = org.json.JSONArray()
        val component = ExternalMusic(context).browserComponent(MusicSource.VLC) ?: error("VLC has no exported music service")
        var client: MusicSessionBrowser? = null
        var automatic: AutoMusicResume? = null
        var stage = "connect"
        suspend fun awaitState(expected: Int): MusicSessionBrowser.Snapshot = withTimeout(12_000) {
            while (true) {
                observations.lastOrNull()?.takeIf { it.connected && it.state == expected && it.title.contains(ownedTitle.removeSuffix(".wav")) }?.let { return@withTimeout it }
                delay(100)
            }
            error("unreachable")
        }
        try {
            instrumentation.runOnMainSync {
                client = MusicSessionBrowser(context, component) { observations.add(it) }.also { it.connect() }
            }
            withTimeout(12_000) { while (observations.none { it.connected }) delay(100) }
            stage = "owned-provider-source"
            OwnedVlcSource.prepare(client!!, { observations.lastOrNull() }, ownedTitle.removeSuffix(".wav"))
            stage = "owned-title"
            // Only resumes the explicitly named fixture prepared by the host. Never touches personal music.
            check(observations.last().title.contains(ownedTitle.removeSuffix(".wav"))) { "Wrong media; refuse controls" }
            if (observations.last().state == PlaybackState.STATE_PLAYING) {
                instrumentation.runOnMainSync { assertTrue(client!!.pause()) }
                awaitState(PlaybackState.STATE_PAUSED)
            }
            val before = observations.last()
            phases.put(JSONObject().put("phase", "before-resume").put("state", before.state).put("position", before.positionMillis))
            stage = "play-pause-resume"
            instrumentation.runOnMainSync { assertTrue(client!!.resume()) }
            awaitState(PlaybackState.STATE_PLAYING)
            instrumentation.runOnMainSync { assertTrue(client!!.pause()) }
            awaitState(PlaybackState.STATE_PAUSED)
            instrumentation.runOnMainSync { assertTrue(client!!.resume()) }
            awaitState(PlaybackState.STATE_PLAYING)
            delay(1500)
            val beforeClose = observations.last()
            phases.put(JSONObject().put("phase", "before-close").put("state", beforeClose.state).put("position", beforeClose.positionMillis))
            assertEquals("Owned playback stopped before closing the observer", PlaybackState.STATE_PLAYING, beforeClose.state)
            instrumentation.runOnMainSync { client!!.close() }
            stage = "detach-reconnect"
            observations.clear()
            // Detaching this app must not stop the selected player's own session.
            instrumentation.runOnMainSync {
                client = MusicSessionBrowser(context, component) { observations.add(it) }.also { it.connect() }
            }
            awaitState(PlaybackState.STATE_PLAYING)
            instrumentation.runOnMainSync { assertTrue(client!!.pause()) }
            val after = awaitState(PlaybackState.STATE_PAUSED)
            stage = "position"
            assertTrue("Playback position must advance, not just acknowledge a command", after.positionMillis > before.positionMillis)
            stage = "automatic-resume"
            val automaticResult = CompletableDeferred<String>()
            instrumentation.runOnMainSync {
                automatic = AutoMusicResume(context, component, { true }) { automaticResult.complete(it) }.also { it.start() }
            }
            val automaticMessage = withTimeout(10_000) { automaticResult.await() }
            assertEquals("음악 앱이 재생 중임을 확인했어요.", automaticMessage)
            awaitState(PlaybackState.STATE_PLAYING)
            instrumentation.runOnMainSync { assertTrue(client!!.pause()) }
            awaitState(PlaybackState.STATE_PAUSED)
            stage = "revoked-before-command"
            var permitted = true
            var revokedResult = false
            instrumentation.runOnMainSync {
                automatic = AutoMusicResume(context, component, { permitted }) { revokedResult = true }.also { it.start() }
                permitted = false // Owner/foreground/gear changed before the asynchronous session reply.
            }
            delay(500)
            assertEquals(PlaybackState.STATE_PAUSED, observations.last().state)
            assertFalse("Revoked activation must not report a completed action", revokedResult)
            File(context.filesDir, "music-session-probe.json").writeText(JSONObject()
                .put("component", component.flattenToString()).put("ownedSyntheticFixture", true)
                .put("actualPlayPauseResumeVerified", true)
                .put("closingObserverPreservedPlayback", true)
                .put("optedInAutomaticResumeVerified", true)
                .put("revocationBeforeCommandPreservedPause", true)
                .put("states", org.json.JSONArray(observations.map { it.state }))
                .put("catalogEntries", observations.maxOf { it.entries.size })
                .put("playbackPositionAdvanced", true)
                .put("beforePositionMillis", before.positionMillis).put("afterPositionMillis", after.positionMillis)
                .put("elapsedMillis", SystemClock.elapsedRealtime()).toString(2))
        } finally {
            instrumentation.runOnMainSync { automatic?.close() }
            File(context.filesDir, "music-session-diagnostic.json").writeText(JSONObject()
                .put("stage", stage).put("observationCount", observations.size)
                .put("phases", phases)
                .put("observations", org.json.JSONArray(observations.takeLast(30).map {
                    JSONObject().put("message", it.message).put("connected", it.connected)
                        .put("state", it.state).put("actions", it.actions).put("entries", it.entries.size)
                        .put("position", it.positionMillis)
                        .put("ownedTitle", it.title.contains(ownedTitle.removeSuffix(".wav")))
                })).toString(2))
            // Only our named synthetic recording is owned by this test, including on failure.
            if (observations.lastOrNull()?.let { it.title.contains(ownedTitle.removeSuffix(".wav")) &&
                    it.state == PlaybackState.STATE_PLAYING } == true) {
                instrumentation.runOnMainSync { client?.pause() }
                runCatching { withTimeout(2_000) { awaitState(PlaybackState.STATE_PAUSED) } }
            }
            instrumentation.runOnMainSync { client?.close() }
        }
    }
}
