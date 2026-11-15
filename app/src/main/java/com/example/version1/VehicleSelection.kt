package com.example.version1

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/** A display label, not a claim that model-specific cabin geometry or equipment is installed. */
data class VehicleProfile(
    val id: String,
    val brand: String,
    val model: String,
    val modelYear: Int? = null
) {
    val displayName: String
        get() = listOf(brand, model, modelYear?.let { "${it}년형" }.orEmpty())
            .filter { it.isNotBlank() }.joinToString(" ")
}

/** Public metadata only. This type deliberately contains no VIN or other vehicle identifier. */
data class VehicleIdentity(val make: String?, val model: String?, val modelYear: Int?) {
    val hasMetadata: Boolean
        get() = !make.isNullOrBlank() || !model.isNullOrBlank() || modelYear != null
    val displayName: String
        get() = listOf(make.orEmpty(), model.orEmpty(), modelYear?.let { "${it}년형" }.orEmpty())
            .filter { it.isNotBlank() }.joinToString(" ").ifBlank { "차량 정보 없음" }
}

object VehicleCatalog {
    const val GENERIC_ID = "generic"
    const val DETECTED_ID = "detected_vehicle"
    val profiles: List<VehicleProfile> = listOf(
        VehicleProfile(GENERIC_ID, "", "공통 실내"),
        VehicleProfile("hyundai_ioniq5", "현대", "IONIQ 5"),
        VehicleProfile("hyundai_ioniq5_n", "현대", "IONIQ 5 N"),
        VehicleProfile("hyundai_kona", "현대", "KONA"),
        VehicleProfile("hyundai_nexo", "현대", "NEXO"),
        VehicleProfile("hyundai_inster", "현대", "INSTER"),
        VehicleProfile("kia_ev6", "기아", "EV6"),
        VehicleProfile("kia_ev9", "기아", "EV9")
    )

    fun find(id: String): VehicleProfile = profiles.firstOrNull { it.id == id } ?: profiles.first()

    /** Exact public-name aliases only: unknown OEM codes, trims and guessed model years are ignored. */
    fun match(identity: VehicleIdentity): VehicleProfile? {
        val model = normalize(identity.model ?: return null)
        if (model.isEmpty()) return null
        val make = identity.make?.takeIf { it.isNotBlank() }?.let(::normalize)
        return profiles.asSequence().filter { it.id != GENERIC_ID }.firstOrNull { profile ->
            val brands = brandAliases.getValue(profile.brand)
            val names = modelAliases[profile.id].orEmpty() + profile.model
            (make == null || make in brands) && names.any { name ->
                val normalizedName = normalize(name)
                model == normalizedName || brands.any { model == it + normalizedName }
            }
        }?.copy(modelYear = identity.modelYear)
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    private val brandAliases = mapOf(
        "현대" to setOf("현대", "현대자동차", "hyundai", "hyundaimotor", "hyundaimotorcompany"),
        "기아" to setOf("기아", "기아자동차", "kia", "kiamotors", "kiacorporation"),
        "제네시스" to setOf("제네시스", "genesis")
    )
    private val modelAliases = mapOf(
        "hyundai_ioniq5" to setOf("아이오닉 5"),
        "hyundai_ioniq5_n" to setOf("아이오닉 5 N"),
        "hyundai_ioniq6" to setOf("아이오닉 6"),
        "hyundai_ioniq9" to setOf("아이오닉 9"),
        "hyundai_kona" to setOf("코나"),
        "hyundai_nexo" to setOf("넥쏘"),
        "hyundai_inster" to setOf("인스터"),
        "hyundai_palisade" to setOf("팰리세이드"),
        "kia_sportage" to setOf("스포티지")
    )
}

data class VehicleSelectionPreferences(
    val autoEnabled: Boolean = true,
    val manualProfileId: String = VehicleCatalog.GENERIC_ID,
    val rightHandDrive: Boolean = false,
    val visualVariantIds: Map<String, String> = emptyMap(),
    val exteriorTransparency: Int = 55
)

/** These two choices persist independently; an automatic result never replaces the manual choice. */
class VehicleSelectionStore(context: Context) {
    private val prefs = ProtectedPreferences.get(context, "vehicle_selection")

    fun load(): VehicleSelectionPreferences = VehicleSelectionPreferences(
        autoEnabled = prefs.getBoolean("auto_enabled", true),
        manualProfileId = VehicleCatalog.find(prefs.getString("manual_profile_id", null).orEmpty()).id,
        rightHandDrive = prefs.getBoolean("right_hand_drive", false),
        exteriorTransparency = prefs.getInt("exterior_transparency", 55).coerceIn(0, 100),
        visualVariantIds = runCatching {
            val saved = JSONObject(prefs.getString("visual_variants", "{}").orEmpty())
            saved.keys().asSequence().filter { key -> VehicleCatalog.find(key).id == key }
                .mapNotNull { key -> saved.optString(key).takeIf(VehicleVisualCatalog::isSafeId)?.let { key to it } }.toMap()
        }.getOrDefault(emptyMap())
    )

    fun save(value: VehicleSelectionPreferences) {
        prefs.edit().putBoolean("auto_enabled", value.autoEnabled)
            .putString("manual_profile_id", VehicleCatalog.find(value.manualProfileId).id)
            .putBoolean("right_hand_drive", value.rightHandDrive)
            .putInt("exterior_transparency", value.exteriorTransparency.coerceIn(0, 100))
            .putString("visual_variants", JSONObject(value.visualVariantIds).toString()).apply()
    }
}

sealed class VehicleIdentityState {
    object Loading : VehicleIdentityState()
    data class Available(val identity: VehicleIdentity) : VehicleIdentityState()
    data class Unavailable(val reason: String = "차량 정보를 조회할 수 없습니다.") : VehicleIdentityState()
    data class PermissionDenied(val reason: String = "차량 정보 조회 권한이 없습니다.") : VehicleIdentityState()
}

enum class VehicleSelectionSource { AUTOMATIC, MANUAL, FALLBACK }

data class VehicleSelection(
    val profile: VehicleProfile,
    val source: VehicleSelectionSource,
    val identity: VehicleIdentity?,
    val message: String
)

object VehicleSelectionResolver {
    fun resolve(preferences: VehicleSelectionPreferences, state: VehicleIdentityState,
                installedProfileIds: Set<String>? = null): VehicleSelection {
        val manual = VehicleCatalog.find(preferences.manualProfileId)
        if (!preferences.autoEnabled) return VehicleSelection(
            manual, VehicleSelectionSource.MANUAL, null, "수동으로 선택한 차량입니다.")
        val identity = (state as? VehicleIdentityState.Available)?.identity
        val matched = identity?.let(VehicleCatalog::match)
        if (matched != null && (installedProfileIds == null || matched.id in installedProfileIds)) return VehicleSelection(
            matched, VehicleSelectionSource.AUTOMATIC, identity, "차량에서 조회한 정보로 선택했습니다.")
        if (identity != null && !identity.model.isNullOrBlank() && installedProfileIds != null) return VehicleSelection(
            manual, VehicleSelectionSource.FALLBACK, identity,
            "조회한 차종의 3D 자료가 없어 저장한 수동 선택을 표시합니다.")
        // A catalog entry is not necessary to use real public metadata. This label still uses
        // common concept geometry; no known model, equipment or cabin dimensions are inferred.
        if (identity != null && !identity.model.isNullOrBlank()) return VehicleSelection(
            VehicleProfile(VehicleCatalog.DETECTED_ID, identity.make.orEmpty(), identity.model, identity.modelYear),
            VehicleSelectionSource.AUTOMATIC, identity,
            "조회한 차량 정보를 사용합니다. 실내는 공통 컨셉 모델입니다.")
        val fallback = if (manual.id == VehicleCatalog.GENERIC_ID) "공통 실내를 사용합니다." else "저장한 수동 선택을 사용합니다."
        val reason = when (state) {
            VehicleIdentityState.Loading -> "차량 정보를 조회하는 동안 $fallback"
            is VehicleIdentityState.Available -> "차량에서 차종 정보를 제공하지 않아 $fallback"
            is VehicleIdentityState.Unavailable -> "${state.reason} $fallback"
            is VehicleIdentityState.PermissionDenied -> "${state.reason} $fallback"
        }
        return VehicleSelection(manual, VehicleSelectionSource.FALLBACK, identity, reason)
    }
}

/** Main-thread generation gate shared by service callbacks, reads and their timeout callbacks. */
internal class VehicleIdentityRequestGate {
    private var generation = 0L
    var closed: Boolean = false
        private set

    fun begin(): Long? = if (closed) null else ++generation
    fun isCurrent(request: Long): Boolean = !closed && request == generation
    fun invalidate() { generation++ }
    fun close() { closed = true; invalidate() }
}
