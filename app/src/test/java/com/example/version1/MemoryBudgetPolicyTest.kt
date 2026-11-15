package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class MemoryBudgetPolicyTest {
    private val mib = 1024L * 1024L
    private val profile = VehicleMemoryProfile(1, true, 4 * mib, 250_000, 8 * mib, 11 * mib, 3 * mib)

    @Test fun sourcePreparationNeverAuthorizesNativeImportAtTheSameResidentPressure() {
        val pressure = ProcessMemorySnapshot(300_000_000, 250_000_000)
        assertTrue(MemoryBudgetPolicy.canStageInput(pressure, profile, inputBufferCopies = 1))
        assertFalse(MemoryBudgetPolicy.canLoad(pressure, profile, inputBufferCopies = 1))
        assertFalse(MemoryBudgetPolicy.canLoad(pressure, profile, profile.glbBytes, inputBufferCopies = 1))
        // Only a new actual, lower whole-process reading can authorize native allocations.
        assertTrue(MemoryBudgetPolicy.canLoad(ProcessMemorySnapshot(240_000_000, 220_000_000), profile,
            profile.glbBytes, inputBufferCopies = 1))
    }

    @Test fun sourcePreparationKeepsFullReserveStrictBoundaryAndValidatedInputs() {
        val staged = MemoryBudgetPolicy.estimatedStagingBytes(profile, 1)
        assertEquals(profile.glbBytes + 24 * mib, staged)
        val boundary = MemoryBudgetPolicy.WORKING_LIMIT_BYTES - staged
        assertFalse(MemoryBudgetPolicy.canStageInput(ProcessMemorySnapshot(boundary, 1), profile, 1))
        assertFalse(MemoryBudgetPolicy.canStageInput(ProcessMemorySnapshot(1, boundary), profile, 1))
        assertTrue(MemoryBudgetPolicy.canStageInput(ProcessMemorySnapshot(boundary - 1, 1), profile, 1))
        assertFalse(MemoryBudgetPolicy.canStageInput(null, profile, 1))
        assertFalse(MemoryBudgetPolicy.canStageInput(ProcessMemorySnapshot(1, 1), profile.copy(validated = false), 1))
        listOf(-1, 0, 3).forEach { assertFalse(MemoryBudgetPolicy.canStageInput(ProcessMemorySnapshot(1, 1), profile, it)) }
    }

    @Test fun streamedInputRemovesOnlyTheEliminatedCopyAndCannotClaimTwoResidentInputs() {
        val old = MemoryBudgetPolicy.estimatedImportBytes(profile)
        assertEquals(old - profile.glbBytes, MemoryBudgetPolicy.estimatedImportBytes(profile, inputBufferCopies = 1))
        val boundary = MemoryBudgetPolicy.WORKING_LIMIT_BYTES - MemoryBudgetPolicy.estimatedImportBytes(profile, 1)
        assertFalse(MemoryBudgetPolicy.canLoad(ProcessMemorySnapshot(boundary, 1), profile, inputBufferCopies = 1))
        assertTrue(MemoryBudgetPolicy.canLoad(ProcessMemorySnapshot(boundary - 1, 1), profile, inputBufferCopies = 1))
        val staged = ProcessMemorySnapshot(boundary - 1 + profile.glbBytes, 1)
        assertTrue(MemoryBudgetPolicy.canLoad(staged, profile, profile.glbBytes, inputBufferCopies = 1))
        assertFalse(MemoryBudgetPolicy.canLoad(staged, profile, profile.glbBytes * 2, inputBufferCopies = 1))
        listOf(0, 3, -1).forEach { assertFalse(MemoryBudgetPolicy.canLoad(staged, profile, inputBufferCopies = it)) }
    }

    @Test fun requiresValidatedAssetsWithMatchingActualFileLength() {
        assertNotNull(MemoryBudgetPolicy.rejectionReason(null, profile.glbBytes))
        assertNotNull(MemoryBudgetPolicy.rejectionReason(profile.copy(validated = false), profile.glbBytes))
        assertNotNull(MemoryBudgetPolicy.rejectionReason(profile, profile.glbBytes + 1))
        assertNull(MemoryBudgetPolicy.rejectionReason(profile, profile.glbBytes))
    }

    @Test fun rejectsSmallCompressedFilesThatExpandBeyondTextureOrGeometryBudget() {
        assertNotNull(MemoryBudgetPolicy.rejectionReason(profile.copy(decodedTextureBytes = 384 * mib), profile.glbBytes))
        assertNotNull(MemoryBudgetPolicy.rejectionReason(profile.copy(geometryBytes = 25 * mib), profile.glbBytes))
        assertNotNull(MemoryBudgetPolicy.rejectionReason(profile.copy(mipmappedTextureBytes = 1), profile.glbBytes))
    }

    @Test fun admissionUsesLargerProcessMeasureAndReservesImportHeadroom() {
        assertTrue(MemoryBudgetPolicy.canLoad(ProcessMemorySnapshot(150 * mib, 130 * mib), profile))
        assertFalse(MemoryBudgetPolicy.canLoad(ProcessMemorySnapshot(290 * mib, 150 * mib), profile))
        assertFalse(MemoryBudgetPolicy.canLoad(ProcessMemorySnapshot(150 * mib, 290 * mib), profile))
        assertFalse(MemoryBudgetPolicy.canLoad(null, profile))
    }

    @Test fun boundaryIsStrictAndResidentHighWaterDoesNotBlockAfterResourcesAreReleased() {
        val boundary = MemoryBudgetPolicy.WORKING_LIMIT_BYTES - MemoryBudgetPolicy.estimatedImportBytes(profile)
        assertFalse(MemoryBudgetPolicy.canLoad(ProcessMemorySnapshot(boundary, 1), profile))
        assertTrue(MemoryBudgetPolicy.canLoad(ProcessMemorySnapshot(boundary - 1, 1, 399_000_000), profile))
        assertTrue(MemoryBudgetPolicy.shouldRelease(ProcessMemorySnapshot(MemoryBudgetPolicy.WORKING_LIMIT_BYTES, 1)))
        assertEquals(400_000_000L, MemoryBudgetPolicy.HARD_LIMIT_BYTES)
        assertTrue(MemoryBudgetPolicy.WORKING_LIMIT_BYTES < MemoryBudgetPolicy.HARD_LIMIT_BYTES)
    }

    @Test fun nativeAdmissionDoesNotCountStronglyRetainedInputBuffersTwice() {
        val copies = profile.glbBytes * 2
        val beforeStaging = ProcessMemorySnapshot(250 * mib, 150 * mib)
        val afterStaging = ProcessMemorySnapshot(beforeStaging.rssBytes + copies, beforeStaging.pssBytes + copies)
        assertTrue(MemoryBudgetPolicy.canLoad(beforeStaging, profile))
        assertTrue(MemoryBudgetPolicy.canLoad(afterStaging, profile, copies))
        assertEquals(MemoryBudgetPolicy.canLoad(beforeStaging, profile),
            MemoryBudgetPolicy.canLoad(afterStaging, profile, copies))
        assertFalse(MemoryBudgetPolicy.canLoad(afterStaging, profile, copies + 1))
        assertFalse(MemoryBudgetPolicy.canLoad(afterStaging, profile, -1))
    }
}
