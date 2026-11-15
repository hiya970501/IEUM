package com.example.version1

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.session.PlaybackState
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.sin

/** Real cross-UID file grant and independent VLC session; never browses personal music. */
@RunWith(AndroidJUnit4::class)
class OwnedCacheVlcDeviceTest {
    @Test fun exactOwnedUriPlaysPausesAndResumesWithoutStoragePermission() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("ownedCacheVlcFixture") == "emulator")
        val context = instrumentation.targetContext
        check(context.packageName == "com.example.version1" && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val component = ExternalMusic(context).browserComponent(MusicSource.VLC) ?: error("VLC exported service required")
        val stem = "Afterglow_QA_" + UUID.randomUUID().toString().replace("-", "")
        val directory = File(context.cacheDir, "owned-audio-fixtures").canonicalFile.apply { mkdirs() }
        val file = File(directory, "$stem.wav").canonicalFile
        check(file.parentFile == directory && !file.exists())
        val frames = 40 * 16_000
        val bytes = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + frames * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(frames * 2)
            repeat(frames) { sample ->
                val fade = minOf(1.0, sample / 1600.0, (frames - sample) / 1600.0)
                putShort((220 * sin(2 * PI * 440 * sample / 16_000) * fade).toInt().toShort())
            }
        }.array()
        file.outputStream().use { it.write(bytes); it.fd.sync() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.owned.audio", file)
        val output = File(context.filesDir, "owned-cache-vlc-result.json")
        val receipt = JSONObject().put("passed", false).put("synthetic", true).put("fixtureName", file.name)
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            .put("bytes", bytes.size).put("exactUriReadGrant", true).put("broadStoragePermissionGranted", false)
            .put("vehicleCommandsIssued", false).put("acousticQualityVerified", false).put("cleanupConfirmed", false)
        output.writeText(receipt.toString()) // ownership before any playback
        val observations = CopyOnWriteArrayList<MusicSessionBrowser.Snapshot>()
        var client: MusicSessionBrowser? = null
        var automatic: AutoMusicResume? = null
        var paused = false
        suspend fun awaitOwned(state: Int) = withTimeout(12_000) {
            while (observations.lastOrNull()?.let { it.connected && it.title.contains(stem) && it.state == state } != true) delay(50)
            observations.last()
        }
        try {
            instrumentation.runOnMainSync {
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setComponent(ComponentName("org.videolan.vlc", "org.videolan.vlc.StartActivity"))
                    setDataAndType(uri, "audio/wav")
                    clipData = ClipData.newUri(context.contentResolver, "IEUM synthetic audio", uri)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
                client = MusicSessionBrowser(context, component) { observations.add(it) }.also { it.connect() }
            }
            receipt.put("stage", "actual-owned-playback")
            awaitOwned(PlaybackState.STATE_PLAYING)
            instrumentation.runOnMainSync { assertTrue(client!!.pause()) }
            val before = awaitOwned(PlaybackState.STATE_PAUSED)
            instrumentation.runOnMainSync { assertTrue(client!!.resume()) }
            awaitOwned(PlaybackState.STATE_PLAYING)
            delay(1500)
            instrumentation.runOnMainSync { assertTrue(client!!.pause()) }
            val after = awaitOwned(PlaybackState.STATE_PAUSED); paused = true
            assertTrue("Actual position advances", after.positionMillis > before.positionMillis)
            val resumed = CompletableDeferred<String>()
            instrumentation.runOnMainSync {
                automatic = AutoMusicResume(context, component, { true }) { resumed.complete(it) }.also { it.start() }
            }
            assertEquals("음악 앱이 재생 중임을 확인했어요.", withTimeout(10_000) { resumed.await() })
            paused = false
            awaitOwned(PlaybackState.STATE_PLAYING)
            instrumentation.runOnMainSync { assertTrue(client!!.pause()) }
            awaitOwned(PlaybackState.STATE_PAUSED); paused = true
            receipt.put("passed", true).put("actualOwnedPlayback", true).put("pauseResumeAcknowledged", true)
                .put("positionAdvanced", true).put("automaticResumeAcknowledged", true)
        } catch (failure: Throwable) {
            receipt.put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally {
            try {
                val owned = observations.lastOrNull()?.takeIf { it.title.contains(stem) }
                if (owned?.state == PlaybackState.STATE_PLAYING) {
                    instrumentation.runOnMainSync { client?.pause() }
                    awaitOwned(PlaybackState.STATE_PAUSED); paused = true
                }
                instrumentation.runOnMainSync { automatic?.close(); client?.close() }
                // Never delete/revoke a source whose active playback is unknown.
                if (paused) {
                    context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    check(file.delete()); receipt.put("cleanupConfirmed", true)
                }
            } finally { output.writeText(receipt.toString()) }
        }
    }
}
