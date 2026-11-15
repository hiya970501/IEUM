package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class RecentWorkoutTest {
    private val now = 1_700_000_000_000L
    private fun record(
        kind: ActivityKind? = ActivityKind.RUN,
        end: Long? = now - 10 * 60_000,
        source: WorkoutRecordSource = WorkoutRecordSource.WATCH_RELAY,
        minutes: Int? = 37
    ) = RecentWorkout(kind, end, minutes, source, "Wear OS", HeartRateReading.watch(128, 0), now)

    @Test fun onlyPairedRelayRecordsCanSelectAnActivityAutomatically() {
        assertTrue(record().canAutoSelect(now))
        assertFalse(record(source = WorkoutRecordSource.MANUAL_IMPORT).canAutoSelect(now))
        assertFalse(record(source = WorkoutRecordSource.DEMO).canAutoSelect(now))
        assertNull(record(source = WorkoutRecordSource.MANUAL_IMPORT).automaticComfortRequest(now))
        assertNull(record(source = WorkoutRecordSource.DEMO).automaticComfortRequest(now))
    }

    @Test fun configuredEndAutomationSelectsActivityWithoutClaimingAWorkoutRecord() {
        val event = record(source = WorkoutRecordSource.PHONE_AUTOMATION)
        assertTrue(event.canAutoSelect(now))
        assertTrue(event.sourceLabel.contains("완료 운동 레코드 조회 아님"))
        assertNull(event.validDurationMinutes)
        assertNull(event.automaticComfortRequest(now)!!.actualWorkoutMinutes)
        assertFalse(event.automaticComfortRequest(now)!!.activityFromRecentRecord)
    }

    @Test fun missingUnknownFutureAndOlderSessionDoNotAutoSelect() {
        assertFalse(record(kind = null).canAutoSelect(now))
        assertFalse(record(end = null).canAutoSelect(now))
        assertFalse(record(end = -1).canAutoSelect(now))
        assertFalse(record(end = now + 1).canAutoSelect(now))
        assertFalse(record(end = now - 3_600_001).canAutoSelect(now))
        assertTrue(record(end = now - 3_600_000).canAutoSelect(now))
    }

    @Test fun activityAliasesAreKnownButUnsupportedExerciseIsNotGuessed() {
        for (alias in listOf("RUNNING", "outdoor running", "러닝", "달리기")) {
            assertEquals(ActivityKind.RUN, normalizeActivityKind(alias))
        }
        for (alias in listOf("SWIMMING", "open-water-swimming", "수영")) {
            assertEquals(ActivityKind.WATER, normalizeActivityKind(alias))
        }
        for (alias in listOf("hiking", "walking", "걷기", "등산")) {
            assertEquals(ActivityKind.HIKE, normalizeActivityKind(alias))
        }
        assertNull(normalizeActivityKind("cycling"))
        assertNull(normalizeActivityKind(null))
    }

    @Test fun recordsNeverInventSampleExerciseDurations() {
        val request = record(minutes = null).automaticComfortRequest(now)!!
        assertNull(request.actualWorkoutMinutes)
        for (minutes in listOf(-1, 0, 1441, Int.MAX_VALUE)) {
            assertNull(record(minutes = minutes).validDurationMinutes)
        }
        assertEquals(37, record().automaticComfortRequest(now)!!.actualWorkoutMinutes)
    }

    @Test fun swimmingPresetDoesNotClaimWetClothesWereMeasured() {
        val request = record(kind = ActivityKind.WATER).automaticComfortRequest(now)!!
        assertFalse(request.workout.wet)
        assertTrue(request.usesSavedPreset)
        val result = RecoveryPlan.recommend(request)
        assertEquals(25f, result.plan.temperature)
        assertEquals(1, result.plan.heat)
        assertEquals(0, result.plan.ventilation)
        assertTrue(result.explanation.any { it.contains("프리셋") && it.contains("체온을 추정한 결과는 아니") })
        assertFalse(result.explanation.any { it.contains("젖은 상태를 선택") })
    }

    @Test fun runningStarterPresetWorksWithoutFreshHeartRate() {
        val noPulse = record().copy(heartRate = HeartRateReading.missing())
        val result = RecoveryPlan.recommend(noPulse.automaticComfortRequest(now)!!)
        assertEquals(23f, result.plan.temperature)
        assertEquals(3, result.plan.fan)
        assertEquals(1, result.plan.ventilation)
        assertFalse(result.heartRateAssisted)
        assertTrue(result.activitySummary.contains("수신한 운동 기록"))
    }

    @Test fun savedPreferencesOverrideStarterActivityPreset() {
        val preferences = AutomaticComfortPreferences(
            preferredTemperature = 23f, feeling = ThermalFeeling.NEUTRAL,
            wet = true, soundPreset = SoundPreset.OFF, preferredFan = 3)
        val request = record().automaticComfortRequest(now, preferences)!!
        val result = RecoveryPlan.recommend(request)
        assertEquals(23f, result.plan.temperature)
        assertEquals(2, result.plan.fan)
        assertEquals(0, result.plan.heat)
        assertEquals(0, result.plan.ventilation)
        assertEquals(SoundPreset.OFF, result.soundPreset)
        assertTrue(request.workout.wet)
    }

    @Test fun cachedWorkoutHeartRateExpiresIndependentlyOfSessionRecency() {
        val recent = record()
        assertEquals(HeartRateStatus.LIVE, recent.heartRateAt(now + 29_000).status)
        assertEquals(HeartRateStatus.STALE, recent.heartRateAt(now + 30_000).status)
        assertNull(recent.heartRateAt(now + 30_000).bpmForDisplay)
        assertTrue(recent.canAutoSelect(now + 30_000))
    }

    @Test fun unknownOrFutureReceiveTimeDoesNotPresentCurrentWatchPulse() {
        assertEquals(HeartRateStatus.MISSING, record().copy(receivedAtEpochMillis = null).heartRateAt(now).status)
        assertEquals(HeartRateStatus.MISSING, record().heartRateAt(now - 1).status)
        val invalid = record().copy(heartRate = HeartRateReading.watch(128, -1))
        assertEquals(HeartRateStatus.INVALID, invalid.heartRateAt(now + 1000).status)
    }

    @Test fun noPreferenceOrElevatedPulseDoesNotInferHeatForNeutralProfile() {
        val request = record().automaticComfortRequest(now, AutomaticComfortPreferences())!!
        val result = RecoveryPlan.recommend(request)
        assertEquals(ThermalFeeling.NEUTRAL, request.feeling)
        assertEquals(24f, result.plan.temperature)
        assertEquals(0, result.plan.heat)
        assertEquals(0, result.plan.ventilation)
        assertFalse(result.heartRateAssisted)
    }

    @Test fun cabinConnectionStateOnlyMeansSomeReadbackExists() {
        val missing = CabinTelemetry(null, null, null, null, null)
        assertFalse(missing.connected)
        assertNull(missing.vehicleName)
        assertTrue(missing.copy(driverTemperature = 24.5f).connected)
        assertTrue(missing.copy(powerOn = false).connected)
    }
}
