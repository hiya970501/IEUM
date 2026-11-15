package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class DrivingNoticeTest {
    @Test fun translucentNoticeKeepsWhiteForegroundContrastOverBrightestBackground() {
        val channel=(255-DrivingNotice.SHADE_ALPHA)/255.0
        val luminance=if(channel<=.04045) channel/12.92 else Math.pow((channel+.055)/1.055,2.4)
        assertTrue(1.05/(luminance+.05)>=4.5)
        assertTrue(DrivingNotice.SHADE_ALPHA<175)
    }
    @Test fun compactNoticesRemainSingleLineAndDoNotAskParkedUsersToPark() {
        val unknown=DrivingNotice.compactForState(false,false,false)
        val parked=DrivingNotice.compactForState(true,true,false)
        assertFalse(unknown.contains("주행"));assertFalse(unknown.contains("P단"))
        assertFalse(parked.contains("P단"));assertFalse(parked.contains("주행"))
        for(value in listOf(unknown,parked,DrivingNotice.compactForState(true,false,false),DrivingNotice.compactForState(true,false,true)))
            assertFalse(value.contains('\n'))
    }
    @Test fun sdkUnknownEnumIsStillUnknownRatherThanANonParkGear() {
        assertNull(DrivingNotice.knownGear(ai.pleos.playground.vehicle.constant.control.VehicleGear.GEAR_UNKNOWN))
        assertNull(DrivingNotice.knownGear(null))
        assertEquals(ai.pleos.playground.vehicle.constant.control.VehicleGear.GEAR_PARK,
            DrivingNotice.knownGear(ai.pleos.playground.vehicle.constant.control.VehicleGear.GEAR_PARK))
    }
    @Test fun startupAndConnectionLossDoNotInventDrivingOrRequireGearChange() {
        for(ux in listOf(false,true)) {
            val text=DrivingNotice.forState(false,false,ux)
            assertEquals("차량 상태를 확인하고 있어요.",text)
            assertFalse(text.contains("P단"));assertFalse(text.contains("주행"))
        }
    }
    @Test fun confirmedParkWithPendingSafetyNeverAsksToParkAgain() {
        assertEquals("차량의 안전 상태를 확인하고 있어요.",DrivingNotice.forState(true,true,false))
    }
    @Test fun knownNonParkRestrictionUsesOneLineNotice() {
        assertEquals("주행 중에는 조작할 수 없어요.",DrivingNotice.forState(true,false,false))
        assertEquals("정차 후 P단에서 이용해 주세요.",DrivingNotice.forState(true,false,true))
    }
}
