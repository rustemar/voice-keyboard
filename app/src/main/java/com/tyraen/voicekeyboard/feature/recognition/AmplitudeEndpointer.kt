package com.tyraen.voicekeyboard.feature.recognition

/** Bounded speech endpointing from the recorder's peak-amplitude samples, not a language model. */
internal class AmplitudeEndpointer(
    private val limits: RecognitionSession.Limits,
    private val startedAtMs: Long
) {
    enum class Action { WAIT, STOP, SPEECH_TIMEOUT }

    var hasSpeech: Boolean = false
        private set
    private var lastSpeechAtMs = startedAtMs

    /** A single speech-sized peak can be a whole short answer; do not require multiple frames. */
    fun amplitude(value: Int, nowMs: Long): Boolean {
        if (value >= limits.speechAmplitude) {
            lastSpeechAtMs = nowMs
            if (!hasSpeech) {
                hasSpeech = true
                return true
            }
        }
        return false
    }

    fun action(nowMs: Long): Action {
        val elapsed = (nowMs - startedAtMs).coerceAtLeast(0)
        if (elapsed >= limits.maximumCaptureMs) return Action.STOP
        if (!hasSpeech && elapsed >= maxOf(limits.noSpeechTimeoutMs, limits.minimumListeningMs)) {
            return Action.SPEECH_TIMEOUT
        }
        if (hasSpeech && elapsed >= limits.minimumListeningMs &&
            nowMs - lastSpeechAtMs >= limits.completeSilenceMs
        ) return Action.STOP
        return Action.WAIT
    }
}
