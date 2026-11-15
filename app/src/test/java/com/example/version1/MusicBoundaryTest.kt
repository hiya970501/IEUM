package com.example.version1

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MusicBoundaryTest {
    @Test fun cancelledUnresponsiveReadDoesNotCreateAnotherWorker() {
        val reader = BoundedMediaRead()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val first = reader.submit({
            started.countDown()
            while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
            true
        }) { finished.countDown() }!!
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            first.cancel(true)
            repeat(20) { assertNull(reader.submit({ error("Must not run") }) { }) }
        } finally { release.countDown() }
        assertTrue(finished.await(2, TimeUnit.SECONDS))
    }

    @Test fun titlesAndOpaqueIdentifiersHaveDifferentRules() {
        assertEquals("긴 제목   다음", MusicText.title("긴 제목\n\t 다음"))
        assertEquals("제목 없음", MusicText.title("\n\u0000"))
        assertEquals(200, MusicText.title("가".repeat(500)).length)
        assertTrue(MusicText.validId("content://provider/원본 id?x=1"))
        assertFalse(MusicText.validId("가".repeat(1366)))
        assertFalse(MusicText.validId("id\u0000suffix"))
        assertFalse(MusicText.validId(""))
    }
    @Test fun missingSessionTitleMustNotBecomeEvidenceOfAnIdentifiedSong() {
        assertEquals("", MusicText.sessionTitle(null))
        assertEquals("", MusicText.sessionTitle("\n\u0000\u202E"))
        assertEquals("곡 이름", MusicText.sessionTitle("곡\u202E이름"))
    }
}
