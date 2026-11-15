package com.example.version1

import java.util.UUID

/** A phone's consented opaque identifier, not a face match or verified human identity. */
data class PairedDriverIdentity(
    val phoneId: String,
    val displayName: String,
    val relaySource: String,
    val receivedAtEpochMillis: Long
) {
    fun isFresh(nowEpochMillis: Long): Boolean =
        nowEpochMillis - receivedAtEpochMillis in 0..60_000

    companion object {
        private val phoneIdFormat = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val relaySources = setOf("apple-health", "health-connect", "native-wear", "apple-shortcuts")

        fun create(phoneId: String, displayName: String?, source: String, receivedAt: Long): PairedDriverIdentity {
            require(phoneId.matches(phoneIdFormat) && source in relaySources && receivedAt > 0)
            val name = displayName?.trim().orEmpty()
            require(name.length <= 24 && name.none { it.isISOControl() })
            return PairedDriverIdentity(phoneId.lowercase(), name.ifBlank {
                if (source in setOf("apple-health", "apple-shortcuts")) "연결한 iPhone" else "연결한 휴대폰"
            }, source, receivedAt)
        }
    }
}

data class DriverProfile(val id: String, val name: String) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        require(name.isNotBlank() && name.length <= 24 && name.none { it.isISOControl() })
    }

    /** Preserve existing preferences for the original profile; new profiles are isolated. */
    val preferencePrefix: String get() = if (id == DEFAULT_ID) "" else "driver_${id}_"

    companion object {
        const val DEFAULT_ID = "default"
        fun create(name: String) = DriverProfile(UUID.randomUUID().toString(), name.trim())
    }
}

/**
 * Small local preferences directory. Health history, vehicle positions and biometric templates
 * are deliberately absent. Binding a paired phone and enabling auto-selection are separate
 * local actions. A new phone cannot silently create, bind or activate a driver profile.
 */
data class DriverProfileSettings(
    val profiles: List<DriverProfile> = listOf(DriverProfile(DriverProfile.DEFAULT_ID, "내 프로필")),
    val selectedProfileId: String = DriverProfile.DEFAULT_ID,
    val autoSelectPairedPhone: Boolean = false,
    val phoneBindings: Map<String, String> = emptyMap()
) {
    init {
        require(profiles.size in 1..MAX_PROFILES && profiles.map { it.id }.distinct().size == profiles.size)
        require(profiles.any { it.id == selectedProfileId })
        require(phoneBindings.size <= MAX_PHONE_BINDINGS && phoneBindings.values.all { bound -> profiles.any { it.id == bound } })
        require(phoneBindings.keys.all { key -> runCatching {
            PairedDriverIdentity.create(key, null, "apple-health", 1).phoneId == key
        }.getOrDefault(false) })
    }

    val selected: DriverProfile get() = profiles.first { it.id == selectedProfileId }

    fun select(profileId: String): DriverProfileSettings {
        require(profiles.any { it.id == profileId })
        return copy(selectedProfileId = profileId)
    }

    fun add(name: String): DriverProfileSettings {
        require(profiles.size < MAX_PROFILES)
        val created = DriverProfile.create(name)
        return copy(profiles = profiles + created, selectedProfileId = created.id)
    }

    fun remove(profileId: String): DriverProfileSettings {
        require(profiles.size > 1 && profiles.any { it.id == profileId })
        val remaining = profiles.filterNot { it.id == profileId }
        return copy(profiles = remaining,
            selectedProfileId = if (selectedProfileId == profileId) remaining.first().id else selectedProfileId,
            phoneBindings = phoneBindings.filterValues { it != profileId })
    }

    /** Called only by an explicit vehicle-side confirmation, never directly from receiver data. */
    fun canBindPhone(identity: PairedDriverIdentity, nowEpochMillis: Long): Boolean =
        identity.isFresh(nowEpochMillis) && (identity.phoneId in phoneBindings || phoneBindings.size < MAX_PHONE_BINDINGS)

    fun bindPhone(identity: PairedDriverIdentity, nowEpochMillis: Long): DriverProfileSettings {
        require(canBindPhone(identity, nowEpochMillis))
        return copy(phoneBindings = phoneBindings + (identity.phoneId to selectedProfileId))
    }

    fun unbindAllPhones(): DriverProfileSettings = copy(phoneBindings = emptyMap(), autoSelectPairedPhone = false)

    /** Returns a profile choice only. It never starts recovery or writes vehicle settings. */
    fun automaticSelection(identity: PairedDriverIdentity, nowEpochMillis: Long, mayChangePreferences: Boolean): DriverProfile? {
        if (!autoSelectPairedPhone || !mayChangePreferences || !identity.isFresh(nowEpochMillis)) return null
        val profileId = phoneBindings[identity.phoneId] ?: return null
        return profiles.firstOrNull { it.id == profileId }
    }

    companion object {
        const val MAX_PROFILES = 8
        const val MAX_PHONE_BINDINGS = 16
    }
}
