package com.example.version1

import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Public view lifecycle fixture only: not a touch latency or vehicle-control test. */
@RunWith(AndroidJUnit4::class)
class InlineUiPanelReuseDeviceTest {
    @Test fun staleDismissalCannotClearReopenedPanelOwnership() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("panelReuseFixture")=="emulator")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        var panel:InlineUiPanel?=null
        var dismissed=0
        try {
            scenario.onActivity { activity ->
                panel=InlineUiPanel(activity,"QA",TextView(activity).apply { text="QA" },
                    TextView(activity).apply { text="Owned lifecycle fixture" },Button(activity).apply { text="닫기" },
                    null,400,android.graphics.drawable.ColorDrawable(android.graphics.Color.DKGRAY))
                panel!!.setOnDismissListener { dismissed++ }
                panel!!.show();panel!!.dismiss();panel!!.show()
            }
            instrumentation.waitForIdleSync()
            val deadline=android.os.SystemClock.uptimeMillis()+10000
            var laidOut=false
            while(!laidOut && android.os.SystemClock.uptimeMillis()<deadline) {
                scenario.onActivity { laidOut=panel!!.getButton(android.app.AlertDialog.BUTTON_POSITIVE)!!.isLaidOut }
                if(!laidOut) Thread.sleep(20)
            }
            assertTrue("Reattached descendants must receive a real layout pass",laidOut)
            scenario.onActivity { assertTrue(panel!!.isShowing);assertEquals(0,dismissed);panel!!.dismiss() }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertFalse(panel!!.isShowing);assertEquals(1,dismissed)
                assertFalse("Closed panel cannot remain shown", panel!!.contentView.isShown)
                assertNull(panel!!.contentView.parent)
            }
        } finally {
            scenario.onActivity { panel?.dismiss() }
            scenario.closeAndAwaitNativeRelease()
        }
    }
}
