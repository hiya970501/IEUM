package com.example.version1

/** Persisted with each owned change; it changes wording, never restoration permissions. */
enum class VehicleSettingOperation(val restoreLabel: String, val restoredMessage: String) {
    RECOVERY("자동 Mode 설정 복원", "자동 Mode OFF · 복원 완료. 직접 바꾼 설정은 유지해요."),
    ZERO_TAP("제로탭 이전 설정 복원", "제로탭 중단 전 설정으로 복원했어요. 직접 바꾼 설정은 유지해요."),
    UNKNOWN("차량 설정 복원", "차량 설정을 복원했어요. 직접 바꾼 설정은 유지해요.")
}

internal fun vehicleSettingOperationFromStored(name: String?): VehicleSettingOperation =
    name?.let(VehicleSettingOperation::valueOf) ?: VehicleSettingOperation.UNKNOWN
