package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class RecoveryJournalPresenceTest {
    @Test fun onlyExplicitEmptyArraysAreEmpty() {
        listOf("[]", " [ \r\n\t ] \n").forEach { assertFalse(RecoveryJournalPresence.mayContainEntries(it)) }
    }
    @Test fun sealedAndLegacyEntriesRemainPendingWithoutOpeningThem() {
        listOf("[{\"sealed\":\"not-decrypted\"}]", "[{\"key\":\"fan\"}]")
            .forEach { assertTrue(RecoveryJournalPresence.mayContainEntries(it)) }
    }
    @Test fun unknownAndMalformedValuesFailClosed() {
        listOf(null, "", "null", "{}", "[", "[] trailing", "[0]", "[[]]", "[ ]\u0000")
            .forEach { assertTrue(RecoveryJournalPresence.mayContainEntries(it)) }
    }
    @Test fun oversizedWhitespaceCannotAdvertiseAnEmptyJournal() {
        assertTrue(RecoveryJournalPresence.mayContainEntries("[" + " ".repeat(262_144) + "]"))
    }
    @Test fun separateControllersObserveAuthoritativePresenceWithoutDecryptingOrCaching() {
        var raw = "[]"
        var reads = 0
        val store = object : RecoveryJournalStore<Int> {
            override fun read(): List<Int> { reads++; error("Presence must not decrypt entries") }
            override fun write(entries: List<Int>) { error("No mutation requested") }
            override fun hasPending() = RecoveryJournalPresence.mayContainEntries(raw)
        }
        val first = SerializedRecoveryJournal(store)
        val second = SerializedRecoveryJournal(store)
        assertFalse(first.hasPending)
        raw = "[{\"sealed\":\"authoritative-other-controller-write\"}]"
        assertTrue(first.hasPending); assertTrue(second.hasPending)
        raw = "[]"
        assertFalse(second.hasPending); assertFalse(first.hasPending)
        assertEquals(0, reads)
    }
}
