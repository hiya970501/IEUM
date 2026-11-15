package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class RecoverySessionInputTest {
    private val now = 1_800_000_000_000L

    private fun record(kind: ActivityKind, source: WorkoutRecordSource = WorkoutRecordSource.WATCH_RELAY) =
        RecentWorkout(kind, now - 1000, 35, source, "test relay",
            HeartRateReading.watch(130, 1), now)

    private fun selection(
        manual: ActivityKind = ActivityKind.HIKE,
        follow: Boolean = true,
        received: RecentWorkout? = record(ActivityKind.RUN),
        imported: RecentWorkout? = null,
        demo: ActivityKind? = null,
        at: Long = now
    ) = RecoveryActivitySelection.resolve(manual, follow, received, imported, demo, at)

    @Test fun automaticActivityExpiresBackToSavedManualFallback() {
        val savedManual = ActivityKind.HIKE
        assertEquals(ActivityKind.RUN, selection(manual = savedManual).kind)
        val expired = selection(manual = savedManual, at = now + 3_600_001)
        assertEquals(ActivityKind.HIKE, expired.kind)
        assertNull(expired.record)
        assertEquals(ActivityKind.HIKE, selection(manual = savedManual, follow = false).kind)
    }

    @Test fun explicitImportAndDemoAreTemporaryAndNeverBecomeManualFallback() {
        val imported = record(ActivityKind.WATER, WorkoutRecordSource.MANUAL_IMPORT)
        assertEquals(ActivityKind.WATER, selection(follow = false, imported = imported).kind)
        assertEquals(imported, selection(follow = false, imported = imported).record)
        assertEquals(ActivityKind.RUN, selection(imported = imported, demo = ActivityKind.RUN).kind)
        assertNull(selection(imported = imported, demo = ActivityKind.RUN).record)
        assertEquals(ActivityKind.HIKE, selection(follow = false, received = null).kind)
        assertEquals(ActivityKind.HIKE, selection(received = imported).kind)
    }

    @Test fun workoutEndAutomationCanSelectActivityWithoutInventingItsDuration() {
        val event = record(ActivityKind.WATER, WorkoutRecordSource.PHONE_AUTOMATION)
        val selected = selection(received = event)
        assertEquals(ActivityKind.WATER, selected.kind)
        assertNull(selected.record!!.validDurationMinutes)
        assertEquals(ActivityKind.HIKE, selection(received = event, follow = false).kind)
    }

    @Test fun receivingAManualFilePreservesTheAutomaticWorkoutUntilExplicitApplication() {
        val swimming = record(ActivityKind.WATER)
        val importedRun = record(ActivityKind.RUN, WorkoutRecordSource.MANUAL_IMPORT)
        val inbox = RecoveryWorkoutInbox().receive(swimming).receive(importedRun)
        assertEquals(swimming, inbox.receivedWorkout)
        assertEquals(importedRun, inbox.pendingImport)
        val stillAutomatic = selection(received = inbox.receivedWorkout)
        assertEquals(ActivityKind.WATER, stillAutomatic.kind)
        assertEquals(swimming, stillAutomatic.record)
        val explicitlyApplied = selection(received = inbox.receivedWorkout, imported = inbox.pendingImport)
        assertEquals(ActivityKind.RUN, explicitlyApplied.kind)
        assertEquals(WorkoutRecordSource.MANUAL_IMPORT, explicitlyApplied.record!!.source)
        val newAutomatic = inbox.receive(record(ActivityKind.HIKE))
        assertEquals(ActivityKind.HIKE, newAutomatic.receivedWorkout!!.kind)
        assertEquals(importedRun, newAutomatic.pendingImport)
        assertNull(RecoveryWorkoutInbox().pendingImport)
    }

    @Test fun aNewWorkoutHeartRateIsNotHiddenByAnOlderDirectCache() {
        val staleDirect = HeartRateReading.watch(150, 40)
        val freshWorkout = HeartRateReading.watch(105, 1)
        val selected = RecoveryHeartRateSelection.newestWatchReading(staleDirect, freshWorkout)
        assertEquals(105, selected.bpm)
        assertEquals(HeartRateStatus.LIVE, selected.status)
        assertEquals(1L, selected.ageSeconds)
    }

    @Test fun newestMeasurementWinsInsteadOfLastPacketArrivalOrSourcePriority() {
        val direct = HeartRateReading.watch(120, 2)
        val delayedWorkout = HeartRateReading.watch(150, 29)
        assertEquals(direct, RecoveryHeartRateSelection.newestWatchReading(direct, delayedWorkout))
        val invalidDirect = HeartRateReading.watch(999, 0)
        assertEquals(delayedWorkout, RecoveryHeartRateSelection.newestWatchReading(invalidDirect, delayedWorkout))
        assertEquals(direct, RecoveryHeartRateSelection.newestWatchReading(direct, HeartRateReading.manual(160)))
        assertEquals(HeartRateStatus.MISSING,
            RecoveryHeartRateSelection.newestWatchReading(null, HeartRateReading.demo(130)).status)
    }

    @Test fun staleCandidatesCannotBecomeFreshByBeingSelectedAgain() {
        val selected = RecoveryHeartRateSelection.newestWatchReading(
            HeartRateReading.watch(140, 50), HeartRateReading.watch(135, 40))
        assertEquals(40L, selected.ageSeconds)
        assertEquals(HeartRateStatus.STALE, selected.status)
        assertNull(selected.bpmForDisplay)
        assertFalse(selected.canAssistComfort)
    }

    @Test fun aDelayedShortcutCannotOverwriteAFresherDirectSensorSample() {
        val freshDirect = DirectHeartRateSample(119, 10_000)
        val olderShortcut = DirectHeartRateSample(150, 5_000)
        val unchanged = RecoveryHeartRateSelection.newestDirectSample(freshDirect, olderShortcut)
        assertEquals(freshDirect, unchanged)
        assertEquals(119, unchanged.bpm)
        val newerShortcut = DirectHeartRateSample(130, 11_000)
        assertEquals(newerShortcut, RecoveryHeartRateSelection.newestDirectSample(unchanged, newerShortcut))
        // A historical sample can predate emulator boot. It remains old, never reset to now.
        val beforeBoot = DirectHeartRateSample(125, -60_000)
        assertEquals(beforeBoot, RecoveryHeartRateSelection.newestDirectSample(null, beforeBoot))
        assertEquals(freshDirect, RecoveryHeartRateSelection.newestDirectSample(freshDirect, beforeBoot))
    }

    @Test fun anActivityChangeDiscardsThePreviousDraftEvenIfTheActivityReturns() {
        val tracker = PreferenceEditorTracker()
        val running = PreferenceEditContext("driver-a", ActivityKind.RUN)
        tracker.moveTo(running)
        val runningDraft = tracker.capture()
        assertFalse(tracker.moveTo(running))
        assertTrue(tracker.canCommit(runningDraft))
        assertTrue(tracker.moveTo(running.copy(kind = ActivityKind.WATER)))
        assertFalse(tracker.canCommit(runningDraft))
        tracker.moveTo(running)
        assertFalse(tracker.canCommit(runningDraft))
        assertTrue(tracker.canCommit(tracker.capture()))
    }

    @Test fun driverChangesAndBackgroundingDiscardDraftsWithoutChangingStoredPreferences() {
        val tracker = PreferenceEditorTracker()
        tracker.moveTo(PreferenceEditContext("driver-a", ActivityKind.RUN))
        val driverADraft = tracker.capture()
        tracker.moveTo(PreferenceEditContext("driver-b", ActivityKind.RUN))
        assertFalse(tracker.canCommit(driverADraft))
        val driverBDraft = tracker.capture()
        tracker.invalidate()
        assertFalse(tracker.canCommit(driverBDraft))
        assertTrue(tracker.canCommit(tracker.capture()))
    }

    @Test fun a29To30SecondTransitionRefreshesTheOffPreviewAndRemovesHeartRateAssistance() {
        val activity = selection(follow = false)
        val fresh = HeartRateReading.watch(135, 29)
        val stale = fresh.copy(ageSeconds = 30)
        val previous = RecoveryPreviewInputs(activity, fresh.status)
        val next = RecoveryPreviewInputs(activity, stale.status)
        assertTrue(next.needsRefresh(previous, recoveryActive = false))
        assertFalse(next.needsRefresh(previous, recoveryActive = true))
        fun recommend(reading: HeartRateReading) = RecoveryPlan.recommend(ComfortRequest(
            Workout(activity.kind, reading.bpmForDisplay ?: 0, false),
            feeling = ThermalFeeling.HOT, preferredFan = 2, heartRate = reading))
        assertEquals(3, recommend(fresh).plan.fan)
        assertTrue(recommend(fresh).heartRateAssisted)
        assertEquals(2, recommend(stale).plan.fan)
        assertFalse(recommend(stale).heartRateAssisted)
    }

    @Test fun activityExpiryRefreshesTheOffPreviewButUnchangedInputsDoNot() {
        val current = RecoveryPreviewInputs(selection(), HeartRateStatus.MISSING)
        val expired = RecoveryPreviewInputs(selection(at = now + 3_600_001), HeartRateStatus.MISSING)
        assertTrue(expired.needsRefresh(current, recoveryActive = false))
        assertFalse(current.needsRefresh(current.copy(), recoveryActive = false))
    }

    @Test fun aBpmChangeDuringBusyWorkRequiresAnOffPreviewRefreshEvenWhenBothSamplesAreLive() {
        val activity = selection(follow = false)
        val previous = RecoveryPreviewInputs.capture(activity, HeartRateReading.watch(119, 1))
        val receivedWhileBusy = RecoveryPreviewInputs.capture(activity, HeartRateReading.watch(130, 1))
        assertEquals(previous.heartRateStatus, receivedWhileBusy.heartRateStatus)
        assertTrue(receivedWhileBusy.needsRefresh(previous, recoveryActive = false))
        assertFalse(receivedWhileBusy.needsRefresh(previous, recoveryActive = true))
        fun recommend(bpm: Int) = RecoveryPlan.recommend(ComfortRequest(
            Workout(activity.kind, bpm, false), feeling = ThermalFeeling.HOT,
            preferredFan = 2, heartRate = HeartRateReading.watch(bpm, 1)))
        assertEquals(2, recommend(119).plan.fan)
        assertEquals(3, recommend(130).plan.fan)
    }
}
