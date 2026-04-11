package com.tyraen.voicekeyboard.feature.ime

import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.app.ServiceLocator
import com.tyraen.voicekeyboard.core.locale.InterfaceLanguageManager
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import com.tyraen.voicekeyboard.feature.audio.MicrophoneCaptureSession
import com.tyraen.voicekeyboard.feature.setup.SetupActivity

class DictationInputMethod : InputMethodService() {

    companion object {
        private const val TAG = "DictationIME"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(InterfaceLanguageManager.applyTo(newBase))
    }

    private lateinit var panel: InputPanelController
    private lateinit var orchestrator: InputOrchestrator
    private lateinit var keystrokes: KeystrokeDispatcher

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        DiagnosticLog.record(TAG, "onCreateInputView")
        val view = layoutInflater.inflate(R.layout.input_panel, null)

        panel = InputPanelController(view)
        keystrokes = KeystrokeDispatcher { currentInputConnection }

        orchestrator = InputOrchestrator(
            context = this,
            preferenceStore = ServiceLocator.preferenceStore,
            speechClient = ServiceLocator.speechToTextClient,
            postProcessingClient = ServiceLocator.postProcessingClient,
            capture = MicrophoneCaptureSession(this),
            onTextReady = { text -> keystrokes.insertText(text) },
            onPhaseChanged = { phase -> panel.transitionTo(phase) },
            onAmplitude = { level -> panel.animator.adjustForAmplitude(level) },
            onPreferencesLoaded = { refreshPostProcessingUI() }
        )

        orchestrator.loadPreferences()
        wireControls(view)
        return view
    }

    override fun onWindowShown() {
        super.onWindowShown()
        DiagnosticLog.record(TAG, "onWindowShown")
        if (::orchestrator.isInitialized) orchestrator.reloadAndAutoStart()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        if (::orchestrator.isInitialized) orchestrator.cancelAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::orchestrator.isInitialized) orchestrator.cancelAll()
    }

    private fun wireControls(view: View) {
        val btnMic: ImageButton = view.findViewById(R.id.btnMic)
        val btnCancel: ImageButton = view.findViewById(R.id.btnCancel)
        val btnBackspace: ImageButton = view.findViewById(R.id.btnBackspace)
        val btnSpace: Button = view.findViewById(R.id.btnSpace)
        val btnEnter: ImageButton = view.findViewById(R.id.btnEnter)
        val btnPaste: ImageButton = view.findViewById(R.id.btnPaste)
        val btnQuestion: Button = view.findViewById(R.id.btnQuestion)
        val btnExclamation: Button = view.findViewById(R.id.btnExclamation)
        val btnCutAll: ImageButton = view.findViewById(R.id.btnCutAll)
        val btnSettings: ImageButton = view.findViewById(R.id.btnSettings)
        val btnHideKeyboard: ImageButton = view.findViewById(R.id.btnHideKeyboard)

        btnMic.setOnClickListener { orchestrator.handleAction(InputAction.ToggleCapture) }
        btnCancel.setOnClickListener { orchestrator.handleAction(InputAction.CancelOperation) }

        btnBackspace.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    keystrokes.startBackspaceRepeat()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    keystrokes.stopBackspaceRepeat()
                    true
                }
                else -> false
            }
        }

        btnCutAll.setOnClickListener { keystrokes.cutAll(this) }

        btnSpace.setOnClickListener { keystrokes.insertText(" ") }
        btnEnter.setOnClickListener { keystrokes.sendEnter() }
        btnPaste.setOnClickListener { keystrokes.pasteFromClipboard(this) }
        btnQuestion.setOnClickListener { keystrokes.insertText("?") }
        btnExclamation.setOnClickListener { keystrokes.insertText("!") }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }

        btnHideKeyboard.setOnClickListener { requestHideSelf(0) }

        // Post-processing toggle buttons
        wirePostProcessingToggles()
    }

    private fun wirePostProcessingToggles() {
        panel.btnPpFix.setOnClickListener {
            if (orchestrator.ppFixActive) {
                orchestrator.ppFixActive = false
            } else {
                orchestrator.ppFixActive = true
                orchestrator.ppShortenActive = false // mutually exclusive
            }
            orchestrator.saveToggleStates()
            updateToggleUI()
        }

        panel.btnPpShorten.setOnClickListener {
            if (orchestrator.ppShortenActive) {
                orchestrator.ppShortenActive = false
            } else {
                orchestrator.ppShortenActive = true
                orchestrator.ppFixActive = false // mutually exclusive
            }
            orchestrator.saveToggleStates()
            updateToggleUI()
        }

        panel.btnPpEmoji.setOnClickListener {
            orchestrator.ppEmojiActive = !orchestrator.ppEmojiActive
            orchestrator.saveToggleStates()
            updateToggleUI()
        }
    }

    private fun refreshPostProcessingUI() {
        val show = orchestrator.isPostProcessingEnabled()
        panel.showPostProcessingButtons(show)
        if (show) updateToggleUI()
    }

    private fun updateToggleUI() {
        panel.updateToggleAppearance(panel.btnPpFix, orchestrator.ppFixActive)
        panel.updateToggleAppearance(panel.btnPpShorten, orchestrator.ppShortenActive)
        panel.updateToggleAppearance(panel.btnPpEmoji, orchestrator.ppEmojiActive)
    }
}
