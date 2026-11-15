package com.example.afterglow.phone

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.IBinder

/** Separate-UID, shell-only silent focus fixture. Absent from every release APK. */
class FocusFixtureService : Service() {
    private var request: AudioFocusRequest? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val kind = intent?.getStringExtra("kind")
        val nonce = intent?.getStringExtra("nonce")
        if (kind !in setOf("transient", "duck", "permanent", "assistant") || nonce?.matches(Regex("[a-f0-9-]{36}")) != true) { stopSelf(); return START_NOT_STICKY }
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("qa_focus", "QA audio focus", NotificationManager.IMPORTANCE_MIN))
        startForeground(619, Notification.Builder(this, "qa_focus").setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle("QA audio focus · silent").build())
        val manager = getSystemService(AudioManager::class.java)
        request?.let { manager.abandonAudioFocusRequest(it) }
        val gain = when (kind) { "transient", "assistant" -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            "duck" -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK; else -> AudioManager.AUDIOFOCUS_GAIN }
        val usage = when (kind) { "transient" -> AudioAttributes.USAGE_VOICE_COMMUNICATION
            "assistant" -> AudioAttributes.USAGE_ASSISTANT
            "duck" -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE; else -> AudioAttributes.USAGE_MEDIA }
        val next = AudioFocusRequest.Builder(gain).setAudioAttributes(AudioAttributes.Builder().setUsage(usage)
            .setContentType(if (kind == "permanent") AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { }.build()
        request = next
        val granted = manager.requestAudioFocus(next) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        getSharedPreferences("qa_focus", 0).edit().putString("kind", kind).putString("nonce", nonce).putBoolean("granted", granted).commit()
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        request?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }; request = null
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
}
