package com.tyraen.voicekeyboard.feature.audio

import kotlin.math.max
import kotlin.math.min

/**
 * Conservative endpointing from the recorder's peak levels, relative to the observed background.
 * This is not a speech classifier: music, other voices and sustained changing noise can also
 * look like speech. Uncertain input keeps recording; it never discards audio or times out before
 * a speech-sized, sustained change has been observed.
 *
 * Feed levels even while disabled, so enabling during a dictation retains its acoustic history.
 * All timestamps use the same monotonic clock. The recorder normally supplies one peak per 150ms.
 */
internal class AdaptiveSilenceEndpointer(
    pauseMs: Long,
    private val startedAtMs: Long,
    enabled: Boolean
) {
    private data class Level(val peak: Int, val atMs: Long)

    private var pauseMs = checkedPause(pauseMs)
    private var enabled = enabled
    private var graceStartedAtMs = startedAtMs
    private var firstObservation = true
    private var lastObservedAtMs: Long? = null
    private var lastSpeechAtMs: Long? = null
    private var noiseFloor: Double? = null
    private var latestIsQuiet = false
    private val history = ArrayDeque<Level>()

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

    fun onAmplitude(peak: Int, nowMs: Long) {
        val previousAt = lastObservedAtMs
        if (nowMs < (previousAt ?: startedAtMs)) return
        if (previousAt != null && nowMs - previousAt > MAX_OBSERVATION_GAP_MS) {
            // A delayed callback is not evidence that the intervening audio was quiet.
            history.clear()
            graceStartedAtMs = nowMs
        }
        lastObservedAtMs = nowMs
        val level = peak.coerceIn(0, 32767)
        if (firstObservation) {
            firstObservation = false
            // MediaRecorder.getMaxAmplitude() always returns 0 on its first invocation.
            if (level == 0) return
        }

        val previousFloor = noiseFloor ?: max(MIN_NOISE_FLOOR, level.toDouble())
        noiseFloor = when {
            level < previousFloor -> max(MIN_NOISE_FLOOR, previousFloor + (level - previousFloor) * .5)
            level < previousFloor * 1.5 ->
                previousFloor + (level - previousFloor) * .05
            else -> previousFloor
        }
        history.addLast(Level(level, nowMs))
        while (history.size > 32 || (history.firstOrNull()?.atMs ?: nowMs) < nowMs - HISTORY_MS) {
            history.removeFirst()
        }

        val floor = noiseFloor!!
        if (lastSpeechAtMs == null) {
            val entry = max(MIN_SPEECH_PEAK, max(floor * 2.4, floor + 120.0))
            findSpeech(entry, requireContrast = true)?.let {
                lastSpeechAtMs = it
            }
        }
        // Compare continuing voice with the background, not a previous loud word or click.
        // A loud earlier peak must never make ordinary quieter speech count as silence.
        val activityThreshold = max(MIN_SPEECH_PEAK, floor * 1.8)
        if (lastSpeechAtMs != null) {
            findSpeech(activityThreshold, requireContrast = false)?.let {
                if (it > lastSpeechAtMs!!) lastSpeechAtMs = it
            }
        }
        // A new, as-yet unconfirmed peak also blocks stopping on this observation.
        latestIsQuiet = level < activityThreshold
    }

    fun shouldStop(nowMs: Long): Boolean {
        val speechAt = lastSpeechAtMs ?: return false
        val observedAt = lastObservedAtMs ?: return false
        return enabled && latestIsQuiet && nowMs >= observedAt &&
            nowMs - observedAt <= MAX_OBSERVATION_GAP_MS &&
            nowMs - max(speechAt, graceStartedAtMs) >= pauseMs
    }

    /**
     * Two adjacent elevated observations reject isolated clicks. At startup, a modulated burst
     * can be recognized retrospectively when it falls back to the background, avoiding a long
     * mandatory calibration period that would miss the first short answer. A flat unknown signal
     * alone does not establish speech merely because the recorder's artificial initial zero was low.
     */
    private fun findSpeech(threshold: Double, requireContrast: Boolean): Long? {
        var quietBefore = false
        var quietBeforeRun = false
        var count = 0
        var previousAt: Long? = null
        var lowest = 32767
        var highest = 0
        var result: Long? = null
        for (sample in history) {
            if (sample.peak < threshold) {
                quietBefore = true
                count = 0
                previousAt = null
                continue
            }
            val gap = previousAt?.let { sample.atMs - it }
            if (count == 0 || gap == null || gap !in MIN_ADJACENT_GAP_MS..MAX_OBSERVATION_GAP_MS) {
                count = 0
                lowest = 32767
                highest = 0
                quietBeforeRun = quietBefore
            }
            count++
            lowest = min(lowest, sample.peak)
            highest = max(highest, sample.peak)
            previousAt = sample.atMs
            if (count >= 2 && (!requireContrast || quietBeforeRun || highest >= lowest * 1.25)) {
                result = sample.atMs
            }
        }
        return result
    }

    private companion object {
        const val MIN_NOISE_FLOOR = 40.0
        const val MIN_SPEECH_PEAK = 180.0
        const val HISTORY_MS = 2_000L
        const val MAX_OBSERVATION_GAP_MS = 450L
        const val MIN_ADJACENT_GAP_MS = 75L

        fun checkedPause(pauseMs: Long): Long {
            require(pauseMs in 300L..3_000L)
            return pauseMs
        }
    }
}
