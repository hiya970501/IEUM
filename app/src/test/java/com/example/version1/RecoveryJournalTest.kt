package com.example.version1

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class RecoveryJournalTest {
    private data class Entry(val id: String, val phase: RecoveryJournalPhase)
    private class Store(initial: List<Entry> = emptyList()) : RecoveryJournalStore<Entry> {
        private var durable = initial.toList()
        var failNextWrite = false
        override fun read() = durable.toList()
        override fun write(entries: List<Entry>) {
            if (failNextWrite) { failNextWrite = false; error("durable write failed") }
            durable = entries.toList()
        }
    }

    private fun decision(phase: RecoveryJournalPhase, current: String) = decideJournalRestore(
        phase, "24", "23", Result.success(current), ::vehicleValuesEquivalent)

    @Test fun delayedSetterCannotEraseJournalWhileVehicleStillReportsOriginalValue() = runBlocking {
        val store = Store()
        val journal = SerializedRecoveryJournal(store)
        var vehicleValue = "24"
        // Dispatch is durable before a fake SDK request that has not completed.
        journal.transaction {
            entries += Entry("request-1", RecoveryJournalPhase.APPLY_UNCONFIRMED)
            save()
        }
        // Our caller times out first. The old code deleted this entry at this point.
        journal.transaction {
            val result = decision(entries.single().phase, vehicleValue)
            assertEquals(RestoreDecision.WAIT_FOR_REQUEST, result)
        }
        assertTrue(journal.hasPending)
        // A late successful SDK callback and matching readback are now available.
        vehicleValue = "23"
        journal.transaction {
            replace(0, entries.single().copy(phase = RecoveryJournalPhase.APPLY_ACKNOWLEDGED))
        }
        journal.transaction {
            assertEquals(RestoreDecision.RESTORE_ORIGINAL, decision(entries.single().phase, vehicleValue))
            replace(0, entries.single().copy(phase = RecoveryJournalPhase.RESTORE_UNCONFIRMED))
        }
        vehicleValue = "24"
        journal.transaction {
            // Even a matching value cannot prove an unacknowledged request has completed.
            assertEquals(RestoreDecision.WAIT_FOR_REQUEST, decision(entries.single().phase, vehicleValue))
            replace(0, entries.single().copy(phase = RecoveryJournalPhase.RESTORE_ACKNOWLEDGED))
            assertEquals(RestoreDecision.ALREADY_RESTORED, decision(entries.single().phase, vehicleValue))
            remove(entries.single())
        }
        assertFalse(journal.hasPending)
    }

    @Test fun unconfirmedApplyAndRestoreNeverDeleteJournalFromAnyReadback() {
        val phases = listOf(RecoveryJournalPhase.APPLY_UNCONFIRMED,
            RecoveryJournalPhase.RESTORE_UNCONFIRMED, RecoveryJournalPhase.LEGACY_UNCONFIRMED)
        phases.forEach { phase ->
            listOf("24", "23", "26").forEach { current ->
                assertEquals("$phase / $current", RestoreDecision.WAIT_FOR_REQUEST, decision(phase, current))
            }
        }
    }

    @Test fun acknowledgementRequiresItsOwnMatchingReadback() {
        assertEquals(RestoreDecision.RESTORE_ORIGINAL, decision(RecoveryJournalPhase.APPLY_ACKNOWLEDGED, "24"))
        assertEquals(RestoreDecision.WAIT_FOR_REQUEST, decision(RecoveryJournalPhase.APPLY_ACKNOWLEDGED, "26"))
        assertEquals(RestoreDecision.RESTORE_ORIGINAL, decision(RecoveryJournalPhase.APPLY_ACKNOWLEDGED, "23"))
        assertEquals(RestoreDecision.WAIT_FOR_REQUEST, decision(RecoveryJournalPhase.RESTORE_ACKNOWLEDGED, "23"))
        assertEquals(RestoreDecision.WAIT_FOR_REQUEST, decision(RecoveryJournalPhase.RESTORE_ACKNOWLEDGED, "26"))
        assertEquals(RestoreDecision.ALREADY_RESTORED, decision(RecoveryJournalPhase.RESTORE_ACKNOWLEDGED, "24"))
    }

    @Test fun confirmedWritesStillPreserveExternalChangesAndCleanNormalRestoration() {
        assertEquals(RestoreDecision.ALREADY_RESTORED, decision(RecoveryJournalPhase.APPLIED_VERIFIED, "24"))
        assertEquals(RestoreDecision.RESTORE_ORIGINAL, decision(RecoveryJournalPhase.APPLIED_VERIFIED, "23"))
        assertEquals(RestoreDecision.PRESERVE_EXTERNAL_CHANGE, decision(RecoveryJournalPhase.APPLIED_VERIFIED, "26"))
    }

    @Test fun failedReadsNeverAuthorizeAcknowledgedOrVerifiedWrites() {
        listOf(RecoveryJournalPhase.APPLY_ACKNOWLEDGED, RecoveryJournalPhase.RESTORE_ACKNOWLEDGED,
            RecoveryJournalPhase.APPLIED_VERIFIED).forEach { phase ->
            assertEquals(RestoreDecision.RETRY, decideJournalRestore(phase, "24", "23",
                Result.failure(Exception("read failed")), ::vehicleValuesEquivalent))
        }
    }

    @Test fun secondControllerWaitsAndReloadsEveryEntryWrittenByFirstController() = runBlocking {
        val store = Store()
        val firstController = SerializedRecoveryJournal(store)
        val secondController = SerializedRecoveryJournal(store)
        val firstHasDispatched = CompletableDeferred<Unit>()
        val continueFirst = CompletableDeferred<Unit>()
        val first = launch {
            firstController.transaction {
                entries += Entry("fan", RecoveryJournalPhase.APPLIED_VERIFIED)
                save()
                firstHasDispatched.complete(Unit)
                continueFirst.await()
                entries += Entry("temperature", RecoveryJournalPhase.APPLIED_VERIFIED)
                save()
            }
        }
        firstHasDispatched.await()
        assertTrue(secondController.hasPending)
        val second = async {
            secondController.transaction {
                val ids = entries.map { it.id }
                entries.toList().forEach(::remove)
                ids
            }
        }
        yield()
        assertFalse("Another Activity must not enter during the first transaction", second.isCompleted)
        continueFirst.complete(Unit)
        first.join()
        assertEquals(listOf("fan", "temperature"), second.await())
        // The old instance must immediately observe the second one's durable cleanup.
        assertFalse(firstController.hasPending)
    }

    @Test fun differentCoordinatorInstancesCannotBypassProcessWideGate() = runBlocking {
        val first = SerializedRecoveryJournal(Store())
        val second = SerializedRecoveryJournal(Store())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = launch { first.transaction { entered.complete(Unit); release.await() } }
        entered.await()
        val blocked = async { second.transaction { "entered" } }
        yield()
        assertFalse(blocked.isCompleted)
        release.complete(Unit)
        held.join()
        assertEquals("entered", blocked.await())
    }

    @Test fun failedJournalRemovalKeepsDurableAndTransactionRecords() = runBlocking {
        val original = Entry("temperature", RecoveryJournalPhase.APPLIED_VERIFIED)
        val store = Store(listOf(original))
        val journal = SerializedRecoveryJournal(store)
        journal.transaction {
            store.failNextWrite = true
            assertTrue(runCatching { remove(original) }.isFailure)
            assertEquals(listOf(original), entries)
        }
        assertEquals(listOf(original), journal.snapshot())
    }

    @Test fun failedAcknowledgementWriteKeepsRequestUnconfirmed() = runBlocking {
        val original = Entry("temperature", RecoveryJournalPhase.APPLY_UNCONFIRMED)
        val store = Store(listOf(original))
        val journal = SerializedRecoveryJournal(store)
        journal.transaction {
            store.failNextWrite = true
            assertTrue(runCatching { replace(0, original.copy(phase = RecoveryJournalPhase.APPLY_ACKNOWLEDGED)) }.isFailure)
            assertEquals(original, entries.single())
        }
        assertEquals(RestoreDecision.WAIT_FOR_REQUEST, decision(journal.snapshot().single().phase, "24"))
    }

    @Test fun lateApplyCallbackCannotAcknowledgeNewerRestoreOrDuplicateAcknowledgement() {
        assertNull(acknowledgedRecoveryPhase("restore-2", RecoveryJournalPhase.RESTORE_UNCONFIRMED,
            "apply-1", RecoveryJournalPhase.APPLY_UNCONFIRMED))
        assertNull(acknowledgedRecoveryPhase("apply-1", RecoveryJournalPhase.APPLY_ACKNOWLEDGED,
            "apply-1", RecoveryJournalPhase.APPLY_UNCONFIRMED))
        assertEquals(RecoveryJournalPhase.APPLY_ACKNOWLEDGED,
            acknowledgedRecoveryPhase("apply-1", RecoveryJournalPhase.APPLY_UNCONFIRMED,
                "apply-1", RecoveryJournalPhase.APPLY_UNCONFIRMED))
        assertEquals(RecoveryJournalPhase.RESTORE_ACKNOWLEDGED,
            acknowledgedRecoveryPhase("restore-2", RecoveryJournalPhase.RESTORE_UNCONFIRMED,
                "restore-2", RecoveryJournalPhase.RESTORE_UNCONFIRMED))
    }

    @Test fun legacyAndUnknownFuturePhaseNamesFailConservatively() {
        assertEquals(RecoveryJournalPhase.LEGACY_UNCONFIRMED, recoveryJournalPhaseFromStoredName(null))
        assertEquals(RestoreDecision.WAIT_FOR_REQUEST,
            decision(recoveryJournalPhaseFromStoredName(null), "24"))
        assertTrue(runCatching { recoveryJournalPhaseFromStoredName("FUTURE_COMPLETED") }.isFailure)
        RecoveryJournalPhase.entries.forEach { assertEquals(it, recoveryJournalPhaseFromStoredName(it.name)) }
    }
}
