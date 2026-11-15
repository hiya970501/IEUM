package com.example.version1

import com.example.afterglow.net.WorkoutHistoryEntry
import com.example.afterglow.net.WorkoutHistorySnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Display rounding never changes sample freshness or the automatic-control policy. */
internal object HealthSummaryPresentation {
    fun age(ageMillis: Long?): String {
        return com.example.afterglow.net.HealthDisplayTime.age(ageMillis)
    }
    class Row(val text: String,val recent: Boolean)
    fun rows(snapshot:WorkoutHistorySnapshot?,now:Long,timeZone:TimeZone=TimeZone.getDefault()):List<Row> {
        if(snapshot==null) return emptyList()
        val format=SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.KOREA).apply {this.timeZone=timeZone}
        return snapshot.entries.filter {now-it.endedAt in 0..WorkoutHistoryEntry.WINDOW_MILLIS}.map {entry->
            val kind=when(entry.kind) {"run"->"러닝";"swim"->"수영";"hike"->"하이킹";"walk"->"걷기";"cycle"->"사이클";"strength"->"근력 운동";else->"기타 운동"}
            val minutes=(entry.endedAt-entry.startedAt)/60_000
            Row("$kind, ${format.format(Date(entry.startedAt))}, ${minutes}분"+(if(entry.manual) " · 수동 입력" else ""),now-entry.endedAt in 0..7_200_000)
        }
    }
}
