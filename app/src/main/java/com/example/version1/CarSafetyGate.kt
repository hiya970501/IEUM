package com.example.version1

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

/** Park-only app: unknown, disconnected or restricted UX state always blocks new control. */
class CarSafetyGate(context: Context, private val onChanged: (Boolean) -> Unit) {
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    var allowed = false
        private set
    private var closed = false
    private val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { update(it) }

    init {
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            runCatching {
                car = Car.createCar(context.applicationContext, Handler(Looper.getMainLooper()),
                    Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                    if (closed) return@createCar
                    if (ready) {
                        runCatching {
                            manager = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                            manager!!.registerListener(listener)
                            update(manager!!.currentCarUxRestrictions)
                        }.onFailure { update(null) }
                    } else { manager = null; update(null) }
                }
            }.onFailure { update(null) }
        }
    }
    private fun update(restrictions: CarUxRestrictions?) {
        val next = restrictions != null && !restrictions.isRequiresDistractionOptimization &&
            restrictions.activeRestrictions == CarUxRestrictions.UX_RESTRICTIONS_BASELINE
        if (allowed != next) { allowed = next; onChanged(next) }
    }
    /** Called again immediately before vehicle changes, in addition to the subscription. */
    fun refreshAllowed(): Boolean {
        if (closed) return false
        runCatching { update(manager?.currentCarUxRestrictions) }.onFailure { update(null) }
        return allowed
    }
    fun close() {
        closed = true
        runCatching { manager?.unregisterListener() }
        manager = null
        runCatching { car?.disconnect() }
        car = null
        allowed = false
    }
}
