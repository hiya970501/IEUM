package com.example.version1

import android.media.AudioTrack
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual screen off/on, not a VM pause or an OEM ignition/suspend certification. */
@RunWith(AndroidJUnit4::class)
class ScreenPowerDeviceTest {
    @Test fun actualScreenOffReleasesAudioAndSceneAndWakeDoesNotAutoPlay() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenPowerFixture") == "emulator")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val power = context.getSystemService(PowerManager::class.java)
        require(power.isInteractive) { "Preserve an originally sleeping display" }
        require(!context.getSystemService(android.media.AudioManager::class.java).isMusicActive) { "Preserve user playback" }
        require(context.getSharedPreferences("recovery_restore", 0).getString("pending", "[]") == "[]")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val owner = AmbientSound(context, outputGain = 0f)
        val soundField = MainActivity::class.java.getDeclaredField("sound").apply { isAccessible = true }
        var previousSound: AmbientSound? = null
        var wakeRequired = false
        fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
        fun key(name: String) = instrumentation.uiAutomation.executeShellCommand("input keyevent $name").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
        }
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 60000
            while (SystemClock.elapsedRealtime() < deadline) { if (condition()) return; Thread.sleep(100) }
            error("Screen/audio/native lifecycle condition not confirmed")
        }
        fun presented(): Boolean {
            var ready = false
            scenario.onActivity { activity ->
                val scene = field(activity, "scene")!!
                val gate = field(scene, "presentation") as PreviewPresentationGate
                check(!gate.failed)
                ready = !gate.covered && field(activity, "foreground") == true
            }
            return ready
        }
        try {
            await(::presented)
            scenario.onActivity { activity ->
                assertFalse(field(activity, "active") as Boolean)
                previousSound = soundField.get(activity) as AmbientSound
                previousSound!!.stop(); soundField.set(activity, owner)
                assertTrue(owner.start())
            }
            await {
                var playing = false
                instrumentation.runOnMainSync { playing = owner.isPlaying }
                playing
            }
            wakeRequired = true; key("KEYCODE_SLEEP")
            await {
                var released = false
                scenario.onActivity { activity ->
                    val scene = field(activity, "scene")!!
                    released = NativeRenderLifecycle.nativeReady && field(activity, "foreground") == false && field(scene, "modelViewer") == null &&
                        field(scene, "shellAsset") == null && field(owner, "track") == null
                }
                !power.isInteractive && released
            }
            key("KEYCODE_WAKEUP"); wakeRequired = false
            await { power.isInteractive && presented() }
            Thread.sleep(500)
            instrumentation.runOnMainSync { assertFalse(owner.isPlaying); assertNull(field(owner, "track")) }
            File(context.filesDir, "screen-power-result.json").writeText(JSONObject()
                .put("screenOffReleasedNativeScene", true).put("screenOffReleasedAudioTrack", true)
                .put("wakePresentedNewPreview", true).put("wakeDidNotAutoPlay", true)
                .put("outputGain", 0).put("oemIgnitionOrSuspendTested", false).put("vehicleCommandsIssued", false).toString(2))
        } finally {
            if (wakeRequired || !power.isInteractive) key("KEYCODE_WAKEUP")
            instrumentation.runOnMainSync { owner.stop() }
            scenario.onActivity { activity -> previousSound?.let { soundField.set(activity, it) } }
            scenario.closeAndAwaitNativeRelease()
        }
    }
}
