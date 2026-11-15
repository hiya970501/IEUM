package com.example.afterglow.net

import org.json.JSONObject

/** User-timed walking observation, NOT Samsung Health history or a detected workout. */
data class WatchActivityRecord(
    val sessionId: String, val startedAt: Long, val observedAt: Long,
    val endedAt: Long? = null, val stepsSinceBaseline: Int? = null,
    val stepBaselineAt: Long? = null, val stepsMeasuredAt: Long? = null
) {
    fun validate(now: Long): WatchActivityRecord {
        require(sessionId.matches(Regex("[0-9a-f-]{36}")))
        java.util.UUID.fromString(sessionId).also { require(it.toString() == sessionId) }
        require(startedAt > 0 && observedAt >= startedAt && observedAt - startedAt <= 86_400_000)
        require(observedAt <= now && now - observedAt < 30_000)
        endedAt?.let { require(it in startedAt..observedAt && it > startedAt) }
        if (stepsSinceBaseline == null) require(stepBaselineAt == null && stepsMeasuredAt == null)
        else {
            require(stepsSinceBaseline in 0..200_000)
            require(stepBaselineAt != null && stepsMeasuredAt != null)
            require(stepBaselineAt in startedAt..observedAt && stepsMeasuredAt in stepBaselineAt..observedAt)
        }
        return this
    }
    fun json(requestId: String): JSONObject {
        require(requestId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        return JSONObject().put("source", SOURCE).put("kind", "walking")
            .put("requestId", requestId).put("sessionId", sessionId)
            .put("startedAt", startedAt).put("observedAt", observedAt).apply {
                endedAt?.let { put("endedAt", it) }
                stepsSinceBaseline?.let {
                    put("stepsSinceBaseline", it); put("stepBaselineAt", stepBaselineAt); put("stepsMeasuredAt", stepsMeasuredAt)
                }
            }
    }
    companion object {
        const val SOURCE = "wear-os-user-session"
        fun parse(json: JSONObject, now: Long): WatchActivityRecord {
            require(json.getString("source") == SOURCE && json.getString("kind") == "walking")
            val allowed = setOf("source","kind","requestId","sessionId","startedAt","observedAt","endedAt",
                "stepsSinceBaseline","stepBaselineAt","stepsMeasuredAt")
            require(json.keys().asSequence().all { it in allowed })
            fun integer(name: String): Long {
                val raw = json.get(name); require(raw is Int || raw is Long)
                return (raw as Number).toLong()
            }
            val count = if (json.has("stepsSinceBaseline")) integer("stepsSinceBaseline").also { require(it in 0..200_000) }.toInt() else null
            return WatchActivityRecord(json.getString("sessionId"), integer("startedAt"), integer("observedAt"),
                if (json.has("endedAt")) integer("endedAt") else null, count,
                if (json.has("stepBaselineAt")) integer("stepBaselineAt") else null,
                if (json.has("stepsMeasuredAt")) integer("stepsMeasuredAt") else null).validate(now)
        }
    }
}
