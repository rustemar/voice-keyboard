package com.tyraen.voicekeyboard.feature.ime

import android.view.View
import android.widget.*
import com.tyraen.voicekeyboard.R

class InputPanelController(rootView: View) {

    private val statusText: TextView = rootView.findViewById(R.id.statusText)
    private val btnMic: ImageButton = rootView.findViewById(R.id.btnMic)
    private val btnCancel: ImageButton = rootView.findViewById(R.id.btnCancel)
    private val progressBar: ProgressBar = rootView.findViewById(R.id.progressBar)

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
            is InputPhase.Failed -> {
                displayError(phase.reason)
            }
        }
    }

    fun displayError(message: String) {
        statusText.text = statusText.context.getString(R.string.status_error, message)
    }
}
