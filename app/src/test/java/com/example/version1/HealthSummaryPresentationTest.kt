package com.example.version1

import com.example.afterglow.net.WorkoutHistoryEntry
import com.example.afterglow.net.WorkoutHistorySnapshot
import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class HealthSummaryPresentationTest {
    @Test fun relativeTimeRoundsDownAtMinuteAndHourBoundaries() {
        for((millis,expected) in listOf(0L to "0분 전",59_999L to "0분 전",60_000L to "1분 전",3_599_999L to "59분 전",3_600_000L to "1시간 전",7_199_999L to "1시간 전",7_200_000L to "2시간 전"))
            assertEquals(expected,HealthSummaryPresentation.age(millis))
        assertEquals("시각 확인 필요",HealthSummaryPresentation.age(null))
        assertEquals("시각 확인 필요",HealthSummaryPresentation.age(-1))
    }
    @Test fun workoutRowUsesStartDateAndElapsedMinutesAndEndForRedBoundary() {
        val end=Instant.parse("2026-11-14T01:00:00Z").toEpochMilli()
        val entry=WorkoutHistoryEntry(1,"hike",end-9*60_000,end,"owned.health",false)
        val snapshot=WorkoutHistorySnapshot(end,1,listOf(entry))
        val timeZone=TimeZone.getTimeZone("Asia/Seoul")
        val row=HealthSummaryPresentation.rows(snapshot,end+7_200_000,timeZone).single()
        assertEquals("하이킹, 2026-11-14 09:51, 9분",row.text);assertTrue(row.recent)
        assertFalse(HealthSummaryPresentation.rows(snapshot,end+7_200_001,timeZone).single().recent)
        assertEquals(1,HealthSummaryPresentation.rows(snapshot,end+86_400_000,timeZone).size)
        assertTrue(HealthSummaryPresentation.rows(snapshot,end+86_400_001,timeZone).isEmpty())
    }
    @Test fun futureAndMissingRecordsAreNotShownAsCurrentWorkouts() {
        val end=Instant.parse("2026-11-14T01:00:00Z").toEpochMilli()
        val snapshot=WorkoutHistorySnapshot(end,1,listOf(WorkoutHistoryEntry(1,"run",end-30_000,end,"owned.health",true)))
        assertTrue(HealthSummaryPresentation.rows(snapshot,end-1).isEmpty())
        assertTrue(HealthSummaryPresentation.rows(null,end).isEmpty())
        assertTrue(HealthSummaryPresentation.rows(snapshot,end).single().text.endsWith("0분 · 수동 입력"))
    }
}
