package com.example.version1

/** Presence is safe to answer without decrypting vehicle settings on the UI thread.
 * Only an explicit empty JSON array is empty. Sealed, legacy, malformed, missing
 * or oversized values all remain pending; transactions still fully decode them.
 * The caller reads the authoritative shared store each time, never an instance cache.
 */
internal object RecoveryJournalPresence {
    private val emptyArray = Regex("[ \\t\\r\\n]*\\[[ \\t\\r\\n]*][ \\t\\r\\n]*")
    fun mayContainEntries(raw: String?): Boolean =
        raw == null || raw.length > 262_144 || !emptyArray.matches(raw)
}
