package com.example.afterglow.phone

import com.example.afterglow.net.WorkoutHistoryEntry
import com.example.afterglow.net.WorkoutHistorySnapshot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PagedWorkoutHistoryTest {
    private val now=1_800_000_000_000L
    private fun record(age:Long,type:Int=0,manual:Boolean=false)=WorkoutHistoryEntry(type,"other",now-age-60_000,now-age,"com.example.health",manual)
    @Test fun twentyFourHourHistoryIncludesOlderUnsupportedAndManualRecordsButNotOutsideWindow() {
        val scan=PagedWorkoutHistory(now)
        val yesterday=record(23*3_600_000L,999,true)
        scan.acceptPage(sequenceOf(record(10_000),yesterday,record(86_400_001)),null)
        val result=scan.snapshot()!!
        assertEquals(2,result.totalMatches);assertEquals(yesterday,result.entries.last())
        assertEquals(result.entries,WorkoutHistorySnapshot.parse(result.json("history-1"),now).entries)
    }
    @Test fun noRecordsIsAuthoritativeOnlyWhenPaginationCompletes() {
        val scan=PagedWorkoutHistory(now)
        scan.acceptPage(emptySequence(),"next")
        assertNull(scan.snapshot())
        scan.acceptPage(emptySequence(),"")
        assertEquals(0,scan.snapshot()!!.totalMatches)
    }
    @Test fun repeatedTokenCannotPublishAnApparentlyEmptyHistory() {
        val scan=PagedWorkoutHistory(now)
        scan.acceptPage(sequenceOf(record(10_000)),"again")
        scan.acceptPage(emptySequence(),"again")
        assertFalse(scan.canReadNextPage);assertNull(scan.snapshot())
    }
    @Test fun latestTwentyFourAreRetainedAndTruncationIsExplicit() {
        val scan=PagedWorkoutHistory(now)
        scan.acceptPage((0..30).map { record(it*60_000L) }.asSequence(),null)
        val result=scan.snapshot()!!
        assertEquals(31,result.totalMatches);assertEquals(24,result.entries.size);assertTrue(result.truncated)
        assertEquals(now,result.entries.first().endedAt)
        assertEquals(now-23*60_000L,result.entries.last().endedAt)
        assertTrue(result.json("test").toString().toByteArray().size<8192)
    }
    @Test fun staleIncompleteCoercedAndInconsistentHistoryIsRejected() {
        val valid=WorkoutHistorySnapshot(now,0,emptyList()).json("test")
        for(bad in listOf(JSONObject(valid.toString()).put("complete",false),
            JSONObject(valid.toString()).put("windowEnd",now.toString()),
            JSONObject(valid.toString()).put("totalMatches",1))) {
            assertThrows(IllegalArgumentException::class.java) { WorkoutHistorySnapshot.parse(bad,now) }
        }
        assertThrows(IllegalArgumentException::class.java) { WorkoutHistorySnapshot.parse(valid,now+120_000) }
    }
    @Test fun duplicateProviderRowsDoNotInflateTotalAndHistoryCannotMutateAfterPublication() {
        val scan=PagedWorkoutHistory(now);val item=record(1000)
        scan.acceptPage(sequenceOf(item,item),null)
        assertEquals(1,scan.snapshot()!!.totalMatches)
        val mutable=mutableListOf(item);val published=WorkoutHistorySnapshot(now,1,mutable)
        mutable.clear();assertEquals(1,published.entries.size)
    }
}
