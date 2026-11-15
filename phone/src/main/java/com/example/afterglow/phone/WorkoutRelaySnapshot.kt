package com.example.afterglow.phone

import org.json.JSONObject
import java.security.MessageDigest
import java.time.Duration

/** One outgoing observation. Its fingerprint compares values, not only record timestamps. */
internal class WorkoutRelaySnapshot(
    private val kind: String,
    private val endedAtMillis: Long,
    duration: Duration,
    private val bpm: Long? = null,
    private val bpmMeasuredAtMillis: Long? = null,
    private val identity: PhoneRelayIdentity? = null
) {
    init {
        require(kind in setOf("run", "swim", "hike") && endedAtMillis > 0 && !duration.isNegative)
        require((bpm == null) == (bpmMeasuredAtMillis == null))
        require(bpm == null || bpm in 40L..220L)
        require(bpmMeasuredAtMillis == null || bpmMeasuredAtMillis > 0)
    }
    val durationMinutes = duration.toMinutes().takeIf { it in 1L..1440L }?.toInt()
    val durationLabel = durationMinutes?.let { "${it}분" }
        ?: if (duration.toMinutes() < 1) "1분 미만" else "시간 지원 범위 초과"

    val fingerprint: String = MessageDigest.getInstance("SHA-256").digest(
        "v2|$kind|$endedAtMillis|$durationMinutes|$bpm|$bpmMeasuredAtMillis|${identity?.phoneId}|${identity?.displayName}".toByteArray(Charsets.UTF_8)
    ).joinToString("") { "%02x".format(it) }

    fun payload(requestId: String): String {
        require(requestId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val json = JSONObject().put("source", "health-connect").put("kind", kind)
            .put("requestId", requestId).put("endedAt", endedAtMillis)
        durationMinutes?.let { json.put("durationMinutes", it) }
        bpm?.let { json.put("bpm", it).put("bpmMeasuredAt", bpmMeasuredAtMillis) }
        identity?.appendTo(json)
        return json.toString()
    }
    override fun toString() = "WorkoutRelaySnapshot(redacted)"
}
