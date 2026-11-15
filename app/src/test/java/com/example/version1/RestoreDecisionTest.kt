package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class RestoreDecisionTest {
    @Test fun originalValueNeedsNoVehicleWrite() {
        assertEquals(RestoreDecision.ALREADY_RESTORED,
            decideRestore("24", "23", Result.success("24"), ::vehicleValuesEquivalent))
    }

    @Test fun valueStillOwnedByAppCanRestoreOriginal() {
        assertEquals(RestoreDecision.RESTORE_ORIGINAL,
            decideRestore("24", "23", Result.success("23"), ::vehicleValuesEquivalent))
    }

    @Test fun externalChangeTakesPrecedenceOverSnapshot() {
        assertEquals(RestoreDecision.PRESERVE_EXTERNAL_CHANGE,
            decideRestore("24", "23", Result.success("26"), ::vehicleValuesEquivalent))
    }

    @Test fun numericReadbackToleranceAppliesToBeforeAndAfter() {
        assertEquals(RestoreDecision.ALREADY_RESTORED,
            decideRestore("24", "23", Result.success("24.14"), ::vehicleValuesEquivalent))
        assertEquals(RestoreDecision.RESTORE_ORIGINAL,
            decideRestore("24", "23", Result.success("23.14"), ::vehicleValuesEquivalent))
        assertEquals(RestoreDecision.PRESERVE_EXTERNAL_CHANGE,
            decideRestore("24", "23", Result.success("24.15"), ::vehicleValuesEquivalent))
        assertEquals(RestoreDecision.PRESERVE_EXTERNAL_CHANGE,
            decideRestore("24", "23", Result.success("22.85"), ::vehicleValuesEquivalent))
    }

    @Test fun aFailedReadNeverAuthorizesWriteOrJournalDeletion() {
        val failures = listOf(Exception("read timed out"), IllegalStateException("no state"))
        for (failure in failures) {
            assertEquals(RestoreDecision.RETRY,
                decideRestore("24", "23", Result.failure(failure), ::vehicleValuesEquivalent))
        }
    }

    @Test fun unchangedValuesPreferAlreadyRestoredEvenWhenBothMatch() {
        assertEquals(RestoreDecision.ALREADY_RESTORED,
            decideRestore("24", "24.0", Result.success("24.00"), ::vehicleValuesEquivalent))
    }

    @Test fun booleanAndIntegerPropertiesUseTheSameOwnershipRule() {
        assertEquals(RestoreDecision.ALREADY_RESTORED,
            decideRestore(false, true, Result.success(false)))
        assertEquals(RestoreDecision.RESTORE_ORIGINAL,
            decideRestore(1, 3, Result.success(3)))
        assertEquals(RestoreDecision.PRESERVE_EXTERNAL_CHANGE,
            decideRestore(1, 3, Result.success(2)))
        assertFalse(vehicleValuesEquivalent("true", "false"))
        assertTrue(vehicleValuesEquivalent("1", "1.0"))
    }
}
