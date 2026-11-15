package com.example.version1

/** Byte budgets apply to the whole app process, including native and accounted graphics memory. */
data class ProcessMemorySnapshot(
    val rssBytes: Long,
    val pssBytes: Long,
    val peakRssBytes: Long = 0
) {
    val maxAccountedBytes: Long get() = maxOf(rssBytes, pssBytes)
}

/** Produced by the offline asset validator; compressed file size alone is not a RAM estimate. */
data class VehicleMemoryProfile(
    val schemaVersion: Int,
    val validated: Boolean,
    val glbBytes: Long,
    val jsonBytes: Long,
    val decodedTextureBytes: Long,
    val mipmappedTextureBytes: Long,
    val geometryBytes: Long
)

object MemoryBudgetPolicy {
    private const val MIB = 1024L * 1024L
    // Pleos does not specify MB/MiB or PSS/RSS accounting. Use decimal MB and check both.
    const val HARD_LIMIT_BYTES = 400_000_000L
    const val WORKING_LIMIT_BYTES = 320L * MIB
    const val MAX_GLB_BYTES = 24L * MIB
    const val MAX_JSON_BYTES = 1L * MIB
    const val MAX_DECODED_TEXTURE_BYTES = 32L * MIB
    const val MAX_MIPMAPPED_TEXTURE_BYTES = 44L * MIB
    const val MAX_GEOMETRY_BYTES = 24L * MIB
    private const val IMPORT_RESERVE_BYTES = 24L * MIB

    fun rejectionReason(profile: VehicleMemoryProfile?, fileBytes: Long): String? {
        if (profile == null || !profile.validated || profile.schemaVersion != 1) return "unvalidated asset"
        if (fileBytes <= 0 || fileBytes > MAX_GLB_BYTES || profile.glbBytes != fileBytes) return "GLB size budget"
        if (profile.jsonBytes !in 1..MAX_JSON_BYTES || profile.jsonBytes > fileBytes - 20) return "JSON size budget"
        if (profile.decodedTextureBytes !in 0..MAX_DECODED_TEXTURE_BYTES) return "decoded texture budget"
        if (profile.mipmappedTextureBytes !in profile.decodedTextureBytes..MAX_MIPMAPPED_TEXTURE_BYTES) return "texture mip budget"
        if (profile.geometryBytes !in 0..MAX_GEOMETRY_BYTES) return "geometry budget"
        return null
    }

    /** Includes input copies, GPU textures, geometry staging and extra import/shader headroom. */
    fun estimatedImportBytes(profile: VehicleMemoryProfile, inputBufferCopies: Int = 2): Long {
        if (inputBufferCopies !in 1..2 || rejectionReason(profile, profile.glbBytes) != null) return Long.MAX_VALUE
        // Compatibility loading owns heap + direct inputs. Validated streaming owns only
        // a direct input. Both still reserve one native source copy and all old headroom.
        return profile.glbBytes * (inputBufferCopies + 1L) + profile.decodedTextureBytes + profile.mipmappedTextureBytes +
            profile.geometryBytes * 2L + IMPORT_RESERVE_BYTES
    }

    fun canLoad(snapshot: ProcessMemorySnapshot?, profile: VehicleMemoryProfile, residentInputBytes: Long = 0,
                inputBufferCopies: Int = 2): Boolean {
        if (snapshot == null || snapshot.rssBytes <= 0 || snapshot.pssBytes <= 0) return false
        val wholeImport = estimatedImportBytes(profile, inputBufferCopies)
        if (wholeImport == Long.MAX_VALUE || residentInputBytes !in 0..profile.glbBytes * inputBufferCopies) return false
        // The native-entry caller retains its fully touched direct input and, on the
        // compatibility path, the raw array. Count only future allocations at that point.
        // Never credit unallocated/freed buffers or arbitrary presumed allocator reuse.
        val importBytes = wholeImport - residentInputBytes
        return importBytes < WORKING_LIMIT_BYTES &&
            snapshot.maxAccountedBytes < WORKING_LIMIT_BYTES - importBytes
    }

    /** Source preparation allocates inputs and bounded JSON work, not decoded textures,
     * native geometry or GPU resources. Keep the same 24 MiB reserve in this phase.
     * This permission never authorizes native import: canLoad must re-read real memory
     * afterwards, retaining the complete texture/geometry/native-copy/reserve estimate.
     */
    fun estimatedStagingBytes(profile: VehicleMemoryProfile, inputBufferCopies: Int = 2): Long {
        if (estimatedImportBytes(profile, inputBufferCopies) == Long.MAX_VALUE) return Long.MAX_VALUE
        return profile.glbBytes * inputBufferCopies + IMPORT_RESERVE_BYTES
    }

    fun canStageInput(snapshot: ProcessMemorySnapshot?, profile: VehicleMemoryProfile,
                      inputBufferCopies: Int = 2): Boolean {
        if (snapshot == null || snapshot.rssBytes <= 0 || snapshot.pssBytes <= 0) return false
        val stagingBytes = estimatedStagingBytes(profile, inputBufferCopies)
        return stagingBytes < WORKING_LIMIT_BYTES && snapshot.maxAccountedBytes < WORKING_LIMIT_BYTES - stagingBytes
    }

    fun shouldRelease(snapshot: ProcessMemorySnapshot): Boolean =
        snapshot.maxAccountedBytes >= WORKING_LIMIT_BYTES
}
