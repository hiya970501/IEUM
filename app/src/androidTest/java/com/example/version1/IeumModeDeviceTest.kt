package com.example.version1

import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual emulator SDK apply/restore from the real buttons. No physical vehicle claim or audible playback. */
@RunWith(AndroidJUnit4::class)
class IeumModeDeviceTest {
    @Test fun automaticButtonAppliesThenSwitchToLearningRestoresBeforeObservation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("modeControlFixture")=="emulator")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val originalPreferences=ProtectedPreferences.get(context,"comfort_preferences")
        val beforePreferences=originalPreferences.all.toMap()
        val namespace="qa_ieum_mode_${java.util.UUID.randomUUID()}"
        val owned=ProtectedPreferences.get(context,namespace)
        val database="$namespace.db"
        val ownedStore=ComfortLearningStore(context,database)
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        val feedback=if(InstrumentationRegistry.getArguments().getString("modeFeedbackFixture")=="emulator")
            OwnedModeFeedback(scenario,context) else null
        fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }
        var previousPrefs:Any?=null
        var previousMode:Any?=null
        var previousSound:Any?=null
        var previousConsent=false
        var previousLearningStore:Any?=null
        var previousLearningPrivacy:Any?=null
        var previousPrepared=false
        var controller:PleosRecoveryController?=null
        var baseline:CabinTelemetry?=null
        val preparationRelease=java.util.concurrent.CountDownLatch(1)
        fun await(label:String,condition:(MainActivity)->Boolean) {
            val deadline=android.os.SystemClock.uptimeMillis()+60000
            while(android.os.SystemClock.uptimeMillis()<deadline) {
                var ready=false;scenario.onActivity { ready=condition(it) }
                if(ready) return
                Thread.sleep(50)
            }
            error(label)
        }
        fun click(mode:ComfortMode) {
            if(feedback!=null){feedback.click(mode);return}
            scenario.onActivity {
            @Suppress("UNCHECKED_CAST")
            val buttons=field("comfortModeButtons").get(it) as Map<ComfortMode,Button>
            val button=buttons.getValue(mode);assertTrue(button.isShown && button.isEnabled);assertTrue(button.performClick())
        } }
        fun settled(mode:ComfortMode?,running:Boolean) = await("Mode apply/restore did not settle") {
            !field("busy").getBoolean(it) && field("active").getBoolean(it)==running &&
                (mode==null || field("comfortMode").get(it)==mode) &&
                (running || !(field("controller").get(it) as PleosRecoveryController).hasPending)
        }
        fun settings(value:CabinTelemetry)=listOf(value.driverTemperature,value.fan,value.powerOn,
            value.seatHeat,value.ventilation,value.seatCooling,value.steeringHeat)
        try {
            await("Parked real vehicle preview and driver required") {
                val safe=MainActivity::class.java.getDeclaredMethod("safeToAct").apply { isAccessible=true }.invoke(it)==true
                val scene=field("scene").get(it) as PremiumCabinView
                val gate=scene.javaClass.getDeclaredField("presentation").apply { isAccessible=true }.get(scene) as PreviewPresentationGate
                safe && !gate.covered && field("driverSeatState").get(it) is DriverSeatState.Available &&
                    !field("busy").getBoolean(it) &&
                    (field("learningStoragePreparation").get(it) as? kotlinx.coroutines.Job)?.isActive != true
            }
            scenario.onActivity {
                assertFalse(field("active").getBoolean(it))
                controller=field("controller").get(it) as PleosRecoveryController
                assertFalse(controller!!.hasPending)
                previousPrefs=field("prefs\$delegate").get(it);previousMode=field("comfortMode").get(it)
                previousConsent=field("learningConsent").getBoolean(it);previousSound=field("soundPreset").get(it)
                previousLearningStore=field("learningStore\$delegate").get(it)
                previousLearningPrivacy=field("learningPrivacy\$delegate").get(it)
                previousPrepared=field("learningStoragePrepared").getBoolean(it)
                field("prefs\$delegate").set(it,lazy { owned })
                field("learningStore\$delegate").set(it,lazy { ownedStore })
                field("learningPrivacy\$delegate").set(it,lazy { LearningPrivacyStore(owned,ownedStore::delete) })
                field("learningStoragePrepared").setBoolean(it,false)
                field("learningConsent").setBoolean(it,true)
                field("soundPreset").set(it,SoundPreset.OFF)
                MainActivity::class.java.getDeclaredMethod("prepareLearningStorageIfNeeded").apply { isAccessible=true }.invoke(it)
            }
            await("Consented learning storage required") { field("learningStoragePrepared").getBoolean(it) }
            baseline=runBlocking { controller!!.cabinTelemetry() }
            if(InstrumentationRegistry.getArguments().getString("modePreparationBackgroundFixture")=="emulator") {
                check(feedback==null) { "Blocked IO lifecycle fixture is separate from gesture latency" }
                val preparationMode=if(InstrumentationRegistry.getArguments().getString("modePreparationMode")=="LEARNING") ComfortMode.LEARNING else ComfortMode.AUTOMATIC
                val entered=java.util.concurrent.CountDownLatch(1)
                val blocked=java.util.concurrent.atomic.AtomicBoolean(false)
                val persistenceCompleted=java.util.concurrent.atomic.AtomicBoolean(false)
                val wrapped=object:android.content.SharedPreferences by owned {
                    override fun edit():android.content.SharedPreferences.Editor {
                        val editor=owned.edit()
                        return object:android.content.SharedPreferences.Editor by editor {
                            private var trip=false
                            override fun putString(key:String?,value:String?):android.content.SharedPreferences.Editor {
                                if(key?.endsWith("learning_trip_id")==true) trip=true
                                editor.putString(key,value);return this
                            }
                            override fun putLong(key:String?,value:Long):android.content.SharedPreferences.Editor {
                                editor.putLong(key,value);return this
                            }
                            override fun apply() {
                                if(trip && blocked.compareAndSet(false,true)) {
                                    check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) { "Trip persistence blocked Main" }
                                    entered.countDown()
                                    check(preparationRelease.await(45,java.util.concurrent.TimeUnit.SECONDS)) { "Preparation fixture release timed out" }
                                }
                                editor.apply()
                                if(trip) persistenceCompleted.set(true)
                            }
                        }
                    }
                }
                scenario.onActivity { field("prefs\$delegate").set(it,lazy { wrapped }) }
                click(preparationMode)
                assertTrue("Trip persistence must execute on IO",entered.await(15,java.util.concurrent.TimeUnit.SECONDS))
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                await("Foreground owner/driver must return while old IO remains blocked") {
                    field("foreground").getBoolean(it) && field("driverSeatState").get(it) is DriverSeatState.Available
                }
                // Resume intentionally resets gear to unknown; the normal poll
                // waits for busy=false. Demanding a ready P-only native preview
                // BEFORE releasing IO would deadlock the fixture's own gate.
                scenario.onActivity { assertFalse("Unknown gear must stay blocked during pending IO",field("parked").getBoolean(it)) }
                preparationRelease.countDown()
                settled(preparationMode,false)
                assertTrue("Cancellation must follow completed persistence, not an IO timeout",persistenceCompleted.get())
                await("Actual parked preview/driver must return after cancellation") {
                    val scene=field("scene").get(it) as PremiumCabinView
                    val gate=scene.javaClass.getDeclaredField("presentation").apply { isAccessible=true }.get(scene) as PreviewPresentationGate
                    return@await !gate.covered && !gate.failed && field("parked").getBoolean(it) && field("driverSeatState").get(it) is DriverSeatState.Available
                }
                assertFalse("Resuming cannot revive the old command",controller!!.hasPending)
                assertEquals("Cancelled preparation must leave actual SDK settings unchanged",
                    settings(baseline!!),settings(runBlocking { controller!!.cabinTelemetry() }))
                scenario.onActivity { field("prefs\$delegate").set(it,lazy { owned }) }
                java.io.File(context.filesDir,"mode-preparation-background-result.json").writeText(
                    org.json.JSONObject().put("ioWorkerVerified",true).put("backgroundThenResumeBeforeIoRelease",true)
                        .put("mode",preparationMode.name)
                        .put("staleCommandRejected",true).put("actualSdkSettingsPreserved",true)
                        .put("actualParkedPreviewVerifiedAfterCancellation",true).toString())
            }
            click(ComfortMode.AUTOMATIC);settled(ComfortMode.AUTOMATIC,true)
            val applied=runBlocking { controller!!.cabinTelemetry() }
            scenario.onActivity {
                val plan=field("plan").get(it) as RecoveryPlan
                assertEquals(plan.temperature,applied.driverTemperature!!,0.1f)
                assertEquals(plan.fan,applied.fan)
            }
            if(InstrumentationRegistry.getArguments().getString("modeBackgroundFixture")=="emulator") {
                check(feedback==null) { "Background restoration is functional coverage, not the3-gesture latency probe" }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                settled(ComfortMode.AUTOMATIC,false)
                // Background deliberately clears the live driver binding; its
                // telemetry is unknown, not zero or proof of failed restoration.
                assertFalse(controller!!.hasPending)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                await("Foreground real vehicle must return before a second request") {
                    val scene=field("scene").get(it) as PremiumCabinView
                    val gate=scene.javaClass.getDeclaredField("presentation").apply { isAccessible=true }.get(scene) as PreviewPresentationGate
                    return@await !gate.covered && !gate.failed &&
                        MainActivity::class.java.getDeclaredMethod("safeToAct").apply { isAccessible=true }.invoke(it)==true &&
                        field("driverSeatState").get(it) is DriverSeatState.Available
                }
                assertEquals("First read after driver reacquisition must match original comfort values",
                    settings(baseline!!),settings(runBlocking { controller!!.cabinTelemetry() }))
                click(ComfortMode.AUTOMATIC);settled(ComfortMode.AUTOMATIC,true)
            }
            // A different mode is one request: old settings restored before learning starts.
            click(ComfortMode.LEARNING);settled(ComfortMode.LEARNING,true)
            assertEquals(settings(baseline!!),settings(runBlocking { controller!!.cabinTelemetry() }))
            click(ComfortMode.LEARNING);settled(ComfortMode.LEARNING,false)
            assertEquals(settings(baseline!!),settings(runBlocking { controller!!.cabinTelemetry() }))
            assertFalse(controller!!.hasPending)
        } finally {
            preparationRelease.countDown()
            feedback?.close()
            // Close triggers the production restoration path if any earlier assertion failed.
            scenario.onActivity {
                if(previousPrefs!=null) {
                    field("prefs\$delegate").set(it,previousPrefs)
                    field("learningStore\$delegate").set(it,previousLearningStore)
                    field("learningPrivacy\$delegate").set(it,previousLearningPrivacy)
                    field("learningStoragePrepared").setBoolean(it,previousPrepared)
                    field("soundPreset").set(it,previousSound)
                    field("learningConsent").setBoolean(it,previousConsent)
                    if(!field("active").getBoolean(it) && !field("busy").getBoolean(it)) field("comfortMode").set(it,previousMode)
                }
            }
            scenario.closeAndAwaitNativeRelease()
            owned.edit().clear().commit();context.deleteSharedPreferences(namespace)
            ownedStore.close();context.deleteDatabase(database);context.deleteDatabase("$database.enc")
            ProtectedPreferences.get(context,"learning_database_keys").edit()
                .remove("key_$database").remove("migration_$database").commit()
        }
        assertEquals("Original user preferences must remain unchanged",beforePreferences,originalPreferences.all)
        feedback?.receipt(true,true)
    }
}
