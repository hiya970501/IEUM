package com.example.afterglow.phone

import com.example.afterglow.net.WorkoutHistoryEntry
import com.example.afterglow.net.WorkoutHistorySnapshot

/** Bounded newest records. An incomplete page scan is never published as a full 24h history. */
internal class PagedWorkoutHistory(private val windowEnd: Long) {
    private val seen=HashSet<WorkoutHistoryEntry>()
    private val latest=ArrayList<WorkoutHistoryEntry>()
    private val tokens=HashSet<String>()
    private var pages=0
    private var repeated=false
    var nextPageToken: String?=null
        private set
    var complete=false
        private set
    val canReadNextPage get() = !complete && !repeated && pages<5
    fun acceptPage(records: Sequence<WorkoutHistoryEntry>,next: String?) {
        check(canReadNextPage)
        records.forEach { record ->
            if(record.endedAt in (windowEnd-WorkoutHistoryEntry.WINDOW_MILLIS)..windowEnd) {
                record.validate(windowEnd)
                check(seen.size<5000 || record in seen)
                if(seen.add(record)) {
                    latest.add(record)
                    latest.sortWith(compareByDescending<WorkoutHistoryEntry> { it.endedAt }.thenByDescending { it.startedAt }.thenBy { it.type }.thenBy { it.origin })
                    if(latest.size>WorkoutHistorySnapshot.MAX_RECORDS) latest.removeAt(latest.lastIndex)
                }
            }
        }
        pages++;nextPageToken=next?.takeIf { it.isNotEmpty() }
        complete=nextPageToken==null
        nextPageToken?.let { repeated=!tokens.add(it) }
    }
    fun snapshot(): WorkoutHistorySnapshot? = if(complete) WorkoutHistorySnapshot(windowEnd,seen.size,latest) else null
}
