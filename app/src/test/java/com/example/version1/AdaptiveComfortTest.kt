package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class AdaptiveComfortTest {
    private val now = 1_800_000_000_000L
    private val context = ComfortContext("driver-a","ioniq","RUN",false,ComfortPhase.ARRIVAL,
        outdoorC=30f,humidity=70f,cabinC=32f,bpm=115,minutesAfterExercise=5)
    private fun case(i:Int, temperature:Float=24f, ctx:ComfortContext=context, good:Boolean=true,
                     session:String="session-$i", fan:Int=2,heat:Int=0,vent:Int=0) =
        ComfortCase(session,now-i*86_400_000L,ctx,LearnedSetting(temperature,fan,heat,vent),good)
    @Test fun requiresThreeIndependentSatisfiedSessions() {
        assertNull(AdaptiveComfort.recommend(context,listOf(case(1),case(2)),now).setting)
        assertNotNull(AdaptiveComfort.recommend(context,(1..3).map { case(it) },now).setting)
        assertNull(AdaptiveComfort.recommend(context,(1..20).map { case(it,session="same-trip") },now).setting)
    }
    @Test fun neverCrossesDriverVehicleWetnessOrPhase() {
        listOf(context.copy(profile="driver-b"),context.copy(vehicle="kona"),context.copy(wet=true),context.copy(phase=ComfortPhase.SETTLED)).forEach { other ->
            assertNull(AdaptiveComfort.recommend(context,(1..4).map { case(it,ctx=other) },now).setting)
        }
    }
    @Test fun variationIsNotAveragedIntoFalseConfidence() {
        assertNull(AdaptiveComfort.recommend(context,listOf(case(1,20f),case(2,25f),case(3,28f)),now).setting)
        assertNull(AdaptiveComfort.recommend(context,listOf(case(1,fan=1),case(2,fan=7),case(3,fan=4)),now).setting)
        assertNull(AdaptiveComfort.recommend(context,listOf(case(1,heat=1),case(2,vent=1),case(3)),now).setting)
    }
    @Test fun onlySatisfactionIsPositiveEvidence() {
        assertNull(AdaptiveComfort.recommend(context,(1..8).map { case(it,good=false) },now).setting)
    }
    @Test fun pulseAloneCannotSelectClimate() {
        val unknown=ComfortContext("driver-a","ioniq","RUN",false,ComfortPhase.ARRIVAL,bpm=140)
        assertNull(AdaptiveComfort.recommend(unknown,(1..5).map { case(it,ctx=unknown) },now).setting)
    }
    @Test fun weatherMismatchOutweighsSameExerciseAndPulse() {
        val cold=context.copy(outdoorC=-5f,cabinC=5f,humidity=30f)
        assertTrue(AdaptiveComfort.similarity(context,cold)<.22)
    }
    @Test fun hundredDifferentDaysSelectSimilarWeather() {
        val cases=(1..100).map { i ->
            if(i<=5) case(i,23f) else case(i,27f,context.copy(outdoorC=-5f,cabinC=5f))
        }
        assertEquals(23f,AdaptiveComfort.recommend(context,cases,now).setting!!.temperature)
    }
    @Test fun choosesRealCombinationAndRejectsInvalidSettings() {
        val cases=listOf(case(1,23f),case(2,24f),case(3,23f))
        assertTrue(AdaptiveComfort.recommend(context,cases,now).setting in cases.map { it.setting })
        assertFalse(LearnedSetting(24f,2,1,1).valid())
        assertNull(AdaptiveComfort.recommend(context,(1..4).map { case(it,Float.NaN) },now).setting)
    }
    @Test fun futureRecordsAndInvalidEnvironmentDoNotMatch() {
        assertNull(AdaptiveComfort.recommend(context,(1..4).map { case(it).copy(at=now+1) },now).setting)
        assertNull(AdaptiveComfort.recommend(context.copy(outdoorC=Float.NaN),(1..4).map { case(it) },now).setting)
    }
    @Test fun unsupportedSeatStaysUnknownAndIsNotCommandedOff() {
        val setting=LearnedSetting(24f,2,null,null)
        assertTrue(setting.valid());assertFalse(setting.asPlan().controlsSeat)
    }
    @Test fun recentLowRatingVetoesPreviouslyLikedSettingButNotOtherDrivers() {
        val liked=(1..3).map { case(it).copy(rating=5) }
        val bad=case(0,good=false).copy(at=now,rating=1)
        assertNull(AdaptiveComfort.recommend(context,liked+bad,now).setting)
        assertNotNull(AdaptiveComfort.recommend(context,liked+bad.copy(context=context.copy(profile="other")),now).setting)
        val different=bad.copy(setting=LearnedSetting(28f,6,0,0))
        assertNotNull(AdaptiveComfort.recommend(context,liked+different,now).setting)
    }
    @Test fun neutralAndMalformedRatingsCannotBecomePositiveEvidence() {
        val neutral=(1..4).map { case(it,good=false).copy(rating=3) }
        assertNull(AdaptiveComfort.recommend(context,neutral,now).setting)
        assertNull(AdaptiveComfort.recommend(context,(1..4).map { case(it).copy(rating=1) },now).setting)
        assertFalse(case(1).copy(rating=6).validFeedback())
    }
    @Test fun coolingAndSteeringArePreservedWithoutInventingSupport() {
        val cooling=LearnedSetting(23f,2,-2,0,1)
        assertTrue(cooling.valid())
        val decision=AdaptiveComfort.recommend(context,(1..3).map { case(it).copy(setting=cooling,rating=4) },now)
        assertEquals(2,decision.setting!!.asPlan().seatCooling)
        assertEquals(0,decision.setting!!.asPlan().heat)
        assertEquals(1,decision.setting!!.asPlan().steeringHeat)
        assertFalse(cooling.copy(ventilation=1).valid())
        assertNull(LearnedSetting(24f,2,null,null).asPlan().steeringHeat)
    }
}
