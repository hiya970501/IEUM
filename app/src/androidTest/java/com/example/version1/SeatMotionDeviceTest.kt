package com.example.version1

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.SeatBackrestAnglePositionListener
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Explicit emulator-only physical-property probe; no preview animation or synthetic position write. */
@RunWith(AndroidJUnit4::class)
class SeatMotionDeviceTest {
    /** Read-only comparison against a separately labelled official-console input fixture. */
    @Test fun readOnlyBackrestSnapshot() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("seatSnapshot") == "emulator")
        val context = instrumentation.targetContext
        val ready = CompletableDeferred<DriverSeatBinding>()
        val reader = DriverSeatReader(context) { if (it is DriverSeatState.Available) ready.complete(it.binding) }
        var controller: PleosRecoveryController? = null
        try {
            instrumentation.runOnMainSync { reader.start() }
            val binding = withTimeout(12000) { ready.await() }
            val api = PleosRecoveryController(context, { binding }, log = {})
            controller = api
            api.apiPreparation.await()
            val seat = api.occupancySeat
            val (_, positions) = response<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>> { ok, fail ->
                seat.checkSeatBackrestAnglePositionCapabilities({ cfg, areas -> ok(cfg to areas) }, fail)
            }
            val area = selectDriverArea(positions.keys, binding.position, true) { it.values.map { v -> v.name }.toSet() }
                ?: error("No unambiguous driver area")
            val value = response<Int?> { ok, fail -> seat.getCurrentSeatBackrestAnglePosition(area, ok, fail) }
            val events = java.util.concurrent.CopyOnWriteArrayList<String>()
            val listener = object : SeatBackrestAnglePositionListener {
                override fun onSeatBackrestAnglePositionUpdated(position: Int?) { events.add(position.toString()) }
                override fun onFailed(e: Exception) { events.add("error: ${e.message}") }
            }
            val reads = JSONArray()
            seat.registerSeatBackrestAnglePosition(area, listener)
            try {
                File(context.filesDir, "seat-read-only-ready").writeText("registered")
                repeat(15) {
                    reads.put(response<Int?> { ok, fail -> seat.getCurrentSeatBackrestAnglePosition(area, ok, fail) })
                    delay(200)
                }
                File(context.filesDir, "seat-read-only-probe.json").writeText(JSONObject()
                    .put("driver", binding.position.name).put("position", value)
                    .put("getterSamplesWithListener", reads).put("listenerEvents", JSONArray(events.toList()))
                    .put("source", "actual SDK getter and listener; no movement command").toString(2))
            } finally {
                seat.unregisterSeatBackrestAnglePosition(area, listener)
                File(context.filesDir, "seat-read-only-ready").delete()
            }
        } finally {
            controller?.release()
            instrumentation.runOnMainSync { reader.close() }
        }
    }

    private suspend fun <T> response(call: ((T) -> Unit, (Exception) -> Unit) -> Unit): T =
        withTimeout(6000) {
            suspendCancellableCoroutine { c ->
                call({ if (c.isActive) c.resume(it) }, { if (c.isActive) c.resumeWithException(it) })
            }
        }

    @Test fun directBackrestMoveReadbackStopAndRestore() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Seat motion requires an explicit emulator-only test invocation",
            InstrumentationRegistry.getArguments().getString("allowSeatMotion") == "emulator")
        val context = instrumentation.targetContext
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("emulator", ignoreCase = true)) {
            "This bounded probe is authorized for the emulator only"
        }
        val result = JSONObject().put("passed", false).put("physicalVehicle", false)
            .put("source", "typed Pleos SDK movement and position readback")
            .put("positionUnit", "SDK position units, not verified degrees")
        val samples = JSONArray(); result.put("samples", samples)
        val bindingReady = CompletableDeferred<DriverSeatBinding>()
        val reader = DriverSeatReader(context) { state ->
            if (state is DriverSeatState.Available) bindingReady.complete(state.binding)
        }
        var controller: PleosRecoveryController? = null
        var area: SeatArea? = null
        var before: Int? = null
        var attempted = false
        var lastDirection: MoveControl? = null
        var restored = false
        try {
            instrumentation.runOnMainSync { reader.start() }
            val binding = withTimeout(12000) { bindingReady.await() }
            result.put("driver", binding.position.name)
            val api = PleosRecoveryController(context, { binding }, log = {})
            controller = api
            val seat = api.occupancySeat
            api.apiPreparation.await()
            withTimeout(12000) {
                while (runCatching { api.gear() }.getOrNull() != VehicleGear.GEAR_PARK) delay(200)
            }
            val (_, positions) = response<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>> { ok, fail ->
                seat.checkSeatBackrestAnglePositionCapabilities({ cfg, bounds -> ok(cfg to bounds) }, fail)
            }
            area = selectDriverArea(positions.keys, binding.position, true) { it.values.map { s -> s.name }.toSet() }
                ?: error("No unambiguous single driver position area")
            val selected = area!!
            val bounds = positions.getValue(selected)
            result.put("minimum", bounds.minValue).put("maximum", bounds.maxValue)
            val (moveConfig, moves) = response<Pair<CapabilityConfig, List<SeatArea>>> { ok, fail ->
                seat.checkMoveSeatBackrestAngleCapabilities({ cfg, supported -> ok(cfg to supported) }, fail)
            }
            check(moveConfig.accessType in setOf(AccessType.WRITE, AccessType.READ_WRITE))
            check(selected in moves)
            suspend fun position(): Int = response<Int?> { ok, fail ->
                seat.getCurrentSeatBackrestAnglePosition(selected, ok, fail)
            } ?: error("SDK returned unknown position")
            suspend fun command(direction: MoveControl) {
                if (direction != MoveControl.STOP) check(api.gear() == VehicleGear.GEAR_PARK)
                response<Unit> { ok, fail ->
                    seat.moveSeatBackrestAngle(selected, direction, { _, _ -> ok(Unit) }, fail)
                }
            }
            before = position()
            result.put("before", before)
            check(before == bounds.minValue || before == bounds.maxValue) {
                "Use an observed boundary fixture so reverse movement can restore exactly"
            }
            val handler = Handler(Looper.getMainLooper())
            var moved: Int = before!!
            for (direction in listOf(MoveControl.FORWARD, MoveControl.BACKWARD)) {
                val stop = Runnable { seat.moveSeatBackrestAngle(selected, MoveControl.STOP, { _, _ -> }, {}) }
                handler.postDelayed(stop, 1500)
                attempted = true
                lastDirection = direction
                try {
                    command(direction)
                    val started = android.os.SystemClock.elapsedRealtime()
                    do {
                        moved = position()
                        samples.put(JSONObject().put("direction", direction.name).put("position", moved))
                        if (moved != before) break
                        delay(100)
                    } while (android.os.SystemClock.elapsedRealtime() - started < 1000)
                } finally {
                    withContext(NonCancellable) { command(MoveControl.STOP) }
                    handler.removeCallbacks(stop)
                }
                moved = position()
                if (moved != before) break
            }
            result.put("afterStop", moved)
            check(moved != before) { "Movement commands acknowledged but SDK position did not change" }
            delay(350)
            result.put("heldAfterStop", position())
            check(position() == moved) { "STOP did not hold the observed position" }
            result.put("positionChanged", true).put("stopVerified", true)
        } catch (e: Exception) {
            result.put("failure", e.message ?: e.javaClass.simpleName)
            throw e
        } finally {
            val api = controller
            val selected = area
            if (api != null && selected != null && before != null && attempted) {
                withContext(NonCancellable) {
                    val seat = api.occupancySeat
                    suspend fun pos() = response<Int?> { ok, fail -> seat.getCurrentSeatBackrestAnglePosition(selected, ok, fail) }
                    suspend fun move(d: MoveControl) = response<Unit> { ok, fail ->
                        seat.moveSeatBackrestAngle(selected, d, { _, _ -> ok(Unit) }, fail)
                    }
                    try {
                        move(MoveControl.STOP)
                        if (pos() != before) {
                            check(api.gear() == VehicleGear.GEAR_PARK)
                            val reverse = if (lastDirection == MoveControl.FORWARD) MoveControl.BACKWARD else MoveControl.FORWARD
                            val handler = Handler(Looper.getMainLooper())
                            val stop = Runnable { seat.moveSeatBackrestAngle(selected, MoveControl.STOP, { _, _ -> }, {}) }
                            handler.postDelayed(stop, 1500)
                            try {
                                move(reverse)
                                val started = android.os.SystemClock.elapsedRealtime()
                                while (pos() != before && android.os.SystemClock.elapsedRealtime()-started < 1000) delay(80)
                            } finally { move(MoveControl.STOP); handler.removeCallbacks(stop) }
                        }
                        val finalPosition = pos()
                        restored = finalPosition == before
                        result.put("restoredPosition", finalPosition).put("originalRestored", restored)
                    } catch (e: Exception) { result.put("restoreFailure", e.message) }
                }
            }
            result.put("passed", result.optBoolean("positionChanged") && result.optBoolean("stopVerified") && restored)
            File(context.filesDir, "seat-motion-probe.json").writeText(result.toString(2))
            controller?.release()
            instrumentation.runOnMainSync { reader.close() }
        }
        check(result.getBoolean("passed")) { result.toString() }
    }
}
