package com.tyraen.voicekeyboard.feature.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * What the focused field asks of the keyboard, read from the EditorInfo the app hands over.
 * Pure rules over its values and resource identity, so they run in JVM tests.
 */
object EditorField {

    const val TERMIUS_PACKAGE = "com.server.auditor.ssh.client"
    private const val TERMIUS_TERMINAL_FIELD = "$TERMIUS_PACKAGE:id/terminalView"

    /**
     * Strict password classification, including the visible-password variation used by "show
     * password" toggles. This excludes recent context; voice uses [blocksVoice] for its narrow
     * terminal exception. The set matches AOSP LatinIME's isPasswordInputType and
     * isVisiblePasswordInputType. Termux sends the variation without a class, so it does not match.
     */
    fun isPassword(inputType: Int): Boolean {
        val masked = inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
        return masked == InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            masked == InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            masked == InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            masked == InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    /**
     * Termius marks its terminal view as a visible password to suppress keyboard suggestions.
     * Its credential fields still follow the normal password rules. Resolve the resource name
     * from the editor app: numeric view IDs change between releases. Unknown fields stay blocked.
     * [isPassword] remains strict for recent-context privacy, including this terminal view.
     */
    fun blocksVoice(inputType: Int, packageName: String?, fieldResourceName: String?): Boolean {
        if (!isPassword(inputType)) return false
        val masked = inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
        val termiusTerminal = packageName == TERMIUS_PACKAGE &&
            fieldResourceName == TERMIUS_TERMINAL_FIELD &&
            masked == InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        return !termiusTerminal
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
