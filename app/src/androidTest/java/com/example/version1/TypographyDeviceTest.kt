package com.example.version1

import android.graphics.Rect
import android.os.SystemClock
import android.util.TypedValue
import android.widget.TextView
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

/** Actual Android font paint on production views; reported-DPI proxy, not panel metrology. */
@RunWith(AndroidJUnit4::class)
class TypographyDeviceTest {
    @Test fun noticeRemainsSingleLineAtNominalFiveMillimetersWithoutShrinking() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("guidelineUi")=="emulator")
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        val rows=JSONArray()
        fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }
        try {
            val deadline=SystemClock.uptimeMillis()+60000
            var ready=false
            while(!ready && SystemClock.uptimeMillis()<deadline) {
                scenario.onActivity {
                    val scene=field("scene").get(it) as PremiumCabinView
                    val gate=scene.javaClass.getDeclaredField("presentation").apply { isAccessible=true }.get(scene) as PreviewPresentationGate
                    ready=!gate.covered && !field("busy").getBoolean(it) && !field("active").getBoolean(it)
                }
                if(!ready)Thread.sleep(100)
            }
            assertTrue("Real production preview required",ready)
            scenario.onActivity { activity ->
                val notice=field("drivingNotice").get(activity) as TextView
                val originalText=notice.text;val originalSize=notice.textSize;val originalMax=notice.maxWidth
                val metrics=activity.resources.displayMetrics
                fun verify(role:String,view:TextView,target:Float) {
                    val text=view.text.toString();val bounds=Rect()
                    view.paint.getTextBounds(text,0,text.length,bounds)
                    val required=PleosTypography.requiredInkPixels(target,metrics.ydpi,metrics.density)
                    assertTrue("Font ink below reported-DPI floor: $role",bounds.height()>=required)
                    rows.put(JSONObject().put("role",role).put("targetMm",target).put("requiredInkPx",required)
                        .put("actualInkPx",bounds.height()).put("fontPx",view.textSize)
                        .put("reportedYDpi",metrics.ydpi).put("density",metrics.density))
                }
                try {
                    // The actual state/visibility is untouched. Exercise all strings on
                    // the same production paint and the narrowest supported width.
                    for(width in listOf(originalMax,(484*metrics.density).toInt()).distinct()) {
                        notice.maxWidth=width
                        for((known,parked,ux) in listOf(Triple(false,false,false),Triple(true,true,false),Triple(true,false,false),Triple(true,false,true))) {
                            PleosTypography.safetyNotice(notice,DrivingNotice.forState(known,parked,ux),DrivingNotice.compactForState(known,parked,ux))
                            verify("notice",notice,5f)
                            assertEquals(1,notice.maxLines)
                            assertEquals(TextView.AUTO_SIZE_TEXT_TYPE_NONE,notice.autoSizeTextType)
                            assertTrue("Single-line notice must fit",notice.paint.measureText(notice.text.toString())<=width)
                        }
                    }
                    val modes=field("comfortModeButtons").get(activity) as Map<*,*>
                    modes.values.forEach { verify("mode-button",it as TextView,3f) }
                    verify("connection-button",field("connectButton").get(activity) as TextView,3f)
                    verify("settings-button",field("settingsButton").get(activity) as TextView,3f)
                }finally {
                    notice.text=originalText;notice.maxWidth=originalMax
                    notice.setTextSize(TypedValue.COMPLEX_UNIT_PX,originalSize)
                }
            }
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"typography-result.json").writeText(JSONObject()
                .put("passed",true).put("physicalMillimetersVerified",false).put("vehicleCommandsIssued",false)
                .put("scope","Production Android paint, 5mm notice and 3mm header using reported DPI; narrow-width text simulation, not physical calibration")
                .put("rows",rows).toString(2))
        }finally {scenario.closeAndAwaitNativeRelease()}
    }
}
