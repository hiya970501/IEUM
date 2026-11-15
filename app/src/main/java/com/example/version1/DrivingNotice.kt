package com.example.version1

import ai.pleos.playground.vehicle.constant.control.VehicleGear

/** Unknown startup/connection states must never assert that a parked user is driving. */
internal object DrivingNotice {
    const val SHADE_ALPHA=144 // White text/icon stays >=4.5:1 even over a white frame.
    fun knownGear(gear:VehicleGear?)=gear?.takeUnless { it==VehicleGear.GEAR_UNKNOWN }
    fun forState(gearKnown:Boolean,parked:Boolean,uxAllowed:Boolean)=when {
        !gearKnown -> "차량 상태를 확인하고 있어요."
        parked -> "차량의 안전 상태를 확인하고 있어요."
        !uxAllowed -> "주행 중에는 조작할 수 없어요."
        else -> "정차 후 P단에서 이용해 주세요."
    }
    fun compactForState(gearKnown:Boolean,parked:Boolean,uxAllowed:Boolean)=when {
        !gearKnown -> "차량 상태 확인 중"
        parked -> "안전 상태 확인 중"
        !uxAllowed -> "주행 중 조작 제한"
        else -> "P단에서 이용하세요"
    }
}
