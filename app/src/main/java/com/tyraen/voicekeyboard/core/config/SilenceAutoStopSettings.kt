package com.tyraen.voicekeyboard.core.config

/** The same pause range is used by persistence, the settings slider and keyboard endpointing. */
object SilenceAutoStopSettings {
    const val MIN_PAUSE_MS = 300
    const val MAX_PAUSE_MS = 3_000
    const val STEP_MS = 100
    const val DEFAULT_PAUSE_MS = 1_200
    const val MAX_PROGRESS = (MAX_PAUSE_MS - MIN_PAUSE_MS) / STEP_MS

    fun normalizePauseMs(value: Int): Int {
        val bounded = value.coerceIn(MIN_PAUSE_MS, MAX_PAUSE_MS)
        return ((bounded + STEP_MS / 2) / STEP_MS) * STEP_MS
    }

    /** A zero-based progress value also works on Android 7, where SeekBar has no minimum. */
    fun progressForPauseMs(value: Int): Int =
        (normalizePauseMs(value) - MIN_PAUSE_MS) / STEP_MS

    fun pauseMsForProgress(progress: Int): Int =
        MIN_PAUSE_MS + progress.coerceIn(0, MAX_PROGRESS) * STEP_MS
}
