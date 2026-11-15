package com.example.afterglow.phone

import org.json.JSONObject
import java.security.MessageDigest

/** Health Connect observation without inventing an exercise session or sensor provenance. */
internal class HeartRateRelaySnapshot(private val bpm:Long, private val measuredAt:Long,
    private val identity:PhoneRelayIdentity?=null) {
    init { require(bpm in 40..220 && measuredAt>0) }
    val fingerprint=MessageDigest.getInstance("SHA-256").digest(
        "hc-heart-v1|$bpm|$measuredAt|${identity?.phoneId}|${identity?.displayName}".toByteArray(Charsets.UTF_8)
    ).joinToString("") { "%02x".format(it) }
    fun payload(requestId:String):String {
        require(requestId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val json=JSONObject().put("source","health-connect").put("bpm",bpm)
            .put("measuredAt",measuredAt).put("requestId",requestId)
        identity?.appendTo(json)
        return json.toString()
    }
    override fun toString()="HeartRateRelaySnapshot(redacted)"
}
