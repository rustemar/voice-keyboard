package com.tyraen.voicekeyboard.feature.ime

import android.text.TextPaint
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.tyraen.voicekeyboard.R

class InputPanelController(rootView: View) {

    private val statusText: TextView = rootView.findViewById(R.id.statusText)
    private val btnMic: ImageButton = rootView.findViewById(R.id.btnMic)
    private val btnCancel: ImageButton = rootView.findViewById(R.id.btnCancel)
    private val progressBar: ProgressBar = rootView.findViewById(R.id.progressBar)
    private val queueBadge: TextView = rootView.findViewById(R.id.queueBadge)
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
                statusText.setText(R.string.status_recording)
                btnMic.setBackgroundResource(R.drawable.mic_button_recording)
                btnMic.visibility = View.VISIBLE
                btnCancel.visibility = View.VISIBLE
                progressBar.visibility = View.GONE
                animator.beginPulse()
            }
            is InputPhase.Failed -> {
                displayError(phase.reason)
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

    fun updateProcessingPhase(phase: ProcessingQueue.ProcessingPhase) {
        currentProcessingPhase = phase
        if (currentPhase is InputPhase.Ready && currentQueueCount > 0) {
            applyQueueState()
        }
    }

    private fun applyQueueState() {
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

    fun displayError(message: String) {
        statusText.text = statusText.context.getString(R.string.status_error, message)
    }

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

            // Shrink bar to content width when text is short, fill available space when long
            clipboardBar.post {
                val textWidth = clipboardText.paint.measureText(displayText)
                val density = clipboardBar.resources.displayMetrics.density
                val iconWidth = 18 * density // 18dp icon
                val textMargin = 8 * density // 8dp marginStart on text
                val paddingH = clipboardBar.paddingStart + clipboardBar.paddingEnd
                val contentWidth = (iconWidth + textMargin + textWidth + paddingH).toInt()

                val barParams = clipboardBar.layoutParams as ConstraintLayout.LayoutParams
                val textParams = clipboardText.layoutParams as LinearLayout.LayoutParams
                if (contentWidth < clipboardBar.width) {
                    // Content fits — shrink bar and center it
                    barParams.width = ConstraintLayout.LayoutParams.WRAP_CONTENT
                    textParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    textParams.weight = 0f
                } else {
                    // Content overflows — fill available space, ellipsize
                    barParams.width = 0 // match constraints
                    textParams.width = 0
                    textParams.weight = 1f
                }
                clipboardBar.layoutParams = barParams
                clipboardText.layoutParams = textParams
            }
        }
    }

    fun updateTranslateToggle(active: Boolean, langCode: String) {
        btnPpTranslate.text = langCode.uppercase()
        val context = btnPpTranslate.context
        if (active) {
            btnPpTranslate.setBackgroundResource(R.drawable.toggle_key_bg_active)
            btnPpTranslate.setTextColor(ContextCompat.getColor(context, R.color.white))
        } else {
            btnPpTranslate.setBackgroundResource(R.drawable.toggle_key_bg)
            btnPpTranslate.setTextColor(ContextCompat.getColor(context, R.color.key_text))
        }
    }
}
