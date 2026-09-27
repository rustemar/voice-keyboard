package com.tyraen.voicekeyboard.feature.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * What the focused field asks of the keyboard, read from the EditorInfo the app hands over.
 * Pure functions over its ints and label, so they run in JVM tests; the rules follow AOSP LatinIME.
 */
object EditorField {

    /**
     * Password fields of every kind, including the visible-password variation that "show password"
     * toggles switch to: no dictation there, since the audio and the transcript would go to the
     * cloud provider and the transcript into the diagnostic log. The same set AOSP LatinIME hides
     * its mic in (InputAttributes: isPasswordInputType || isVisiblePasswordInputType). Termux sends
     * the visible-password variation without a class, so it does not match.
     */
    fun isPassword(inputType: Int): Boolean {
        val masked = inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
        return masked == InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            masked == InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            masked == InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            masked == InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    /** A text field that takes line breaks. */
    fun isMultiLine(inputType: Int): Boolean =
        inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0

    /** What the Enter key does in the field. */
    sealed class Enter {
        /** A plain Enter key event, as the key always sent before: a new line where there is one. */
        object NewLine : Enter()

        /** `performEditorAction(id)`; [label] is the app's own wording for it, if it gave one. */
        data class Action(val id: Int, val label: CharSequence?) : Enter()
    }

    /**
     * A single-line field runs its action (Search, Go, Send, Next, Done). View-based multi-line
     * fields get IME_FLAG_NO_ENTER_ACTION from TextView, so a chat box keeps its new line; Compose,
     * Flutter or React Native chat boxes that declare an action keep it, and there long-press types
     * the new line. An unspecified action is still performed, like AOSP does: TextView turns it back
     * into an Enter key event.
     */
    fun enter(imeOptions: Int, actionId: Int, actionLabel: CharSequence?): Enter = when {
        imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0 -> Enter.NewLine
        actionLabel != null -> Enter.Action(actionId, actionLabel)
        else -> (imeOptions and EditorInfo.IME_MASK_ACTION).let { id ->
            if (id == EditorInfo.IME_ACTION_NONE) Enter.NewLine else Enter.Action(id, null)
        }
    }

    /** The send key runs the field's own Send action where there is one, else Ctrl+Enter. */
    fun sendsByAction(enter: Enter): Boolean =
        enter is Enter.Action && enter.label == null && enter.id == EditorInfo.IME_ACTION_SEND
}
