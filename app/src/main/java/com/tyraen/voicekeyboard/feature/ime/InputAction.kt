package com.tyraen.voicekeyboard.feature.ime

sealed class InputAction {
    object ToggleCapture : InputAction()
    object CancelOperation : InputAction()
}
