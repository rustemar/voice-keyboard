package com.tyraen.voicekeyboard.feature.ime

/**
 * Decides when the keyboard hands the screen back to the one that opened it, typically another
 * keyboard's mic key. Checked a moment after a dictation has been typed, and again when the queue
 * drains: only if the text was handed to the field and the panel has nothing left to show — no
 * recording or error on screen, nothing still queued, and no recording waiting for resend, so the
 * queue badge and the resend button are never hidden.
 */
object ReturnToPreviousKeyboard {

    /**
     * Pause between typing the text and switching away. Switching in the same step can make some
     * apps (WhatsApp) drop text that was just committed.
     */
    const val DELAY_MS = 150L

    /**
     * [inserted] is false when a dictation went to the clipboard instead of the field.
     * [idle] means no recording in progress and no error on the status line.
     * [parkedLoaded] is false until the parked-recording store has published its initial load;
     * before that, [failedCount] can't be trusted to be zero.
     */
    fun shouldReturnToPreviousKeyboard(
        enabled: Boolean,
        visible: Boolean,
        inserted: Boolean,
        idle: Boolean,
        pendingCount: Int,
        failedCount: Int,
        parkedLoaded: Boolean
    ): Boolean = enabled && visible && inserted && idle &&
        pendingCount == 0 && parkedLoaded && failedCount == 0
}
