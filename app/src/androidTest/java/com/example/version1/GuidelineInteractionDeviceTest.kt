package com.example.version1

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Real injected touches, read-only settings open/close. Never applies vehicle commands. */
@RunWith(AndroidJUnit4::class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 34)
class GuidelineInteractionDeviceTest {
    @Test fun settingsTouchToReadyWindow() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("guidelineUi") == "emulator")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val context = instrumentation.targetContext
        val profileAccessibility=InstrumentationRegistry.getArguments().getString("accessibilityTimingFixture")=="emulator"
        val batchedAccessibility=InstrumentationRegistry.getArguments().getString("batchedAccessibilityFixture")=="emulator"
        val accessibilityWaitTimings=LongArray(8)
        fun accessibilityWaitReceipt():Any = if(!profileAccessibility) JSONObject.NULL else JSONObject()
            .put("strategy",if(batchedAccessibility) "platform-batch-exact-filter" else "legacy-recursive-walk")
            .put("queries",accessibilityWaitTimings[0]).put("nullRoots",accessibilityWaitTimings[1])
            .put("foreignRoots",accessibilityWaitTimings[2]).put("rootQueryTotalMs",accessibilityWaitTimings[3])
            .put("rootQueryMaxMs",accessibilityWaitTimings[4]).put("targetLookupTotalMs",accessibilityWaitTimings[5])
            .put("targetLookupMaxMs",accessibilityWaitTimings[6])
            .put("treeWalkTotalMs",if(batchedAccessibility) JSONObject.NULL else accessibilityWaitTimings[5])
            .put("treeWalkMaxMs",if(batchedAccessibility) JSONObject.NULL else accessibilityWaitTimings[6])
            .put("waitDurationMs",accessibilityWaitTimings[7])
        fun targetTextAndState(node:AccessibilityNodeInfo,text:String,enabled:Boolean)=
            node.text?.toString()==text && (!enabled || (node.isEnabled && node.isClickable))
        fun walk(node: AccessibilityNodeInfo?, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.packageName?.toString() == context.packageName && node.isVisibleToUser && match(node)) return node
            for (index in 0 until node.childCount) walk(node.getChild(index), match)?.let { return it }
            return null
        }
        fun waitFor(text: String, enabled: Boolean = false): AccessibilityNodeInfo {
            val started=SystemClock.uptimeMillis()
            val deadline = started + 15000
            if(profileAccessibility) accessibilityWaitTimings.fill(0)
            while (SystemClock.uptimeMillis() < deadline) {
                val queryStarted=if(profileAccessibility) SystemClock.uptimeMillis() else 0L
                val root=automation.rootInActiveWindow
                val queryFinished=if(profileAccessibility) SystemClock.uptimeMillis() else 0L
                val found=if(batchedAccessibility) root?.findAccessibilityNodeInfosByText(text)?.firstOrNull {
                    it.packageName?.toString()==context.packageName && it.isVisibleToUser && targetTextAndState(it,text,enabled)
                } else walk(root) { targetTextAndState(it,text,enabled) }
                if(profileAccessibility) {
                    val finished=SystemClock.uptimeMillis()
                    accessibilityWaitTimings[0]++
                    if(root==null) accessibilityWaitTimings[1]++
                    else if(root.packageName?.toString()!=context.packageName) accessibilityWaitTimings[2]++
                    val queryMs=queryFinished-queryStarted
                    val walkMs=finished-queryFinished
                    accessibilityWaitTimings[3]+=queryMs
                    accessibilityWaitTimings[4]=maxOf(accessibilityWaitTimings[4],queryMs)
                    accessibilityWaitTimings[5]+=walkMs
                    accessibilityWaitTimings[6]=maxOf(accessibilityWaitTimings[6],walkMs)
                    accessibilityWaitTimings[7]=finished-started
                }
                found?.let { return it }
                SystemClock.sleep(10)
            }
            error("Expected visible UI missing: $text")
        }
        fun touch(node: AccessibilityNodeInfo): Long {
            val bounds = Rect(); check(node.getBoundsInScreen(bounds).let { !bounds.isEmpty })
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { check(automation.injectInputEvent(event, false)) } finally { event.recycle() }
                if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(16)
            }
            return down
        }
        val samples = JSONArray()
        val hostInput = InstrumentationRegistry.getArguments().getString("hostInputFixture") == "emulator"
        val delivered = java.util.concurrent.LinkedBlockingQueue<Triple<Int,Long,Long>>()
        var requestSequence = 0
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val readyPreview=InstrumentationRegistry.getArguments().getString("readyPreviewFixture")=="emulator"
        val rapid=InstrumentationRegistry.getArguments().getString("rapidDemoFixture")=="emulator"
        val routing = JSONArray()
        val observers = mutableListOf<Triple<android.view.Window, android.view.Window.Callback, android.view.Window.Callback>>()
        val observeStacks=InstrumentationRegistry.getArguments().getString("inputStackDiagnostic")=="emulator"
        val observing=java.util.concurrent.atomic.AtomicBoolean(observeStacks)
        val activeSequence=java.util.concurrent.atomic.AtomicInteger(0)
        val stackSamples=JSONArray()
        val sampler=if(observeStacks) Thread({
            val main=android.os.Looper.getMainLooper().thread
            while(observing.get()) {
                val sequence=activeSequence.get()
                if(sequence>0 && stackSamples.length()<2000) synchronized(stackSamples) {
                    stackSamples.put(JSONObject().put("sequence",sequence).put("at",SystemClock.uptimeMillis())
                        .put("mainState",main.state.name).put("stack",main.stackTrace.take(22).joinToString("\n")))
                }
                Thread.sleep(50)
            }
        },"OwnedInputStackDiagnostic").apply { start() } else null
        fun currentPoint(label: String): Rect {
            val deadline = SystemClock.uptimeMillis() + 15000
            while (SystemClock.uptimeMillis() < deadline) {
                var actual: Rect? = null
                scenario.onActivity { activity ->
                    fun find(view: View): View? {
                        if (view is TextView && view.text?.toString() == label && view.isShown && view.isEnabled && view.isClickable) return view
                        if (view is ViewGroup) for (i in view.childCount - 1 downTo 0) find(view.getChildAt(i))?.let { return it }
                        return null
                    }
                    val targetWindow = if (label == "설정") activity.window else {
                        val panel=MainActivity::class.java.getDeclaredField("modal").apply { isAccessible=true }.get(activity) as? UiPanel
                        panel?.nativeWindow ?: activity.window
                    }
                    val view = find(targetWindow.decorView)
                    if (view != null && view.isLaidOut && targetWindow.decorView.hasWindowFocus()) {
                        val xy = IntArray(2); view.getLocationOnScreen(xy)
                        actual = Rect(xy[0], xy[1], xy[0]+view.width, xy[1]+view.height)
                        if (observers.none { it.first === targetWindow }) {
                            val original = targetWindow.callback
                            val wrapper = object : android.view.Window.Callback by original {
                                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                                    if (hostInput && (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP))
                                        delivered.offer(Triple(event.actionMasked,event.downTime,event.eventTime))
                                    if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP)
                                        synchronized(routing) { routing.put(JSONObject().put("kind", "delivered")
                                            .put("activity", System.identityHashCode(activity)).put("action", event.actionMasked)
                                            .put("eventAt", event.eventTime).put("receivedAt", SystemClock.uptimeMillis())
                                            .put("x",event.x).put("y",event.y)
                                            .put("windowDisplayId",targetWindow.decorView.display.displayId)) }
                                    return original.dispatchTouchEvent(event)
                                }
                            }
                            targetWindow.callback = wrapper
                            observers += Triple(targetWindow, original, wrapper)
                        }
                        synchronized(routing) { routing.put(JSONObject().put("kind", "target")
                            .put("activity",System.identityHashCode(activity)).put("displayId",view.display.displayId)
                            .put("bounds",actual.toString()).put("windowFocus",true)
                            .put("windowWidth",targetWindow.decorView.width).put("windowHeight",targetWindow.decorView.height)
                            .put("nativeWindow",targetWindow !== activity.window)) }
                    }
                }
                if (actual != null && !actual!!.isEmpty) return actual!!
                SystemClock.sleep(10)
            }
            val diagnostic=JSONObject().put("target",label)
            scenario.onActivity { activity ->
                diagnostic.put("windowFocus",activity.window.decorView.hasWindowFocus())
                val views=JSONArray()
                fun capture(view:View) {
                    if(view is TextView && view.text?.toString() in setOf("설정","닫기","차량 · 미리보기 설정")) {
                        views.put(JSONObject().put("text",view.text.toString()).put("shown",view.isShown)
                            .put("enabled",view.isEnabled).put("clickable",view.isClickable)
                            .put("laidOut",view.isLaidOut).put("width",view.width).put("height",view.height)
                            .put("attached",view.isAttachedToWindow).put("parent",view.parent?.javaClass?.simpleName))
                    }
                    if(view is ViewGroup) for(i in 0 until view.childCount) capture(view.getChildAt(i))
                }
                capture(activity.window.decorView);diagnostic.put("views",views)
            }
            File(context.filesDir,"guideline-touch-target-failure.json").writeText(diagnostic.toString(2))
            error("No focused, laid-out current touch target: $label")
        }
        fun touchCurrent(label: String): Long {
            waitFor(label, enabled = true)
            val actual = currentPoint(label)
            val accessible = Rect().also { waitFor(label, enabled = true).getBoundsInScreen(it) }
            check(actual == accessible) { "Current view and accessibility coordinates disagree; no latency sample claimed" }
            var targetDisplay = -1
            scenario.onActivity { targetDisplay = it.window.decorView.display.displayId }
            check(targetDisplay >= 0)
            if (hostInput) {
                delivered.clear()
                val requestedAt=SystemClock.uptimeMillis()
                activeSequence.set(requestSequence+1)
                File(context.filesDir,"guideline-host-touch-request.json").writeText(JSONObject()
                    .put("sequence",++requestSequence).put("displayId",targetDisplay)
                    .put("x",actual.centerX()).put("y",actual.centerY())
                    .put("holdMillis",16).put("requestedAt",requestedAt).toString())
                val downEvent=checkNotNull(delivered.poll(20,java.util.concurrent.TimeUnit.SECONDS)) { "Host DOWN not delivered" }
                check(downEvent.first==MotionEvent.ACTION_DOWN && downEvent.third>=requestedAt)
                val upEvent=checkNotNull(delivered.poll(20,java.util.concurrent.TimeUnit.SECONDS)) { "Host UP not delivered" }
                check(upEvent.first==MotionEvent.ACTION_UP && upEvent.second==downEvent.second && upEvent.third-downEvent.third>=16)
                return downEvent.third
            }
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                // Public API 34 overload supplies an explicit display. The legacy
                // obtain(x,y) event may otherwise be routed through UiAutomation's
                // user-associated display rather than this actual vehicle window.
                val pointer=MotionEvent.PointerProperties().apply { id=0; toolType=MotionEvent.TOOL_TYPE_FINGER }
                val point=MotionEvent.PointerCoords().apply {
                    x=actual.exactCenterX(); y=actual.exactCenterY(); pressure=1f; size=1f
                }
                val event=checkNotNull(MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,arrayOf(pointer),arrayOf(point),
                    0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,targetDisplay,0,MotionEvent.CLASSIFICATION_NONE))
                try { check(automation.injectInputEvent(event,false)) } finally { event.recycle() }
                if(action==MotionEvent.ACTION_DOWN) SystemClock.sleep(16)
            }
            return down
        }
        try {
            if(readyPreview) {
                val deadline=SystemClock.uptimeMillis()+60000
                var ready=false
                while(!ready && SystemClock.uptimeMillis()<deadline) {
                    scenario.onActivity { activity ->
                        val scene=MainActivity::class.java.getDeclaredField("scene").apply { isAccessible=true }.get(activity)!!
                        val gate=scene.javaClass.getDeclaredField("presentation").apply { isAccessible=true }.get(scene) as PreviewPresentationGate
                        check(!gate.failed) { "Actual preview failed before ready-state demonstration" }
                        ready=!gate.covered && MainActivity::class.java.getDeclaredMethod("safeToAct").apply { isAccessible=true }.invoke(activity)==true
                    }
                    if(!ready) SystemClock.sleep(100)
                }
                check(ready) { "No actual presented preview for ready-state demonstration" }
                // Distinct loaded-state scenario. Preserve cold/early-start gates and the same DOWN/250ms metrics.
                SystemClock.sleep(500)
            }
            repeat(6) { trial ->
                val start = touchCurrent("설정")
                waitFor("차량 · 미리보기 설정")
                val ready = SystemClock.uptimeMillis() - start
                activeSequence.set(0)
                var progressDown = 0L; var progressCommitted = 0L
                val openFrameDeadline=SystemClock.uptimeMillis()+1000
                do {
                    scenario.onActivity { activity ->
                        progressDown = activity.settingsProgressDownAt
                        progressCommitted = activity.settingsProgressCommittedAt
                    }
                    // The exact accessibility target can arrive before its real
                    // commit callback. Capture that callback after the target's
                    // elapsed timestamp; never replace its DOWN anchor or threshold.
                    if(batchedAccessibility && progressDown!=start) SystemClock.sleep(10)
                } while(batchedAccessibility && progressDown!=start && SystemClock.uptimeMillis()<openFrameDeadline)
                samples.put(JSONObject().put("trial", trial).put("operation", "openSettings")
                    .put("accessibilityWaitDiagnostic",accessibilityWaitReceipt())
                    .put("touchToAccessibleContentMs", ready).put("within250", ready <= 250)
                    .put("progressFrameMatchesTouch",progressDown==start)
                    .put("touchToProgressCommitMs",if(progressDown==start && progressCommitted>=start) progressCommitted-start else JSONObject.NULL)
                    .put("progressWithin250",progressDown==start && progressCommitted-start in 0..250))
                if(!rapid) SystemClock.sleep(300)
                val closeStart = touchCurrent("닫기")
                waitFor("설정", enabled = true)
                val closed = SystemClock.uptimeMillis() - closeStart
                activeSequence.set(0)
                var closeDown = 0L; var closeCommitted = 0L
                var closeProgressDown=0L;var closeProgressCommitted=0L
                val closeFrameDeadline=SystemClock.uptimeMillis()+1000
                do {
                    scenario.onActivity { activity ->
                        closeDown=activity.panelCloseDownAt;closeCommitted=activity.panelCloseCommittedAt
                        closeProgressDown=activity.panelCloseProgressDownAt;closeProgressCommitted=activity.panelCloseProgressCommittedAt
                    }
                    if(closeDown!=closeStart) SystemClock.sleep(10)
                } while(closeDown!=closeStart && SystemClock.uptimeMillis()<closeFrameDeadline)
                samples.put(JSONObject().put("trial", trial).put("operation", "closeSettings")
                    .put("accessibilityWaitDiagnostic",accessibilityWaitReceipt())
                    .put("touchToAccessibleContentMs", closed).put("within250", closed <= 250)
                    .put("closeFrameMatchesTouch",closeDown==closeStart)
                    .put("touchToCloseCommitMs",if(closeDown==closeStart && closeCommitted>=closeStart) closeCommitted-closeStart else JSONObject.NULL)
                    .put("closeFrameWithin250",closeDown==closeStart && closeCommitted-closeStart in 0..250)
                    .put("closeProgressFrameMatchesTouch",closeProgressDown==closeStart)
                    .put("touchToCloseProgressCommitMs",if(closeProgressDown==closeStart && closeProgressCommitted>=closeStart) closeProgressCommitted-closeStart else JSONObject.NULL)
                    .put("closeProgressWithin250",closeProgressDown==closeStart && closeProgressCommitted-closeStart in 0..250))
                if(!rapid) SystemClock.sleep(500)
            }
            File(context.filesDir, "guideline-interaction-result.json").writeText(JSONObject()
                .put("metric", "Real touch DOWN to accessible target window content, includes 16ms touch hold and polling overhead; not physical scanout")
                .put("accessibilityLookupStrategy",if(batchedAccessibility) "platform-batch-exact-filter" else "legacy-recursive-walk")
                .put("inputOrigin",if(hostInput) "host-adb-public-os-touch" else "UiAutomation")
                .put("readyStatePreviewRequired",readyPreview).put("rapidDemo",rapid)
                .put("samples", samples).put("vehicleCommandsIssuedByTest", false).toString(2))
        } finally {
            observing.set(false);sampler?.join(1000)
            if(observeStacks) File(context.filesDir,"guideline-input-main-stacks.json").writeText(JSONObject()
                .put("scope","Optional sampled main-thread diagnostic; observer overhead, not a latency certification")
                .put("samples",stackSamples).toString(2))
            File(context.filesDir,"guideline-input-routing.json").writeText(JSONObject()
                .put("scope","Same-trial focused-window and actual event routing; not a replacement latency gate")
                .put("routing",routing).put("completedSamples",samples).toString(2))
            scenario.onActivity { for ((window,original,wrapper) in observers)
                if(window.callback === wrapper) window.callback=original }
            scenario.closeAndAwaitNativeRelease()
        }
    }
}
