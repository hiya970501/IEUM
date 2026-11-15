package com.example.version1

import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Owned synthetic metadata on actual production font/button factories.
 * This is a layout fixture, not an external provider, playback or acoustic test. */
@RunWith(AndroidJUnit4::class)
class MusicTextLayoutDeviceTest {
    @Test fun narrowMusicLabelsKeepPlaybackVisibleAndFullBoundedAccessibleTitle() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("musicTextFixture") == "emulator")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = ProtectedPreferences.get(context, "comfort_preferences")
        val original = prefs.all.toMap()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val records = JSONArray()
        var panel: LinearLayout? = null
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun await(condition: (MainActivity) -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 60000
            while (SystemClock.uptimeMillis() < deadline) {
                var done = false; scenario.onActivity { done = condition(it) }
                if (done) return
                Thread.sleep(100)
            }
            error("Owned text layout or actual preview not ready")
        }
        try {
            await { activity ->
                val scene = field("scene").get(activity) as PremiumCabinView
                val presentation = scene.javaClass.getDeclaredField("presentation").apply { isAccessible = true }
                    .get(scene) as PreviewPresentationGate
                check(!presentation.failed)
                field("foreground").getBoolean(activity) && !presentation.covered && field("parked").getBoolean(activity)
            }
            val cases = listOf("A".repeat(200), List(25) { "i" }.joinToString(" "),
                "あ".repeat(80), "音楽".repeat(40), "😀".repeat(100))
            for ((index, raw) in cases.withIndex()) {
                val canonical = MusicText.title(raw)
                val label = MusicDisplayText.title(canonical, true)
                val summary = MusicDisplayText.status("음악 앱 연결됨", canonical, "재생 중")
                lateinit var status: TextView; lateinit var entry: Button
                var originalStatusPixels = 0f; var originalEntryPixels = 0f
                scenario.onActivity { activity ->
                    assertFalse(field("active").getBoolean(activity)); assertFalse(field("busy").getBoolean(activity))
                    val density = activity.resources.displayMetrics.density
                    val palette = PleosPalette.forNight(activity.resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
                    val textFactory = MainActivity::class.java.getDeclaredMethod("text", String::class.java,
                        Float::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                        .apply { isAccessible = true }
                    val buttonFactory = MainActivity::class.java.getDeclaredMethod("button", String::class.java,
                        kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
                    status = (textFactory.invoke(activity, summary, 24f, palette.text, false) as TextView).apply {
                        maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                        contentDescription = MusicDisplayText.accessibleStatus("음악 앱 연결됨", canonical, "재생 중")
                    }
                    entry = (buttonFactory.invoke(activity, label, {}) as Button).apply {
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END; contentDescription = canonical
                    }
                    originalStatusPixels = status.textSize; originalEntryPixels = entry.textSize
                    val host = activity.findViewById<ViewGroup>(android.R.id.content)
                    panel = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL; setBackgroundColor(palette.surface)
                        setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
                        addView(status, LinearLayout.LayoutParams(-1, -2))
                        addView(entry, LinearLayout.LayoutParams(-1, (80 * density).toInt()))
                    }
                    host.addView(panel, FrameLayout.LayoutParams((484 * density).toInt(), -2, Gravity.CENTER))
                }
                await { status.width > 0 && status.layout != null && entry.layout != null }
                scenario.onActivity { activity ->
                    val visible = Rect(); assertTrue(status.isShown && status.getGlobalVisibleRect(visible))
                    assertTrue(status.lineCount in 1..3); assertEquals(1, entry.lineCount)
                    val playbackLine = status.layout.getLineForOffset(summary.indexOf("재생 중"))
                    assertTrue("Playback must precede variable title", playbackLine <= 1)
                    assertEquals("Playback line cannot be ellipsized", 0, status.layout.getEllipsisCount(playbackLine))
                    assertEquals(originalStatusPixels, status.textSize, 0f); assertEquals(originalEntryPixels, entry.textSize, 0f)
                    assertEquals(canonical, entry.contentDescription.toString())
                    assertTrue(status.contentDescription.toString().endsWith(canonical))
                    val maxCharacters = if (index in 2..3) 30 else 80
                    assertTrue(label.codePointCount(0, label.length) <= maxCharacters)
                    val ink = Rect(); status.paint.getTextBounds("재생 중", 0, 4, ink)
                    assertTrue(ink.height() >= PleosTypography.requiredInkPixels(3f,
                        activity.resources.displayMetrics.ydpi, activity.resources.displayMetrics.density))
                    records.put(JSONObject().put("caseIndex", index).put("panelWidthDp", 484)
                        .put("statusLines", status.lineCount).put("entryLines", entry.lineCount)
                        .put("playbackLine", playbackLine).put("playbackEllipsized", false)
                        .put("displayCodepoints", label.codePointCount(0, label.length))
                        .put("metadataAccessible", true).put("fontShrunk", false).put("actualInkPx", ink.height()))
                    activity.findViewById<ViewGroup>(android.R.id.content).removeView(panel); panel = null
                }
            }
            assertEquals(original, prefs.all)
            File(context.filesDir, "music-text-layout-result.json").writeText(JSONObject()
                .put("passed", true).put("actualProductionFontAndButtons", true)
                .put("syntheticOwnedMetadata", true).put("realProviderOrAudioVerified", false)
                .put("preferencesPreserved", true).put("vehicleCommandsIssued", false)
                .put("records", records).toString(2))
        } finally {
            scenario.onActivity { activity -> panel?.let { activity.findViewById<ViewGroup>(android.R.id.content).removeView(it) } }
            scenario.closeAndAwaitNativeRelease()
        }
    }
}
