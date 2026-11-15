package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class VehicleSettingOperationTest {
    @Test fun oldRecordsNeverInventAnOperation() {
        assertEquals(VehicleSettingOperation.UNKNOWN, vehicleSettingOperationFromStored(null))
        assertFalse(vehicleSettingOperationFromStored(null).restoredMessage.contains("리커버리"))
    }
    @Test fun zeroTapAndRecoveryHaveDifferentRestorationMeaning() {
        assertEquals(VehicleSettingOperation.ZERO_TAP, vehicleSettingOperationFromStored("ZERO_TAP"))
        assertTrue(VehicleSettingOperation.ZERO_TAP.restoredMessage.contains("중단 전"))
        assertFalse(VehicleSettingOperation.ZERO_TAP.restoredMessage.contains("리커버리 OFF"))
        assertTrue(VehicleSettingOperation.RECOVERY.restoredMessage.contains("자동 Mode OFF"))
    }
    @Test fun newerUnknownOperationIsNotSilentlyReclassified() {
        assertTrue(runCatching { vehicleSettingOperationFromStored("unsupported-future-owner") }.isFailure)
    }
}
