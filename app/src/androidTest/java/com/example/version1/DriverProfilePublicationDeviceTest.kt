package com.example.version1

import android.content.SharedPreferences
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Switch
import java.util.concurrent.atomic.AtomicLong
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.lang.ref.WeakReference
import android.widget.TextView

/** Inject a failed disk acknowledgement in an isolated store, through actual Main selection.
 * Never writes the user's driver directory or issues vehicle setting commands. */
@RunWith(AndroidJUnit4::class)
class DriverProfilePublicationDeviceTest {
    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    fun actualConsentTapCommitsPendingStatusWithin250msWhileDiskIsBlocked() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("driverPublicationFixture") == "emulator")
        // Establish the injector before taking any DOWN timestamp. First access
        // can connect the test accessibility service; that is harness setup,
        // not an OS input event waiting in the app's queue.
        val automation = instrumentation.uiAutomation
        val context = instrumentation.targetContext
        val actualStore = DriverProfileStore(context)
        val original = actualStore.load()
        val userRaw = context.getSharedPreferences("driver_profiles", 0)
        val userBefore = userRaw.all.toMap()
        val name = "qa_consent_frame_${UUID.randomUUID()}"
        val raw = context.getSharedPreferences(name, 0)
        val fault = FailedAcknowledgement(raw)
        val secured = ProtectedPreferences(fault, "qa-consent-frame:$name")
        val isolated = DriverProfileStore(secured).apply { save(original) }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        val canChange = MainActivity::class.java.getDeclaredMethod("canChange").apply { isAccessible = true }
        var oldStore: Any? = null; var oldResult: Any? = null; var injected = false
        var root: View? = null; var listener: ViewTreeObserver.OnPreDrawListener? = null
        val committedAt = AtomicLong()
        val frame = CountDownLatch(1)
        val committed = Runnable { committedAt.compareAndSet(0, SystemClock.uptimeMillis()); frame.countDown() }
        fun inFlight(): Boolean { var pending = false; scenario.onActivity { pending = field("driverWriteInFlight").get(it) as Boolean }; return pending }
        try {
            var ready = false
            val deadline = SystemClock.uptimeMillis() + 60_000
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { ready = canChange.invoke(it) as Boolean }; if (!ready) SystemClock.sleep(100)
            }
            assertTrue("Actual P/UX required", ready)
            scenario.onActivity { activity ->
                oldStore = field("driverStore\$delegate").get(activity); oldResult = field("result").get(activity)
                field("driverStore\$delegate").set(activity, lazyOf(isolated)); injected = true
                MainActivity::class.java.getDeclaredMethod("showDriverProfiles").apply { isAccessible = true }.invoke(activity)
            }
            var point: FloatArray? = null
            val layoutDeadline = SystemClock.uptimeMillis() + 3_000
            while (point == null && SystemClock.uptimeMillis() < layoutDeadline) {
                scenario.onActivity { activity ->
                    val status = (field("driverConsentStatusView").get(activity) as WeakReference<*>).get() as TextView
                    fun switches(view: View): List<Switch> = if (view is Switch) listOf(view)
                        else if (view is ViewGroup) (0 until view.childCount).flatMap { switches(view.getChildAt(it)) } else emptyList()
                    val control = switches(status.parent as View).single()
                    val bounds = android.graphics.Rect()
                    if (control.isEnabled && control.getLocalVisibleRect(bounds) && bounds.height() >= 48) {
                        // Pleos places this Activity in a pane with a screen offset.
                        // GlobalVisibleRect is root-relative, not an OS input coordinate.
                        val screen = IntArray(2); control.getLocationOnScreen(screen)
                        point = floatArrayOf(screen[0] + bounds.exactCenterX(), screen[1] + bounds.exactCenterY())
                        val decor = activity.window.decorView
                        check(decor.isHardwareAccelerated)
                        root = decor
                        var registered = false
                        listener = ViewTreeObserver.OnPreDrawListener {
                            if (!registered && status.isShown && status.text.toString().contains("저장하고")) {
                                registered = true; decor.viewTreeObserver.registerFrameCommitCallback(committed)
                            }
                            true
                        }.also { decor.viewTreeObserver.addOnPreDrawListener(it) }
                    }
                }
                if (point == null) SystemClock.sleep(50)
            }
            val coordinates = checkNotNull(point) { "Visible actual consent switch required" }
            fault.holdNext = true
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, coordinates[0], coordinates[1], 0)
                    .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { check(automation.injectInputEvent(event, false)) } finally { event.recycle() }
                if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(16)
            }
            assertTrue("Actual tap reached the owned delayed writer", fault.entered.await(2, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                assertEquals("Main must receive the exact OS DOWN event", down, field("lastTouchDownUptime").getLong(activity))
            }
            assertTrue("Pending status needs a committed hardware frame", frame.await(1, TimeUnit.SECONDS))
            val elapsed = committedAt.get() - down
            assertTrue("Actual DOWN-to-pending-frame must be 0..250ms; measured=$elapsed", elapsed in 0..250)
            assertTrue(inFlight()); assertEquals(original, isolated.load())
            // Inline panels deliver their dismissal callback on the next Main
            // message, matching native Dialog ordering. Do not assert it inline.
            scenario.onActivity { activity ->
                (field("modal").get(activity) as UiPanel).dismiss()
            }
            var homeProgress = false
            val closeDeadline = SystemClock.uptimeMillis() + 1_000
            while (!homeProgress && SystemClock.uptimeMillis() < closeDeadline) {
                scenario.onActivity { activity ->
                    val progress = field("resultLabel").get(activity) as TextView
                    homeProgress = field("modal").get(activity) == null && progress.isShown &&
                        progress.getGlobalVisibleRect(android.graphics.Rect()) && progress.text.toString().contains("저장하고")
                    assertFalse(canChange.invoke(activity) as Boolean)
                }
                if (!homeProgress) SystemClock.sleep(10)
            }
            assertTrue("Pending save remains visibly laid out after closing its panel", homeProgress)
            fault.release.countDown()
            val writeDeadline = SystemClock.uptimeMillis() + 5_000
            while (inFlight() && SystemClock.uptimeMillis() < writeDeadline) SystemClock.sleep(25)
            assertFalse("Owned write must finish before cleanup", inFlight())
            assertEquals(original.copy(autoSelectPairedPhone = !original.autoSelectPairedPhone), isolated.load())
            assertEquals(userBefore, userRaw.all)
            scenario.onActivity { assertNull("Completed toggle must not reopen a closed panel", field("modal").get(it)) }
            File(context.filesDir, "driver-consent-frame-result.json").writeText(JSONObject()
                .put("passed", true).put("actualOsTouch", true).put("touchHoldMs", 16).put("samples", 1)
                .put("downToPendingFrameCommitMs", elapsed).put("pendingFrameWithin250", true)
                .put("unacknowledgedValueNotPublished", true).put("userDirectoryUnchanged", true)
                .put("pendingPanelClosedShowsHomeStatus", true).put("completionDoesNotReopenPanel", true)
                .put("inputChannelInitializedBeforeDown", true)
                .put("vehicleCommandsIssued", false).put("physicalScanoutVerified", false)
                .put("scope", "One actual consent tap to progress frame under held disk commit; not final storage latency or every-button certification").toString())
        } finally {
            fault.release.countDown()
            if (injected) {
                val deadline = SystemClock.uptimeMillis() + 5_000
                while (inFlight() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
                check(!inFlight()) { "Preserve owned namespace while write completion is unknown" }
                scenario.onActivity { activity ->
                    root?.viewTreeObserver?.let { observer ->
                        listener?.let { observer.removeOnPreDrawListener(it) }; observer.unregisterFrameCommitCallback(committed)
                    }
                    field("driverStore\$delegate").set(activity, oldStore)
                    field("driverSettings").set(activity, original); field("result").set(activity, oldResult)
                    MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
                }
            }
            scenario.close(); raw.edit().clear().commit(); context.deleteSharedPreferences(name)
        }
    }

    @Test fun delayedConsentWriteDoesNotBlockMainAndFailureCannotPublishOrOverwriteNewerState() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("driverPublicationFixture") == "emulator")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val actualStore = DriverProfileStore(context)
        val original = actualStore.load()
        val userRaw = context.getSharedPreferences("driver_profiles", 0)
        val userCiphertext = userRaw.all.toMap()
        val name = "qa_driver_consent_${UUID.randomUUID()}"
        val raw = context.getSharedPreferences(name, 0)
        val fault = FailedAcknowledgement(raw)
        val secured = ProtectedPreferences(fault, "qa-driver-consent:$name")
        val isolated = DriverProfileStore(secured)
        isolated.save(original)
        // A newer durable directory must survive an obsolete consent request.
        val newer = original.copy(autoSelectPairedPhone = !original.autoSelectPairedPhone)
        isolated.save(newer)
        assertFalse(isolated.saveIfUnchanged(original, original))
        assertEquals(newer, isolated.load())
        isolated.save(original)
        val before = raw.all.toMap()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        val changeAllowed = MainActivity::class.java.getDeclaredMethod("canChange").apply { isAccessible = true }
        val persist = MainActivity::class.java.getDeclaredMethod("persistDriverConsentAsync",
            DriverProfileSettings::class.java, kotlin.jvm.functions.Function1::class.java).apply { isAccessible = true }
        var activityStore: Any? = null
        var activityResult: Any? = null
        var injected = false
        val callback = AtomicReference<Boolean?>()
        val completed = CountDownLatch(1)
        try {
            val deadline = SystemClock.uptimeMillis() + 60_000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { ready = changeAllowed.invoke(it) as Boolean }
                if (!ready) SystemClock.sleep(100)
            }
            assertTrue("Actual park/UX required", ready)
            fault.holdNext = true; fault.failNext = true
            scenario.onActivity { activity ->
                activityStore = field("driverStore\$delegate").get(activity)
                activityResult = field("result").get(activity)
                field("driverStore\$delegate").set(activity, lazyOf(isolated)); injected = true
                MainActivity::class.java.getDeclaredMethod("showDriverProfiles").apply { isAccessible = true }.invoke(activity)
                val done: (Boolean) -> Unit = { callback.set(it); completed.countDown() }
                persist.invoke(activity, newer, done)
                assertEquals(original, field("driverSettings").get(activity))
                assertTrue(field("driverWriteInFlight").get(activity) as Boolean)
            }
            assertTrue("Worker reached owned blocked disk write", fault.entered.await(2, TimeUnit.SECONDS))
            val pulse = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { pulse.countDown() }
            assertTrue("Main still dispatches while disk acknowledgement is blocked", pulse.await(500, TimeUnit.MILLISECONDS))
            assertNull(callback.get())
            assertFalse("Commit must run off Main", fault.commitWasMain)
            val readDone = CountDownLatch(1)
            val readFailure = AtomicReference<Throwable?>()
            Handler(Looper.getMainLooper()).post {
                try {
                    assertEquals("Pending write exposes only the acknowledged directory", original, isolated.load())
                    val detached = secured.all
                    detached.clear()
                    assertEquals("Returned map cannot mutate the acknowledged cache", original, isolated.load())
                } catch (error: Throwable) { readFailure.set(error) }
                finally { readDone.countDown() }
            }
            assertTrue("Already-loaded Main reads must not wait for the held disk writer", readDone.await(500, TimeUnit.MILLISECONDS))
            readFailure.get()?.let { throw AssertionError("Acknowledged snapshot read failed", it) }
            assertNull("Read must not release or acknowledge the pending writer", callback.get())
            scenario.onActivity { activity ->
                assertEquals(original, field("driverSettings").get(activity))
                assertFalse("Other profile/mode changes blocked during pending write", changeAllowed.invoke(activity) as Boolean)
                val status = (field("driverConsentStatusView").get(activity) as WeakReference<*>).get() as TextView
                assertTrue("Pending status must be visible in the actual profile panel", status.isShown)
                assertTrue(status.text.toString().contains("저장하고"))
            }
            fault.release.countDown()
            assertTrue("Failure callback completed", completed.await(5, TimeUnit.SECONDS))
            assertEquals(false, callback.get())
            assertEquals(original, isolated.load())
            assertEquals(before, raw.all)
            scenario.onActivity { activity ->
                assertEquals(original, field("driverSettings").get(activity))
                assertFalse(field("driverWriteInFlight").get(activity) as Boolean)
            }
            // Retry must publish only after its actual durable acknowledgement.
            val retry = CountDownLatch(1)
            scenario.onActivity { activity ->
                val done: (Boolean) -> Unit = { callback.set(it); retry.countDown() }
                persist.invoke(activity, newer, done)
            }
            assertTrue(retry.await(5, TimeUnit.SECONDS)); assertEquals(true, callback.get())
            assertEquals(newer, isolated.load())
            scenario.onActivity { assertEquals(newer, field("driverSettings").get(it)) }
            assertEquals(userCiphertext, userRaw.all); assertEquals(original, actualStore.load())
            File(context.filesDir, "driver-consent-io-result.json").writeText(JSONObject()
                .put("passed", true).put("mainDispatchesWhileDiskBlocked", true)
                .put("commitOffMain", true).put("unacknowledgedConsentNotPublished", true)
                .put("failedWriteRolledBack", true).put("acknowledgedRetryPublishes", true)
                .put("obsoleteDirectoryRejected", true).put("userDirectoryUnchanged", true)
                .put("pendingStatusVisibleInProfilePanel", true)
                .put("mainReadsConfirmedSnapshotWhileDiskBlocked", true).put("returnedMapCannotMutateCache", true)
                .put("vehicleCommandsIssued", false).put("visibleLatencyCertification", false).toString())
        } finally {
            fault.release.countDown()
            if (injected) {
                completed.await(5, TimeUnit.SECONDS)
                scenario.onActivity { activity ->
                    field("driverStore\$delegate").set(activity, activityStore)
                    field("driverSettings").set(activity, original)
                    field("result").set(activity, activityResult)
                    MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
                }
            }
            scenario.close(); fault.failNext = false
            secured.edit().clear().commit(); context.deleteSharedPreferences(name)
        }
    }

    @Test fun failedSelectionKeepsCurrentIdentityAndHealthThenAcknowledgedSavePublishes() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("driverPublicationFixture") == "emulator")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val actualStore = DriverProfileStore(context)
        val original = actualStore.load()
        val userRaw = context.getSharedPreferences("driver_profiles", 0)
        val originalCiphertext = userRaw.all.toMap()
        val comfort = ProtectedPreferences.get(context, "comfort_preferences")
        val comfortBefore = comfort.all.toMap()
        val name = "qa_driver_publish_${UUID.randomUUID()}"
        val raw = context.getSharedPreferences(name, 0)
        val fault = FailedAcknowledgement(raw)
        val secured = ProtectedPreferences(fault, "qa-driver-publication:$name")
        val isolated = DriverProfileStore(secured)
        isolated.save(original)
        val before = raw.all.toMap()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        val selection = MainActivity::class.java.getDeclaredMethod("selectDriver", String::class.java,
            DriverProfileSettings::class.java).apply { isAccessible = true }
        val persist = MainActivity::class.java.getDeclaredMethod("persistDriverSettings",
            DriverProfileSettings::class.java).apply { isAccessible = true }
        val changeAllowed = MainActivity::class.java.getDeclaredMethod("canChange").apply { isAccessible = true }
        var originalActivityStore: Any? = null
        var originalResult: Any? = null
        var injected = false
        try {
            var ready = false
            val deadline = SystemClock.uptimeMillis() + 60_000
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { ready = changeAllowed.invoke(it) as Boolean }
                if (!ready) SystemClock.sleep(150)
            }
            assertTrue("Known parked ready app required; restrictions are never overridden", ready)
            scenario.onActivity { activity ->
                assertEquals(original, field("driverSettings").get(activity))
                originalActivityStore = field("driverStore\$delegate").get(activity)
                originalResult = field("result").get(activity)
                val healthBefore = field("watchBpm").get(activity)
                val next = original.add("QA 저장 실패")
                field("driverStore\$delegate").set(activity, lazyOf(isolated)); injected = true
                fault.failNext = true
                assertFalse(selection.invoke(activity, next.selectedProfileId, next) as Boolean)
                assertEquals(original, field("driverSettings").get(activity))
                assertEquals(healthBefore, field("watchBpm").get(activity))
                assertEquals(before, raw.all)
                assertEquals(original, isolated.load())
                assertTrue(field("result").get(activity).toString().contains("기존 프로필을 유지"))
                // A retry with a confirmed write may publish only the consent flag;
                // no driver's health/preferences are selected in this retry.
                val acknowledged = original.copy(autoSelectPairedPhone = !original.autoSelectPairedPhone)
                assertTrue(persist.invoke(activity, acknowledged) as Boolean)
                assertEquals(acknowledged, field("driverSettings").get(activity))
                assertEquals(acknowledged, isolated.load())
                assertEquals(healthBefore, field("watchBpm").get(activity))
            }
            assertEquals(originalCiphertext, userRaw.all)
            assertEquals(original, actualStore.load())
            assertEquals(comfortBefore, comfort.all)
            File(context.filesDir, "driver-publication-result.json").writeText(JSONObject()
                .put("passed", true).put("failedSelectionRetainsIdentity", true)
                .put("failedSelectionRetainsHealth", true).put("failedBackingWriteRolledBack", true)
                .put("acknowledgedRetryPublishes", true).put("userDirectoryUnchanged", true)
                .put("comfortPreferencesUnchanged", true).put("vehicleCommandsIssued", false).toString())
        } finally {
            if (injected) scenario.onActivity { activity ->
                field("driverStore\$delegate").set(activity, originalActivityStore)
                field("driverSettings").set(activity, original)
                field("result").set(activity, originalResult)
                MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
            }
            scenario.close()
            fault.failNext = false
            secured.edit().clear().commit()
            context.deleteSharedPreferences(name)
        }
    }

    /** Android can update its in-memory map even when the disk acknowledgement fails. */
    internal class FailedAcknowledgement(private val raw: SharedPreferences) : SharedPreferences by raw {
        @Volatile var failNext = false
        @Volatile var holdNext = false
        @Volatile var commitWasMain = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun edit(): SharedPreferences.Editor {
            val delegate = raw.edit()
            return object : SharedPreferences.Editor by delegate {
                override fun clear(): SharedPreferences.Editor { delegate.clear(); return this }
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    delegate.putString(key, value); return this
                }
                override fun commit(): Boolean {
                    if (holdNext) {
                        holdNext = false
                        commitWasMain = Looper.myLooper() == Looper.getMainLooper()
                        entered.countDown()
                        check(release.await(4, TimeUnit.SECONDS)) { "Owned disk barrier timed out" }
                    }
                    val actual = delegate.commit()
                    return if (failNext) { failNext = false; false } else actual
                }
            }
        }
    }
}
