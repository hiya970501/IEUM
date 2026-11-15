package com.example.version1

import android.app.ActivityOptions
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ImageView
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Read-only product navigation. Reports geometry without saving text, names, health or keys. */
@RunWith(AndroidJUnit4::class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 30)
class GuidelineUiAuditDeviceTest {
    @Test fun auditVisibleTextAndTargetsAcrossSettings() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("guidelineUi") == "emulator")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val displayId = InstrumentationRegistry.getArguments().getString("formFactorDisplayId")?.toInt()
        val requirePreview = InstrumentationRegistry.getArguments().getString("formFactorRequirePreview") == "true"
        val previousFlags = automation.serviceInfo.flags
        if (displayId != null) automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        fun root(): AccessibilityNodeInfo? = if (displayId == null) automation.rootInActiveWindow else
            automation.windowsOnAllDisplays[displayId].orEmpty().sortedByDescending { it.layer }.asSequence().mapNotNull { it.root }
                .firstOrNull { it.packageName?.toString() == context.packageName }
        val prefs = ProtectedPreferences.get(context, "comfort_preferences")
        val original = prefs.all.toMap()
        val directory = DriverProfileStore(context).load()
        fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.packageName?.toString() == context.packageName && node.isVisibleToUser &&
                node.isEnabled && node.isClickable && node.text?.toString() == label) return node
            for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
            return null
        }
        var captureNavigationFailure: () -> Unit = {}
        fun click(label: String) {
            repeat(100) {
                find(root(), label)?.let {
                    check(it.performAction(AccessibilityNodeInfo.ACTION_CLICK)); instrumentation.waitForIdleSync()
                    if (label == "설정") {
                        val deadline = android.os.SystemClock.uptimeMillis() + 15000
                        while (find(root(), "차량 · 미리보기 설정") == null && android.os.SystemClock.uptimeMillis() < deadline)
                            Thread.sleep(10)
                        if(find(root(), "차량 · 미리보기 설정")==null) captureNavigationFailure()
                        check(find(root(), "차량 · 미리보기 설정") != null) { "Progress must resolve to the actual settings menu" }
                    }
                    if (label == "닫기") {
                        val deadline = android.os.SystemClock.uptimeMillis() + 15000
                        while (find(root(), "설정") == null && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(10)
                        if (find(root(), "설정") == null) captureNavigationFailure()
                        check(find(root(), "설정") != null) { "Closing progress must restore the actual home screen" }
                    }
                    return
                }
                if (it % 5 == 4) {
                    fun scroll(node: AccessibilityNodeInfo?): Boolean {
                        if (node == null) return false
                        if (node.packageName?.toString() == context.packageName && node.isScrollable && node.isVisibleToUser)
                            return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        for (index in 0 until node.childCount) if (scroll(node.getChild(index))) return true
                        return false
                    }
                    scroll(root())
                }
                Thread.sleep(100)
            }
            error("Expected enabled navigation missing: $label")
        }
        val rows = JSONArray()
        val observedNightModes = linkedSetOf<Int>()
        val auditContrast = InstrumentationRegistry.getArguments().getString("auditTextContrast") == "true"
        val auditTextMetrics = InstrumentationRegistry.getArguments().getString("auditTextMetrics") == "true"
        val selectionObservations = JSONArray()
        var lastStage = "launch"
        var allStagesCompleted = false
        var previewAtStart = false
        var previewAtEnd = false
        var submissionAccountingChecked = false
        val options = displayId?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
        val scenario = ActivityScenario.launch(MainActivity::class.java, options)
        val modalField = MainActivity::class.java.getDeclaredField("modal").apply { isAccessible = true }
        captureNavigationFailure = {
            // Structural state only: do not export displayed profile, health or credential text.
            val diagnostic = JSONObject().put("stage", lastStage).put("displayId", displayId)
            scenario.onActivity { activity ->
                val panel = modalField.get(activity) as? UiPanel
                diagnostic.put("modalPresent", panel != null).put("modalShowing", panel?.isShowing)
                    .put("windowFocus", activity.hasWindowFocus())
                    .put("actualDisplayId", activity.windowManager.defaultDisplay.displayId)
                    .put("focusRole", activity.currentFocus?.javaClass?.simpleName)
                for (field in listOf("foreground", "busy", "parked", "settingsOpening")) {
                    diagnostic.put(field, MainActivity::class.java.getDeclaredField(field)
                        .apply { isAccessible = true }.get(activity))
                }
                val button = MainActivity::class.java.getDeclaredField("settingsButton")
                    .apply { isAccessible = true }.get(activity) as View
                val bounds = Rect()
                diagnostic.put("settingsShown", button.isShown).put("settingsEnabled", button.isEnabled)
                    .put("settingsClickable", button.isClickable)
                    .put("settingsGloballyVisible", button.getGlobalVisibleRect(bounds))
                    .put("settingsBounds", bounds.toShortString())
                val scrolls = JSONArray()
                fun visit(view: View) {
                    if (view is ScrollView) scrolls.put(JSONObject().put("scrollY", view.scrollY)
                        .put("height", view.height).put("shown", view.isShown))
                    if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
                }
                visit(activity.window.decorView)
                diagnostic.put("homeScrolls", scrolls)
            }
            val accessibleRoot = root()
            diagnostic.put("accessibleRootPresent", accessibleRoot != null)
                .put("accessibleRootRole", accessibleRoot?.className?.toString())
                .put("accessibleRootChildCount", accessibleRoot?.childCount)
            File(context.filesDir, "guideline-navigation-failure.json").writeText(diagnostic.toString(2))
        }
        fun awaitPreview() {
            val deadline = android.os.SystemClock.elapsedRealtime() + 60000
            var presented = false
            while (!presented && android.os.SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { activity ->
                    val scene = MainActivity::class.java.getDeclaredField("scene").apply { isAccessible = true }.get(activity)!!
                    val gate = scene.javaClass.getDeclaredField("presentation").apply { isAccessible = true }.get(scene) as PreviewPresentationGate
                    check(!gate.failed) { "UI audit actual 3D preview failed at $lastStage" }
                    presented = !gate.covered
                    if (presented) {
                        val viewer = scene.javaClass.getDeclaredField("modelViewer").apply { isAccessible = true }.get(scene)
                            as com.google.android.filament.utils.BudgetModelViewer
                        val accepted = scene.javaClass.getDeclaredField("acceptedFrameCount").apply { isAccessible = true }.getLong(scene)
                        assertTrue("A presented model must have real submissions", accepted > 0)
                        assertEquals("One accepted callback must account for one native submission", accepted, viewer.submittedFrameCount)
                        submissionAccountingChecked = true
                    }
                }
                if (!presented) Thread.sleep(250)
            }
            assertTrue("UI audit did not present a real 3D frame at $lastStage", presented)
        }
        fun capture(screen: String, bottom: Boolean = false) {
            lastStage = "$screen:${if (bottom) "bottom" else "top"}"
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val dialog = modalField.get(activity) as? UiPanel
                val decor = dialog?.contentView ?: activity.window.decorView
                observedNightModes += activity.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
                val density = activity.resources.displayMetrics.density
                fun visit(view: View, path: String) {
                    val visible = Rect()
                    if (view.isShown && view.getGlobalVisibleRect(visible)) {
                        if (view is TextView && view.text.isNotEmpty()) {
                            val fm = view.paint.fontMetrics
                            val row = JSONObject().put("screen", screen).put("bottom", bottom).put("path", path)
                                .put("role", view.javaClass.simpleName).put("lines", view.lineCount)
                                .put("textSp", view.textSize / activity.resources.displayMetrics.scaledDensity)
                                .put("fontBoxPixels", fm.descent - fm.ascent)
                                .put("overThreeLines", view.lineCount > 3)
                            if (auditContrast) {
                                row.put("contrast", VisibleTextContrastAudit.inspect(view))
                                if (view is android.widget.Button && view.isEnabled &&
                                    view.background is android.graphics.drawable.StateListDrawable) {
                                    val drawable = view.background
                                    val originalState = drawable.state.copyOf()
                                    try {
                                        for ((name, state) in listOf("pressedContrast" to android.R.attr.state_pressed,
                                            "focusedContrast" to android.R.attr.state_focused)) {
                                            drawable.state = originalState.filter {
                                                it != android.R.attr.state_pressed && it != android.R.attr.state_focused
                                            }.toIntArray() + state
                                            row.put(name, VisibleTextContrastAudit.inspect(view)
                                                .put("scope", "production background state fixture; not physical press/panel certification"))
                                        }
                                    } finally { drawable.state = originalState }
                                }
                            }
                            if (auditTextMetrics && view.text.any(Char::isLetterOrDigit)) {
                                val ink = Rect()
                                view.paint.getTextBounds(view.text.toString(), 0, view.text.length, ink)
                                val metrics = activity.resources.displayMetrics
                                val required = PleosTypography.requiredInkPixels(3f, metrics.ydpi, metrics.density)
                                val layout = view.layout
                                var ellipsizedLines = 0
                                if (layout != null) for (line in 0 until layout.lineCount)
                                    if (layout.getEllipsisCount(line) > 0) ellipsizedLines++
                                row.put("ink", JSONObject().put("actualHeightPx", ink.height())
                                    .put("requiredThreeMmPx", required).put("reportedYDpi", metrics.ydpi)
                                    .put("belowThreeMmProxy", ink.height() < required)
                                    .put("below18Px", ink.height() < 18).put("ellipsizedLines", ellipsizedLines)
                                    .put("physicalMillimetersVerified", false))
                            }
                            rows.put(row)
                        }
                        if (auditContrast && view is ImageView && view.drawable != null)
                            rows.put(JSONObject().put("screen", screen).put("bottom", bottom).put("path", path)
                                .put("role", "imageContrast").put("iconContrast", VisibleTextContrastAudit.icon(view)))
                        if (view.isClickable) rows.put(JSONObject().put("screen", screen).put("bottom", bottom)
                            .put("path", path).put("role", "touchTarget")
                            .put("widthDp", view.width / density).put("heightDp", view.height / density)
                            .put("visibleWidthDp", visible.width() / density).put("visibleHeightDp", visible.height() / density)
                            .put("partiallyVisible", visible.width() < view.width || visible.height() < view.height)
                            .put("under48Dp", view.width / density < 48 || view.height / density < 48))
                    }
                    if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), "$path/$i")
                }
                visit(decor, "root")
            }
        }
        try {
            if (displayId != null) scenario.onActivity { assertEquals(displayId, it.windowManager.defaultDisplay.displayId) }
            if (requirePreview) { awaitPreview(); previewAtStart = true }
            capture("home")
            var originalBackgroundAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            scenario.onActivity { originalBackgroundAccessibility = it.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).importantForAccessibility }
            click("설정"); capture("settings")
            scenario.onActivity {
                assertEquals("Modal must exclude background controls from accessibility navigation",
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                    it.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).importantForAccessibility)
            }
            click("닫기")
            scenario.onActivity { assertEquals(originalBackgroundAccessibility,
                it.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).importantForAccessibility) }
            val source = runCatching { MusicSource.valueOf(prefs.getString(directory.selected.preferencePrefix + "music_source", "AMBIENT")!!) }
                .getOrDefault(MusicSource.AMBIENT)
            var preset = SoundPreset.WAVES
            scenario.onActivity { activity ->
                preset = MainActivity::class.java.getDeclaredField("soundPreset").apply { isAccessible = true }
                    .get(activity) as SoundPreset
            }
            val routes = listOf(
                "vehicle" to "차량 · 미리보기 설정",
                "profiles" to "운전자 프로필 · ${directory.selected.name}",
                "zeroTap" to "제로탭 · 휴대폰 거치 시 기본 설정",
                "activity" to "운동 기록 자동 / 수동 선택",
                "comfort" to "내 공조 선호 조정",
                "learning" to "학습·자동 모드 · 날씨",
                "music" to "음악 · ${if (source == MusicSource.AMBIENT) preset.title else source.title}",
                "data" to "워치 연결 · 데이터 관리",
                "appInfo" to "앱 정보 · 업데이트"
            )
            for ((screen, label) in routes) {
                lastStage = "$screen:openSettings"
                click("설정"); click(label); capture(screen)
                if (screen == "appInfo" && context.getString(R.string.update_metadata_url).isBlank()) {
                    click("업데이트 확인")
                    val deadline=android.os.SystemClock.uptimeMillis()+15000
                    var unavailable=false
                    while(!unavailable && android.os.SystemClock.uptimeMillis()<deadline){
                        unavailable=root()?.findAccessibilityNodeInfosByText("업데이트 정보를 확인할 수 없어요.")
                            ?.any { it.packageName?.toString()==context.packageName && it.isVisibleToUser &&
                                it.text?.toString()=="업데이트 정보를 확인할 수 없어요." } == true
                        if(!unavailable) Thread.sleep(10)
                    }
                    check(unavailable) { "Unavailable update metadata must not be presented as up-to-date success" }
                    capture("appInfoUnavailable")
                }
                if (screen == "comfort") {
                    for (choice in listOf("25°C", "3 단계", "더워요")) {
                        click(choice)
                        // Accessibility snapshots can remain cached after the main-thread
                        // click has finished. Observe eventual semantic exposure separately
                        // from the unchanged real-input 250ms timing test.
                        val started = android.os.SystemClock.uptimeMillis()
                        var selected = find(root(), choice)?.isSelected == true
                        while (!selected && android.os.SystemClock.uptimeMillis() - started < 1000) {
                            Thread.sleep(10)
                            selected = find(root(), choice)?.isSelected == true
                        }
                        selectionObservations.put(JSONObject().put("choiceIndex", selectionObservations.length())
                            .put("selectedExposed", selected).put("observationMs", android.os.SystemClock.uptimeMillis() - started))
                        assertTrue("Choice must expose selected state: $choice", selected)
                    }
                    capture("comfortDraft")
                }
                scenario.onActivity { activity ->
                    val dialog = modalField.get(activity) as? UiPanel ?: error("Panel missing")
                    fun scroll(view: View): Boolean {
                        if (view is ScrollView) { view.fullScroll(View.FOCUS_DOWN); return true }
                        if (view is ViewGroup) for (i in 0 until view.childCount) if (scroll(view.getChildAt(i))) return true
                        return false
                    }
                    scroll(dialog.contentView)
                }
                instrumentation.waitForIdleSync(); capture(screen, bottom = true)
                click(if (screen == "comfort") "취소" else "닫기")
            }
            assertEquals(original, prefs.all)
            assertEquals(directory, DriverProfileStore(context).load())
            if (requirePreview) { lastStage = "finalPreview"; awaitPreview(); previewAtEnd = true }
            allStagesCompleted = true
        } finally {
            File(context.filesDir, "guideline-ui-audit.json").writeText(JSONObject()
                .put("allStagesCompleted", allStagesCompleted).put("lastStage", lastStage)
                .put("previewRequired", requirePreview).put("previewAtStart", previewAtStart).put("previewAtEnd", previewAtEnd)
                .put("nativeSubmissionAccountingChecked", submissionAccountingChecked)
                .put("readOnlyPreferencesPreserved", original == prefs.all)
                .put("driverDirectoryPreserved", directory == DriverProfileStore(context).load())
                .put("selectionObservations", selectionObservations)
                .put("textContrastRequested", auditContrast)
                .put("textMetricsRequested", auditTextMetrics)
                .put("observedNightModes", JSONArray(observedNightModes.toList()))
                .put("physicalMillimetersVerified", false).put("records", rows).toString(2))
            val closed = java.util.concurrent.atomic.AtomicBoolean(false)
            val cleanupStage = java.util.concurrent.atomic.AtomicReference("scenario.closeAndAwaitNativeRelease")
            val testThread = Thread.currentThread()
            val closeStacks = JSONArray()
            val watcher = Thread({
                repeat(10) {
                    Thread.sleep(1000)
                    if (closed.get()) return@Thread
                    closeStacks.put(JSONObject().put("afterSeconds", it + 1)
                        .put("cleanupStage", cleanupStage.get())
                        .put("testStack", testThread.stackTrace.joinToString("\n"))
                        .put("mainStack", android.os.Looper.getMainLooper().thread.stackTrace.joinToString("\n")))
                    File(context.filesDir, "guideline-close-stack.json").writeText(closeStacks.toString(2))
                }
            }, "OwnedCloseDiagnostic")
            watcher.start()
            try {
                try { scenario.closeAndAwaitNativeRelease() } finally {
                    cleanupStage.set("restoreAccessibilityFlags")
                    automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
                }
                cleanupStage.set("complete")
            } finally {
                closed.set(true)
                watcher.join(1500)
                File(context.filesDir, "guideline-cleanup.json").writeText(JSONObject()
                    .put("stage", cleanupStage.get()).put("nativeReady", NativeRenderLifecycle.nativeReady).toString(2))
            }
        }
    }
}
