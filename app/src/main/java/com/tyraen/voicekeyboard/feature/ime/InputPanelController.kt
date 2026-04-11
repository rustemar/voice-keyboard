package com.tyraen.voicekeyboard.feature.ime

import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import com.tyraen.voicekeyboard.R

class InputPanelController(rootView: View) {

    private val statusText: TextView = rootView.findViewById(R.id.statusText)
    private val btnMic: ImageButton = rootView.findViewById(R.id.btnMic)
    private val btnCancel: ImageButton = rootView.findViewById(R.id.btnCancel)
    private val progressBar: ProgressBar = rootView.findViewById(R.id.progressBar)

    // Post-processing toggle UI
    val ppToggleRow: View = rootView.findViewById(R.id.ppToggleRow)
    val btnPpFix: ImageButton = rootView.findViewById(R.id.btnPpFix)
    val btnPpShorten: ImageButton = rootView.findViewById(R.id.btnPpShorten)
    val btnPpEmoji: ImageButton = rootView.findViewById(R.id.btnPpEmoji)
    val btnPpTranslate: Button = rootView.findViewById(R.id.btnPpTranslate)

    val animator = InputPanelAnimator(
        wave1 = rootView.findViewById(R.id.ripple1),
        wave2 = rootView.findViewById(R.id.ripple2)
    )

    var currentPhase: InputPhase = InputPhase.Ready
        private set

    fun transitionTo(phase: InputPhase) {
        currentPhase = phase
        when (phase) {
            is InputPhase.Ready -> {
                statusText.setText(R.string.status_idle)
                btnMic.setBackgroundResource(R.drawable.mic_button_bg)
                btnMic.visibility = View.VISIBLE
                btnCancel.visibility = View.GONE
                progressBar.visibility = View.GONE
                animator.haltPulse()
            }
            is InputPhase.Capturing -> {
                statusText.setText(R.string.status_recording)
                btnMic.setBackgroundResource(R.drawable.mic_button_recording)
                btnMic.visibility = View.VISIBLE
                btnCancel.visibility = View.VISIBLE
                progressBar.visibility = View.GONE
                animator.beginPulse()
            }
            is InputPhase.Processing -> {
                statusText.setText(R.string.status_transcribing)
                btnMic.visibility = View.GONE
                btnCancel.visibility = View.VISIBLE
                progressBar.visibility = View.VISIBLE
                animator.haltPulse()
            }
            is InputPhase.PostProcessing -> {
                statusText.setText(R.string.status_postprocessing)
                btnMic.visibility = View.GONE
                btnCancel.visibility = View.VISIBLE
                progressBar.visibility = View.VISIBLE
                animator.haltPulse()
            }
            is InputPhase.Failed -> {
                displayError(phase.reason)
            }
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
