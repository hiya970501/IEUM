package com.example.version1

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.UUID

/** A separately enrolled dock claim. It is not a health event or proof of human identity. */
data class ZeroTapEvent(val phoneId: String, val credential: String, val eventId: String, val occurredAt: Long) {
    companion object {
        fun parse(json: JSONObject): ZeroTapEvent {
            require(json.getBoolean("docked"))
            val phone = UUID.fromString(json.getString("phoneId")).toString()
            val event = UUID.fromString(json.getString("eventId")).toString()
            val secret = json.getString("credential")
            require(secret.matches(Regex("[a-f0-9]{64}")))
            return ZeroTapEvent(phone, secret, event,
                OffsetDateTime.parse(json.getString("occurredAt")).toInstant().toEpochMilli())
        }
    }
}

internal fun zeroTapDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

/** Pure decision, also used by the receiver tests. Unknown/P-exit never queues future movement. */
internal fun zeroTapRejection(event: ZeroTapEvent, phone: String, hash: String, now: Long,
                              enabled: Boolean, allowed: Boolean, replay: Boolean, lastAttempt: Long): String? = when {
    !enabled -> "disabled"
    !MessageDigest.isEqual(zeroTapDigest(event.credential).toByteArray(), hash.toByteArray()) || event.phoneId != phone -> "unregistered_device"
    now - event.occurredAt !in -5_000..30_000 -> "expired_event"
    replay -> "duplicate_event"
    lastAttempt > 0 && now - lastAttempt < 60_000 -> "dock_cooldown"
    !allowed -> "park_or_busy_required"
    else -> null
}

class ZeroTapStore internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(ProtectedPreferences.get(context, "zero_tap"))
    fun config(profile: String): JSONObject? = prefs.getString("config_$profile", null)?.let(::JSONObject)
    /** Status is profile-scoped: it can include an event id and must not bleed to another driver. */
    fun status(profile: String): String = prefs.getString("status_$profile", "미등록").orEmpty()
    /** One atomic preference snapshot; no phone credential, hash, VIN or health data. */
    fun receipt(profile: String): JSONObject {
        val snapshot = prefs.all
        return JSONObject().put("eventId", snapshot["event_$profile"] ?: JSONObject.NULL)
            .put("running", snapshot["running_$profile"] == true)
            .put("status", snapshot["status_$profile"] ?: "미등록")
    }
    fun setStatus(profile: String, value: String) {
        check(prefs.edit().putString("status_$profile", value.take(240)).putBoolean("running_$profile", false).commit())
    }
    fun isCurrentEvent(profile: String, eventId: String): Boolean =
        prefs.getString("event_$profile", null) == eventId && prefs.contains("config_$profile")

    /** A late callback cannot replace a newer event, re-enrollment or revoked registration. */
    fun recordResult(profile: String, eventId: String, value: String): Boolean {
        if (!isCurrentEvent(profile, eventId)) return false
        setStatus(profile, value)
        return true
    }

    /** Process death cannot leave a forever-running success-looking message on the next launch. */
    fun reconcileInterrupted(profiles: Collection<String>) {
        for (profile in profiles) if (prefs.getBoolean("running_$profile", false)) {
            setStatus(profile, "이전 적용이 중단됐습니다. 차량 설정 복원 상태를 확인하세요.")
        }
    }
    fun save(profile: String, json: JSONObject) { check(prefs.edit().putString("config_$profile", json.toString()).commit()) }
    fun enroll(profile: String, baseline: JSONObject): JSONObject {
        val credential = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val phoneId = UUID.randomUUID().toString()
        val cfg = JSONObject().put("enabled", false).put("phoneId", phoneId)
            .put("hash", zeroTapDigest(credential)).put("baseline", baseline).put("events", JSONArray()).put("lastAttempt", 0)
        check(prefs.edit().putString("config_$profile", cfg.toString())
            .putString("status_$profile", "등록 완료 · 자동 적용 꺼짐")
            .remove("event_$profile").remove("running_$profile").commit())
        // Display once. Only the hash is persisted; re-enrollment revokes the previous credential.
        return JSONObject().put("action", "zero-tap").put("phoneId", phoneId).put("credential", credential)
            .put("docked", true)
    }
    fun remove(profile: String) {
        check(prefs.edit().remove("config_$profile").remove("status_$profile")
            .remove("event_$profile").remove("running_$profile").commit())
    }
    fun profileFor(event: ZeroTapEvent, profiles: List<DriverProfile>): String? = profiles.firstOrNull {
        config(it.id)?.optString("phoneId") == event.phoneId
    }?.id
    fun beginEvent(profile: String, event: ZeroTapEvent, allowed: Boolean, now: Long): JSONObject {
        val cfg = config(profile) ?: error("unregistered_device")
        val events = cfg.getJSONArray("events")
        val previous = (0 until events.length()).map { events.getString(it) }
        val rejection = zeroTapRejection(event, cfg.getString("phoneId"), cfg.getString("hash"), now,
            cfg.getBoolean("enabled"), allowed, event.eventId in previous, cfg.optLong("lastAttempt"))
        check(rejection == null) { rejection.orEmpty() }
        // Durable consumption BEFORE sending a vehicle command: process death cannot replay it.
        cfg.put("events", JSONArray((previous + event.eventId).takeLast(32))).put("lastAttempt", now)
        // Admission, replay evidence and running state are one durable commit. Callers mark
        // themselves busy only AFTER this succeeds and before dispatching any vehicle write.
        check(prefs.edit().putString("config_$profile", cfg.toString())
            .putString("status_$profile", "기본 설정 적용 중").putString("event_$profile", event.eventId)
            .putBoolean("running_$profile", true).commit())
        return cfg.getJSONObject("baseline")
    }
}
