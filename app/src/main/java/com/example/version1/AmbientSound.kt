package com.example.version1

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.AudioFocusRequest
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.*

/** Locally generated quiet surf; no network, recording or third-party audio asset. */
class AmbientSound(context: Context, private val outputGain: Float = 1f) {
    init { require(outputGain.isFinite() && outputGain in 0f..1f) }
    enum class Scene(val title: String) { WAVES("잔잔한 파도"), RAIN("부드러운 빗소리"), FOREST("고요한 숲") }
    private val manager = context.getSystemService(AudioManager::class.java)
    private var track: AudioTrack? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var preparation: Job? = null
    private var generation = 0L
    private var focusOwned = false
    val isPlaying: Boolean get() = track != null && playback.shouldPlay
    private val playback = AmbientPlaybackPolicy()
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    var onFocusLost: (() -> Unit)? = null
    var onPlaybackChanged: ((Boolean) -> Unit)? = null
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({
            val event = when (it) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AmbientFocusEvent.DUCK
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AmbientFocusEvent.TRANSIENT_LOSS
                AudioManager.AUDIOFOCUS_LOSS -> AmbientFocusEvent.LOSS
                AudioManager.AUDIOFOCUS_GAIN -> AmbientFocusEvent.GAIN
                else -> null
            }
            if (event != null) {
                playback.focus(event)
                if (event == AmbientFocusEvent.LOSS) stop()
                else runCatching {
                    track?.let { audio ->
                        audio.setVolume(playback.volume * outputGain)
                        if (playback.shouldPlay) audio.play() else audio.pause()
                    }
                }.onFailure { stop() }
                if (event == AmbientFocusEvent.LOSS || event == AmbientFocusEvent.TRANSIENT_LOSS) onFocusLost?.invoke()
                onPlaybackChanged?.invoke(track != null && playback.shouldPlay)
            }
        }, Handler(Looper.getMainLooper())).build()

    fun start(scene: Scene = Scene.WAVES): Boolean {
        if (track != null || preparation?.isActive == true) return playback.shouldPlay
        playback.requestStart()
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            playback.stop(); return false
        }
        focusOwned = true
        val owner = ++generation
        preparation = scope.launch {
          try {
            val samples = withContext(Dispatchers.Default) {
            val rate = 22050
            val samples = ShortArray(rate * 8)
            var filtered = 0.0
            val random = Random(42 + scene.ordinal)
            for (i in samples.indices) {
                if (i % 4096 == 0) ensureActive()
                filtered = filtered * .97 + (random.nextDouble() * 2 - 1) * .03
                val swell = when (scene) {
                    Scene.WAVES -> .4 + .6 * (sin(2 * Math.PI * i / samples.size - Math.PI / 2) + 1) / 2
                    Scene.RAIN -> .65 + .15 * sin(2 * Math.PI * i / rate / 2)
                    Scene.FOREST -> .5 + .1 * sin(2 * Math.PI * i / rate / 5)
                }
                val fade = minOf(1.0, i / 1800.0, (samples.lastIndex - i) / 1800.0)
                val foliage = if (scene == Scene.FOREST)
                    .018 * sin(2 * Math.PI * 900 * i / rate) *
                        kotlin.math.max(0.0, sin(2 * Math.PI * 3 * i / rate)) else 0.0
                samples[i] = ((filtered * swell + foliage) * fade * 12000).toInt().toShort()
            }
            samples
            }
            if (owner != generation) return@launch
            val audio = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(22050)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(samples.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
            track = audio // Retain ownership before writes so every failure releases native resources.
            // MODE_STATIC starts in STATE_NO_STATIC_DATA. It becomes initialized after write.
            check(audio.state != AudioTrack.STATE_UNINITIALIZED)
            check(audio.write(samples, 0, samples.size) == samples.size)
            check(audio.state == AudioTrack.STATE_INITIALIZED)
            check(audio.setLoopPoints(0, samples.size, -1) == AudioTrack.SUCCESS)
            audio.setVolume(playback.volume * outputGain)
            if (playback.shouldPlay) audio.play()
            onPlaybackChanged?.invoke(playback.shouldPlay)
          } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { if (owner == generation) stop() }
          finally { if (owner == generation) preparation = null }
        }
        return true // Accepted; onPlaybackChanged reports actual playback after preparation.
    }
    fun stop() {
        // An OFF preset never requested focus. Repeated lifecycle/mode stops
        // must not perform Binder work or publish a false playback transition.
        val hadResources = focusOwned || track != null || preparation != null
        generation++
        preparation?.cancel(); preparation = null
        playback.stop()
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
        if (focusOwned) {
            focusOwned = false
            manager.abandonAudioFocusRequest(focus)
        }
        if (hadResources) onPlaybackChanged?.invoke(false)
    }
}
