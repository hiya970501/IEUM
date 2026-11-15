package com.example.afterglow.net

import org.json.JSONArray
import org.json.JSONObject

/** Minimal session metadata only: no route, title, user ID, raw health series, or invented calories. */
data class WorkoutHistoryEntry(val type: Int, val kind: String, val startedAt: Long, val endedAt: Long,
    val origin: String, val manual: Boolean) {
    fun validate(windowEnd: Long): WorkoutHistoryEntry {
        require(type in 0..10_000 && kind in KINDS)
        require(startedAt > 0 && endedAt > startedAt && endedAt in (windowEnd-WINDOW_MILLIS)..windowEnd)
        require(origin.length in 1..160 && origin.matches(Regex("[A-Za-z0-9_.]+")))
        return this
    }
    fun json(): JSONObject = JSONObject().put("type",type).put("kind",kind).put("startedAt",startedAt)
        .put("endedAt",endedAt).put("origin",origin).put("manual",manual)
    override fun toString()="WorkoutHistoryEntry(redacted)"
    companion object {
        const val WINDOW_MILLIS=86_400_000L
        val KINDS=setOf("run","swim","hike","walk","cycle","strength","other")
    }
}

class WorkoutHistorySnapshot(val windowEnd: Long, val totalMatches: Int, entries: List<WorkoutHistoryEntry>) {
    val entries: List<WorkoutHistoryEntry> = java.util.Collections.unmodifiableList(entries.toList())
    val truncated: Boolean get() = totalMatches > entries.size
    fun validate(now: Long): WorkoutHistorySnapshot {
        require(windowEnd>WorkoutHistoryEntry.WINDOW_MILLIS && windowEnd<=now && now-windowEnd<120_000)
        require(entries.size<=MAX_RECORDS && totalMatches in entries.size..5000)
        require(entries.size==minOf(totalMatches,MAX_RECORDS))
        entries.forEach { it.validate(windowEnd) }
        require(entries.distinct().size==entries.size)
        require(entries.zipWithNext().all { (a,b) -> a.endedAt>=b.endedAt })
        return this
    }
    fun json(requestId: String): JSONObject {
        require(requestId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        return JSONObject().put("source","health-connect").put("requestId",requestId)
            .put("windowEnd",windowEnd).put("complete",true).put("totalMatches",totalMatches)
            .put("entries",JSONArray().apply { entries.forEach { put(it.json()) } })
    }
    override fun toString()="WorkoutHistorySnapshot(redacted)"
    companion object {
        const val MAX_RECORDS=24
        fun parse(json: JSONObject,now: Long): WorkoutHistorySnapshot {
            require(json.getString("source")=="health-connect" && json.get("complete")==true)
            val allowed=setOf("source","requestId","windowEnd","complete","totalMatches","entries","driverId","driverName")
            require(json.keys().asSequence().all { it in allowed })
            fun long(j: JSONObject,key: String): Long {
                val raw=j.get(key);require(raw is Int || raw is Long);return (raw as Number).toLong()
            }
            val end=long(json,"windowEnd");val total=long(json,"totalMatches");require(total in 0..5000)
            val list=json.getJSONArray("entries");require(list.length()<=MAX_RECORDS)
            val records=(0 until list.length()).map { index ->
                val item=list.getJSONObject(index)
                require(item.keys().asSequence().toSet()==setOf("type","kind","startedAt","endedAt","origin","manual"))
                val type=long(item,"type");require(type in 0..10_000)
                val manual=item.get("manual");require(manual is Boolean)
                WorkoutHistoryEntry(type.toInt(),item.getString("kind"),long(item,"startedAt"),long(item,"endedAt"),item.getString("origin"),manual)
            }
            return WorkoutHistorySnapshot(end,total.toInt(),records).validate(now)
        }
    }
}
