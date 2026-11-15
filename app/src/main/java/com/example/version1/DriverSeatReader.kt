package com.example.version1

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*

/** One read-only INFO_DRIVER_SEAT query per Car connection; no model/preview inference.
 * createCar is asynchronous and synchronous property reads stay off the UI thread. */
class DriverSeatReader(context: Context, private val changed: (DriverSeatState) -> Unit) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = VehicleIdentityRequestGate()
    private var car: Car? = null
    private var job: Job? = null
    private var timeout: Runnable? = null
    private var started = false

    fun start() {
        if (started || gate.closed) return
        started = true
        val request = gate.begin() ?: return
        changed(DriverSeatState.Loading)
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) ||
            context.checkSelfPermission(Car.PERMISSION_CAR_INFO) != PackageManager.PERMISSION_GRANTED) {
            changed(DriverSeatState.Unavailable("운전석 위치 조회 권한 또는 차량 서비스가 없습니다.")); return
        }
        deadline(request)
        try {
            car = Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (gate.closed) return@createCar
                job?.cancel(); clearDeadline()
                val current = gate.begin() ?: return@createCar
                if (!ready) changed(DriverSeatState.Unavailable("차량 연결 확인 필요"))
                else {
                    changed(DriverSeatState.Loading); deadline(current)
                    job = scope.launch {
                        val state = try {
                            withContext(Dispatchers.IO) {
                                val manager = connected.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                                val property = manager?.getProperty(Int::class.javaObjectType, VehiclePropertyIds.INFO_DRIVER_SEAT, 0)
                                val value = property?.takeIf { it.status == CarPropertyValue.STATUS_AVAILABLE }?.value
                                DriverSeatPosition.fromAreaMask(value ?: 0)?.let {
                                    DriverSeatState.Available(DriverSeatBinding(it))
                                } ?: DriverSeatState.Unavailable("차량에서 운전석 위치를 제공하지 않습니다.")
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { DriverSeatState.Unavailable("운전석 위치를 조회하지 못했습니다.") }
                        if (gate.isCurrent(current)) { clearDeadline(); changed(state) }
                    }
                }
            }
        } catch (_: Exception) { clearDeadline(); changed(DriverSeatState.Unavailable("차량 서비스 연결 실패")) }
    }
    private fun deadline(request: Long) {
        clearDeadline()
        timeout = Runnable {
            if (gate.isCurrent(request)) {
                gate.invalidate(); job?.cancel(); changed(DriverSeatState.Unavailable("운전석 위치 조회 시간 초과"))
            }
        }.also { handler.postDelayed(it, 8000) }
    }
    private fun clearDeadline() { timeout?.let(handler::removeCallbacks); timeout = null }
    fun close() {
        gate.close(); clearDeadline(); job?.cancel(); scope.cancel()
        runCatching { car?.disconnect() }; car = null
    }
}
