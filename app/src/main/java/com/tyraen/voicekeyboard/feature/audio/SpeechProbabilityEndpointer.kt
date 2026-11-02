package com.tyraen.voicekeyboard.feature.audio

import kotlin.math.max

/**
 * Pause timing over local speech probabilities, independent of microphone volume.
 * Feed observations while disabled too, so enabling the timer retains the same speech history.
 */
internal class SpeechProbabilityEndpointer(
    pauseMs: Long,
    private val startedAtMs: Long,
    enabled: Boolean
) {
    private var pauseMs = checkedPause(pauseMs)
    private var enabled = enabled
    private var graceStartedAtMs = startedAtMs
    private var lastObservedAtMs: Long? = null
    private var speechRunStartedAtMs: Long? = null
    private var speechRunFrames = 0
    private var speechEstablished = false
    private var lastActivityAtMs: Long? = null
    private var latestIsQuiet = false

    fun setEnabled(enabled: Boolean, nowMs: Long) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        graceStartedAtMs = nowMs
    }

    fun setPauseMs(pauseMs: Long, nowMs: Long) {
        val checked = checkedPause(pauseMs)
        if (this.pauseMs == checked) return
        this.pauseMs = checked
        graceStartedAtMs = nowMs
    }

    fun onProbability(probability: Float, nowMs: Long) {
        val previousAt = lastObservedAtMs
        if (nowMs < (previousAt ?: startedAtMs)) return
        if (!probability.isFinite() || probability !in 0f..1f) {
            // A broken/unsupported inference result is not evidence of silence.
            speechRunStartedAtMs = null
            speechRunFrames = 0
            latestIsQuiet = false
            graceStartedAtMs = max(graceStartedAtMs, nowMs)
            return
        }
        val gapMs = previousAt?.let { nowMs - it }
        if (gapMs != null && gapMs > MAX_OBSERVATION_GAP_MS) {
            // No samples means no evidence about the sound in the gap. Wait a fresh full pause.
            graceStartedAtMs = max(graceStartedAtMs, nowMs)
            speechRunStartedAtMs = null
            speechRunFrames = 0
        }
        lastObservedAtMs = nowMs

        if (probability >= SPEECH_THRESHOLD) {
            if (speechRunStartedAtMs == null || gapMs == null ||
                gapMs > MAX_CONSECUTIVE_FRAME_GAP_MS
            ) {
                speechRunStartedAtMs = max(startedAtMs, nowMs - FRAME_MS)
                speechRunFrames = 0
            }
            speechRunFrames++
            if (speechRunFrames >= MIN_SPEECH_FRAMES &&
                nowMs - speechRunStartedAtMs!! >= MIN_SPEECH_MS
            ) {
                speechEstablished = true
            }
        } else {
            speechRunStartedAtMs = null
            speechRunFrames = 0
        }

        latestIsQuiet = probability < QUIET_THRESHOLD
        // Hysteresis protects low-probability word tails and brief uncertain voiced frames.
        if (speechEstablished && !latestIsQuiet) lastActivityAtMs = nowMs
    }

    fun shouldStop(nowMs: Long): Boolean {
        val observedAtMs = lastObservedAtMs ?: return false
        val activeAtMs = lastActivityAtMs ?: return false
        return enabled && speechEstablished && latestIsQuiet && nowMs >= observedAtMs &&
            nowMs - observedAtMs <= MAX_OBSERVATION_GAP_MS &&
            observedAtMs - max(activeAtMs, graceStartedAtMs) >= pauseMs
    }

    private companion object {
        // Test-build choices; probabilities classify speech, not the owner's identity.
        const val SPEECH_THRESHOLD = .6f
        const val QUIET_THRESHOLD = .35f
        const val FRAME_MS = 32L
        const val MIN_SPEECH_FRAMES = 2
        const val MIN_SPEECH_MS = 64L
        const val MAX_CONSECUTIVE_FRAME_GAP_MS = 96L
        const val MAX_OBSERVATION_GAP_MS = 160L

        fun checkedPause(pauseMs: Long): Long {
            require(pauseMs in 300L..3_000L)
            return pauseMs
        }
    }
}
