package com.example.version1

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Public owner-only exit diagnostics, no root, global bugreport, or user setting dump. */
@RunWith(AndroidJUnit4::class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 30)
class OwnedAnrTraceDeviceTest {
    @Test fun collectOwnRecentAnrMainAndRendererStacks() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ownedAnrTrace") == "emulator")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ActivityManager::class.java)
        val rows = JSONArray()
        for (exit in manager.getHistoricalProcessExitReasons(context.packageName, 0, 16)) {
            val text = exit.traceInputStream?.use { String(it.readNBytes(2 * 1024 * 1024), Charsets.UTF_8) }
            val threads = JSONArray()
            if (text != null) {
                for (block in text.split(Regex("\\r?\\n\\r?\\n"))) {
                    val header = block.lineSequence().firstOrNull().orEmpty()
                    if (block.contains(Regex("(?m)^\"(?:main|RenderThread|FEngine[^\"]*|Filament[^\"]*)\""))) threads.put(block.take(20000))
                }
            }
            rows.put(JSONObject().put("pid", exit.pid).put("timestamp", exit.timestamp)
                .put("reason", exit.reason).put("pssKiB", exit.pss).put("rssKiB", exit.rss)
                .put("traceAvailable", text != null).put("selectedThreadStacks", threads))
        }
        File(context.filesDir, "owned-anr-diagnostics.json").writeText(JSONObject()
            .put("source", "ApplicationExitInfo for this package only").put("records", rows).toString(2))
    }
}
