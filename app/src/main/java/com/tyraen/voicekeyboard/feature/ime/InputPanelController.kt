package com.tyraen.voicekeyboard.feature.ime

import android.content.res.ColorStateList
import android.text.TextPaint
import android.view.View
import androidx.annotation.StringRes
import android.view.ViewGroup
import android.widget.*
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.core.locale.TranscriptionLocale

class InputPanelController(rootView: View) {

    private val statusText: TextView = rootView.findViewById(R.id.statusText)
    private val btnMic: ImageButton = rootView.findViewById(R.id.btnMic)
    private val btnCancel: ImageButton = rootView.findViewById(R.id.btnCancel)
    private val progressBar: ProgressBar = rootView.findViewById(R.id.progressBar)
    private val queueBadge: TextView = rootView.findViewById(R.id.queueBadge)
    val retryFrame: View = rootView.findViewById(R.id.retryFrame)
    private val retryBadge: TextView = rootView.findViewById(R.id.retryBadge)
    val clipboardBar: View = rootView.findViewById(R.id.clipboardBar)
    private val clipboardText: TextView = rootView.findViewById(R.id.clipboardText)

    // Post-processing toggle UI
    val ppToggleRow: View = rootView.findViewById(R.id.ppToggleRow)
    val btnPpFix: ImageButton = rootView.findViewById(R.id.btnPpFix)
    val btnPpShorten: ImageButton = rootView.findViewById(R.id.btnPpShorten)
    val btnPpEmoji: ImageButton = rootView.findViewById(R.id.btnPpEmoji)
    val btnPpRhyme: ImageButton = rootView.findViewById(R.id.btnPpRhyme)
    val btnPpTerminal: ImageButton = rootView.findViewById(R.id.btnPpTerminal)
    private val ppTerminalSpacer: View = rootView.findViewById(R.id.ppTerminalSpacer)
    val btnPpTranslate: Button = rootView.findViewById(R.id.btnPpTranslate)

    // Dictation language key (bottom row, left of space) — only shown when the user configured
    // more than one language to dictate in.
    val btnLanguage: Button = rootView.findViewById(R.id.btnLanguage)
    private val languageSpacer: View = rootView.findViewById(R.id.languageSpacer)
    private val btnHideKeyboard: ImageButton = rootView.findViewById(R.id.btnHideKeyboard)

    val animator = InputPanelAnimator(
        wave1 = rootView.findViewById(R.id.ripple1),
        wave2 = rootView.findViewById(R.id.ripple2)
    )

    var currentPhase: InputPhase = InputPhase.Ready
        private set

    private var currentQueueCount: Int = 0
    private var currentProcessingPhase: ProcessingQueue.ProcessingPhase = ProcessingQueue.ProcessingPhase.TRANSCRIBING

    fun transitionTo(phase: InputPhase) {
        currentPhase = phase
        when (phase) {
            is InputPhase.Ready -> {
                btnMic.setBackgroundResource(R.drawable.mic_button_bg)
                btnMic.visibility = View.VISIBLE
                btnCancel.visibility = View.GONE
                animator.haltPulse()
                // Status and progress depend on queue state
                applyQueueState()
            }
            is InputPhase.Capturing -> {
                clearNotice()
                statusText.setText(R.string.status_recording)
                btnMic.setBackgroundResource(R.drawable.mic_button_recording)
                btnMic.visibility = View.VISIBLE
                btnCancel.visibility = View.VISIBLE
                progressBar.visibility = View.GONE
                animator.beginPulse()
            }
            is InputPhase.Failed -> {
                displayError(phase.reasonRes)
            }
        }
    }

    fun updateQueueCount(count: Int) {
        currentQueueCount = count
        // Update badge
        if (count > 0) {
            queueBadge.text = count.toString()
            queueBadge.visibility = View.VISIBLE
        } else {
            queueBadge.visibility = View.GONE
        }
        // If in Ready phase, update progress bar and status text
        if (currentPhase is InputPhase.Ready) {
            applyQueueState()
        }
    }

    /** Show/hide the "resend failed recordings" button and its count badge. */
    fun updateFailedCount(count: Int) {
        if (count > 0) {
            retryBadge.text = count.toString()
            retryFrame.visibility = View.VISIBLE
        } else {
            retryFrame.visibility = View.GONE
        }
    }

    fun updateProcessingPhase(phase: ProcessingQueue.ProcessingPhase) {
        currentProcessingPhase = phase
        if (currentPhase is InputPhase.Ready && currentQueueCount > 0) {
            applyQueueState()
        }
    }

    private fun applyQueueState() {
        clearNotice()
        if (currentQueueCount > 0) {
            when (currentProcessingPhase) {
                ProcessingQueue.ProcessingPhase.TRANSCRIBING ->
                    statusText.setText(R.string.status_transcribing)
                ProcessingQueue.ProcessingPhase.POST_PROCESSING ->
                    statusText.setText(R.string.status_postprocessing)
            }
            progressBar.visibility = View.VISIBLE
        } else {
            statusText.setText(R.string.status_idle)
            progressBar.visibility = View.GONE
        }
    }

    fun displayError(@StringRes reasonRes: Int) {
        clearNotice()
        val context = statusText.context
        statusText.text = context.getString(R.string.status_error, context.getString(reasonRes))
    }

    private var noticeReset: Runnable? = null

    /** Any other status write ends the notice, so [noticeShowing] means the text is really on screen. */
    private fun clearNotice() {
        noticeReset?.let { statusText.removeCallbacks(it) }
        noticeReset = null
    }

    /**
     * Show a transient line in the status area (confirmations, hints) and fall back to the
     * regular status after [durationMs], unless a recording started meanwhile.
     */
    fun showNotice(text: String, durationMs: Long = 4000L) {
        clearNotice()
        statusText.text = text
        val reset = Runnable {
            noticeReset = null
            if (currentPhase is InputPhase.Ready) applyQueueState()
        }
        noticeReset = reset
        statusText.postDelayed(reset, durationMs)
    }

    /** Whether a notice is currently on screen (used for two-step confirmations). */
    val noticeShowing: Boolean get() = noticeReset != null

    fun showPostProcessingButtons(show: Boolean) {
        ppToggleRow.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun updateToggleAppearance(button: ImageButton, active: Boolean) {
        val context = button.context
        if (active) {
            button.setBackgroundResource(R.drawable.toggle_key_bg_active)
            button.imageTintList = ContextCompat.getColorStateList(context, R.color.white)
        } else {
            button.setBackgroundResource(R.drawable.toggle_key_bg)
            button.imageTintList = ContextCompat.getColorStateList(context, R.color.key_text)
        }
    }

    fun showTerminalButton(show: Boolean) {
        val visibility = if (show) View.VISIBLE else View.GONE
        btnPpTerminal.visibility = visibility
        ppTerminalSpacer.visibility = visibility
    }

    fun updateClipboard(text: String?) {
        if (text.isNullOrBlank()) {
            clipboardBar.visibility = View.GONE
        } else {
            val displayText = text.replace('\n', ' ')
            clipboardText.text = displayText
            clipboardBar.visibility = View.VISIBLE

            // Reset bar to full width first so we can measure the maximum available space,
            // then shrink to content width if the text is short enough.
            val barParams = clipboardBar.layoutParams as ConstraintLayout.LayoutParams
            val textParams = clipboardText.layoutParams as LinearLayout.LayoutParams
            barParams.width = 0 // match constraints = full available width
            textParams.width = 0
            textParams.weight = 1f
            clipboardBar.layoutParams = barParams
            clipboardText.layoutParams = textParams

            clipboardBar.post {
                val textWidth = clipboardText.paint.measureText(displayText)
                val density = clipboardBar.resources.displayMetrics.density
                val iconWidth = 18 * density // 18dp icon
                val textMargin = 8 * density // 8dp marginStart on text
                val paddingH = clipboardBar.paddingStart + clipboardBar.paddingEnd
                val contentWidth = (iconWidth + textMargin + textWidth + paddingH).toInt()

                val bp = clipboardBar.layoutParams as ConstraintLayout.LayoutParams
                val tp = clipboardText.layoutParams as LinearLayout.LayoutParams
                if (contentWidth < clipboardBar.width) {
                    // Content fits — shrink bar and center it
                    bp.width = ConstraintLayout.LayoutParams.WRAP_CONTENT
                    tp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    tp.weight = 0f
                } else {
                    // Content overflows — fill available space, ellipsize
                    bp.width = 0 // match constraints
                    tp.width = 0
                    tp.weight = 1f
                }
                clipboardBar.layoutParams = bp
                clipboardText.layoutParams = tp
            }
        }
    }

    /** With "Return to previous keyboard" on, ⌄ becomes a key back to that keyboard. */
    fun showHideKeyAsReturn(asReturn: Boolean) {
        btnHideKeyboard.setImageResource(
            if (asReturn) R.drawable.ic_keyboard_switch else R.drawable.ic_keyboard_hide
        )
        btnHideKeyboard.contentDescription = btnHideKeyboard.context.getString(
            if (asReturn) R.string.cd_previous_keyboard else R.string.cd_hide_keyboard
        )
    }

    /**
     * Shows the dictation-language key labelled with [code]. Hidden entirely when [visible] is
     * false (a single configured language) so the space bar keeps its full width.
     */
    fun updateLanguageKey(code: String, visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        btnLanguage.visibility = visibility
        languageSpacer.visibility = visibility
        if (visible) btnLanguage.text = TranscriptionLocale.shortLabel(code)
    }

    /**
     * Labels the toggle "→EN": the arrow (a drawable, so it mirrors in RTL) marks a target
     * language, unlike the language key's bare "RU", which is the language being dictated.
     * Long-press and TalkBack both say "Translate into English".
     */
    fun updateTranslateToggle(active: Boolean, langCode: String) {
        btnPpTranslate.text = TranscriptionLocale.shortLabel(langCode)
        val context = btnPpTranslate.context
        val description = context.getString(
            R.string.pp_translate_into,
            TranscriptionLocale.displayNameIn(langCode, context.resources.configuration.locales[0])
        )
        btnPpTranslate.contentDescription = description
        ViewCompat.setTooltipText(btnPpTranslate, description)
        val textColor: Int
        if (active) {
            btnPpTranslate.setBackgroundResource(R.drawable.toggle_key_bg_active)
            textColor = ContextCompat.getColor(context, R.color.white)
        } else {
            btnPpTranslate.setBackgroundResource(R.drawable.toggle_key_bg)
            textColor = ContextCompat.getColor(context, R.color.key_text)
        }
        btnPpTranslate.setTextColor(textColor)
        btnPpTranslate.compoundDrawableTintList = ColorStateList.valueOf(textColor)
    }
}
