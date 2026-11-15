package com.example.version1

import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
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
import java.util.concurrent.TimeUnit

/** Recreates the actual screen during an owned directory write. Never writes
 * the user's directory or invokes a vehicle setter to test an admission gap. */
@RunWith(AndroidJUnit4::class)
class DriverWriteRecreationDeviceTest {
    @Test fun recreatedScreenKeepsPendingDirectoryWriteInterlock() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("driverRecreationFixture") == "emulator")
        val context = instrumentation.targetContext
        val actualStore = DriverProfileStore(context)
        val original = actualStore.load()
        val userRaw = context.getSharedPreferences("driver_profiles", 0)
        val userBefore = userRaw.all.toMap()
        val name = "qa_driver_recreate_${UUID.randomUUID()}"
        val raw = context.getSharedPreferences(name, 0)
        val fault = DriverProfilePublicationDeviceTest.FailedAcknowledgement(raw)
        val isolated = DriverProfileStore(ProtectedPreferences(fault, "qa-driver-recreate:$name")).apply { save(original) }
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun method(name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }
        val canChange = method("canChange"); val safe = method("safeToAct")
        val persist = MainActivity::class.java.getDeclaredMethod("persistDriverConsentAsync",
            DriverProfileSettings::class.java, kotlin.jvm.functions.Function1::class.java).apply { isAccessible = true }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var old: MainActivity? = null; var oldStore: Any? = null; var oldResult: Any? = null
        val receipt = JSONObject().put("passed", false).put("vehicleCommandsIssued", false)
            .put("actualUserDirectoryWritten", false).put("isolatedCleanupConfirmed", false)
        fun oldWritePending(): Boolean {
            var pending = false
            instrumentation.runOnMainSync { old?.let { pending = field("driverWriteInFlight").getBoolean(it) } }
            return pending
        }
        try {
            var ready = false; val deadline = SystemClock.uptimeMillis() + 60_000
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { ready = canChange.invoke(it) as Boolean }; if (!ready) SystemClock.sleep(100)
            }
            assertTrue("Actual parked/UX-ready screen required", ready)
            fault.holdNext = true
            scenario.onActivity { activity ->
                old = activity; oldStore = field("driverStore\$delegate").get(activity); oldResult = field("result").get(activity)
                field("driverStore\$delegate").set(activity, lazyOf(isolated))
                val done: (Boolean) -> Unit = { }
                persist.invoke(activity, original.copy(autoSelectPairedPhone = !original.autoSelectPairedPhone), done)
            }
            assertTrue("Owned disk barrier reached", fault.entered.await(2, TimeUnit.SECONDS))
            scenario.recreate()
            var safeAfter = false; var admitted = false; var settingsEnabled = false; var modesDisabled = false; var progressVisible = false
            val recreatedDeadline = SystemClock.uptimeMillis() + 2_500
            while (!safeAfter && SystemClock.uptimeMillis() < recreatedDeadline) {
                scenario.onActivity { activity ->
                    assertNotSame("Actual new Activity required", old, activity)
                    assertTrue("Original owner still holds the disk writer", field("driverWriteInFlight").getBoolean(old))
                    assertFalse("New screen has no local writer flag", field("driverWriteInFlight").getBoolean(activity))
                    safeAfter = safe.invoke(activity) as Boolean
                    if (safeAfter) {
                        admitted = canChange.invoke(activity) as Boolean
                        settingsEnabled = (field("settingsButton").get(activity) as Button).isEnabled
                        @Suppress("UNCHECKED_CAST")
                        val modes = field("comfortModeButtons").get(activity) as Map<ComfortMode, Button>
                        modesDisabled = modes.values.all { !it.isEnabled }
                        val status = field("resultLabel").get(activity) as TextView
                        progressVisible = status.isShown && status.text.toString().contains("저장")
                    }
                }
                if (!safeAfter) SystemClock.sleep(25)
            }
            receipt.put("actualRecreation", true).put("knownParkAndUxAfterRecreation", safeAfter)
                .put("oldOwnerWriteStillPending", oldWritePending()).put("newScreenAdmittedChange", admitted)
                .put("newSettingsEnabled", settingsEnabled).put("newModesDisabled", modesDisabled)
                .put("pendingProgressVisible", progressVisible)
            assertTrue("Known actual P/UX before testing admission", safeAfter)
            assertTrue("Writer must remain held during the admission observation", oldWritePending())
            assertFalse("New screen must not admit another directory/vehicle change during the old owner's write", admitted)
            assertFalse("New settings control must reflect the shared pending interlock", settingsEnabled)
            assertTrue(modesDisabled); assertTrue("Shared pending write needs visible progress", progressVisible)
            fault.release.countDown()
            val completed = SystemClock.uptimeMillis() + 5_000
            while (oldWritePending() && SystemClock.uptimeMillis() < completed) SystemClock.sleep(25)
            assertFalse(oldWritePending())
            var unlocked = false
            val unlockDeadline = SystemClock.uptimeMillis() + 5_000
            while (!unlocked && SystemClock.uptimeMillis() < unlockDeadline) {
                scenario.onActivity { unlocked = canChange.invoke(it) as Boolean }
                if (!unlocked) SystemClock.sleep(25)
            }
            assertTrue("Acknowledgement must release the shared interlock for the new screen", unlocked)
            assertEquals(userBefore, userRaw.all); assertEquals(original, actualStore.load())
            receipt.put("userDirectoryUnchanged", true).put("newScreenUnlocksAfterAcknowledgement", true).put("passed", true)
        } finally {
            fault.release.countDown()
            val completed = SystemClock.uptimeMillis() + 5_000
            while (oldWritePending() && SystemClock.uptimeMillis() < completed) SystemClock.sleep(25)
            val knownDone = !oldWritePending()
            if (knownDone) {
                scenario.onActivity { activity ->
                    if (activity === old) {
                        field("driverStore\$delegate").set(activity, oldStore)
                        field("driverSettings").set(activity, original); field("result").set(activity, oldResult)
                    }
                }
                scenario.close(); raw.edit().clear().commit(); context.deleteSharedPreferences(name)
                receipt.put("isolatedCleanupConfirmed", true)
            }
            receipt.put("userDirectoryUnchanged", userBefore == userRaw.all && actualStore.load() == original)
            File(context.filesDir, "driver-recreation-write-result.json").writeText(receipt.toString())
            check(knownDone) { "Preserve isolated namespace until original writer completion is known" }
        }
    }
}
