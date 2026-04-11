package com.tyraen.voicekeyboard.feature.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.*
import java.io.File

class MicrophoneCaptureSession(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var levelMonitor: Job? = null
    private var fileCounter = 0
    var capturedFile: File? = null
        private set

    val isActive: Boolean
        get() = recorder != null

    fun begin(onAmplitude: (Int) -> Unit): File {
        val file = File(context.externalCacheDir, "recording_${fileCounter++}.m4a")
        capturedFile = file

        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mr.apply {
            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128000)
            setAudioChannels(1)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        recorder = mr

        levelMonitor = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                try {
                    onAmplitude(mr.maxAmplitude)
                } catch (_: Exception) {
                    break
                }
                delay(150)
            }
        }

        return file
    }

    fun finalize(): File? {
        levelMonitor?.cancel()
        levelMonitor = null
        try {
            recorder?.apply { stop(); release() }
        } catch (_: Exception) {}
        recorder = null
        return capturedFile
    }

    fun abort() {
        levelMonitor?.cancel()
        levelMonitor = null
        try {
            recorder?.apply { stop(); release() }
        } catch (_: Exception) {}
        recorder = null
        capturedFile?.delete()
        capturedFile = null
    }
}
