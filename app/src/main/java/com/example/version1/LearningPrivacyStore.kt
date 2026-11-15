package com.example.version1

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/** Durable revocation precedes deletion. An interrupted deletion blocks reuse after restart.
 * Markers are outside per-profile owned keys so deleting a profile cannot drop its cleanup.
 */
internal class LearningPrivacyStore(
    private val prefs: SharedPreferences,
    private val delete: (String) -> Unit
) {
    constructor(context: Context, store: ComfortLearningStore) : this(
        ProtectedPreferences.get(context, "comfort_preferences"), store::delete)

    fun pending(profile: String): Boolean = prefs.contains(PREFIX + profile)
    fun hasPending(): Boolean = prefs.all.keys.any { it.startsWith(PREFIX) }

    fun revoke(profile: DriverProfile) {
        check(prefs.edit().putBoolean(profile.preferencePrefix + "learning_consent", false)
            .remove(profile.preferencePrefix + "learning_trip_id")
            .remove(profile.preferencePrefix + "learning_trip_marker")
            .remove(profile.preferencePrefix + "learning_trip_started")
            .putString(PREFIX + profile.id, UUID.randomUUID().toString()).commit()) {
            "개인화 저장 동의 철회를 저장하지 못했습니다."
        }
    }

    /** IO only. Shared across Activity instances; stale jobs cannot clear a new deletion marker. */
    fun recover(): Set<String> = synchronized(gate) {
        val completed = linkedSetOf<String>()
        for ((key, value) in prefs.all) {
            if (!key.startsWith(PREFIX)) continue
            val profile = key.removePrefix(PREFIX)
            check(profile.matches(Regex("[a-zA-Z0-9_-]{1,64}")) && value is String) { "Invalid privacy deletion marker" }
            if (prefs.getString(key, null) != value) continue
            delete(profile)
            if (prefs.getString(key, null) == value) {
                check(prefs.edit().remove(key).commit()) { "개인화 기록 삭제 완료를 저장하지 못했습니다." }
                completed += profile
            }
        }
        completed
    }

    companion object {
        private const val PREFIX = "privacy_delete_learning_"
        private val gate = Any()
    }
}
