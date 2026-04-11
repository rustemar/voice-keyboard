package com.tyraen.voicekeyboard.feature.ime

sealed class InputPhase {
    object Ready : InputPhase()
    data class Capturing(val startTimeMs: Long = System.currentTimeMillis()) : InputPhase()
    object Processing : InputPhase()
    object PostProcessing : InputPhase()
    data class Failed(val reason: String) : InputPhase()
}
