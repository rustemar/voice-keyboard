package com.tyraen.voicekeyboard.feature.audio

/** One microphone owner: audio and haptics share each start/stop event, including cancellation. */
internal class RecordingFeedbackSession @JvmOverloads constructor(
    private val playStart: () -> Unit,
    private val playStop: () -> Unit,
    private val playHaptic: () -> Unit = {}
) {
    private var active = false
    private var enabled = false
    private var hapticsEnabled = false

    /** Call only after the microphone actually starts; an unknown setting must pass false. */
    @JvmOverloads
    fun started(soundsEnabled: Boolean, hapticsEnabled: Boolean = false) {
        if (active) return
        active = true
        enabled = soundsEnabled
        this.hapticsEnabled = hapticsEnabled
        emit(playStart, soundsEnabled, hapticsEnabled)
    }

    /** Call after the microphone closes, even when cancelling or discarding the recording. */
    fun stopped() {
        if (!active) return
        active = false
        val shouldPlaySound = enabled
        val shouldPlayHaptic = hapticsEnabled
        enabled = false
        hapticsEnabled = false
        emit(playStop, shouldPlaySound, shouldPlayHaptic)
    }

    private fun emit(playSound: () -> Unit, soundEnabled: Boolean, hapticEnabled: Boolean) {
        // Dispatch consecutive requests from the same lifecycle event. One unavailable output
        // must not suppress the other output or interfere with microphone cleanup.
        if (soundEnabled) runCatching(playSound)
        if (hapticEnabled) runCatching(playHaptic)
    }
}
