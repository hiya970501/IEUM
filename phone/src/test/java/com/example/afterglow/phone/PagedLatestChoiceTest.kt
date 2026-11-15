package com.example.afterglow.phone

import org.junit.Assert.*
import org.junit.Test

class PagedLatestChoiceTest {
    @Test fun nullAndEmptyTokensBothFinishWithoutAdditionalRead() {
        listOf(null, "").forEach { token ->
            val scan = PagedLatestChoice<Long> { it }
            scan.acceptPage(sequenceOf(10), token)
            assertTrue(scan.complete); assertFalse(scan.canReadNextPage); assertEquals(10L, scan.latest)
        }
    }
    @Test fun latestIsUnknownUntilEveryPageHasBeenRead() {
        val scan = PagedLatestChoice<Long> { it }
        scan.acceptPage(sequenceOf(10, 20), "page-2")
        assertNull(scan.latest)
        scan.acceptPage(sequenceOf(5, 40, 30), null)
        assertEquals(40L, scan.latest)
    }
    @Test fun scanLimitNeverPromotesPartialResultToLatest() {
        val scan = PagedLatestChoice<Long>(maxPages = 2) { it }
        scan.acceptPage(sequenceOf(10), "page-2")
        scan.acceptPage(sequenceOf(20), "page-3")
        assertFalse(scan.complete); assertFalse(scan.canReadNextPage); assertNull(scan.latest)
    }
    @Test fun terminalPageAtExactLimitIsAccepted() {
        val scan = PagedLatestChoice<Long>(maxPages = 2) { it }
        scan.acceptPage(sequenceOf(20), "page-2")
        scan.acceptPage(sequenceOf(10), "")
        assertTrue(scan.complete); assertEquals(20L, scan.latest)
    }
    @Test fun repeatedCursorStopsWithoutCallingPartialResultLatest() {
        val scan = PagedLatestChoice<Long> { it }
        scan.acceptPage(sequenceOf(10), "same-cursor")
        scan.acceptPage(sequenceOf(20), "same-cursor")
        assertTrue(scan.repeatedToken); assertFalse(scan.canReadNextPage)
        assertFalse(scan.complete); assertNull(scan.latest)
    }
    @Test fun filteredRecordsDoNotDisplaceEligibleCandidate() {
        val scan = PagedLatestChoice<Long> { it.takeIf { value -> value in 1..100 } }
        scan.acceptPage(sequenceOf(25, 200, -10), "page-2")
        scan.acceptPage(sequenceOf(40, 1000), null)
        assertEquals(40L, scan.latest)
    }
    @Test fun emptyOrEntirelyFilteredScanHasNoCandidate() {
        val empty = PagedLatestChoice<Long> { it }
        empty.acceptPage(emptySequence(), null)
        assertTrue(empty.complete); assertNull(empty.latest)
        val filtered = PagedLatestChoice<Long> { null }
        filtered.acceptPage(sequenceOf(10, 20), null)
        assertTrue(filtered.complete); assertNull(filtered.latest)
    }
    @Test fun responseCannotAppendAfterCompletionOrQuota() {
        val scan = PagedLatestChoice<Long>(maxPages = 1) { it }
        scan.acceptPage(sequenceOf(10), "more")
        assertThrows(IllegalStateException::class.java) { scan.acceptPage(sequenceOf(20), null) }
        assertThrows(IllegalArgumentException::class.java) { PagedLatestChoice<Long>(maxPages = 0) { it } }
    }
}
