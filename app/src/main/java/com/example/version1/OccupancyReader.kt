package com.example.version1

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener

/**
 * Lifecycle-bound read-only Pleos occupancy source. No belt inference and no
 * occupant defaults. Only singleton capability areas can identify a particular
 * seat; a grouped area is intentionally not expanded into invented passengers.
 * Call close on Activity stop, then create a new reader when the UI resumes.
 * The controller owns the shared vehicle connection; this reader removes only
 * its own registrations when closed.
 */
class OccupancyReader internal constructor(context: Context, api: Seat,
    private val preparation: SdkApiPreparation,
    private val beforePreparation: suspend () -> Unit,
    private val onChanged: (OccupancySensorUpdate) -> Unit) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var seatApi: Seat? = api
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var closed = false
    private var ready = false
    private var wanted = emptySet<OccupancySeat>()
    private val readings = mutableMapOf<OccupancySeat, Boolean?>()
    private val registrations = mutableListOf<Pair<SeatArea, SeatOccupancyListener>>()
    private val revisions = mutableMapOf<OccupancySeat, Int>()
    private var lastUpdate: OccupancySensorUpdate? = null
    private var capabilityTimeout: Runnable? = null

    @MainThread
    fun start(layout: OccupancyLayout) {
        if (started || closed) return
        started = true
        wanted = layout.seats.toSet()
        publish(OccupancySensorStatus.LOADING, "좌석 센서를 확인하고 있습니다.")
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            publish(OccupancySensorStatus.UNAVAILABLE, "차량 센서 미지원 · +로 탑승자를 추가할 수 있습니다.")
            return
        }
        val api = seatApi ?: return
        scope.launch {
          try {
            beforePreparation()
            PerformanceMarks.event("occupancy.metadata.wait")
            withTimeout(12_000) { preparation.await() }
            if (closed) return@launch
            PerformanceMarks.event("occupancy.metadata.ready")
            capabilityTimeout = Runnable {
                if (!closed && !ready) publish(OccupancySensorStatus.UNAVAILABLE,
                    "좌석 센서 응답 없음 · +로 탑승자를 추가할 수 있습니다.")
            }.also { handler.postDelayed(it, 8_000) }
            val success: (CapabilityConfig, List<SeatArea>) -> Unit = { config, areas ->
                onMain {
                    if (config.accessType != AccessType.READ && config.accessType != AccessType.READ_WRITE) {
                        finishCapability(OccupancySensorStatus.PERMISSION_DENIED,
                            "좌석 센서 읽기 권한 없음 · 수동 추가 가능")
                    } else connectAreas(api, areas)
                }
            }
            PerformanceMarks.measure("occupancy.capability.request") {
                api.checkSeatOccupancyCapabilities(success) { error -> onMain { failCapability(error) } }
            }
          } catch (timeout: TimeoutCancellationException) { failCapability(timeout) }
          catch (cancelled: CancellationException) { throw cancelled }
          catch (error: Exception) {
            failCapability(error)
          }
        }
    }

    private fun connectAreas(api: Seat, areas: List<SeatArea>) {
        if (closed) return
        capabilityTimeout?.let(handler::removeCallbacks)
        capabilityTimeout = null
        val supported = areas.mapNotNull { area ->
            val value = area.values.singleOrNull() ?: return@mapNotNull null
            val position = OccupancySeat.entries.firstOrNull { it.name == value.name && it in wanted }
                ?: return@mapNotNull null
            position to area
        }.distinctBy { it.first }
        if (supported.isEmpty()) {
            finishCapability(OccupancySensorStatus.UNAVAILABLE,
                "좌석별 탑승 센서 미지원 · +로 탑승자를 추가할 수 있습니다.")
            return
        }
        ready = true
        supported.forEach { (position, area) ->
            revisions[position] = 0
            val listener = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(value: Boolean?) = onMain {
                    revisions[position] = (revisions[position] ?: 0) + 1
                    readings[position] = value
                    publishAvailable()
                }
                override fun onFailed(error: Exception) = onMain {
                    revisions[position] = (revisions[position] ?: 0) + 1
                    readings[position] = null
                    publishAvailable()
                }
            }
            try {
                api.registerSeatOccupancy(area, listener)
                registrations += area to listener
            } catch (_: Exception) {
                // The initial get can still provide an honest single observation.
            }
            val requestedRevision = revisions[position] ?: 0
            try {
                api.getCurrentSeatOccupancy(area, { value -> onMain {
                    // A late initial response must not overwrite a newer sensor event.
                    if (revisions[position] == requestedRevision) {
                        readings[position] = value
                        publishAvailable()
                    }
                } }, { _ -> onMain {
                    if (revisions[position] == requestedRevision) {
                        readings[position] = null
                        publishAvailable()
                    }
                } })
            } catch (_: Exception) {
                readings[position] = null
            }
        }
        publishAvailable()
    }

    private fun publishAvailable() {
        val known = wanted.count { readings[it] != null }
        publish(OccupancySensorStatus.AVAILABLE, when {
            known == 0 -> "센서 연결됨 · 좌석 상태 미확인"
            known < wanted.size -> "차량 센서 $known/${wanted.size}석 확인 · 나머지 수동 추가 가능"
            else -> "차량 좌석 센서 연동"
        })
    }

    private fun failCapability(error: Exception) {
        finishCapability(if (error is SecurityException) OccupancySensorStatus.PERMISSION_DENIED
            else OccupancySensorStatus.UNAVAILABLE,
            if (error is SecurityException) "좌석 센서 읽기 권한 없음 · 수동 추가 가능"
            else "좌석 센서 조회 불가 · +로 탑승자를 추가할 수 있습니다.")
    }

    private fun finishCapability(status: OccupancySensorStatus, message: String) {
        capabilityTimeout?.let(handler::removeCallbacks)
        capabilityTimeout = null
        publish(status, message)
    }

    private fun publish(status: OccupancySensorStatus, message: String) {
        if (closed) return
        val update = OccupancySensorUpdate(readings.toMap(), status, message)
        if (lastUpdate == update) return
        lastUpdate = update
        onChanged(update)
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!closed) action()
        } else handler.post { if (!closed) action() }
    }

    @MainThread
    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        val api = seatApi
        if (api != null) registrations.forEach { (area, listener) ->
            runCatching { api.unregisterSeatOccupancy(area, listener) }
        }
        registrations.clear()
        readings.clear()
        revisions.clear()
        seatApi = null
        lastUpdate = null
    }
}
