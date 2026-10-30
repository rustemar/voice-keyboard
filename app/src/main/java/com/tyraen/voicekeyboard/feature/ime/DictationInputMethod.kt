package com.tyraen.voicekeyboard.feature.ime

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
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
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.app.ServiceLocator
import com.tyraen.voicekeyboard.core.config.AdvancedPreferences
import com.tyraen.voicekeyboard.core.config.ThemeManager
import com.tyraen.voicekeyboard.core.locale.InterfaceLanguageManager
import com.tyraen.voicekeyboard.core.locale.TranscriptionLocale
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import com.tyraen.voicekeyboard.feature.audio.MicrophoneCaptureSession
import com.tyraen.voicekeyboard.feature.setup.MicrophonePermissionActivity
import com.tyraen.voicekeyboard.feature.setup.SetupActivity
import java.util.concurrent.atomic.AtomicLong

class DictationInputMethod : InputMethodService() {

    companion object {
        private const val TAG = "DictationIME"
        private val editorSessionSequence = AtomicLong()
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
    private lateinit var compactTerminalPanel: CompactTerminalPanel
    private val advancedPreferences by lazy { AdvancedPreferences(this) }
    private lateinit var orchestrator: InputOrchestrator
    private lateinit var keystrokes: KeystrokeDispatcher
    private var pendingTranscriptsPanel: PendingTranscriptsPanel? = null
    private var quickCorrectionPanel: QuickCorrectionPanel? = null
    private var textActionsPanel: SelectedTextActionsPanel? = null
    private var experimentsBar: View? = null
    private var quickCorrectionButton: View? = null
    private var textActionsButton: View? = null
    private var editorSessionToken: Long? = null
    private var currentTheme: String = ""
    private var currentUiLanguage: String = ""
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var keyboardVisible = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastDictationInserted = false
    private var savedResultSinceShown = false
    private val returnCheck = Runnable { returnToPreviousKeyboardIfDone() }
    private val switchCheck = Runnable { recoverIfSwitchDidNotHappen() }
    private var hideIfSwitchFails = false

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        DiagnosticLog.record(TAG, "onCreateInputView")
        return createInputView()
    }

    private fun createInputView(): View {
        quickCorrectionPanel?.destroy()
        quickCorrectionPanel = null
        textActionsPanel?.destroy()
        textActionsPanel = null
        pendingTranscriptsPanel?.destroy()
        pendingTranscriptsPanel = null
        if (::keystrokes.isInitialized) keystrokes.stopBackspaceRepeat()
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
        currentUiLanguage = InterfaceLanguageManager.resolveActive(this)
        val themedContext = ThemeManager.applyToContext(InterfaceLanguageManager.applyTo(this))
        val fullPanel = LayoutInflater.from(themedContext).inflate(R.layout.input_panel, null) as ConstraintLayout
        compactTerminalPanel = CompactTerminalPanel(fullPanel)
        val view = compactTerminalPanel.root
        matchNavigationBarToPanel(themedContext)

        panel = InputPanelController(view)
        keystrokes = KeystrokeDispatcher { currentInputConnection }

        orchestrator = InputOrchestrator(
            context = this,
            preferenceStore = ServiceLocator.preferenceStore,
            processingQueue = ServiceLocator.transcriptionQueue,
            capture = MicrophoneCaptureSession(this),
            editorSessionToken = { editorSessionToken },
            onTextReady = { text, addTrailingSpace, originSessionToken ->
                // A result belongs to the editor connection that started its recording. A new
                // field or connection must not receive a late result intended for the old one.
                // Returning false makes the queue save it for an explicit insertion instead.
                val inserted = originSessionToken == editorSessionToken &&
                    keyboardVisible && !isVoiceBlocked() &&
                    keystrokes.insertDictation(text, addTrailingSpace)
                if (!inserted && keyboardVisible) savedResultSinceShown = true
                scheduleReturnToPreviousKeyboard(inserted)
                inserted
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
            onCancellableWorkChanged = { available -> panel.updateCancellableWork(available) },
            onPreferencesLoaded = {
                panel.renderSilenceAutoStop(orchestrator.isSilenceAutoStopEnabled())
                refreshPostProcessingUI()
                refreshLanguageKey()
            },
            onPermissionNeeded = { requestMicPermission() },
            voiceAllowed = { !isVoiceBlocked() },
            onVoiceBlocked = { panel.showPasswordFieldNotice() },
            onApiKeyNeeded = { openSettings() }
        )
        orchestrator.viewVisible = keyboardVisible

        orchestrator.loadPreferences()
        wireControls(view)
        applyFieldToPanel(currentInputEditorInfo)
        pendingTranscriptsPanel = PendingTranscriptsPanel(
            view,
            ServiceLocator.pendingTranscriptStore,
            onInsert = { transcript ->
                keyboardVisible && editorSessionToken != null && !isVoiceBlocked() &&
                    keystrokes.insertDictation(transcript.text, transcript.addTrailingSpace)
            },
            onNotice = { message -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
        )
        textActionsPanel = SelectedTextActionsPanel(
            root = view,
            inputConnection = { currentInputConnection },
            editorInfo = { currentInputEditorInfo },
            editorSession = { editorSessionToken },
            allowed = { keyboardVisible && !isVoiceBlocked() },
            onNotice = { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        )
        quickCorrectionPanel = QuickCorrectionPanel(
            root = view,
            getInputConnection = { currentInputConnection },
            onEnter = { keystrokes.pressEnter(enterFor(currentInputEditorInfo)) },
            onShown = { shown -> if (shown) suspendAutomaticReturn() },
            onNotice = { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        )
        textActionsPanel?.onInstructionKeyboardRequested = {
            textActionsPanel?.let { actions ->
                quickCorrectionPanel?.showForInstruction(actions.instructionEditor) { actions.show() }
            }
        }
        refreshExperimentalControls()
        return view
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        quickCorrectionPanel?.stopRepeatingKeys()
        // Even restartInput can represent another DOM field in a browser. Treat replacement
        // editor connections conservatively; recreating just our panel keeps the token intact.
        if (!restarting && editorSessionToken != null && ::orchestrator.isInitialized) {
            orchestrator.gracefulShutdown()
        }
        editorSessionToken = editorSessionSequence.incrementAndGet()
        if (quickCorrectionPanel?.isInstructionMode == true) quickCorrectionPanel?.hide()
        textActionsPanel?.onEditorChanged()
        lastDictationInserted = false
        mainHandler.removeCallbacks(returnCheck)
        if (::keystrokes.isInitialized) keystrokes.stopBackspaceRepeat()
        val contextAllowed = info != null &&
            !EditorField.isPassword(info.inputType) &&
            info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
        ServiceLocator.transcriptionQueue.setPostProcessingContextScope(
            if (contextAllowed) info?.packageName else null
        )
    }

    override fun onFinishInput() {
        quickCorrectionPanel?.stopRepeatingKeys()
        editorSessionToken = null
        if (quickCorrectionPanel?.isInstructionMode == true) quickCorrectionPanel?.hide()
        textActionsPanel?.onEditorChanged()
        lastDictationInserted = false
        mainHandler.removeCallbacks(returnCheck)
        if (::keystrokes.isInitialized) keystrokes.stopBackspaceRepeat()
        if (::orchestrator.isInitialized) orchestrator.gracefulShutdown()
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        textActionsPanel?.onUpdateSelection(newSelStart, newSelEnd)
    }

    /** Focus moved to another field (or the keyboard opened on one): follow what it asks for. */
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyFieldToPanel(info)
        // A recording started in the previous field (a login's user name, say) stops when focus
        // moves into a password field, so the mic is not left open there. What was said is still
        // queued, and its result is saved for later insertion.
        if (::orchestrator.isInitialized && isVoiceBlocked(info)) {
            orchestrator.gracefulShutdown()
        }
    }

    private fun applyFieldToPanel(info: EditorInfo?) {
        if (!::panel.isInitialized) return
        val inputType = info?.inputType ?: InputType.TYPE_NULL
        val enter = enterFor(info)
        panel.setVoiceBlocked(isVoiceBlocked(info))
        refreshExperimentalControls()
        panel.updateEnterKey(enter, newLineOnLongPress = enter is EditorField.Enter.Action && EditorField.isMultiLine(inputType))
    }

    private fun isVoiceBlocked(info: EditorInfo? = currentInputEditorInfo): Boolean {
        val inputType = info?.inputType ?: InputType.TYPE_NULL
        if (!EditorField.isPassword(inputType)) return false
        if (info?.packageName != EditorField.TERMIUS_PACKAGE) return true
        val fieldResourceName = try {
            packageManager.getResourcesForApplication(info.packageName).getResourceName(info.fieldId)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: Resources.NotFoundException) {
            null
        }
        return EditorField.blocksVoice(inputType, info.packageName, fieldResourceName)
    }

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
        savedResultSinceShown = false

        // Recreate view if theme changed in settings
        val newTheme = ThemeManager.current(this)
        val newLanguage = InterfaceLanguageManager.resolveActive(this)
        if (newTheme != currentTheme || newLanguage != currentUiLanguage) {
            setInputView(createInputView())
        }

        if (::orchestrator.isInitialized) {
            orchestrator.viewVisible = true
            orchestrator.reloadAndAutoStart()
        }
        refreshClipboardBar()
        refreshExperimentalControls()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        keyboardVisible = false
        lastDictationInserted = false
        mainHandler.removeCallbacks(returnCheck)
        mainHandler.removeCallbacks(switchCheck)
        if (::keystrokes.isInitialized) keystrokes.stopBackspaceRepeat()
        pendingTranscriptsPanel?.hide()
        quickCorrectionPanel?.hide()
        textActionsPanel?.hide()
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

    private fun openSettings() {
        // SINGLE_TOP reuses a settings screen that is already open instead of stacking a second
        // one on top of it.
        startActivity(Intent(this, SetupActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
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
        if (quickCorrectionPanel?.visible == true || textActionsPanel?.isVisible == true) return
        val parked = ServiceLocator.parkedRecordingStore
        val handBack = ReturnToPreviousKeyboard.shouldReturnToPreviousKeyboard(
            enabled = orchestrator.isReturnToPreviousKeyboardEnabled(),
            visible = keyboardVisible,
            inserted = lastDictationInserted && !savedResultSinceShown,
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
            // app has already deactivated: anything delivered in that gap is saved for later.
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
        if (hideIfSwitchFails) showKeyboardPickerOrHide()
    }

    /** A manual return must still offer a way out when Android has no usable keyboard history. */
    private fun showKeyboardPickerOrHide() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val otherKeyboardEnabled = imm.enabledInputMethodList.any { it.packageName != packageName }
        if (otherKeyboardEnabled) {
            runCatching { imm.showInputMethodPicker() }
                .onFailure {
                    DiagnosticLog.recordFailure(TAG, "Opening the keyboard picker failed", it)
                    requestHideSelf(0)
                }
        } else {
            requestHideSelf(0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(returnCheck)
        mainHandler.removeCallbacks(switchCheck)
        editorSessionToken = null
        quickCorrectionPanel?.destroy()
        quickCorrectionPanel = null
        textActionsPanel?.destroy()
        textActionsPanel = null
        pendingTranscriptsPanel?.destroy()
        pendingTranscriptsPanel = null
        if (::keystrokes.isInitialized) keystrokes.stopBackspaceRepeat()
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
        experimentsBar = view.findViewById(R.id.experimentalActionsBar)
        quickCorrectionButton = view.findViewById<View>(R.id.btnQuickCorrection).apply {
            ViewCompat.setTooltipText(this, contentDescription)
            setOnClickListener {
                if (!advancedPreferences.quickCorrectionEnabled) return@setOnClickListener
                suspendAutomaticReturn()
                orchestrator.gracefulShutdown()
                pendingTranscriptsPanel?.hide()
                textActionsPanel?.hide()
                quickCorrectionPanel?.toggle()
            }
        }
        textActionsButton = view.findViewById<View>(R.id.btnTextActions).apply {
            ViewCompat.setTooltipText(this, contentDescription)
            setOnClickListener {
                if (!advancedPreferences.textActionsEnabled) return@setOnClickListener
                suspendAutomaticReturn()
                orchestrator.gracefulShutdown()
                pendingTranscriptsPanel?.hide()
                quickCorrectionPanel?.hide()
                textActionsPanel?.toggle()
            }
        }

        val microphonePress = MicrophonePressGuard()
        btnMic.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> microphonePress.onTouchDown(orchestrator.currentPhase)
                MotionEvent.ACTION_CANCEL -> microphonePress.cancelTouch()
            }
            false // Preserve the platform button's pressed state, click and accessibility behavior.
        }
        btnMic.setOnClickListener {
            if (microphonePress.shouldHandleClick(orchestrator.currentPhase)) {
                orchestrator.handleAction(InputAction.ToggleCapture)
            }
        }
        btnCancel.setOnClickListener { orchestrator.handleAction(InputAction.CancelOperation) }
        btnRetryFailed.setOnClickListener { orchestrator.retryFailed() }
        panel.btnSilenceAutoStop.setOnClickListener {
            panel.renderSilenceAutoStop(orchestrator.toggleSilenceAutoStop())
        }
        panel.renderSilenceAutoStop(orchestrator.isSilenceAutoStopEnabled())
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

        // Touch deletes immediately and repeats while held. Accessibility and hardware clicks
        // use the click listener; suppress only the click announced for an already-handled touch.
        var announcingHandledTouch = false
        btnBackspace.setOnClickListener {
            if (!announcingHandledTouch) keystrokes.deleteBack()
        }
        btnBackspace.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    keystrokes.startBackspaceRepeat()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    keystrokes.stopBackspaceRepeat()
                    announcingHandledTouch = true
                    try {
                        v.performClick()
                    } finally {
                        announcingHandledTouch = false
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
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

        btnSettings.setOnClickListener { openSettings() }

        // Back to the keyboard the user came from: Gboard via the keyboard list, HeliBoard's mic
        // key, or whichever keyboard they type with. Just hiding is left to the system back gesture
        // and button. If Android has no usable history, offer its keyboard picker instead.
        btnPreviousKeyboard.setOnClickListener {
            // A reported switch is still under way (the panel lingers until the app restarts
            // input): a second tap would have the platform switch straight back to this keyboard.
            if (!keyboardVisible) return@setOnClickListener
            // The switch skips onWindowHidden, so a recording in progress is queued here first.
            orchestrator.gracefulShutdown()
            if (!switchToPreviousKeyboard(hideIfItFails = true)) showKeyboardPickerOrHide()
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

    private fun suspendAutomaticReturn() {
        lastDictationInserted = false
        mainHandler.removeCallbacks(returnCheck)
    }

    private fun refreshExperimentalControls() {
        val letters = advancedPreferences.quickCorrectionEnabled
        val actions = advancedPreferences.textActionsEnabled && !isVoiceBlocked()
        quickCorrectionButton?.visibility = if (letters) View.VISIBLE else View.GONE
        textActionsButton?.visibility = if (actions) View.VISIBLE else View.GONE
        experimentsBar?.visibility = if (letters || actions) View.VISIBLE else View.GONE
        if (!letters && quickCorrectionPanel?.isInstructionMode != true) quickCorrectionPanel?.hide()
        if (!advancedPreferences.textActionsEnabled) {
            if (quickCorrectionPanel?.isInstructionMode == true) quickCorrectionPanel?.hide()
            textActionsPanel?.hide()
        }
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
        } else {
            refreshCompactTerminal()
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
        refreshCompactTerminal()
    }

    private fun refreshCompactTerminal() {
        compactTerminalPanel.updateOptions(
            advancedPreferences.compactTerminal,
            orchestrator.isPostProcessingEnabled(),
            orchestrator.isTerminalVisible(),
            orchestrator.toggles.terminalActive
        )
    }
}
