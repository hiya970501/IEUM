package com.example.version1

import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real AAOS focus arbitration with a separate UID. All owned sample output gain is zero. */
@RunWith(AndroidJUnit4::class)
class AudioFocusDeviceTest {
    @Test fun silentTrackRespondsToSeparateAppFocusWithoutRestartingAStoppedSession() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("audioFocusFixture") == "emulator")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        require(!context.getSystemService(AudioManager::class.java).isMusicActive) { "Preserve existing user playback" }
        require(context.getSharedPreferences("recovery_restore", 0).getString("pending", "[]") == "[]")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val owner = AmbientSound(context, outputGain = 0f)
        val trackField = AmbientSound::class.java.getDeclaredField("track").apply { isAccessible = true }
        val records = JSONArray()
        fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
        fun trackState(): Int? {
            var state: Int? = null
            instrumentation.runOnMainSync { state = (trackField.get(owner) as? AudioTrack)?.playState }
            return state
        }
        fun waitFor(expected: Int?) {
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (SystemClock.elapsedRealtime() < deadline) { if (trackState() == expected) return; Thread.sleep(50) }
            assertEquals("Real AudioTrack focus response", expected, trackState())
        }
        fun start() {
            var granted = false
            val before = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync { granted = owner.start() }
            assertTrue("Owned playback request not accepted", granted)
            records.put(JSONObject().put("stage", "start").put("mainHandlerMs", SystemClock.elapsedRealtime() - before))
            waitFor(AudioTrack.PLAYSTATE_PLAYING)
        }
        fun request(kind: String) {
            val nonce = java.util.UUID.randomUUID().toString()
            shell("am start-foreground-service --user 10 -n com.example.afterglow.phone/.FocusFixtureService --es kind $kind --es nonce $nonce")
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (SystemClock.elapsedRealtime() < deadline) {
                val status = shell("run-as com.example.afterglow.phone --user 10 cat shared_prefs/qa_focus.xml")
                if (status.contains(">" + kind + "</string>") && status.contains(">" + nonce + "</string>") &&
                    status.contains("name=\"granted\" value=\"true\"")) return
                Thread.sleep(100)
            }
            error("Separate UID focus request not confirmed: $kind")
        }
        fun release() { shell("am stopservice --user 10 -n com.example.afterglow.phone/.FocusFixtureService") }
        try {
            var presented = false
            val deadline = SystemClock.elapsedRealtime() + 60000
            while (!presented && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { activity ->
                    val scene = MainActivity::class.java.getDeclaredField("scene").apply { isAccessible = true }.get(activity)
                    val gate = scene.javaClass.getDeclaredField("presentation").apply { isAccessible = true }.get(scene) as PreviewPresentationGate
                    check(!gate.failed); presented = !gate.covered
                }
                if (!presented) Thread.sleep(250)
            }
            assertTrue("Real foreground preview did not settle", presented)
            Thread.sleep(1000)
            var notifications = 0
            instrumentation.runOnMainSync {
                owner.onPlaybackChanged = { notifications++ }
                owner.stop(); owner.stop()
                assertEquals("Never-started OFF must not publish playback changes", 0, notifications)
                assertTrue(owner.start()); owner.stop()
                assertEquals("Cancelling preparation releases exactly one owned request", 1, notifications)
                owner.stop()
                assertEquals("Repeated stop must be idempotent", 1, notifications)
                owner.onPlaybackChanged = null
            }
            records.put(JSONObject().put("stage", "offAndRepeatedStopAreIdempotent").put("passed", true))
            Thread.sleep(500); waitFor(null)
            records.put(JSONObject().put("stage", "stopDuringPreparationDoesNotStartLater").put("passed", true))
            request("transient")
            instrumentation.runOnMainSync { assertFalse("Music must be rejected while CALL holds focus", owner.start()) }
            waitFor(null); release(); Thread.sleep(300); waitFor(null)
            records.put(JSONObject().put("stage", "musicRejectedWhileCallContextHoldsFocus").put("passed", true))
            start(); request("transient"); waitFor(AudioTrack.PLAYSTATE_PAUSED)
            release(); waitFor(AudioTrack.PLAYSTATE_PLAYING)
            records.put(JSONObject().put("stage", "transientLossAndGain").put("passed", true))
            request("assistant"); waitFor(AudioTrack.PLAYSTATE_PAUSED)
            release(); waitFor(AudioTrack.PLAYSTATE_PLAYING)
            records.put(JSONObject().put("stage", "assistantContextPreemptsAndReleasesMusic").put("passed", true)
                .put("actualGleoApplicationInvoked", false))
            request("duck"); Thread.sleep(300); waitFor(AudioTrack.PLAYSTATE_PLAYING)
            release(); waitFor(AudioTrack.PLAYSTATE_PLAYING)
            records.put(JSONObject().put("stage", "navigationConcurrency").put("passed", true)
                .put("audibleDuckFactorMeasured", false))
            request("permanent"); waitFor(null); release(); Thread.sleep(300); waitFor(null)
            records.put(JSONObject().put("stage", "permanentLossDoesNotAutoRestart").put("passed", true))
            start(); request("transient"); waitFor(AudioTrack.PLAYSTATE_PAUSED)
            instrumentation.runOnMainSync { owner.stop() }; release(); Thread.sleep(300); waitFor(null)
            records.put(JSONObject().put("stage", "explicitStopDuringInterruption").put("passed", true))
            File(context.filesDir, "audio-focus-result.json").writeText(JSONObject().put("outputGain", 0)
                .put("systemVolumeChanged", false).put("physicalCallOrGleoTested", false)
                .put("records", records).toString(2))
        } finally {
            release(); instrumentation.runOnMainSync { owner.stop() }; scenario.closeAndAwaitNativeRelease()
        }
    }
}
