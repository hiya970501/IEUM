package com.example.version1

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

/** Opt-in real Android UI flow. Long text is entered with accessibility SET_TEXT, like paste. */
@RunWith(AndroidJUnit4::class)
class PortableProfileUiDeviceTest {
    @Test fun previewCancelImportExportAndDeleteOwnedProfile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("profileUi") == "emulator")
        val context = instrumentation.targetContext
        val directory = DriverProfileStore(context)
        val beforeDirectory = directory.load()
        check(beforeDirectory.profiles.size <= DriverProfileSettings.MAX_PROFILES-2)
        val name = "QA${java.util.UUID.randomUUID().toString().take(8)}"
        check(beforeDirectory.profiles.none { it.name == name })
        val prefs = ProtectedPreferences.get(context, "comfort_preferences")
        val before = prefs.all.toMap()
        val fixture = PortableDriverProfile(name, listOf(
            PortableActivityPreference(ActivityKind.RUN, 22f, 2, ThermalFeeling.COLD, SoundPreset.OFF, SoundPreset.RAIN),
            PortableActivityPreference(ActivityKind.WATER, 25f, 3, ThermalFeeling.COLD, SoundPreset.RAIN, SoundPreset.RAIN),
            PortableActivityPreference(ActivityKind.HIKE, 26f, 0, ThermalFeeling.COLD, SoundPreset.FOREST, SoundPreset.RAIN)), MusicSource.VLC)
        val code = PortableDriverProfileCodec.encode(fixture)
        val automation = instrumentation.uiAutomation
        fun walk(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) walk(node.getChild(i), predicate)?.let { return it }
            return null
        }
        fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? = walk(automation.rootInActiveWindow, predicate)
        fun waitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
            repeat(120) { find(predicate)?.let { return it }; Thread.sleep(100) }
            error("UI control did not become available")
        }
        fun click(text: String) {
            repeat(5) {
                val node = find { it.text?.toString() == text && it.isEnabled }
                if (node != null) {
                    var target: AccessibilityNodeInfo = node
                    while (!target.isClickable && target.parent != null) target = requireNotNull(target.parent)
                    check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Required UI click rejected" }
                    Thread.sleep(400); return
                }
                if (it == 0) Thread.sleep(1500)
                else find { it.isScrollable && it.packageName?.toString() == context.packageName }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                Thread.sleep(400)
            }
            error("Required UI control missing")
        }
        fun profiles() {
            waitNode { it.text?.toString()=="설정" && it.isVisibleToUser && it.isClickable && it.isEnabled }
            click("설정")
            val selected = directory.load().selected.name
            click("운전자 프로필 · $selected")
        }
        fun enterCode() {
            click("다른 기기의 선호 가져오기")
            val input = waitNode { it.className?.toString() == "android.widget.EditText" }
            check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code)
            }))
            waitNode { it.className?.toString() == "android.widget.EditText" && it.text?.toString() == code }
            click("내용 확인")
            waitNode { it.text?.toString() == "가져올 선호 확인" }
        }
        var created: DriverProfile? = null
        var duplicateCreated: DriverProfile? = null
        var completed = false
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun home() {
            repeat(12) {
                if(find { it.text?.toString()=="설정" && it.isVisibleToUser && it.isEnabled && it.isClickable }!=null) return
                val exit=find { it.text?.toString() in setOf("취소","닫기") && it.isVisibleToUser && it.isEnabled && it.isClickable }
                if(exit!=null) check(exit.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                Thread.sleep(300)
            }
            error("Home did not return during owned fixture cleanup")
        }
        fun restoreOriginalFromProfileList() {
            if(directory.load().selectedProfileId!=beforeDirectory.selectedProfileId) click(beforeDirectory.selected.name)
            check(directory.load().selectedProfileId==beforeDirectory.selectedProfileId)
        }
        fun preferencesRestored():Boolean {
            repeat(150) { if(prefs.all==before) return true;Thread.sleep(100) }
            return false
        }
        try {
            waitNode { it.text?.toString()=="자동 Mode" && it.isEnabled && !it.isSelected }
            profiles(); enterCode()
            assertTrue("Existing preferences changed",before==prefs.all)
            assertTrue("Existing profile directory changed",beforeDirectory==directory.load())
            click("취소")
            assertTrue("Cancel changed preferences",before==prefs.all)
            profiles(); enterCode(); click("새 프로필로 가져오기")
            val imported = directory.load()
            created = imported.profiles.single { it.id !in beforeDirectory.profiles.map { original -> original.id } }
            assertEquals(name, created.name)
            assertEquals(beforeDirectory.selectedProfileId, imported.selectedProfileId)
            val store = PortableDriverProfileStore(context, directory)
            assertEquals(fixture, store.snapshot(created))
            before.forEach { (key, value) -> assertEquals(value, prefs.all[key]) }
            assertFalse(prefs.contains(created.preferencePrefix + "learning_consent"))
            click("다른 기기의 선호 가져오기")
            val duplicateInput = waitNode { it.className?.toString() == "android.widget.EditText" }
            check(duplicateInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code)
            }))
            click("내용 확인")
            waitNode { it.text?.toString()?.contains("이 기기에서는 ‘$name (2)’") == true }
            click("새 프로필로 가져오기")
            val afterDuplicate = directory.load()
            duplicateCreated = afterDuplicate.profiles.single { it.id !in imported.profiles.map { previous -> previous.id } }
            assertEquals("$name (2)", duplicateCreated.name)
            assertEquals(beforeDirectory.selectedProfileId, afterDuplicate.selectedProfileId)
            assertEquals(fixture, store.snapshot(created))
            assertEquals(fixture.copy(name = "$name (2)"), store.snapshot(duplicateCreated))
            click("$name (2)"); click("현재 프로필 삭제")
            assertTrue("Deleting the selected owned profile must use the documented first-profile fallback",
                imported.copy(selectedProfileId=imported.profiles.first().id)==directory.load())
            duplicateCreated = null
            restoreOriginalFromProfileList()
            assertTrue("Original selection was not restored through UI",imported==directory.load())
            click(name)
            click("내 선호 내보내기")
            waitNode { it.contentDescription?.toString() == "개인 선호 설정 코드 QR" }
            instrumentation.waitForIdleSync()
            val image = automation.takeScreenshot() ?: error("Screenshot unavailable")
            File(context.filesDir, "portable-profile-qr.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }; image.recycle()
            click("닫기")
            profiles(); click("현재 프로필 삭제")
            created = null
            restoreOriginalFromProfileList()
            assertTrue("Original profile directory must remain unchanged",beforeDirectory==directory.load())
            assertTrue("Original preferences and durable cleanup must settle",preferencesRestored())
            completed = true
            File(context.filesDir, "portable-profile-ui-result.json").writeText(JSONObject()
                .put("previewCancelUnchanged", true).put("importedPreferencesMatch", true)
                .put("existingPreferencesPreserved", true).put("importDoesNotAutoSelect", true)
                .put("consentNotTransferred", true).put("qrScreenshotCaptured", true)
                .put("duplicateNameDisambiguated", true).put("duplicateImportDoesNotOverwrite", true)
                .put("previewNameMatchesSaved", true)
                .put("ownedProfileDeleted", true).put("inputMethod", "Android accessibility SET_TEXT").toString(2))
        } finally {
            var cleanupFailure:Throwable?=null
            try {
                // Use the real UI even after an assertion fails. Never rewrite
                // the user's directory or preferences to manufacture a pass.
                for(profile in listOfNotNull(duplicateCreated,created)) {
                    if(directory.load().profiles.any { it.id==profile.id }) {
                        home();profiles()
                        if(directory.load().selectedProfileId!=profile.id) click(profile.name)
                        click("현재 프로필 삭제")
                        check(directory.load().profiles.none { it.id==profile.id })
                    }
                }
                home()
                if(directory.load().selectedProfileId!=beforeDirectory.selectedProfileId) {
                    profiles();restoreOriginalFromProfileList();home()
                }
                check(directory.load()==beforeDirectory && preferencesRestored())
            }catch(error:Throwable){cleanupFailure=error}
            File(context.filesDir,"portable-profile-cleanup.json").writeText(JSONObject()
                .put("ownedUiCleanupComplete",cleanupFailure==null)
                .put("originalDirectoryRestored",directory.load()==beforeDirectory)
                .put("originalPreferencesRestored",prefs.all==before)
                .put("failureType",cleanupFailure?.javaClass?.simpleName?:JSONObject.NULL).toString(2))
            scenario.closeAndAwaitNativeRelease()
            if(completed && cleanupFailure!=null) throw AssertionError("Owned UI cleanup failed",cleanupFailure)
        }
    }
}
