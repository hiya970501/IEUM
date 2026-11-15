package com.example.version1

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Only public model selection + a logical checksum, never exports keys or other profile settings. */
@RunWith(AndroidJUnit4::class)
class VehicleSelectionSnapshotDeviceTest {
    @Test fun captureSelectionWithoutStartingNativeRendering() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("selectionSnapshot") == "emulator")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.getSharedPreferences("recovery_restore", 0).getString("pending", "[]") == "[]")
        val p = VehicleSelectionStore(context).load()
        val canonical = listOf(p.autoEnabled, p.manualProfileId, p.rightHandDrive, p.exteriorTransparency,
            p.visualVariantIds.toSortedMap().entries.joinToString("|") { "${it.key}=${it.value}" }).joinToString("\n")
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
        File(context.filesDir, "vehicle-selection-checksum.json").writeText(JSONObject()
            .put("sha256", hash).put("autoEnabled", p.autoEnabled).put("manualProfileId", p.manualProfileId)
            .put("rightHandDrive", p.rightHandDrive)
            .put("visualVariantId", p.visualVariantIds[p.manualProfileId] ?: JSONObject.NULL)
            .put("nativeRenderingStarted", false).put("vehicleCommandsIssued", false).toString(2))
    }
}
