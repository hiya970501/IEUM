package com.example.version1

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Owned synthetic survey input, real product panel and encrypted DB. No vehicle setters or user consent writes. */
@RunWith(AndroidJUnit4::class)
class ComfortRatingUiDeviceTest {
    @Test fun surveySavesStarsAndCancelPreservesData() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("featureFixture")=="emulator")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val prefs=ProtectedPreferences.get(context,"comfort_preferences")
        val before=prefs.all.toMap()
        val directory=DriverProfileStore(context).load()
        val database="qa_rating_ui_${UUID.randomUUID()}.db"
        val store=ComfortLearningStore(context,database)
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }
        var snapshot:ComfortCase?=null
        var ratingShown=false
        try {
            val deadline=android.os.SystemClock.uptimeMillis()+60000
            while(!ratingShown && android.os.SystemClock.uptimeMillis()<deadline) {
                scenario.onActivity { activity ->
                    val safe=MainActivity::class.java.getDeclaredMethod("safeToAct").apply { isAccessible=true }.invoke(activity)==true
                    val scene=field("scene").get(activity)!!
                    val gate=scene.javaClass.getDeclaredField("presentation").apply { isAccessible=true }.get(scene) as PreviewPresentationGate
                    if(safe && !gate.covered && field("foreground").getBoolean(activity)) {
                        field("learningStore\$delegate").set(activity,lazy { store })
                        field("learningConsent").setBoolean(activity,true)
                        val actualContext=MainActivity::class.java.getDeclaredMethod("learningContext").apply { isAccessible=true }.invoke(activity) as ComfortContext
                        snapshot=ComfortCase("qa-survey-${UUID.randomUUID()}",System.currentTimeMillis(),
                            ComfortContext(actualContext.profile,actualContext.vehicle,"RUN",false,ComfortPhase.ARRIVAL,30f,60f,cabinC=29f),
                            LearnedSetting(23f,2,-2,0,1),false)
                        field("pendingComfortRating").set(activity,snapshot)
                        MainActivity::class.java.getDeclaredMethod("showComfortRating").apply { isAccessible=true }.invoke(activity)
                        ratingShown=true
                    }
                }
                if(!ratingShown) Thread.sleep(100)
            }
            assertTrue("Real parked preview must be ready before survey",ratingShown)
            fun click(label:String) {
                scenario.onActivity { activity ->
                    val panel=field("modal").get(activity) as UiPanel
                    fun find(view:View):Button? {
                        if(view is Button && view.text.toString()==label && view.isShown && view.isEnabled) return view
                        if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                        return null
                    }
                    assertTrue("Actual product survey button must exist",checkNotNull(find(panel.contentView)).performClick())
                }
            }
            click("나중에")
            assertEquals(0,store.count(directory.selectedProfileId))
            scenario.onActivity { MainActivity::class.java.getDeclaredMethod("showComfortRating").apply { isAccessible=true }.invoke(it) }
            click("1 ★")
            var saved=false
            val saveDeadline=android.os.SystemClock.uptimeMillis()+15000
            while(!saved && android.os.SystemClock.uptimeMillis()<saveDeadline) {
                scenario.onActivity { saved=field("pendingComfortRating").get(it)==null && !field("ratingSaving").getBoolean(it) }
                if(!saved) Thread.sleep(20)
            }
            assertTrue("Actual async save must complete",saved)
            val record=store.candidates(checkNotNull(snapshot).context).single()
            assertEquals(1,record.rating);assertFalse(record.satisfied)
            assertEquals(checkNotNull(snapshot).setting,record.setting)
            assertEquals(before,prefs.all);assertEquals(directory,DriverProfileStore(context).load())
        } finally {
            scenario.closeAndAwaitNativeRelease()
            store.close();context.deleteDatabase(database);context.deleteDatabase("$database.enc")
            ProtectedPreferences.get(context,"learning_database_keys").edit()
                .remove("key_$database").remove("migration_$database").commit()
        }
        assertEquals(before,prefs.all)
        File(context.filesDir,"comfort-rating-ui-result.json").writeText(JSONObject()
            .put("surveyInputSynthetic",true).put("actualPanelAndEncryptedStorage",true)
            .put("cancelPreservedData",true).put("lowRatingPersisted",true)
            .put("userPreferencesPreserved",true).put("vehicleCommandsIssued",false).toString(2))
    }
}
