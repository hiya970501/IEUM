package com.example.version1

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import java.net.URI

enum class MusicSource(val title: String, val packages: List<String>) {
    AMBIENT("차량 내 환경음", emptyList()),
    VEHICLE("차량 음악 앱", listOf("ai.umos.mediaapp", "com.android.music")),
    VLC("VLC", listOf("org.videolan.vlc")),
    YOUTUBE_MUSIC("YouTube Music", listOf("com.google.android.apps.youtube.music")),
    MELON("멜론", listOf("com.iloen.melon"));

    fun acceptsLink(value: String): Boolean = runCatching {
        val uri = URI(value)
        val hosts = when (this) {
            YOUTUBE_MUSIC -> setOf("music.youtube.com", "www.youtube.com", "youtube.com", "youtu.be")
            MELON -> setOf("melon.com", "www.melon.com", "m2.melon.com", "kko.to")
            else -> emptySet()
        }
        uri.scheme == "https" && uri.host?.lowercase() in hosts && uri.userInfo == null && uri.port == -1
    }.getOrDefault(false)
}

internal data class MusicBrowserCandidate(val packageName: String, val className: String,
    val enabled: Boolean, val exported: Boolean)

enum class MusicAppAccess(val label: String) {
    OPENABLE("앱 열기 가능"), NOT_INSTALLED("앱 없음"), UNSUPPORTED("열기 미지원")
}

internal fun musicAppAccess(openable: Boolean, present: Boolean) = when {
    openable -> MusicAppAccess.OPENABLE
    present -> MusicAppAccess.UNSUPPORTED
    else -> MusicAppAccess.NOT_INSTALLED
}

internal fun selectMusicBrowser(source: MusicSource, candidates: List<MusicBrowserCandidate>): MusicBrowserCandidate? =
    candidates.filter { it.packageName in source.packages && it.enabled && it.exported }
        .sortedWith(compareBy<MusicBrowserCandidate> { source.packages.indexOf(it.packageName) }.thenBy { it.className })
        .firstOrNull()

/** Handoff to installed official players; no account scraping, playback claims or health upload. */
class ExternalMusic(private val context: Context) {
    private val accessCache = mutableMapOf<MusicSource, Pair<Long, MusicAppAccess>>()

    /** UI observation only. Opening an app still resolves a fresh intent below. */
    fun access(source: MusicSource): MusicAppAccess {
        if (source == MusicSource.AMBIENT) return MusicAppAccess.OPENABLE
        val now = SystemClock.elapsedRealtime()
        accessCache[source]?.takeIf { now - it.first in 0..5_000 }?.let { return it.second }
        val openable = installedPackage(source) != null
        val present = openable || source.packages.any { pkg ->
            runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess
        }
        return musicAppAccess(openable, present).also { accessCache[source] = now to it }
    }

    fun selectionLabel(source: MusicSource) = "${source.title} · ${access(source).label}"

    fun browserComponent(source: MusicSource): ComponentName? {
        val pm = context.packageManager
        val candidates = pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0)
            .mapNotNull { it.serviceInfo }.filter { it.packageName in source.packages }
            .map { MusicBrowserCandidate(it.packageName, it.name, it.enabled && it.applicationInfo.enabled, it.exported) }
        val service = selectMusicBrowser(source, candidates) ?: return null
        return ComponentName(service.packageName, service.className)
    }

    private fun carMediaIntent(source: MusicSource): Pair<String, Intent>? {
        val pm = context.packageManager
        val service = browserComponent(source) ?: return null
        val intent = Intent("android.car.intent.action.MEDIA_TEMPLATE")
            .putExtra("android.car.intent.extra.MEDIA_COMPONENT", service.flattenToString())
        // Use the installed OEM host with the declared service, rather than inventing a component name.
        val host = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY)
            .firstOrNull { it.activityInfo?.exported == true && it.activityInfo.enabled }?.activityInfo ?: return null
        return service.packageName to intent.setComponent(ComponentName(host.packageName, host.name))
    }
    private fun launchIntent(pkg: String): Intent? =
        if (pkg == "ai.umos.mediaapp") null else context.packageManager.getLaunchIntentForPackage(pkg)
    fun installedPackage(source: MusicSource): String? = source.packages.firstOrNull {
        launchIntent(it) != null
    } ?: carMediaIntent(source)?.first
    fun open(source: MusicSource, link: String): Boolean = runCatching {
        val car = carMediaIntent(source)
        val pkg = car?.first ?: installedPackage(source) ?: return false
        val linkIntent = if (link.isNotBlank() && source.acceptsLink(link))
            Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg).takeIf {
                context.packageManager.resolveActivity(it, 0)?.activityInfo?.exported == true
            } else null
        val intent = linkIntent ?: car?.second ?: launchIntent(pkg) ?: return false
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
