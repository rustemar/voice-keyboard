package com.tyraen.voicekeyboard.feature.ime

/** A stop press must not start another recording if automatic endpointing wins the race. */
internal class MicrophonePressGuard {
    private var captureAtTouchDown: InputPhase.Capturing? = null

    fun onTouchDown(phase: InputPhase) {
        captureAtTouchDown = phase as? InputPhase.Capturing
    }

    fun cancelTouch() {
        captureAtTouchDown = null
    }

    fun shouldHandleClick(phase: InputPhase): Boolean {
        val capture = captureAtTouchDown
        cancelTouch()
        return capture == null || capture === phase
    }
}
