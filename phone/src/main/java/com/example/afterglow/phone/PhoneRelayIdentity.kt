package com.example.afterglow.phone

import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** Opt-in app-generated identifier. It is neither a hardware ID nor proof of a person. */
internal class PhoneRelayIdentity(phoneId: String, name: String) {
    val phoneId = phoneId.lowercase(Locale.ROOT)
    val displayName = name.trim().ifBlank { "Android 휴대폰" }
    init {
        require(this.phoneId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        require(displayName.length <= 24 && displayName.none { it.isISOControl() })
    }
    fun appendTo(json: JSONObject): JSONObject = json.put("driverId", phoneId).put("driverName", displayName)
    fun connectionPayload(requestId: String): String {
        require(requestId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        return appendTo(JSONObject().put("source", "health-connect").put("requestId", requestId)).toString()
    }
    override fun toString() = "PhoneRelayIdentity(redacted)"
    companion object { fun create(name: String) = PhoneRelayIdentity(UUID.randomUUID().toString(), name) }
}
