package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class RecoveryPlanTest {
    private val run = Workout(ActivityKind.RUN, 128, false)

    private fun recommend(
        feeling: ThermalFeeling = ThermalFeeling.NEUTRAL,
        wet: Boolean = false,
        reading: HeartRateReading = HeartRateReading.watch(128, 0),
        preferred: Float = 24f
    ) = RecoveryPlan.recommend(ComfortRequest(run.copy(wet = wet), feeling, preferred, reading))

    @Test fun heartRateAloneDoesNotInferThatAUserIsHot() {
        val plan = RecoveryPlan.forWorkout(run.copy(bpm = 180))
        assertEquals(24f, plan.temperature)
        assertEquals(2, plan.fan)
        assertEquals(0, plan.heat)
        assertEquals(0, plan.ventilation)
        assertEquals(600, plan.durationSeconds)
    }

    @Test fun explicitHotFeelingAndFreshHeartRateCanAssistFanOnly() {
        val below = recommend(ThermalFeeling.HOT, reading = HeartRateReading.watch(119, 0))
        val at = recommend(ThermalFeeling.HOT, reading = HeartRateReading.watch(120, 0))
        assertEquals(2, below.plan.fan)
        assertEquals(3, at.plan.fan)
        assertTrue(at.heartRateAssisted)
        assertEquals(below.plan.temperature, at.plan.temperature)
        assertEquals(23f, at.plan.temperature)
        assertEquals(0, at.plan.heat)
        assertEquals(1, at.plan.ventilation)
    }

    @Test fun explicitColdFeelingOverridesElevatedHeartRate() {
        val result = recommend(ThermalFeeling.COLD, reading = HeartRateReading.watch(180, 0))
        assertEquals(25f, result.plan.temperature)
        assertEquals(2, result.plan.fan)
        assertEquals(1, result.plan.heat)
        assertEquals(0, result.plan.ventilation)
        assertFalse(result.heartRateAssisted)
    }

    @Test fun wetHotUserDoesNotReceiveHeatOrStrongSeatAirflow() {
        val result = recommend(ThermalFeeling.HOT, wet = true, reading = HeartRateReading.watch(180, 0))
        assertEquals(23f, result.plan.temperature)
        assertEquals(2, result.plan.fan)
        assertEquals(0, result.plan.heat)
        assertEquals(0, result.plan.ventilation)
        assertFalse(result.heartRateAssisted)
    }

    @Test fun wetClothesAloneDoNotInferColdFeeling() {
        val result = recommend(wet = true)
        assertEquals(24f, result.plan.temperature)
        assertEquals(0, result.plan.heat)
        assertEquals(0, result.plan.ventilation)
    }

    @Test fun thirtySecondBoundaryExcludesStaleWatchSample() {
        val fresh = recommend(ThermalFeeling.HOT, reading = HeartRateReading.watch(128, 29))
        val stale = recommend(ThermalFeeling.HOT, reading = HeartRateReading.watch(128, 30))
        assertEquals(HeartRateStatus.LIVE, fresh.heartRateStatus)
        assertEquals(3, fresh.plan.fan)
        assertEquals(HeartRateStatus.STALE, stale.heartRateStatus)
        assertEquals(2, stale.plan.fan)
        assertFalse(stale.heartRateAssisted)
        assertNull(HeartRateReading.watch(128, 30).bpmForDisplay)
    }

    @Test fun unknownAndFutureWatchTimesNeverAssist() {
        val unknown = recommend(ThermalFeeling.HOT, reading = HeartRateReading.watch(128, null))
        val future = recommend(ThermalFeeling.HOT, reading = HeartRateReading.watch(128, -1))
        assertEquals(HeartRateStatus.MISSING, unknown.heartRateStatus)
        assertEquals(HeartRateStatus.INVALID, future.heartRateStatus)
        assertFalse(unknown.heartRateAssisted)
        assertFalse(future.heartRateAssisted)
        assertNull(HeartRateReading.watch(128, null).bpmForDisplay)
    }

    @Test fun invalidHeartRateFallsBackToExplicitFeeling() {
        for (bpm in listOf(Int.MIN_VALUE, 0, 39, 221, Int.MAX_VALUE)) {
            val result = recommend(ThermalFeeling.HOT, reading = HeartRateReading.watch(bpm, 0))
            assertEquals(HeartRateStatus.INVALID, result.heartRateStatus)
            assertEquals(23f, result.plan.temperature)
            assertEquals(2, result.plan.fan)
            assertFalse(result.heartRateAssisted)
            assertNull(HeartRateReading.watch(bpm, 0).bpmForDisplay)
        }
        assertEquals(HeartRateStatus.LIVE, HeartRateReading.watch(40, 0).status)
        assertEquals(HeartRateStatus.LIVE, HeartRateReading.watch(220, 0).status)
    }

    @Test fun missingHeartRateDoesNotBlockManualComfortPlan() {
        val result = recommend(ThermalFeeling.COLD, reading = HeartRateReading.missing())
        assertEquals(HeartRateStatus.MISSING, result.heartRateStatus)
        assertEquals(25f, result.plan.temperature)
        assertEquals(1, result.plan.heat)
        assertEquals(600, result.plan.durationSeconds)
        assertTrue(result.explanation.any { it.contains("수동으로 계속") })
    }

    @Test fun manualAndDemoSamplesAreNeverLabelledAsLiveWatchData() {
        val manual = recommend(ThermalFeeling.HOT, reading = HeartRateReading.manual(128))
        val demo = recommend(ThermalFeeling.HOT, reading = HeartRateReading.demo(128))
        assertEquals(HeartRateStatus.MANUAL, manual.heartRateStatus)
        assertEquals(HeartRateStatus.DEMO, demo.heartRateStatus)
        assertTrue(manual.dataLabel.contains("수동"))
        assertTrue(demo.dataLabel.contains("데모"))
        assertTrue(manual.heartRateAssisted)
        assertTrue(demo.heartRateAssisted)
        assertFalse(manual.dataLabel.contains("Wear OS"))
        assertFalse(demo.dataLabel.contains("Wear OS"))
    }

    @Test fun staleManualOrDemoInputAlsoExpiresFromFanAssistance() {
        for (source in listOf(HeartRateSource.MANUAL, HeartRateSource.DEMO)) {
            val result = recommend(ThermalFeeling.HOT, reading = HeartRateReading(128, source, 30))
            assertEquals(HeartRateStatus.STALE, result.heartRateStatus)
            assertFalse(result.heartRateAssisted)
            assertEquals(2, result.plan.fan)
        }
        val untimed = recommend(ThermalFeeling.HOT, reading = HeartRateReading(128, HeartRateSource.MANUAL))
        assertEquals(HeartRateStatus.MANUAL, untimed.heartRateStatus)
        assertFalse(untimed.heartRateAssisted)
    }

    @Test fun sampleActivityMinutesAreNotPresentedAsAnActualWorkoutRecord() {
        for (kind in ActivityKind.entries) {
            val result = RecoveryPlan.recommend(ComfortRequest(Workout(kind, kind.demoBpm, false)))
            assertNull(result.actualWorkoutMinutes)
            assertTrue(result.activitySummary.contains("미수신"))
            assertFalse(result.activitySummary.contains("${kind.minutes}분"))
        }
        val recorded = RecoveryPlan.recommend(ComfortRequest(run, actualWorkoutMinutes = 37))
        assertEquals(37, recorded.actualWorkoutMinutes)
        assertTrue(recorded.activitySummary.contains("37분"))
        val invalid = RecoveryPlan.recommend(ComfortRequest(run, actualWorkoutMinutes = 0))
        assertNull(invalid.actualWorkoutMinutes)
    }

    @Test fun preferredTemperatureIsUsedAndInvalidValuesHaveBoundedFallback() {
        assertEquals(23.5f, recommend(preferred = 23.5f).plan.temperature)
        assertEquals(24.5f, recommend(ThermalFeeling.COLD, preferred = 23.5f).plan.temperature)
        assertEquals(22f, recommend(ThermalFeeling.HOT, preferred = -20f).plan.temperature)
        assertEquals(26f, recommend(ThermalFeeling.COLD, preferred = 40f).plan.temperature)
        assertEquals(24f, recommend(preferred = Float.NaN).plan.temperature)
        assertEquals(24f, recommend(preferred = Float.POSITIVE_INFINITY).plan.temperature)
    }

    @Test fun allCombinationsKeepSeatFunctionsExclusiveAndSettingsBounded() {
        for (kind in ActivityKind.entries)
            for (feeling in ThermalFeeling.entries)
                for (wet in listOf(true, false))
                    for (bpm in listOf(39, 40, 119, 120, 220, 221))
                        for (age in listOf(-1L, 0L, 29L, 30L)) {
                            val result = RecoveryPlan.recommend(ComfortRequest(
                                Workout(kind, bpm, wet), feeling,
                                heartRate = HeartRateReading.watch(bpm, age)))
                            assertFalse(result.plan.heat > 0 && result.plan.ventilation > 0)
                            assertTrue(result.plan.temperature in 22f..26f)
                            assertTrue(result.plan.fan in 2..3)
                            assertTrue(result.plan.heat in 0..1)
                            assertTrue(result.plan.ventilation in 0..1)
                            if (wet || feeling != ThermalFeeling.HOT || age !in 0..29 || bpm !in 120..220) {
                                assertFalse(result.heartRateAssisted)
                            }
                        }
    }
}
