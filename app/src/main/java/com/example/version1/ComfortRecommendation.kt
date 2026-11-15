package com.example.version1

enum class ThermalFeeling(val title: String) {
    HOT("더워요"), NEUTRAL("적당해요"), COLD("추워요")
}

enum class HeartRateSource { WATCH, MANUAL, DEMO }

enum class HeartRateStatus {
    LIVE, MANUAL, DEMO, STALE, INVALID, MISSING
}

/**
 * ageSeconds is the age of the sample at recommendation time, not time since connection.
 * Callers must advance it while no new sample arrives; no Android clock is used in this domain.
 * null means the sample's age is unknown and excludes it from assisted fan adjustment.
 */
data class HeartRateReading(
    val bpm: Int?,
    val source: HeartRateSource,
    val ageSeconds: Long? = null
) {
    val status: HeartRateStatus
        get() = when {
            bpm == null -> HeartRateStatus.MISSING
            bpm !in ComfortPolicy.INPUT_BPM_RANGE -> HeartRateStatus.INVALID
            ageSeconds != null && ageSeconds < 0 -> HeartRateStatus.INVALID
            ageSeconds != null && ageSeconds >= ComfortPolicy.FRESH_FOR_SECONDS -> HeartRateStatus.STALE
            source == HeartRateSource.WATCH && ageSeconds == null -> HeartRateStatus.MISSING
            source == HeartRateSource.WATCH -> HeartRateStatus.LIVE
            source == HeartRateSource.MANUAL -> HeartRateStatus.MANUAL
            else -> HeartRateStatus.DEMO
        }

    val canAssistComfort: Boolean
        get() = status in setOf(HeartRateStatus.LIVE, HeartRateStatus.MANUAL, HeartRateStatus.DEMO) &&
            ageSeconds != null && ageSeconds in 0 until ComfortPolicy.FRESH_FOR_SECONDS

    /** Stale, invalid, or unverified watch readings must not be labelled as a current pulse. */
    val bpmForDisplay: Int?
        get() = if (status in setOf(HeartRateStatus.LIVE, HeartRateStatus.MANUAL, HeartRateStatus.DEMO)) bpm else null

    companion object {
        fun demo(bpm: Int) = HeartRateReading(bpm, HeartRateSource.DEMO, 0)
        fun manual(bpm: Int) = HeartRateReading(bpm, HeartRateSource.MANUAL, 0)
        fun watch(bpm: Int?, ageSeconds: Long?) = HeartRateReading(bpm, HeartRateSource.WATCH, ageSeconds)
        fun missing() = HeartRateReading(null, HeartRateSource.WATCH)
    }
}

enum class SoundPreset(val title: String) {
    WAVES("잔잔한 파도"), RAIN("차분한 빗소리"), FOREST("고요한 숲"), OFF("소리 끄기")
}

data class ComfortRequest(
    val workout: Workout,
    val feeling: ThermalFeeling = ThermalFeeling.NEUTRAL,
    val preferredTemperature: Float = 24f,
    val heartRate: HeartRateReading = HeartRateReading.demo(workout.bpm),
    /** Only populate from an explicit session record/input; ActivityKind.minutes is never copied. */
    val actualWorkoutMinutes: Int? = null,
    val soundPreset: SoundPreset = SoundPreset.WAVES,
    /** Only true for an explicitly supplied session record, not an inferred activity. */
    val activityFromRecentRecord: Boolean = false,
    /** A saved cool/warm preference is not a measurement of the user's body temperature. */
    val usesSavedPreset: Boolean = false,
    val preferredFan: Int = 2
)

data class ComfortRecommendation(
    val plan: RecoveryPlan,
    val heartRateStatus: HeartRateStatus,
    val dataLabel: String,
    val explanation: List<String>,
    val actualWorkoutMinutes: Int?,
    val activitySummary: String,
    val soundPreset: SoundPreset,
    val heartRateAssisted: Boolean
)

/**
 * Product demonstration rules, not physiological thresholds or a treatment/recovery model.
 * Explicit feeling and wet clothing take precedence over a heart-rate-assisted fan adjustment.
 */
internal object ComfortPolicy {
    val INPUT_BPM_RANGE = 40..220
    const val FRESH_FOR_SECONDS = 30L
    const val FAN_ASSIST_BPM = 120
    private const val MIN_TEMPERATURE = 22f
    private const val MAX_TEMPERATURE = 26f

    fun recommend(request: ComfortRequest): ComfortRecommendation {
        val reasons = mutableListOf<String>()
        val base = if (request.preferredTemperature.isFinite()) {
            request.preferredTemperature.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE)
        } else 24f
        val adjustment = when (request.feeling) {
            ThermalFeeling.HOT -> -1f
            ThermalFeeling.NEUTRAL -> 0f
            ThermalFeeling.COLD -> 1f
        }
        val target = (base + adjustment).coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE)
        reasons += if (request.usesSavedPreset) when (request.feeling) {
            ThermalFeeling.HOT -> "시원한 프리셋으로 선호 온도보다 1°C 낮은 설정과 시트 통풍을 준비해요. 체온을 추정한 결과는 아니에요."
            ThermalFeeling.NEUTRAL -> "저장한 선호 온도를 유지하는 기본 프리셋이에요."
            ThermalFeeling.COLD -> "포근한 프리셋으로 선호 온도보다 1°C 높은 설정과 열선 1단을 준비해요. 체온을 추정한 결과는 아니에요."
        } else when (request.feeling) {
            ThermalFeeling.HOT -> "더워요를 선택해 선호 온도보다 1°C 낮은 설정을 제안해요."
            ThermalFeeling.NEUTRAL -> "저장한 선호 온도를 유지해요. 별도의 체감 입력은 필요하지 않아요."
            ThermalFeeling.COLD -> "추워요를 선택해 선호 온도보다 1°C 높은 설정과 열선 1단을 제안해요."
        }
        if (!request.preferredTemperature.isFinite()) {
            reasons += "선호 온도를 확인할 수 없어 기본 24°C를 기준으로 삼았어요."
        } else if (base != request.preferredTemperature || target != base + adjustment) {
            reasons += "설정 온도는 이 데모의 제안 범위인 22–26°C 안에서 제한해요."
        }

        if (request.workout.wet) {
            reasons += "젖은 상태를 선택해 외기 유입과 A/C를 제안해요. 시트 통풍과 강한 바람은 사용하지 않아요."
        }
        val assisted = request.feeling == ThermalFeeling.HOT && !request.workout.wet &&
            request.heartRate.canAssistComfort && request.heartRate.bpm!! >= FAN_ASSIST_BPM
        val fan = if (request.workout.wet) 2 else if (assisted) 3 else request.preferredFan.coerceIn(2, 3)
        val heat = if (request.feeling == ThermalFeeling.COLD) 1 else 0
        val ventilation = if (request.feeling == ThermalFeeling.HOT && !request.workout.wet) 1 else 0
        val sourceName = when (request.heartRate.source) {
            HeartRateSource.WATCH -> "워치"
            HeartRateSource.MANUAL -> "수동 입력"
            HeartRateSource.DEMO -> "데모"
        }
        val dataLabel = when (request.heartRate.status) {
            HeartRateStatus.LIVE -> "워치/폰 · 최근 30초 이내 심박"
            HeartRateStatus.MANUAL -> "수동 입력 · 워치 실측 아님"
            HeartRateStatus.DEMO -> "데모 데이터 · 워치 미연결"
            HeartRateStatus.STALE -> "$sourceName · 오래된 심박 제외"
            HeartRateStatus.INVALID -> "$sourceName · 입력 범위 확인 필요"
            HeartRateStatus.MISSING -> "$sourceName · 현재 심박 확인 불가"
        }
        if (assisted) {
            val basis = if (request.usesSavedPreset) "시원한 선호 프리셋" else "직접 선택한 더운 체감"
            reasons += "$sourceName 심박 ${request.heartRate.bpm} bpm을 참고해 풍량 3단을 사용해요. $basis 기준의 시연 규칙이에요."
        } else if (!request.heartRate.canAssistComfort) {
            reasons += "신선한 심박을 확인할 수 없어 저장한 선호·프리셋 또는 직접 입력만으로 제안해요. 수동으로 계속 사용할 수 있어요."
        } else {
            reasons += "심박은 참고 정보예요. 저장한 선호·프리셋과 젖은 상태를 우선해 풍량 ${fan}단을 사용해요."
        }
        val activityOrigin = if (request.activityFromRecentRecord) "수신한 최근 운동 기록" else "저장 또는 선택한 활동"
        reasons += "${request.workout.kind.title}은 $activityOrigin 기준이며, 심박으로 체온·탈수·회복 상태를 판단하지 않아요."
        val actualMinutes = request.actualWorkoutMinutes?.takeIf { it > 0 }
        val activitySummary = if (actualMinutes != null) {
            "${request.workout.kind.title} · ${if (request.activityFromRecentRecord) "수신한" else "입력된"} 운동 기록 ${actualMinutes}분"
        } else "${request.workout.kind.title} · 실제 운동 시간 미수신"
        val title = when {
            request.workout.wet && request.feeling == ThermalFeeling.COLD -> "따뜻하게, 보송하게"
            request.workout.wet -> "젖은 옷, 부드러운 바람"
            request.feeling == ThermalFeeling.HOT -> "시원하게, 천천히"
            request.feeling == ThermalFeeling.COLD -> "포근하게 쉬어가기"
            else -> "내 온도로 쉬어가기"
        }
        val plan = RecoveryPlan(target, fan, heat, ventilation, title, reasons.joinToString("\n"))
        return ComfortRecommendation(plan, request.heartRate.status, dataLabel, reasons,
            actualMinutes, activitySummary, request.soundPreset, assisted)
    }
}
