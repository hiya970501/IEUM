package com.example.version1

/** An unconfirmed manual file never replaces the recent record used by automatic recovery. */
data class RecoveryWorkoutInbox(
    val receivedWorkout: RecentWorkout? = null,
    val pendingImport: RecentWorkout? = null
) {
    fun receive(record: RecentWorkout): RecoveryWorkoutInbox =
        if (record.source == WorkoutRecordSource.MANUAL_IMPORT) copy(pendingImport = record)
        else copy(receivedWorkout = record)
}

/** The saved fallback is independent of a temporary automatic or imported activity. */
data class RecoveryActivitySelection(
    val kind: ActivityKind,
    val record: RecentWorkout? = null,
    val demo: Boolean = false
) {
    companion object {
        fun resolve(
            manualKind: ActivityKind,
            followRecentWorkout: Boolean,
            receivedWorkout: RecentWorkout?,
            selectedImport: RecentWorkout?,
            demoKind: ActivityKind?,
            nowEpochMillis: Long
        ): RecoveryActivitySelection {
            if (demoKind != null) return RecoveryActivitySelection(demoKind, demo = true)
            val imported = selectedImport?.takeIf {
                it.source == WorkoutRecordSource.MANUAL_IMPORT && it.kind != null
            }
            if (imported != null) return RecoveryActivitySelection(imported.kind!!, imported)
            val automatic = receivedWorkout?.takeIf { followRecentWorkout && it.canAutoSelect(nowEpochMillis) }
            if (automatic != null) return RecoveryActivitySelection(automatic.kind!!, automatic)
            return RecoveryActivitySelection(manualKind)
        }
    }
}

/** Candidate ages refer to the original measurement, never to a later relay arrival. */
data class DirectHeartRateSample(val bpm: Int, val measuredAtElapsedMillis: Long)

object RecoveryHeartRateSelection {
    fun newestDirectSample(current: DirectHeartRateSample?, incoming: DirectHeartRateSample): DirectHeartRateSample =
        if (current == null || incoming.measuredAtElapsedMillis > current.measuredAtElapsedMillis) incoming else current

    fun newestWatchReading(direct: HeartRateReading?, workout: HeartRateReading?): HeartRateReading {
        fun usable(reading: HeartRateReading?): Boolean = reading != null &&
            reading.source == HeartRateSource.WATCH && reading.bpm != null && reading.bpm in ComfortPolicy.INPUT_BPM_RANGE &&
            reading.ageSeconds != null && reading.ageSeconds >= 0
        val directUsable = usable(direct)
        val workoutUsable = usable(workout)
        if (directUsable && workoutUsable) {
            val directReading = checkNotNull(direct)
            val workoutReading = checkNotNull(workout)
            return if (checkNotNull(directReading.ageSeconds) <= checkNotNull(workoutReading.ageSeconds))
                directReading else workoutReading
        }
        if (directUsable) return direct!!
        if (workoutUsable) return workout!!
        // Preserve a useful invalid/missing status without promoting manual or demo input.
        return direct?.takeIf { it.source == HeartRateSource.WATCH }
            ?: workout?.takeIf { it.source == HeartRateSource.WATCH }
            ?: HeartRateReading.missing()
    }
}

data class PreferenceEditContext(val driverProfileId: String, val kind: ActivityKind)

data class PreferenceEditTicket internal constructor(
    internal val context: PreferenceEditContext,
    internal val revision: Long
)

/** Returning to the same driver/activity must not resurrect an older editor draft. */
class PreferenceEditorTracker {
    private var context: PreferenceEditContext? = null
    private var revision = 0L

    fun moveTo(next: PreferenceEditContext): Boolean {
        if (context == next) return false
        context = next
        revision++
        return true
    }

    fun capture(): PreferenceEditTicket = PreferenceEditTicket(checkNotNull(context), revision)

    fun invalidate() { revision++ }

    fun canCommit(ticket: PreferenceEditTicket): Boolean =
        ticket.context == context && ticket.revision == revision
}

/** An OFF preview is recalculated when an input becomes stale or an activity expires. */
data class RecoveryPreviewInputs(
    val activity: RecoveryActivitySelection,
    val heartRateStatus: HeartRateStatus,
    val heartRateBpm: Int? = null,
    val heartRateSource: HeartRateSource? = null
) {
    fun needsRefresh(previous: RecoveryPreviewInputs, recoveryActive: Boolean): Boolean =
        !recoveryActive && this != previous

    companion object {
        fun capture(activity: RecoveryActivitySelection, reading: HeartRateReading) =
            RecoveryPreviewInputs(activity, reading.status, reading.bpmForDisplay, reading.source)
    }
}
