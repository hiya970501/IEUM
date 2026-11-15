package com.example.version1

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reads only INFO_MAKE / INFO_MODEL / INFO_MODEL_YEAR, with area 0 and no property subscription.
 * Construction does not connect. Start only while automatic selection is enabled; close on stop.
 * Synchronous CarPropertyManager reads run on IO, never on the UI thread.
 */
class VehicleIdentityReader(context: Context, private val onChanged: (VehicleIdentityState) -> Unit) {
    private val context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = VehicleIdentityRequestGate()
    private var car: Car? = null
    private var readyCar: Car? = null
    private var started = false
    private var readJob: Job? = null
    private var timeout: Runnable? = null
    private var lastState: VehicleIdentityState? = null

    @MainThread
    fun start() {
        if (gate.closed || started) return
        started = true
        val request = gate.begin() ?: return
        publish(request, VehicleIdentityState.Loading)
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            publish(request, VehicleIdentityState.Unavailable("차량 정보 조회는 차량용 기기에서 지원합니다."))
            return
        }
        if (!hasPermission()) {
            publish(request, VehicleIdentityState.PermissionDenied())
            return
        }
        scheduleTimeout(request, "차량 정보 서비스에 연결하지 못했습니다.")
        try {
            car = Car.createCar(context, mainHandler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                if (gate.closed) return@createCar
                cancelPendingRead()
                readyCar = connectedCar.takeIf { ready }
                if (ready) refresh() else {
                    val disconnected = gate.begin() ?: return@createCar
                    publish(disconnected, VehicleIdentityState.Unavailable("차량 연결이 끊어졌습니다."))
                }
            }
        } catch (_: SecurityException) {
            cancelPendingRead()
            publish(request, VehicleIdentityState.PermissionDenied())
        } catch (_: Exception) {
            cancelPendingRead()
            publish(request, VehicleIdentityState.Unavailable("차량 정보 서비스에 연결하지 못했습니다."))
        }
    }

    @MainThread
    fun refresh() {
        if (gate.closed) return
        if (!started) { start(); return }
        cancelPendingRead()
        val request = gate.begin() ?: return
        if (!hasPermission()) {
            publish(request, VehicleIdentityState.PermissionDenied())
            return
        }
        val connectedCar = readyCar
        if (connectedCar == null) {
            publish(request, VehicleIdentityState.Unavailable("차량 정보 서비스가 연결되지 않았습니다."))
            return
        }
        publish(request, VehicleIdentityState.Loading)
        scheduleTimeout(request, "차량 정보 조회 시간이 초과됐습니다.")
        readJob = scope.launch {
            val state = try {
                withContext(Dispatchers.IO) { readIdentity(connectedCar) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                VehicleIdentityState.PermissionDenied()
            } catch (_: Exception) {
                VehicleIdentityState.Unavailable()
            }
            if (gate.isCurrent(request)) {
                clearTimeout()
                publish(request, state)
            }
        }
    }

    /** Called on IO. Unsupported individual fields do not hide the remaining supported metadata. */
    private fun readIdentity(connectedCar: Car): VehicleIdentityState {
        val manager = connectedCar.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            ?: return VehicleIdentityState.Unavailable("차량 정보 조회를 지원하지 않습니다.")
        val make = readProperty(manager, String::class.java, VehiclePropertyIds.INFO_MAKE)
        val model = readProperty(manager, String::class.java, VehiclePropertyIds.INFO_MODEL)
        val year = readProperty(manager, Int::class.javaObjectType, VehiclePropertyIds.INFO_MODEL_YEAR)
        val identity = VehicleIdentity(clean(make.value), clean(model.value), year.value?.takeIf { it in 1900..2100 })
        return when {
            identity.hasMetadata -> VehicleIdentityState.Available(identity)
            make.denied || model.denied || year.denied -> VehicleIdentityState.PermissionDenied()
            else -> VehicleIdentityState.Unavailable("차량에서 제조사·차종·연식 정보를 제공하지 않습니다.")
        }
    }

    private data class PropertyRead<T>(val value: T? = null, val denied: Boolean = false)
    private fun <T> readProperty(manager: CarPropertyManager, type: Class<T>, id: Int): PropertyRead<T> = try {
        val property = manager.getProperty(type, id, 0)
        PropertyRead(property?.takeIf { it.status == CarPropertyValue.STATUS_AVAILABLE }?.value)
    } catch (_: SecurityException) {
        PropertyRead(denied = true)
    } catch (_: Exception) {
        PropertyRead()
    }

    private fun clean(value: String?): String? = value?.filterNot { it.isISOControl() }?.trim()
        ?.take(120)?.takeIf { it.isNotBlank() }
    private fun hasPermission(): Boolean = context.checkSelfPermission(Car.PERMISSION_CAR_INFO) == PackageManager.PERMISSION_GRANTED

    private fun publish(request: Long, state: VehicleIdentityState) {
        if (!gate.isCurrent(request) || state == lastState) return
        lastState = state
        onChanged(state)
    }

    private fun scheduleTimeout(request: Long, reason: String) {
        clearTimeout()
        timeout = Runnable {
            if (gate.isCurrent(request)) {
                gate.invalidate()
                readJob?.cancel()
                readJob = null
                lastState = VehicleIdentityState.Unavailable(reason)
                onChanged(lastState!!)
            }
        }.also { mainHandler.postDelayed(it, 8_000) }
    }

    private fun clearTimeout() {
        timeout?.let(mainHandler::removeCallbacks)
        timeout = null
    }

    private fun cancelPendingRead() {
        clearTimeout()
        readJob?.cancel()
        readJob = null
    }

    @MainThread
    fun close() {
        if (gate.closed) return
        gate.close()
        cancelPendingRead()
        readyCar = null
        scope.cancel()
        runCatching { car?.disconnect() }
        car = null
        lastState = null
    }
}
