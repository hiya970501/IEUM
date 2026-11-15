package com.example.version1

import java.util.Locale

/** Describes the transfer path, not a claim that the vehicle verified a health sensor. */
enum class WorkoutRecordSource { WATCH_RELAY, PHONE_AUTOMATION, MANUAL_IMPORT, DEMO }

/**
 * A session record or explicitly configured workout-end event supplied by a paired relay.
 * Session end time and heart-rate sample time are intentionally independent.
 * No persisted health history, estimated duration or inferred wet clothing is added here.
 */
data class RecentWorkout(
    val kind: ActivityKind?,
    val endedAtEpochMillis: Long?,
    val durationMinutes: Int?,
    val source: WorkoutRecordSource,
    val sourceName: String,
    val heartRate: HeartRateReading = HeartRateReading.missing(),
    val receivedAtEpochMillis: Long? = null
) {
    fun ageSeconds(nowEpochMillis: Long): Long? {
        val ended = endedAtEpochMillis ?: return null
        if (ended < 0 || nowEpochMillis < ended) return null
        return (nowEpochMillis - ended) / 1000
    }

    /** Pairing identifies the relay; PHONE_AUTOMATION identifies a trigger, not an HKWorkout query. */
    fun canAutoSelect(nowEpochMillis: Long): Boolean =
        source in setOf(WorkoutRecordSource.WATCH_RELAY, WorkoutRecordSource.PHONE_AUTOMATION) && kind != null &&
            ageSeconds(nowEpochMillis) != null &&
            nowEpochMillis - endedAtEpochMillis!! <= RECENT_FOR_SECONDS * 1000

    val validDurationMinutes: Int?
        get() = if (source == WorkoutRecordSource.PHONE_AUTOMATION) null
            else durationMinutes?.takeIf { it in 1..1440 }

    /** Advance the sample age so a cached session cannot keep a heart rate current forever. */
    fun heartRateAt(nowEpochMillis: Long): HeartRateReading {
        val received = receivedAtEpochMillis
        val originalAge = heartRate.ageSeconds
        if (originalAge != null && originalAge < 0) return heartRate
        if (received == null || originalAge == null || nowEpochMillis < received) {
            return heartRate.copy(ageSeconds = null)
        }
        val elapsed = (nowEpochMillis - received) / 1000
        val advanced = if (originalAge > Long.MAX_VALUE - elapsed) Long.MAX_VALUE else originalAge + elapsed
        return heartRate.copy(ageSeconds = advanced)
    }

    val sourceLabel: String
        get() = when (source) {
            WorkoutRecordSource.WATCH_RELAY -> "워치/폰에서 수신 · 센서 진위 미검증"
            WorkoutRecordSource.PHONE_AUTOMATION -> "단축어 운동 종료 이벤트 · 완료 운동 레코드 조회 아님"
            WorkoutRecordSource.MANUAL_IMPORT -> "직접 가져온 운동 기록"
            WorkoutRecordSource.DEMO -> "데모 운동 기록 · 워치 실측 아님"
        }

    companion object { const val RECENT_FOR_SECONDS = 60L * 60 }
}

/** The existing compact vehicle profiles accept common HealthKit / Health Connect labels. */
fun normalizeActivityKind(raw: String?): ActivityKind? = when (
    raw?.trim()?.lowercase(Locale.ROOT)?.replace('-', '_')?.replace(' ', '_')
) {
    "run", "running", "outdoor_running", "indoor_running", "러닝", "달리기", "조깅" -> ActivityKind.RUN
    "water", "swim", "swimming", "pool_swimming", "open_water_swimming", "수영", "수중", "수중_활동" -> ActivityKind.WATER
    "hike", "hiking", "walk", "walking", "outdoor_walking", "하이킹", "등산", "걷기", "산책" -> ActivityKind.HIKE
    else -> null
}

/** Saved preferences allow one-tap use without asking the same questions after every workout. */
data class AutomaticComfortPreferences(
    val preferredTemperature: Float = 24f,
    val feeling: ThermalFeeling = ThermalFeeling.NEUTRAL,
    val wet: Boolean = false,
    val soundPreset: SoundPreset = SoundPreset.WAVES,
    val preferredFan: Int = 2
) {
    companion object {
        /** Product starter presets, not inferred body heat or wet-clothing measurements. */
        fun forActivity(kind: ActivityKind?): AutomaticComfortPreferences = when (kind) {
            ActivityKind.RUN -> AutomaticComfortPreferences(feeling = ThermalFeeling.HOT, preferredFan = 3)
            ActivityKind.WATER -> AutomaticComfortPreferences(feeling = ThermalFeeling.COLD)
            else -> AutomaticComfortPreferences()
        }
    }
}

/**
 * Returns null when there is no usable recent session; the app can retain a saved manual profile.
 * A swimming record alone never asserts wet clothes, and heart rate alone never asserts heat.
 */
fun RecentWorkout.automaticComfortRequest(
    nowEpochMillis: Long,
    preferences: AutomaticComfortPreferences = AutomaticComfortPreferences.forActivity(kind),
    currentHeartRate: HeartRateReading = heartRateAt(nowEpochMillis)
): ComfortRequest? {
    if (!canAutoSelect(nowEpochMillis)) return null
    return ComfortRequest(
        workout = Workout(kind!!, currentHeartRate.bpmForDisplay ?: 0, preferences.wet),
        feeling = preferences.feeling,
        preferredTemperature = preferences.preferredTemperature,
        heartRate = currentHeartRate,
        actualWorkoutMinutes = validDurationMinutes,
        soundPreset = preferences.soundPreset,
        activityFromRecentRecord = source == WorkoutRecordSource.WATCH_RELAY,
        usesSavedPreset = true,
        preferredFan = preferences.preferredFan
    )
}
