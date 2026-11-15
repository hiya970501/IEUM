package com.example.version1

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.afterglow.net.WorkoutHistoryEntry
import com.example.afterglow.net.WorkoutHistorySnapshot
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Memory-only owned fixtures. Never reads Samsung Health or changes a vehicle setting. */
@RunWith(AndroidJUnit4::class)
class HealthSummaryUiDeviceTest {
    @Test fun spacedSectionsTimeUnitsTypeScaleAndRecentRedAreRenderedOnAndroid() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            instrumentation.waitForIdleSync()
            scenario.onActivity {activity->
                fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply {isAccessible=true}
                fun label(name:String)=field(name).get(activity) as TextView
                val now=System.currentTimeMillis()-100
                val entries=listOf(
                    WorkoutHistoryEntry(1,"hike",now-9*60_000,now-1000,"owned.health",false),
                    WorkoutHistoryEntry(2,"run",now-4*3_600_000L,now-3*3_600_000L,"owned.health",false))
                field("receivedWorkoutHistory").set(activity,WorkoutHistorySnapshot(now,2,entries))
                field("workoutHistoryReceivedAt").setLong(activity,now)
                field("acceptedHealthAt").setLong(activity,now)
                field("source").set(activity,HeartRateSource.WATCH)
                field("watchBpm").set(activity,81)
                field("watchReceivedAt").setLong(activity,SystemClock.elapsedRealtime()-59_000)
                MainActivity::class.java.getDeclaredMethod("render").apply {isAccessible=true}.invoke(activity)
                val render=MainActivity::class.java.getDeclaredMethod("render").apply {isAccessible=true}
                val originalReceiver=field("receiver").get(activity)
                val receiver=WatchReceiver(port=0) {}
                field("receiver").set(activity,receiver)
                try {
                    val diagnostics=WatchReceiver::class.java.getDeclaredField("diagnostics").apply {isAccessible=true}
                    diagnostics.set(receiver,BridgeDiagnostics(lastWatchSensorReceivedAt=now,historyReceivedAt=now))
                    render.invoke(activity)
                    assertEquals("ON",label("watchConnectionLabel").text.toString())
                    diagnostics.set(receiver,BridgeDiagnostics(lastWatchSensorReceivedAt=now-59_000,historyReceivedAt=now))
                    render.invoke(activity)
                    assertEquals("ON",label("watchConnectionLabel").text.toString())
                    diagnostics.set(receiver,BridgeDiagnostics(lastWatchSensorReceivedAt=now-60_000,historyReceivedAt=now,
                        lastAcceptedRequestAtEpochMillis=now))
                    render.invoke(activity)
                    assertEquals("OFF",label("watchConnectionLabel").text.toString())
                } finally { field("receiver").set(activity,originalReceiver) }
                val recommendation=label("planLabel").text.toString()
                field("parked").setBoolean(activity,true)
                field("telemetry").set(activity,CabinTelemetry(28f,6,3,0,true))
                render.invoke(activity)
                assertEquals(recommendation,label("planLabel").text.toString())
                assertTrue(label("telemetryLabel").text.toString().contains("28°C · 풍량 6 · 시트 열선 3"))
                val scene=field("scene").get(activity) as PremiumCabinView
                val sceneState=PremiumCabinView::class.java.getDeclaredField("state").apply {isAccessible=true}
                assertEquals(3,(sceneState.get(scene) as CabinSceneState).heat)
                assertEquals(6,(sceneState.get(scene) as CabinSceneState).fan)
                field("telemetry").set(activity,CabinTelemetry(20f,1,-2,0,true))
                render.invoke(activity)
                assertEquals(recommendation,label("planLabel").text.toString())
                assertTrue(label("telemetryLabel").text.toString().contains("20°C · 풍량 1 · 시트 열선 0 · 통풍 0 · 냉각 2"))
                assertEquals(2,(sceneState.get(scene) as CabinSceneState).seatCooling)
                assertEquals(1,(sceneState.get(scene) as CabinSceneState).fan)
                assertEquals("마지막 건강 데이터 수신 · 0분 전",label("watchDetailLabel").text.toString())
                assertEquals("심박 81 BPM · 0분 전",label("sourceLabel").text.toString())
                assertEquals("최근(24시간 이내) 운동 기록",label("workoutLabel").text.toString())
                assertEquals("2건",label("workoutHistoryCountLabel").text.toString())
                assertEquals(label("modeLabel").textSize,label("workoutHistoryCountLabel").textSize,.01f)
                assertEquals(label("watchDetailLabel").textSize,label("workoutLabel").textSize,.01f)
                val modeRow=label("modeLabel").parent.parent as LinearLayout
                val gap=(modeRow.layoutParams as LinearLayout.LayoutParams).topMargin
                assertTrue(gap>0)
                assertEquals(gap,(label("watchDetailLabel").layoutParams as LinearLayout.LayoutParams).topMargin)
                assertEquals(gap,(label("workoutLabel").layoutParams as LinearLayout.LayoutParams).topMargin)
                val rendered=label("workoutHistoryRowsLabel").text as Spanned
                assertTrue(rendered.toString().startsWith("하이킹, "))
                assertEquals(2,rendered.toString().lines().size)
                val red=rendered.getSpans(0,rendered.length,ForegroundColorSpan::class.java)
                assertEquals(1,red.size)
                assertEquals(0,rendered.getSpanStart(red.single()))
                assertEquals(rendered.toString().indexOf('\n'),rendered.getSpanEnd(red.single()))
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {activity->
                val plan=MainActivity::class.java.getDeclaredField("planLabel").apply {isAccessible=true}.get(activity) as TextView
                val receipt=MainActivity::class.java.getDeclaredField("watchDetailLabel").apply {isAccessible=true}.get(activity) as TextView
                assertEquals(1,receipt.layout.lineCount)
                assertTrue(receipt.paint.measureText(receipt.text.toString())<=receipt.width-receipt.paddingLeft-receipt.paddingRight+1)
                assertEquals(3,plan.text.toString().lines().size)
                assertEquals(3,plan.layout.lineCount)
                // Each explicit line fits its real Android measured width; no fourth-line wrap.
                plan.text.toString().lines().forEach {line->assertTrue(plan.paint.measureText(line)<=plan.width-plan.paddingLeft-plan.paddingRight+1)}
                val panel=MainActivity::class.java.getDeclaredField("driverInfo").apply {isAccessible=true}.get(activity) as android.view.View
                check(panel.width>0 && panel.height>0)
                val image=Bitmap.createBitmap(panel.width,panel.height,Bitmap.Config.ARGB_8888)
                panel.draw(Canvas(image))
                val directory=File(activity.filesDir,"owned-health-ui").apply {mkdirs()}
                File(directory,"summary.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
                image.recycle()
            }
        } finally {
            scenario.onActivity {activity->MainActivity::class.java.getDeclaredMethod("clearReceivedHealth").apply {isAccessible=true}.invoke(activity)}
            scenario.close()
        }
    }
}
