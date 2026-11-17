package com.tyraen.voicekeyboard.feature.audio

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import java.util.concurrent.ConcurrentHashMap

/** Process-wide, preloaded UI sounds. Playback never changes audio routing, volume or focus. */
class RecordingSoundPlayer(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val loaded = ConcurrentHashMap.newKeySet<Int>()
    private var startSample = 0
    private var stopSample = 0
    private var stream = 0
    private var previewGeneration = 0L

    private val pool = runCatching {
        SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            .build()
    }.onFailure { DiagnosticLog.recordFailure("RecordingSounds", "Sound playback unavailable", it) }
        .getOrNull()

    init {
        runCatching {
            pool?.setOnLoadCompleteListener { _, sample, status ->
                if (status == 0) loaded.add(sample)
                else DiagnosticLog.record("RecordingSounds", "Sound load failed status=$status")
            }
            startSample = pool?.load(context, R.raw.recording_start, 1) ?: 0
            stopSample = pool?.load(context, R.raw.recording_stop, 1) ?: 0
        }.onFailure { DiagnosticLog.recordFailure("RecordingSounds", "Sound preload failed", it) }
    }

    internal fun newSession() = newSession {}

    internal fun newSession(onHaptic: () -> Unit) = RecordingFeedbackSession(
        playStart = { playCaptureCue(startSample) },
        playStop = { playCaptureCue(stopSample) },
        playHaptic = onHaptic
    )

    /** Explicit settings preview; never play a preview into a microphone already in use. */
    fun preview(): Boolean {
        val generation = ++previewGeneration
        if (MicrophoneGate.shared.isBusy || startSample !in loaded || stopSample !in loaded) return false
        if (!play(startSample)) return false
        main.postDelayed({
            if (previewGeneration == generation && !MicrophoneGate.shared.isBusy) play(stopSample)
        }, 420)
        return true
    }

    private fun playCaptureCue(sample: Int) {
        // An actual capture supersedes any delayed settings preview.
        previewGeneration++
        play(sample)
    }

    private fun play(sample: Int): Boolean = runCatching {
        val sounds = pool ?: return@runCatching false
        // Retire an earlier cue even when the new cue must remain silent.
        if (stream != 0) sounds.stop(stream)
        stream = 0
        if (sample == 0 || sample !in loaded || !systemAllowsSound()) return@runCatching false
        stream = sounds.play(sample, 1f, 1f, 1, 0, 1f)
        stream != 0
    }.getOrDefault(false)

    private fun systemAllowsSound(): Boolean {
        val manager = audio ?: return false
        val notificationManager = notifications ?: return false
        return manager.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL &&
            manager.getStreamVolume(AudioManager.STREAM_SYSTEM) > 0 &&
            !manager.isStreamMute(AudioManager.STREAM_SYSTEM)
    }
}
