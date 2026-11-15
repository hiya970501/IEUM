package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class MusicDisplayTextTest {
    @Test fun latinSummaryIncludesEllipsisAndFolderIndicatorInsideEightyCharacters() {
        val result = MusicDisplayText.title("a".repeat(200), true)
        assertEquals(80, result.codePointCount(0, result.length))
        assertTrue(result.endsWith("… ›"))
    }
    @Test fun shortEnglishWordsDoNotBypassTwentyFourWordLimit() {
        val result = MusicDisplayText.title(List(25) { "i" }.joinToString(" "))
        assertEquals(24, Regex("[A-Za-z]+").findAll(result).count())
        assertTrue(result.endsWith("…"))
    }
    @Test fun japaneseSummaryIncludesSuffixInsideThirtyCharacters() {
        for (input in listOf("あ".repeat(80), "音楽".repeat(40))) {
            val result = MusicDisplayText.title(input, true)
            assertEquals(30, result.codePointCount(0, result.length))
            assertTrue(result.endsWith("… ›"))
        }
    }
    @Test fun summariesNeverEmitUnpairedSurrogates() {
        val result = MusicDisplayText.title("😀".repeat(99) + "\uD83D", true)
        assertTrue(result.codePointCount(0, result.length) <= 80)
        assertFalse(result.codePoints().anyMatch { it in 0xD800..0xDFFF })
        assertTrue(result.startsWith("😀"))
    }
    @Test fun playbackStatePrecedesLongTitleAndAccessibleMetadataStaysBoundedAndComplete() {
        val original = "Track ".repeat(40)
        val canonical = MusicText.sessionTitle(original)
        val shown = MusicDisplayText.status("연결됨", canonical, "재생 중")
        assertEquals("재생 중", shown.lines()[1])
        assertTrue(MusicDisplayText.accessibleStatus("연결됨", canonical, "재생 중").endsWith(canonical))
        assertEquals(200, canonical.length)
    }
    @Test fun missingMetadataIsNotConvertedToEvidenceOfAnIdentifiedSong() {
        assertEquals("", MusicDisplayText.title(null))
        assertEquals("연결됨\n일시 정지", MusicDisplayText.status("연결됨", "", "일시 정지"))
    }
}
