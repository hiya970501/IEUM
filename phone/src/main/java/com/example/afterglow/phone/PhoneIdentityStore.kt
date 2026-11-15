package com.example.afterglow.phone

import org.json.JSONObject

/** Storage seam has no health records, pairing credentials or hardware identifiers. */
internal interface PhoneIdentityBlob {
    fun snapshot(): Map<String, *>
    fun replace(sealed: String?): Boolean
}

/** An unreadable identity must not silently become an anonymous or newly enrolled phone.
 * Only an explicit settings save can recover a blocked store. Legacy names/IDs are
 * migrated durably before they can be returned to the relay.
 */
internal class PhoneIdentityStore(
    private val blob: PhoneIdentityBlob,
    private val seal: (String) -> String,
    private val open: (String) -> String
) {
    private var blocked = false

    @Synchronized fun load(): PhoneRelayIdentity? = protect {
        check(!blocked)
        val values = blob.snapshot()
        if (values.isEmpty()) return@protect null
        if (values.containsKey(SEALED)) {
            check(values.keys == setOf(SEALED))
            val value = values[SEALED] as? String ?: error("Invalid identity storage")
            check(value.length in 1..8192)
            return@protect decode(open(value))
        }
        check(values.keys.all { it in setOf("enabled", "phone_id", "name") })
        check(values["enabled"] is Boolean)
        val identity = if (values["enabled"] == true) PhoneRelayIdentity(
            values["phone_id"] as? String ?: error("Invalid legacy identity"),
            values["name"] as? String ?: error("Invalid legacy identity")
        ) else null
        check(blob.replace(identity?.let { seal(encode(it)) }))
        identity
    }

    /** Explicit opt-out can delete corrupt ciphertext without decrypting it or creating a key. */
    @Synchronized fun save(identity: PhoneRelayIdentity?) = protect {
        check(blob.replace(identity?.let { seal(encode(it)) }))
        blocked = false
    }

    private inline fun <T> protect(action: () -> T): T = try { action() }
    catch (_: Exception) {
        blocked = true
        throw IllegalStateException("Phone profile storage unavailable")
    }

    private fun encode(identity: PhoneRelayIdentity): String = JSONObject()
        .put("version", 1).put("phone_id", identity.phoneId).put("name", identity.displayName).toString()

    private fun decode(value: String): PhoneRelayIdentity {
        require(value.length in 1..2048)
        val root = JSONObject(value)
        require(root.keys().asSequence().toSet() == setOf("version", "phone_id", "name") && root.get("version") == 1)
        return PhoneRelayIdentity(root.get("phone_id") as? String ?: error("Invalid identity"),
            root.get("name") as? String ?: error("Invalid identity"))
    }

    companion object { const val SEALED = "__sealed_identity_v1" }
}
