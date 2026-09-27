package com.tyraen.voicekeyboard.feature.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.app.ServiceLocator
import com.tyraen.voicekeyboard.core.config.ThemeManager
import com.tyraen.voicekeyboard.core.locale.InterfaceLanguageManager
import com.tyraen.voicekeyboard.core.locale.TranscriptionLocale
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import com.tyraen.voicekeyboard.feature.audio.MicrophoneCaptureSession
import com.tyraen.voicekeyboard.feature.setup.MicrophonePermissionActivity
import com.tyraen.voicekeyboard.feature.setup.SetupActivity

class DictationInputMethod : InputMethodService() {

    companion object {
        private const val TAG = "DictationIME"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(InterfaceLanguageManager.applyTo(newBase))
    }

    // The localized base context above hands out a LayoutInflater bound to itself. The platform
    // inflates the IME's own navigation bar (gesture navigation) with LayoutInflater.from(this), and
    // its back key only acts when its context is this InputMethodService, so the nav-bar hide button
    // silently did nothing. Hand out an inflater bound to the service instead.
    private val serviceInflater by lazy { LayoutInflater.from(baseContext).cloneInContext(this) }

    override fun getSystemService(name: String): Any? =
        if (name == LAYOUT_INFLATER_SERVICE) serviceInflater else super.getSystemService(name)

    private lateinit var panel: InputPanelController
    private lateinit var orchestrator: InputOrchestrator
    private lateinit var keystrokes: KeystrokeDispatcher
    private var currentTheme: String = ""
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var keyboardVisible = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastDictationInserted = false
    private var clipboardFallbackSinceShown = false
    private val returnCheck = Runnable { returnToPreviousKeyboardIfDone() }
    private val switchCheck = Runnable { recoverIfSwitchDidNotHappen() }
    private var hideIfSwitchFails = false

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        DiagnosticLog.record(TAG, "onCreateInputView")
        return createInputView()
    }

    private fun createInputView(): View {
        // Releasing the previous orchestrator unbinds it from the shared queue and frees its mic
        // session. The queue itself (and any parked recordings) is process-wide and survives this.
        // The rebuild runs on every configuration change (rotation, dark mode, font size) without
        // onWindowHidden, so a recording in progress is queued first; the new orchestrator binds to
        // the queue at once and types the text as usual.
        if (::orchestrator.isInitialized) {
            orchestrator.gracefulShutdown()
            orchestrator.destroy()
        }

        currentTheme = ThemeManager.current(this)
        val themedContext = ThemeManager.applyToContext(this)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.input_panel, null)
        matchNavigationBarToPanel(themedContext)

        panel = InputPanelController(view)
        keystrokes = KeystrokeDispatcher { currentInputConnection }

        orchestrator = InputOrchestrator(
            context = this,
            preferenceStore = ServiceLocator.preferenceStore,
            processingQueue = ServiceLocator.transcriptionQueue,
            capture = MicrophoneCaptureSession(this),
            onTextReady = { text, addTrailingSpace ->
                // A visible panel can still lack an InputConnection (some apps drop it mid-edit);
                // the clipboard is the fallback there too, never a silent loss. A dictation started
                // elsewhere that arrives while a password field has focus goes there as well.
                val inserted = keyboardVisible && !inPasswordField() &&
                    keystrokes.insertDictation(text, addTrailingSpace)
                if (!inserted) {
                    copyToClipboard(if (addTrailingSpace) "$text " else text)
                    if (keyboardVisible) clipboardFallbackSinceShown = true
                }
                scheduleReturnToPreviousKeyboard(inserted)
            },
            onPhaseChanged = { phase -> panel.transitionTo(phase) },
            onAmplitude = { level -> panel.animator.adjustForAmplitude(level) },
            onQueueCountChanged = { count ->
                panel.updateQueueCount(count)
                // A queue that drains without a delivery (a blank transcript, say) gets the check too.
                if (count == 0) postReturnCheck()
            },
            onProcessingPhaseChanged = { phase -> panel.updateProcessingPhase(phase) },
            onFailedCountChanged = { count -> panel.updateFailedCount(count) },
            onPreferencesLoaded = {
                refreshPostProcessingUI()
                refreshLanguageKey()
            },
            onPermissionNeeded = { requestMicPermission() },
            voiceAllowed = { !inPasswordField() },
            onVoiceBlocked = { panel.showPasswordFieldNotice() }
        )
        orchestrator.viewVisible = keyboardVisible

        orchestrator.loadPreferences()
        wireControls(view)
        applyFieldToPanel(currentInputEditorInfo)
        return view
    }

    /** Focus moved to another field (or the keyboard opened on one): follow what it asks for. */
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyFieldToPanel(info)
        // A recording started in the previous field (a login's user name, say) stops when focus
        // moves into a password field, so the mic is not left open there. What was said is still
        // queued, and while the password field has focus it goes to the clipboard.
        if (::orchestrator.isInitialized && EditorField.isPassword(info?.inputType ?: InputType.TYPE_NULL)) {
            orchestrator.gracefulShutdown()
        }
    }

    private fun applyFieldToPanel(info: EditorInfo?) {
        if (!::panel.isInitialized) return
        val inputType = info?.inputType ?: InputType.TYPE_NULL
        val enter = enterFor(info)
        panel.setVoiceBlocked(EditorField.isPassword(inputType))
        panel.updateEnterKey(enter, newLineOnLongPress = enter is EditorField.Enter.Action && EditorField.isMultiLine(inputType))
    }

    private fun inPasswordField(): Boolean =
        EditorField.isPassword(currentInputEditorInfo?.inputType ?: InputType.TYPE_NULL)

    private fun enterFor(info: EditorInfo?): EditorField.Enter =
        if (info == null) EditorField.Enter.NewLine
        else EditorField.enter(info.imeOptions, info.actionId, info.actionLabel)

    /**
     * Paints the navigation bar under the panel in the panel's background, with dark buttons on the
     * light theme, instead of the default black strip. Android 9+: 8.1 draws no bar colour for the
     * keyboard window and forces light buttons while it is shown, so there the bar stays as it was.
     * Re-run on every view rebuild, which is what a theme change triggers.
     */
    private fun matchNavigationBarToPanel(themedContext: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val imeWindow = window.window ?: return
        imeWindow.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        imeWindow.navigationBarColor = ContextCompat.getColor(themedContext, R.color.keyboard_bg)
        WindowInsetsControllerCompat(imeWindow, imeWindow.decorView).isAppearanceLightNavigationBars =
            themedContext.resources.getBoolean(R.bool.light_system_bars)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        DiagnosticLog.record(TAG, "onWindowShown")
        keyboardVisible = true
        lastDictationInserted = false
        clipboardFallbackSinceShown = false

        // Recreate view if theme changed in settings
        val newTheme = ThemeManager.current(this)
        if (newTheme != currentTheme) {
            setInputView(createInputView())
            return
        }

        if (::orchestrator.isInitialized) {
            orchestrator.viewVisible = true
            orchestrator.reloadAndAutoStart()
        }
        refreshClipboardBar()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        keyboardVisible = false
        lastDictationInserted = false
        mainHandler.removeCallbacks(returnCheck)
        mainHandler.removeCallbacks(switchCheck)
        if (::orchestrator.isInitialized) {
            orchestrator.viewVisible = false
            orchestrator.gracefulShutdown()
        }
    }

    /**
     * An InputMethodService cannot ask for runtime permissions itself; a transparent activity
     * does it on the keyboard's behalf (or opens app settings after "Don't ask again").
     */
    private fun requestMicPermission() {
        startActivity(Intent(this, MicrophonePermissionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Voice transcription", text))
    }

    /**
     * With "Return to previous keyboard" on, hands the screen back to the keyboard that opened
     * this one (another keyboard's mic key, say) once the last queued dictation has been typed.
     * The check runs [ReturnToPreviousKeyboard.DELAY_MS] later rather than inside the delivery; each
     * new delivery restarts the wait, so only the last one can trigger it.
     */
    private fun scheduleReturnToPreviousKeyboard(inserted: Boolean) {
        lastDictationInserted = inserted
        postReturnCheck()
    }

    private fun postReturnCheck() {
        mainHandler.removeCallbacks(returnCheck)
        if (!lastDictationInserted || !::orchestrator.isInitialized) return
        if (orchestrator.isReturnToPreviousKeyboardEnabled()) {
            mainHandler.postDelayed(returnCheck, ReturnToPreviousKeyboard.DELAY_MS)
        }
    }

    private fun returnToPreviousKeyboardIfDone() {
        if (!::orchestrator.isInitialized) return
        val parked = ServiceLocator.parkedRecordingStore
        val handBack = ReturnToPreviousKeyboard.shouldReturnToPreviousKeyboard(
            enabled = orchestrator.isReturnToPreviousKeyboardEnabled(),
            visible = keyboardVisible,
            inserted = lastDictationInserted && !clipboardFallbackSinceShown,
            idle = orchestrator.currentPhase is InputPhase.Ready,
            pendingCount = ServiceLocator.transcriptionQueue.pendingCount,
            failedCount = parked.count.value,
            parkedLoaded = parked.isLoaded
        )
        // If there is no previous keyboard to go back to, stay put: hiding would only bring this
        // one back on the next field.
        if (handBack) switchToPreviousKeyboard(hideIfItFails = false)
    }

    /**
     * Switches to the keyboard that was active before this one; false when there is none or the
     * platform declines. Below API 28 the service has no such call, so it goes through
     * InputMethodManager with the window token, like other voice keyboards do. A reported switch is
     * checked a moment later; [hideIfItFails] says what to do if it did not happen after all.
     */
    private fun switchToPreviousKeyboard(hideIfItFails: Boolean): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToPreviousInputMethod()
        } else {
            val token = window?.window?.attributes?.token
            @Suppress("DEPRECATION")
            token != null &&
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).switchToLastInputMethod(token)
        }
    }.onFailure { DiagnosticLog.recordFailure(TAG, "Switching to the previous keyboard failed", it) }
        .getOrDefault(false)
        .also { switched ->
            DiagnosticLog.record(TAG, "Switch to previous keyboard: ${if (switched) "done" else "nothing to switch to"}")
            if (!switched) return@also
            // The service lives on until onDestroy, still holding the editor's connection, which the
            // app has already deactivated: anything delivered in that gap goes to the clipboard.
            keyboardVisible = false
            orchestrator.viewVisible = false
            mainHandler.removeCallbacks(returnCheck)
            hideIfSwitchFails = hideIfItFails
            mainHandler.removeCallbacks(switchCheck)
            mainHandler.postDelayed(switchCheck, ReturnToPreviousKeyboard.VERIFY_DELAY_MS)
        }

    /**
     * A real switch destroys this service within moments, which cancels this check. If it is still
     * here, still shown and still the selected keyboard, the platform said "done" without
     * switching: undo the invisible state set for the switch, so dictations reach the field again,
     * and hide if the user asked to leave.
     */
    private fun recoverIfSwitchDidNotHappen() {
        val selected = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull()
        if (!ReturnToPreviousKeyboard.switchDidNotHappen(selected, packageName) || !isInputViewShown) return
        DiagnosticLog.record(TAG, "Switch to previous keyboard: reported done, but this keyboard is still selected")
        keyboardVisible = true
        if (::orchestrator.isInitialized) orchestrator.viewVisible = true
        if (hideIfSwitchFails) requestHideSelf(0)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(returnCheck)
        mainHandler.removeCallbacks(switchCheck)
        clipboardListener?.let {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .removePrimaryClipChangedListener(it)
        }
        if (::orchestrator.isInitialized) {
            // Switching keyboards (the back key, the system picker) destroys this service without
            // onWindowHidden: queue a recording still in progress, or destroy() would discard it.
            orchestrator.gracefulShutdown()
            orchestrator.destroy()
        }
    }

    private fun wireControls(view: View) {
        val btnMic: ImageButton = view.findViewById(R.id.btnMic)
        val btnCancel: ImageButton = view.findViewById(R.id.btnCancel)
        val btnBackspace: ImageButton = view.findViewById(R.id.btnBackspace)
        val btnSpace: Button = view.findViewById(R.id.btnSpace)
        val btnEnter: ImageButton = view.findViewById(R.id.btnEnter)
        val btnPeriod: Button = view.findViewById(R.id.btnPeriod)
        val btnQuestion: Button = view.findViewById(R.id.btnQuestion)
        val btnExclamation: Button = view.findViewById(R.id.btnExclamation)
        val btnCutAll: ImageButton = view.findViewById(R.id.btnCutAll)
        val btnSettings: ImageButton = view.findViewById(R.id.btnSettings)
        val btnPreviousKeyboard: ImageButton = view.findViewById(R.id.btnPreviousKeyboard)
        val btnSend: ImageButton = view.findViewById(R.id.btnSend)
        val btnRetryFailed: ImageButton = view.findViewById(R.id.btnRetryFailed)

        btnMic.setOnClickListener { orchestrator.handleAction(InputAction.ToggleCapture) }
        btnCancel.setOnClickListener { orchestrator.handleAction(InputAction.CancelOperation) }
        btnRetryFailed.setOnClickListener { orchestrator.retryFailed() }
        // Deleting failed recordings is destructive, so it takes two long-presses: the first one
        // explains what a second one will do, and the offer expires with the notice.
        btnRetryFailed.setOnLongClickListener {
            if (panel.noticeShowing(InputPanelController.Notice.DISCARD_CONFIRM)) {
                orchestrator.discardFailed { removed ->
                    panel.showNotice(getString(R.string.notice_discard_failed_done, removed))
                }
            } else {
                panel.showNotice(
                    getString(R.string.notice_discard_failed_confirm),
                    kind = InputPanelController.Notice.DISCARD_CONFIRM
                )
            }
            true
        }

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
        btnEnter.setOnClickListener { keystrokes.pressEnter(enterFor(currentInputEditorInfo)) }
        // Only long-clickable where Enter runs an action in a multi-line field (see updateEnterKey).
        btnEnter.setOnLongClickListener {
            keystrokes.insertNewLine()
            true
        }
        btnPeriod.setOnClickListener { keystrokes.insertPunctuation(".") }
        btnQuestion.setOnClickListener { keystrokes.insertPunctuation("?") }
        btnExclamation.setOnClickListener { keystrokes.insertPunctuation("!") }

        btnSettings.setOnClickListener {
            // SINGLE_TOP reuses a settings screen that is already open instead of stacking a
            // second one on top of it.
            startActivity(Intent(this, SetupActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }

        // Back to the keyboard the user came from: Gboard via the keyboard list, HeliBoard's mic
        // key, or whichever keyboard they type with. Just hiding is left to the system back gesture
        // and button; here it is only the fallback when there is nothing to go back to.
        btnPreviousKeyboard.setOnClickListener {
            // A reported switch is still under way (the panel lingers until the app restarts
            // input): a second tap would have the platform switch straight back to this keyboard.
            if (!keyboardVisible) return@setOnClickListener
            // The switch skips onWindowHidden, so a recording in progress is queued here first.
            orchestrator.gracefulShutdown()
            if (!switchToPreviousKeyboard(hideIfItFails = true)) requestHideSelf(0)
        }
        // Long-press names the key, like the mode toggles (see bindToggle).
        ViewCompat.setTooltipText(btnPreviousKeyboard, btnPreviousKeyboard.contentDescription)

        btnSend.setOnClickListener {
            val enter = enterFor(currentInputEditorInfo)
            if (EditorField.sendsByAction(enter)) keystrokes.pressEnter(enter) else keystrokes.sendCtrlEnter()
        }

        // Dictation language: tap cycles through the configured codes, long-press picks one.
        panel.btnLanguage.setOnClickListener {
            panel.updateLanguageKey(orchestrator.cycleLanguage(), visible = true)
        }
        panel.btnLanguage.setOnLongClickListener { showLanguagePicker(); true }

        // Clipboard bar
        panel.clipboardBar.setOnClickListener {
            keystrokes.pasteFromClipboard(this)
        }
        setupClipboardMonitor()

        // Post-processing toggle buttons
        wirePostProcessingToggles()
    }

    /** The key only earns its space when there is something to switch between. */
    private fun refreshLanguageKey() {
        if (!::panel.isInitialized) return
        val codes = orchestrator.getLanguageCodes()
        panel.updateLanguageKey(orchestrator.getActiveLanguage(), visible = codes.size > 1)
    }

    private fun showLanguagePicker() {
        val codes = orchestrator.getLanguageCodes()
        if (codes.size < 2) return

        val menu = PopupMenu(panel.btnLanguage.context, panel.btnLanguage)
        codes.forEachIndexed { index, code ->
            menu.menu.add(0, index, index, TranscriptionLocale.longLabel(code))
        }
        menu.setOnMenuItemClickListener { item ->
            panel.updateLanguageKey(orchestrator.selectLanguage(codes[item.itemId]), visible = true)
            true
        }
        menu.show()
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
        bindToggle(panel.btnPpTranslate, InputOrchestrator.PpMode.TRANSLATE)
    }

    private fun bindToggle(button: View, mode: InputOrchestrator.PpMode) {
        // Long-press names the mode. ViewCompat skips API 24-25: appcompat's fallback there needs
        // an AppCompat theme the IME doesn't have. Same text as the content description, so
        // TalkBack reads it once; updateTranslateToggle replaces Translate's with its target.
        ViewCompat.setTooltipText(button, button.contentDescription)
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
