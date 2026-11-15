package com.example.version1

import android.content.Context
import android.content.pm.ApplicationInfo
import org.json.JSONObject
import java.io.File

/** A verified installed visualization version, independent of a connected car's identity. */
data class VehicleVisualVariant(
    val profileId: String,
    val assetId: String,
    val label: String,
    val modelYears: Set<Int> = emptySet(),
    val sourceHandedness: String = "unknown",
    val fullVehicle: Boolean = false,
    val sourcePage: String = "",
    val isDefault: Boolean = false,
    val interiorDetail: String = "detailed"
)

object VehicleVisualCatalog {
    fun isSafeId(value: String) = value.matches(Regex("[a-z0-9_]{1,100}"))

    /** Only genuine, installed full-car previews are offered as manual choices. */
    fun selectableProfiles(variants: List<VehicleVisualVariant>): List<VehicleProfile> {
        val installed = variants.filter { it.fullVehicle }.map { it.profileId }.toSet()
        return VehicleCatalog.profiles.filter { it.id != VehicleCatalog.GENERIC_ID && it.id in installed }
    }

    /** Research resources are optional local files; release APKs do not load this catalog. */
    fun load(context: Context): List<VehicleVisualVariant> {
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return emptyList()
        val directory = File(context.filesDir, "vehicle-previews")
        val catalog = File(directory, "catalog.json")
        if (!catalog.isFile || catalog.length() > 512_000) return emptyList()
        return runCatching {
            parse(catalog.readText()).filter { variant ->
                File(directory, "${variant.assetId}.json").isFile &&
                    (File(directory, "${variant.assetId}.glb").isFile ||
                        File(directory, "${variant.assetId}.lhd.glb").isFile ||
                        File(directory, "${variant.assetId}.rhd.glb").isFile)
            }
        }.getOrDefault(emptyList())
    }

    fun parse(json: String): List<VehicleVisualVariant> {
        val document = JSONObject(json)
        require(document.getInt("schemaVersion") == 1)
        val variants = document.getJSONArray("variants")
        require(variants.length() <= 300)
        return (0 until variants.length()).mapNotNull { index ->
            val item = variants.getJSONObject(index)
            val profileId = item.optString("profileId")
            val assetId = item.optString("assetId")
            val label = item.optString("label").trim().take(120)
            if (!isSafeId(profileId) || VehicleCatalog.find(profileId).id != profileId ||
                !isSafeId(assetId) || label.isBlank()) return@mapNotNull null
            val years = item.optJSONArray("modelYears")
            val verifiedYears = if (years == null) emptySet() else (0 until years.length())
                .mapNotNull { years.optInt(it).takeIf { year -> year in 1980..2100 } }.toSet()
            VehicleVisualVariant(profileId, assetId, label, verifiedYears,
                item.optString("sourceHandedness", "unknown").takeIf { it in setOf("left", "right") } ?: "unknown",
                item.optBoolean("fullVehicle"), item.optString("sourcePage"), item.optBoolean("isDefault"),
                item.optString("interiorDetail", "detailed"))
        }.distinctBy { it.assetId }
    }

    fun resolve(variants: List<VehicleVisualVariant>, profileId: String, selectedAssetId: String?,
                modelYear: Int? = null): VehicleVisualVariant? {
        val available = variants.filter { it.profileId == profileId }
        return available.firstOrNull { it.assetId == selectedAssetId }
            ?: modelYear?.let { year -> available.firstOrNull { year in it.modelYears } }
            ?: available.firstOrNull { it.isDefault }
            ?: available.firstOrNull()
    }
}
