package com.example.version1

import android.os.SystemClock
import android.view.TextureView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Owned projection fixture: actual native texture, no HVAC/health/profile writes. */
@RunWith(AndroidJUnit4::class)
class CabinAirDirectionDeviceTest {
    @Test fun outletChangesReachActualTextureWithoutReloadOrContinuousFrames() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("airDirectionFixture")=="emulator")
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        fun field(value:Any,name:String)=value.javaClass.getDeclaredField(name).apply {isAccessible=true}
        fun scene(activity:MainActivity)=field(activity,"scene").get(activity) as PremiumCabinView
        fun count(value:Any,name:String)=(field(value,name).get(value) as Number).toLong()
        fun await(message:String,check:(PremiumCabinView)->Boolean) {
            val deadline=SystemClock.uptimeMillis()+60_000
            while(SystemClock.uptimeMillis()<deadline) {
                var ready=false;scenario.onActivity {ready=check(scene(it))}
                if(ready)return
                Thread.sleep(25)
            }
            error(message)
        }
        var original:CabinSceneState?=null
        try {
            await("Actual model not ready") {value->
                val gate=field(value,"presentation").get(value) as PreviewPresentationGate;
                !gate.covered && !gate.failed && (field(value,"state").get(value) as CabinSceneState).connected &&
                    (field(value,"frameBudget").get(value) as RenderFrameBudget).remaining==0
            }
            var poll:Job?=null
            scenario.onActivity {poll=field(it,"poll").get(it) as? Job;poll?.cancel()}
            runBlocking {poll?.join()}
            await("Scene did not settle") {field(it,"state").get(it)==field(it,"lastSubmittedState").get(it)}
            var viewer:Any?=null;var texture:TextureView?=null
            scenario.onActivity {
                original=field(scene(it),"state").get(scene(it)) as CabinSceneState
                viewer=field(scene(it),"modelViewer").get(scene(it))
                val native=viewer as com.google.android.filament.utils.BudgetModelViewer
                val asset=checkNotNull(native.asset)
                val rm=native.engine.renderableManager
                val outlets=asset.renderableEntities.filter {entity->asset.getName(entity)?.startsWith("afterglow_airflow_floor_")==true}
                assertEquals(16,outlets.size)
                for(entity in outlets) {
                    val instance=rm.getInstance(entity)
                    for(primitive in 0 until rm.getPrimitiveCount(instance)) {
                        val material=rm.getMaterialInstanceAt(instance,primitive)
                        assertFalse("Footwell illustration must stay visible over opaque cabin",material.isDepthCullingEnabled)
                        assertFalse(material.isDepthWriteEnabled)
                    }
                }
                texture=(0 until scene(it).childCount).map {index->scene(it).getChildAt(index)}.filterIsInstance<TextureView>().single()
            }
            val rows=JSONArray()
            val directory=File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"owned-air-directions").apply {mkdirs()}
            val directions=CabinAirDirection.entries.sortedBy {it==original!!.airDirection}.map {it as CabinAirDirection?}+listOf(null)
            for(direction in directions) {
                val target=original!!.copy(fan=3,powerOn=true,connected=true,driverTemperature=22f,airDirection=direction)
                var before=0L;var textureBefore=0L
                scenario.onActivity {
                    before=count(scene(it),"acceptedFrameCount");textureBefore=count(scene(it),"textureUpdateCount")
                    scene(it).update(target)
                }
                await("New outlet selection not presented") {value->
                    field(value,"lastSubmittedState").get(value)==target && count(value,"acceptedFrameCount")>before &&
                        count(value,"textureUpdateCount")>textureBefore &&
                        (field(value,"frameBudget").get(value) as RenderFrameBudget).remaining==0
                }
                val name=direction?.name ?: "UNKNOWN"
                var submitted=0L
                scenario.onActivity {
                    assertSame(viewer,field(scene(it),"modelViewer").get(scene(it)))
                    submitted=count(scene(it),"acceptedFrameCount")
                    val image=checkNotNull(texture!!.getBitmap(800,600))
                    File(directory,"$name.png").outputStream().use {out->image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out)}
                    image.recycle()
                }
                Thread.sleep(250)
                scenario.onActivity {assertEquals("Static outlet selection must stop redrawing",submitted,count(scene(it),"acceptedFrameCount"))}
                rows.put(JSONObject().put("direction",name).put("actualTextureUpdated",true).put("sameViewer",true).put("idleFramesStopped",true))
            }
            File(directory,"result.json").writeText(JSONObject().put("rows",rows).put("vehicleCommandsIssued",false)
                .put("airflowSettingOverlayOverOpaqueCabin",true)
                .put("physicalVehicle",false).put("source","Owned scene input fixture, not SDK readback").toString(2))
        } finally {
            scenario.onActivity {activity->original?.let {scene(activity).update(it)}}
            scenario.close()
        }
    }
}
