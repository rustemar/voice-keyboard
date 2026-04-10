package com.tyraen.voicekeyboard.feature.ime

sealed class InputAction {
    object ToggleCapture : InputAction()
    object CancelOperation : InputAction()
    object DeleteCharacter : InputAction()
    object InsertSpace : InputAction()
    object InsertNewline : InputAction()
    object PasteClipboard : InputAction()
    data class InsertPunctuation(val char: String) : InputAction()
    object OpenSetup : InputAction()
    object DismissKeyboard : InputAction()
}
