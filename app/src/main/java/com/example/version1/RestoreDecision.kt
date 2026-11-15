package com.example.version1

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A failed read never authorizes a write or deletion of the pending restore journal. */
enum class RestoreDecision {
    ALREADY_RESTORED,
    RESTORE_ORIGINAL,
    PRESERVE_EXTERNAL_CHANGE,
    RETRY,
    /** A dispatched request may still complete later; observing a value alone is insufficient. */
    WAIT_FOR_REQUEST
}

/** Persisted per entry; absence in the old array format is explicitly unconfirmed. */
internal enum class RecoveryJournalPhase {
    LEGACY_UNCONFIRMED,
    APPLY_UNCONFIRMED,
    APPLY_ACKNOWLEDGED,
    APPLIED_VERIFIED,
    RESTORE_UNCONFIRMED,
    RESTORE_ACKNOWLEDGED
}

internal fun recoveryJournalPhaseFromStoredName(name: String?): RecoveryJournalPhase =
    name?.let { RecoveryJournalPhase.valueOf(it) } ?: RecoveryJournalPhase.LEGACY_UNCONFIRMED

/** A callback from an older apply must not acknowledge a newer restore request. */
internal fun acknowledgedRecoveryPhase(
    currentRequestId: String,
    currentPhase: RecoveryJournalPhase,
    callbackRequestId: String,
    callbackPhase: RecoveryJournalPhase
): RecoveryJournalPhase? {
    if (currentRequestId != callbackRequestId || currentPhase != callbackPhase) return null
    return when (currentPhase) {
        RecoveryJournalPhase.APPLY_UNCONFIRMED -> RecoveryJournalPhase.APPLY_ACKNOWLEDGED
        RecoveryJournalPhase.RESTORE_UNCONFIRMED -> RecoveryJournalPhase.RESTORE_ACKNOWLEDGED
        else -> null
    }
}

/**
 * A setter timeout does not cancel its SDK request. In particular, a read of the
 * original value before a late setter completes must never delete its journal.
 * An acknowledgement still needs a matching readback before it grants ownership.
 */
internal fun <T> decideJournalRestore(
    phase: RecoveryJournalPhase,
    before: T,
    after: T,
    current: Result<T>,
    equivalent: (T, T) -> Boolean = { a, b -> a == b }
): RestoreDecision = when (phase) {
    RecoveryJournalPhase.LEGACY_UNCONFIRMED,
    RecoveryJournalPhase.APPLY_UNCONFIRMED,
    RecoveryJournalPhase.RESTORE_UNCONFIRMED -> RestoreDecision.WAIT_FOR_REQUEST
    RecoveryJournalPhase.APPLIED_VERIFIED -> decideRestore(before, after, current, equivalent)
    RecoveryJournalPhase.APPLY_ACKNOWLEDGED -> current.fold(
        // A completed SDK request whose target was never observed must not deadlock forever.
        // Reassert before with a NEW durable restore request, acknowledgement and readback;
        // a read of before here never deletes the journal by itself.
        onSuccess = { if (equivalent(it, after) || equivalent(it, before)) RestoreDecision.RESTORE_ORIGINAL
            else RestoreDecision.WAIT_FOR_REQUEST },
        onFailure = { RestoreDecision.RETRY }
    )
    RecoveryJournalPhase.RESTORE_ACKNOWLEDGED -> current.fold(
        onSuccess = { if (equivalent(it, before)) RestoreDecision.ALREADY_RESTORED
            else RestoreDecision.WAIT_FOR_REQUEST },
        onFailure = { RestoreDecision.RETRY }
    )
}

/** The production preferences store and JVM test stores use the same coordinator. */
internal interface RecoveryJournalStore<T> {
    fun read(): List<T>
    fun write(entries: List<T>)
    fun hasPending(): Boolean = read().isNotEmpty()
}

/** One gate for all controller instances in this process, including late SDK callbacks. */
internal object RecoveryTransactionGate {
    private val mutex = Mutex()
    private val reconciliationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun <T> serial(block: suspend () -> T): T = mutex.withLock { block() }

    fun reconcileLater(block: suspend () -> Unit) {
        reconciliationScope.launch {
            // A failed durable acknowledgement remains unconfirmed. Never turn it into success.
            runCatching { block() }
        }
    }
}

internal class RecoveryJournalTransaction<T>(
    private val store: RecoveryJournalStore<T>,
    val entries: MutableList<T>
) {
    fun save() = store.write(entries.toList())

    fun replace(index: Int, value: T) {
        val previous = entries[index]
        entries[index] = value
        try { save() } catch (error: Exception) { entries[index] = previous; throw error }
    }

    fun remove(value: T) {
        val index = entries.indexOf(value)
        if (index < 0) return
        entries.removeAt(index)
        try { save() } catch (error: Exception) { entries.add(index, value); throw error }
    }
}

/**
 * Never holds an instance-local journal cache. Every transaction reloads after
 * acquiring the shared gate, so a second Activity cannot overwrite a partial
 * snapshot from the first. Reads used by UI are authoritative store snapshots.
 */
internal class SerializedRecoveryJournal<T>(private val store: RecoveryJournalStore<T>) {
    val hasPending: Boolean get() = store.hasPending()
    fun snapshot(): List<T> = store.read()

    suspend fun <R> transaction(block: suspend RecoveryJournalTransaction<T>.() -> R): R =
        RecoveryTransactionGate.serial {
            RecoveryJournalTransaction(store, store.read().toMutableList()).block()
        }
}

/**
 * Restores only values that still match this app's applied value. A different current value
 * belongs to the user/vehicle and takes precedence over this app's earlier snapshot.
 */
fun <T> decideRestore(
    before: T,
    after: T,
    current: Result<T>,
    equivalent: (T, T) -> Boolean = { a, b -> a == b }
): RestoreDecision = current.fold(
    onSuccess = { actual ->
        when {
            equivalent(actual, before) -> RestoreDecision.ALREADY_RESTORED
            equivalent(actual, after) -> RestoreDecision.RESTORE_ORIGINAL
            else -> RestoreDecision.PRESERVE_EXTERNAL_CHANGE
        }
    },
    onFailure = { RestoreDecision.RETRY }
)

/** Match the controller's 0.15 numeric readback tolerance without decimal boundary rounding. */
internal fun vehicleValuesEquivalent(a: String, b: String): Boolean {
    if (a == b) return true
    val left = a.toBigDecimalOrNull() ?: return false
    val right = b.toBigDecimalOrNull() ?: return false
    return (left - right).abs() < "0.15".toBigDecimal()
}
