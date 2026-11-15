package com.example.version1

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EmulatorRecoveryTest {
    @Test fun cachedVehicleValuesDoNotMeanTheControlTransportIsOnline() {
        fun state(gear: Boolean, active: Boolean, pub: Boolean, sub: Boolean) = JSONObject()
            .put("gear", JSONObject().put("bridge_ready", gear))
            .put("hvac", JSONObject().put("fan_speed", 3).put("emulator_active", active)
                .put("publisher_ready", JSONObject().put("all", pub))
                .put("subscription_ready", JSONObject().put("all", sub)))
        assertFalse(EmulatorConsoleSession.isControlReady(state(false, false, false, false)))
        assertFalse(EmulatorConsoleSession.isControlReady(state(true, true, true, false)))
        assertFalse(EmulatorConsoleSession.isControlReady(JSONObject()))
        assertTrue(EmulatorConsoleSession.isControlReady(state(true, true, true, true)))
    }

    @Test fun offlineAcknowledgedApplyNeedsANewVerifiedRestoreInsteadOfErasingOrDeadlocking() = runBlocking {
        data class Entry(val request: String, val phase: RecoveryJournalPhase)
        var durable = listOf(Entry("old-apply", RecoveryJournalPhase.APPLY_ACKNOWLEDGED))
        val store = object : RecoveryJournalStore<Entry> {
            override fun read() = durable
            override fun write(entries: List<Entry>) { durable = entries.toList() }
        }
        val journal = SerializedRecoveryJournal(store)
        journal.transaction {
            val action = decideJournalRestore(entries.single().phase, 3, 2, Result.success(3))
            assertEquals(RestoreDecision.RESTORE_ORIGINAL, action)
            assertEquals(1, durable.size) // Observing the original value did NOT erase evidence.
            replace(0, Entry("new-restore", RecoveryJournalPhase.RESTORE_UNCONFIRMED))
            assertEquals(RestoreDecision.WAIT_FOR_REQUEST,
                decideJournalRestore(entries.single().phase, 3, 2, Result.success(3)))
            assertNull(acknowledgedRecoveryPhase("new-restore", entries.single().phase,
                "old-apply", RecoveryJournalPhase.APPLY_UNCONFIRMED))
            replace(0, entries.single().copy(phase = RecoveryJournalPhase.RESTORE_ACKNOWLEDGED))
            assertEquals(RestoreDecision.ALREADY_RESTORED,
                decideJournalRestore(entries.single().phase, 3, 2, Result.success(3)))
            remove(entries.single())
        }
        assertFalse(journal.hasPending)
        assertEquals(RestoreDecision.WAIT_FOR_REQUEST,
            decideJournalRestore(RecoveryJournalPhase.APPLY_ACKNOWLEDGED, 3, 2, Result.success(5)))
    }
}
