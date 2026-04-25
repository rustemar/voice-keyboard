package com.tyraen.voicekeyboard.feature.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageButton
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.app.ServiceLocator
import com.tyraen.voicekeyboard.core.config.ThemeManager
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
    private var currentTheme: String = ""
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var keyboardVisible = false

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        DiagnosticLog.record(TAG, "onCreateInputView")
        return createInputView()
    }

    private fun createInputView(): View {
        currentTheme = ThemeManager.current(this)
        val themedContext = ThemeManager.applyToContext(this)
        val view = android.view.LayoutInflater.from(themedContext).inflate(R.layout.input_panel, null)

        panel = InputPanelController(view)
        keystrokes = KeystrokeDispatcher { currentInputConnection }

        orchestrator = InputOrchestrator(
            context = this,
            preferenceStore = ServiceLocator.preferenceStore,
            speechClient = ServiceLocator.speechToTextClient,
            postProcessingClient = ServiceLocator.postProcessingClient,
            capture = MicrophoneCaptureSession(this),
            onTextReady = { text ->
                if (keyboardVisible) {
                    keystrokes.insertText(text)
                } else {
                    copyToClipboard(text)
                }
            },
            onPhaseChanged = { phase -> panel.transitionTo(phase) },
            onAmplitude = { level -> panel.animator.adjustForAmplitude(level) },
            onQueueCountChanged = { count -> panel.updateQueueCount(count) },
            onProcessingPhaseChanged = { phase -> panel.updateProcessingPhase(phase) },
            onPreferencesLoaded = { refreshPostProcessingUI() }
        )

        orchestrator.loadPreferences()
        wireControls(view)
        return view
    }

    override fun onWindowShown() {
        super.onWindowShown()
        DiagnosticLog.record(TAG, "onWindowShown")
        keyboardVisible = true

        // Recreate view if theme changed in settings
        val newTheme = ThemeManager.current(this)
        if (newTheme != currentTheme) {
            setInputView(createInputView())
            return
        }

        if (::orchestrator.isInitialized) orchestrator.reloadAndAutoStart()
        refreshClipboardBar()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        keyboardVisible = false
        if (::orchestrator.isInitialized) orchestrator.gracefulShutdown()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Voice transcription", text))
    }

    override fun onDestroy() {
        super.onDestroy()
        clipboardListener?.let {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .removePrimaryClipChangedListener(it)
        }
        if (::orchestrator.isInitialized) orchestrator.destroy()
    }

    private fun wireControls(view: View) {
        val btnMic: ImageButton = view.findViewById(R.id.btnMic)
        val btnCancel: ImageButton = view.findViewById(R.id.btnCancel)
        val btnBackspace: ImageButton = view.findViewById(R.id.btnBackspace)
        val btnSpace: Button = view.findViewById(R.id.btnSpace)
        val btnEnter: ImageButton = view.findViewById(R.id.btnEnter)
        val btnQuestion: Button = view.findViewById(R.id.btnQuestion)
        val btnExclamation: Button = view.findViewById(R.id.btnExclamation)
        val btnCutAll: ImageButton = view.findViewById(R.id.btnCutAll)
        val btnSettings: ImageButton = view.findViewById(R.id.btnSettings)
        val btnHideKeyboard: ImageButton = view.findViewById(R.id.btnHideKeyboard)
        val btnSend: ImageButton = view.findViewById(R.id.btnSend)

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

        btnCutAll.setOnClickListener { keystrokes.cutAll() }

        btnSpace.setOnClickListener { keystrokes.insertText(" ") }
        btnSpace.setOnLongClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
            true
        }
        btnEnter.setOnClickListener { keystrokes.sendEnter() }
        btnQuestion.setOnClickListener { keystrokes.insertText("?") }
        btnExclamation.setOnClickListener { keystrokes.insertText("!") }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }

        btnHideKeyboard.setOnClickListener { requestHideSelf(0) }

        btnSend.setOnClickListener { keystrokes.sendCtrlEnter() }

        // Clipboard bar
        panel.clipboardBar.setOnClickListener {
            keystrokes.pasteFromClipboard(this)
        }
        setupClipboardMonitor()

        // Post-processing toggle buttons
        wirePostProcessingToggles()
    }

    private fun setupClipboardMonitor() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        // Remove old listener if any
        clipboardListener?.let { clipboard.removePrimaryClipChangedListener(it) }
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            refreshClipboardBar()
        }
        clipboardListener = listener
        clipboard.addPrimaryClipChangedListener(listener)
        // Show current clipboard content
        refreshClipboardBar()
    }

    private fun refreshClipboardBar() {
        if (!::panel.isInitialized) return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this)?.toString()
        } else null
        panel.updateClipboard(text)
    }

    private fun wirePostProcessingToggles() {
        bindToggle(panel.btnPpFix, InputOrchestrator.PpMode.FIX)
        bindToggle(panel.btnPpShorten, InputOrchestrator.PpMode.SHORTEN)
        bindToggle(panel.btnPpEmoji, InputOrchestrator.PpMode.EMOJI)
        bindToggle(panel.btnPpRhyme, InputOrchestrator.PpMode.RHYME)
        bindToggle(panel.btnPpTerminal, InputOrchestrator.PpMode.TERMINAL)
        panel.btnPpTranslate.setOnClickListener {
            orchestrator.togglePpMode(InputOrchestrator.PpMode.TRANSLATE)
            updateToggleUI()
        }
    }

    private fun bindToggle(button: View, mode: InputOrchestrator.PpMode) {
        button.setOnClickListener {
            orchestrator.togglePpMode(mode)
            updateToggleUI()
        }
    }

    private fun refreshPostProcessingUI() {
        val show = orchestrator.isPostProcessingEnabled()
        panel.showPostProcessingButtons(show)
        if (show) {
            panel.showTerminalButton(orchestrator.isTerminalVisible())
            updateToggleUI()
        }
    }

    private fun updateToggleUI() {
        val s = orchestrator.toggles
        panel.updateToggleAppearance(panel.btnPpFix, s.fixActive)
        panel.updateToggleAppearance(panel.btnPpShorten, s.shortenActive)
        panel.updateToggleAppearance(panel.btnPpEmoji, s.emojiActive)
        panel.updateToggleAppearance(panel.btnPpRhyme, s.rhymeActive)
        panel.updateToggleAppearance(panel.btnPpTerminal, s.terminalActive)
        panel.updateTranslateToggle(s.translateActive, orchestrator.getTranslateLang())
    }
}
