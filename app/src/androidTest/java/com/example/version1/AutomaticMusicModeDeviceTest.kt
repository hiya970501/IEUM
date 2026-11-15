package com.example.version1

import android.media.session.PlaybackState
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Real Auto button -> actual SDK apply -> opted-in public VLC session; isolated preferences. */
@RunWith(AndroidJUnit4::class)
class AutomaticMusicModeDeviceTest {
    @Test fun consentedAutoButtonResumesOwnedSongAndOffRestoresVehicleWithoutPausingMusic() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("musicModeProbe") == "vlc")
        val ownedTitle = args.getString("fixtureTitle")!!.removeSuffix(".wav")
        check(Regex("Afterglow_QA_[a-f0-9]{32}").matches(ownedTitle))
        val context = instrument.targetContext
        val original = ProtectedPreferences.get(context, "comfort_preferences")
        val before = original.all.toMap()
        val namespace = "qa_auto_music_${UUID.randomUUID()}"
        val owned = ProtectedPreferences.get(context, namespace)
        val component = ExternalMusic(context).browserComponent(MusicSource.VLC)!!
        val observations = CopyOnWriteArrayList<MusicSessionBrowser.Snapshot>()
        var browser: MusicSessionBrowser? = null
        var scenario: ActivityScenario<MainActivity>? = null
        var oldPrefs: Any? = null
        var oldMode: Any? = null
        var oldConsent = false
        var controller: PleosRecoveryController? = null
        var baseline: CabinTelemetry? = null
        val memory = org.json.JSONArray()
        fun memorySample(phase: String) {
            val sample = checkNotNull(ProcessMemoryReader.read()) { "Whole-process memory unavailable" }
            val swap = runCatching { File("/proc/self/smaps_rollup").useLines { lines ->
                lines.firstOrNull { it.startsWith("SwapPss:") }?.substringAfter(':')?.trim()
                    ?.substringBefore(' ')?.toLongOrNull()?.times(1024L)
            } }.getOrNull()
            check(sample.peakRssBytes > 0)
            assertTrue("Whole process exceeds internal working ceiling", maxOf(sample.rssBytes,
                sample.pssBytes, sample.peakRssBytes) <= MemoryBudgetPolicy.WORKING_LIMIT_BYTES)
            memory.put(JSONObject().put("phase", phase).put("rssBytes", sample.rssBytes)
                .put("pssBytes", sample.pssBytes).put("residentHighWaterBytes", sample.peakRssBytes)
                .put("swapPssBytes", swap ?: JSONObject.NULL)
                .put("pssAccounting", "Debug.getPss includes proportional swap on Android14; swap reported separately"))
        }
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun call(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredMethod(name)
            .apply { isAccessible = true }.invoke(activity)
        fun await(label: String, timeout: Long = 60_000, condition: () -> Boolean) {
            val deadline = android.os.SystemClock.uptimeMillis() + timeout
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                if (condition()) return
                Thread.sleep(50)
            }
            error(label)
        }
        fun awaitActivity(label: String, condition: (MainActivity) -> Boolean) = await(label) {
            var okay = false; scenario!!.onActivity { okay = condition(it) }; okay
        }
        fun latest() = observations.lastOrNull()?.takeIf { it.title.contains(ownedTitle) }
        fun pauseOwned() {
            check(latest() != null) { "Refuse to control unrelated song" }
            if (latest()!!.state != PlaybackState.STATE_PAUSED) {
                if (latest()!!.state != PlaybackState.STATE_PLAYING) {
                    instrument.runOnMainSync { check(browser!!.resume()) }
                    await("Owned player did not start", 10_000) { latest()?.state == PlaybackState.STATE_PLAYING }
                }
                instrument.runOnMainSync { check(browser!!.pause()) }
                await("Owned player did not pause", 10_000) { latest()?.state == PlaybackState.STATE_PAUSED }
            }
        }
        fun clickAutomatic() { scenario!!.onActivity {
            @Suppress("UNCHECKED_CAST")
            val buttons = field("comfortModeButtons").get(it) as Map<ComfortMode, Button>
            check(buttons.getValue(ComfortMode.AUTOMATIC).performClick())
        } }
        fun controls(value: CabinTelemetry) = listOf(value.driverTemperature, value.fan, value.powerOn,
            value.seatHeat, value.ventilation, value.seatCooling, value.steeringHeat)
        fun findSwitch(view: View): Switch? {
            if (view is Switch && view.text.toString() == "자동 Mode에서 음악 재개") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) findSwitch(view.getChildAt(i))?.let { return it }
            return null
        }
        try {
            instrument.runOnMainSync {
                browser = MusicSessionBrowser(context, component, browseCatalog = false) { observations.add(it) }.also { it.connect() }
            }
            await("Selected player not connected", 10_000) { observations.lastOrNull()?.connected == true }
            runBlocking { OwnedVlcSource.prepare(browser!!, { observations.lastOrNull() }, ownedTitle) }
            await("Owned song session absent", 10_000) { latest()?.connected == true }
            pauseOwned()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            awaitActivity("Parked app not ready") {
                call(it, "safeToAct") == true && field("driverSeatState").get(it) is DriverSeatState.Available &&
                    !field("busy").getBoolean(it) &&
                    (field("learningStoragePreparation").get(it) as? kotlinx.coroutines.Job)?.isActive != true &&
                    !(field("scene").get(it) as PremiumCabinView).let { scene ->
                        scene.javaClass.getDeclaredField("presentation").apply { isAccessible = true }.get(scene) as PreviewPresentationGate
                    }.covered
            }
            scenario!!.onActivity {
                check(!field("active").getBoolean(it))
                controller = field("controller").get(it) as PleosRecoveryController
                check(!controller!!.hasPending)
                oldPrefs = field("prefs\$delegate").get(it); oldMode = field("comfortMode").get(it)
                oldConsent = field("learningConsent").getBoolean(it)
                val settings = field("driverSettings").get(it) as DriverProfileSettings
                owned.edit().putString(settings.selected.preferencePrefix + "music_source", "VLC").commit()
                field("prefs\$delegate").set(it, lazy { owned })
                field("comfortMode").set(it, ComfortMode.AUTOMATIC)
                field("learningConsent").setBoolean(it, false)
                call(it, "showSoundSettings")
                val modal = field("modal").get(it) as UiPanel
                val toggle = findSwitch(modal.contentView)
                    ?: error("Music opt-in control missing")
                assertFalse("New profile consent must default OFF", toggle.isChecked)
                check(toggle.isEnabled)
                // CompoundButton toggles via its checked listener even when View has no click listener.
                toggle.performClick(); assertTrue(toggle.isChecked)
                assertTrue(owned.getBoolean(settings.selected.preferencePrefix + "music_auto_resume_VLC", false))
                modal.dismiss()
            }
            baseline = runBlocking { controller!!.cabinTelemetry() }
            memorySample("before-auto")
            clickAutomatic()
            awaitActivity("Auto mode did not settle") { field("active").getBoolean(it) && !field("busy").getBoolean(it) }
            await("Opted-in mode did not resume VLC", 10_000) { latest()?.state == PlaybackState.STATE_PLAYING }
            awaitActivity("Actual media acknowledgement absent") {
                val notice=field("autoMusicNotice").get(it) as android.widget.TextView
                field("autoMusicStatus").get(it) == "음악 앱이 재생 중임을 확인했어요." &&
                    notice.isShown && notice.text.toString().contains("VLC")
            }
            awaitActivity("Auto scene did not finish its actual submissions") {
                val scene=field("scene").get(it) as PremiumCabinView
                (scene.javaClass.getDeclaredField("frameBudget").apply { isAccessible=true }.get(scene) as RenderFrameBudget).remaining==0
            }
            memorySample("auto-playing-caption")
            clickAutomatic()
            awaitActivity("Auto OFF did not restore") {
                !field("active").getBoolean(it) && !field("busy").getBoolean(it) && !controller!!.hasPending &&
                    (field("autoMusicNotice").get(it) as android.view.View).visibility == android.view.View.GONE
            }
            assertEquals(controls(baseline!!), controls(runBlocking { controller!!.cabinTelemetry() }))
            assertEquals("Mode OFF must not pause external player", PlaybackState.STATE_PLAYING, latest()!!.state)
            memorySample("off-external-playing")
            pauseOwned()
            File(context.filesDir, "automatic-music-mode-result.json").writeText(JSONObject()
                .put("actualVehicleAutoButton", true).put("consentDefaultOff", true).put("consentToggleSaved", true)
                .put("vlcPlaybackAcknowledged", true).put("vehicleBaselineRestored", true)
                .put("modeOffPreservedExternalPlayback", true).put("originalPreferencesUnchanged", original.all == before)
                .put("homeMusicAcknowledgementVisible", true).put("homeMusicNoticeHiddenOnModeOff", true)
                .put("memorySamples", memory).put("wholeProcessWorkingCeilingBytes", MemoryBudgetPolicy.WORKING_LIMIT_BYTES)
                .put("physicalVehicleOrAcousticQualityVerified", false).toString(2))
        } finally {
            runCatching { if (latest()?.state == PlaybackState.STATE_PLAYING) pauseOwned() }
            scenario?.onActivity {
                (field("modal").get(it) as? UiPanel)?.dismiss()
                if (oldPrefs != null) {
                    field("prefs\$delegate").set(it, oldPrefs)
                    field("learningConsent").setBoolean(it, oldConsent)
                    if (!field("active").getBoolean(it) && !field("busy").getBoolean(it)) field("comfortMode").set(it, oldMode)
                }
            }
            scenario?.closeAndAwaitNativeRelease()
            instrument.runOnMainSync { browser?.close() }
            owned.edit().clear().commit(); context.deleteSharedPreferences(namespace)
        }
        assertEquals(before, original.all)
    }
}
