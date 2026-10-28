package com.tyraen.voicekeyboard.feature.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.app.ServiceLocator
import com.tyraen.voicekeyboard.core.config.PostProcessingPreferences
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import com.tyraen.voicekeyboard.core.locale.TranscriptionLocale
import com.tyraen.voicekeyboard.feature.postprocessing.PostProcessingActivity
import com.tyraen.voicekeyboard.feature.vocabulary.TextDictionary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Explicit actions on an external editor selection. Network work never modifies the editor:
 * replacements require a preview and another exact selection/session check at Apply time.
 */
class SelectedTextActionsPanel(
    private val root: FrameLayout,
    private val inputConnection: () -> InputConnection?,
    private val editorInfo: () -> EditorInfo?,
    private val editorSession: () -> Long?,
    private val allowed: () -> Boolean,
    private val onNotice: (String) -> Unit
) {
    companion object {
        private const val CONTEXT_LENGTH = 96
        private const val MAX_SELECTION_LENGTH = 16000
        // Saving a completed preview must survive disposal of the keyboard view that owned it.
        private val preservationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    private enum class Screen { CHOOSE, CUSTOM, PROCESSING, PREVIEW, APPLIED }

    private val context = root.context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var screen = Screen.CHOOSE
    private var source: SelectedTextSnapshot? = null
    private var replacement: String? = null
    private var appliedSelection: SelectedTextSnapshot? = null
    private var resultId = ""
    private var preserved = false
    private var savingId: String? = null
    private var sheetHeight = 0
    private var selectionChanged = false
    private var destroyed = false
    private var preferences: PostProcessingPreferences? = null
    private var preferencesLoadFailed = false
    private var requestJob: Job? = null
    private var preferenceJob: Job? = null
    private var lastSelectionStart = editorInfo()?.initialSelStart ?: -1
    private var lastSelectionEnd = editorInfo()?.initialSelEnd ?: -1
    private var applyButton: Button? = null
    private var undoButton: Button? = null
    private var customPreviewButton: Button? = null
    private var saveButton: Button? = null
    private val accessibilityState = HashMap<View, Int>()

    /** Root may temporarily mount this editor above the quick-correction keys. */
    val instructionEditor = EditText(context).apply {
        id = R.id.textActionInstruction
        hint = context.getString(R.string.text_actions_instruction_hint)
        contentDescription = context.getString(R.string.text_actions_instruction)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        showSoftInputOnFocus = false
        minLines = 2
        maxLines = 5
        textSize = 16f
        setTextColor(ContextCompat.getColor(context, R.color.key_text))
        setHintTextColor(ContextCompat.getColor(context, R.color.status_text))
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                customPreviewButton?.isEnabled = !s.isNullOrBlank()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    var onInstructionKeyboardRequested: (() -> Unit)? = null

    val isVisible: Boolean get() = overlay.visibility == View.VISIBLE
    val isEditingInstruction: Boolean get() = screen == Screen.CUSTOM && instructionEditor.hasFocus()

    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), 0, dp(12), dp(12))
    }
    private val overlay = LinearLayout(context).apply {
        id = R.id.textActionsOverlay
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.keyboard_bg))
        visibility = View.GONE
        elevation = dp(8).toFloat()
        isClickable = true
    }

    init {
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPaddingRelative(dp(12), 0, 0, 0)
        }
        val heading = text(context.getString(R.string.text_actions_open), 18f).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            ViewCompat.setAccessibilityHeading(this, true)
        }
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(ImageButton(context).apply {
            id = R.id.btnTextActionsClose
            setImageResource(R.drawable.ic_close)
            imageTintList = ContextCompat.getColorStateList(context, R.color.key_text)
            contentDescription = context.getString(R.string.text_actions_close)
            setBackgroundResource(R.drawable.borderless_ripple)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { hide() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        overlay.addView(header, LinearLayout.LayoutParams(-1, -2))
        overlay.addView(ScrollView(context).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
    }

    fun instructionInputConnection(): InputConnection? =
        if (isEditingInstruction) instructionEditor.onCreateInputConnection(EditorInfo()) else null

    fun toggle() {
        if (isVisible) hide() else {
            if (source == null) {
                if (!allowed()) {
                    notice(R.string.text_actions_unavailable)
                    return
                }
                val selectionLength = try {
                    inputConnection()?.getSelectedText(0)?.length ?: 0
                } catch (_: Exception) { 0 }
                if (selectionLength > MAX_SELECTION_LENGTH) {
                    notice(R.string.text_actions_too_long)
                    return
                }
                val captured = readSelection()
                if (captured == null || captured.text.isBlank()) {
                    notice(R.string.text_actions_select_first)
                    return
                }
                if (captured.text.length > MAX_SELECTION_LENGTH) {
                    notice(R.string.text_actions_too_long)
                    return
                }
                source = captured
                selectionChanged = false
                screen = Screen.CHOOSE
                loadPreferences()
            }
            show()
        }
    }

    /** Reopens the current instruction after the local typing panel returns its editor. */
    fun show() {
        if (destroyed || source == null) return
        if (!isVisible) {
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index)
                if (child === overlay) continue
                accessibilityState[child] = child.importantForAccessibility
                child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        }
        val available = context.resources.displayMetrics.heightPixels
        if (sheetHeight == 0) sheetHeight = maxOf(root.height, dp(280)).coerceAtMost(available)
        overlay.layoutParams = FrameLayout.LayoutParams(-1, sheetHeight)
        overlay.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        render()
    }

    fun hide() {
        requestJob?.cancel()
        requestJob = null
        preferenceJob?.cancel()
        preferenceJob = null
        preservePreview()
        overlay.visibility = View.GONE
        accessibilityState.forEach { (view, state) -> view.importantForAccessibility = state }
        accessibilityState.clear()
        instructionEditor.clearFocus()
        source = null
        replacement = null
        appliedSelection = null
        preferences = null
        screen = Screen.CHOOSE
        sheetHeight = 0
        applyButton = null
        undoButton = null
        customPreviewButton = null
        saveButton = null
    }

    fun destroy() {
        hide()
        destroyed = true
        scope.cancel()
        root.removeView(overlay)
    }

    fun onEditorChanged() {
        lastSelectionStart = editorInfo()?.initialSelStart ?: -1
        lastSelectionEnd = editorInfo()?.initialSelEnd ?: -1
        if (source != null) selectionChanged = true
        refreshReplacementButtons()
    }

    fun onUpdateSelection(start: Int, end: Int) {
        lastSelectionStart = start
        lastSelectionEnd = end
        val captured = source
        if (captured != null && screen != Screen.APPLIED &&
            (minOf(start, end) != captured.start || maxOf(start, end) != captured.end)
        ) selectionChanged = true
        refreshReplacementButtons()
    }

    private fun render() {
        body.removeAllViews()
        applyButton = null
        undoButton = null
        customPreviewButton = null
        saveButton = null
        val captured = source ?: return
        if (screen != Screen.APPLIED) {
            body.addView(text(context.getString(R.string.text_actions_selection), 13f))
            body.addView(text(captured.text).apply {
                setPadding(0, dp(4), 0, dp(12))
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        when (screen) {
            Screen.CHOOSE -> {
                val prefs = preferences
                if (prefs == null) {
                    if (preferencesLoadFailed) {
                        body.addView(text(context.getString(R.string.text_actions_failed)))
                        addAction(R.string.text_actions_open_settings, View.NO_ID) { openPostProcessingSettings() }
                    } else body.addView(ProgressBar(context))
                    return
                }
                if (!prefs.enabled || prefs.apiKey.isBlank()) {
                    body.addView(text(context.getString(R.string.text_actions_configure)))
                    addAction(R.string.text_actions_open_settings, View.NO_ID) { openPostProcessingSettings() }
                    return
                }
                body.addView(text(context.getString(R.string.text_actions_provider_notice), 13f))
                addAction(R.string.text_actions_fix, R.id.btnTextActionFix) { process(SelectedTextAction.FIX) }
                val language = TranscriptionLocale.displayNameIn(prefs.translateLang, context.resources.configuration.locales[0])
                body.addView(button(context.getString(R.string.text_actions_translate, language), R.id.btnTextActionTranslate) {
                    process(SelectedTextAction.TRANSLATE)
                })
                addAction(R.string.text_actions_custom, R.id.btnTextActionCustom) {
                    screen = Screen.CUSTOM
                    render()
                }
            }
            Screen.CUSTOM -> {
                body.addView(text(context.getString(R.string.text_actions_instruction), 13f))
                (instructionEditor.parent as? ViewGroup)?.removeView(instructionEditor)
                body.addView(instructionEditor, LinearLayout.LayoutParams(-1, -2))
                addAction(R.string.text_actions_type_instruction, R.id.btnTextActionTypeInstruction) {
                    instructionEditor.requestFocus()
                    onInstructionKeyboardRequested?.invoke()
                }
                customPreviewButton = button(context.getString(R.string.text_actions_preview), R.id.btnTextActionPreview) {
                    process(SelectedTextAction.CUSTOM)
                }.also {
                    it.isEnabled = instructionEditor.text.isNotBlank()
                    body.addView(it)
                }
            }
            Screen.PROCESSING -> {
                body.addView(ProgressBar(context))
                body.addView(text(context.getString(R.string.text_actions_processing)))
                addAction(android.R.string.cancel, R.id.btnTextActionCancel) { hide() }
            }
            Screen.PREVIEW -> {
                body.addView(text(context.getString(R.string.text_actions_result), 13f))
                body.addView(text(replacement.orEmpty()).apply { id = R.id.textActionResult })
                body.addView(text(context.getString(R.string.text_actions_preview_hint), 13f))
                if (!canApply()) body.addView(text(context.getString(R.string.text_actions_selection_changed), 13f))
                applyButton = button(context.getString(R.string.text_actions_apply), R.id.btnTextActionApply) { applyResult() }
                    .also { it.isEnabled = canApply(); body.addView(it) }
                addAction(R.string.text_actions_copy, R.id.btnTextActionCopy) { copyResult() }
                saveButton = button(context.getString(R.string.text_actions_save), R.id.btnTextActionSave) {
                    saveAndClose()
                }.also {
                    it.isEnabled = savingId != resultId
                    body.addView(it)
                }
            }
            Screen.APPLIED -> {
                body.addView(text(context.getString(R.string.text_actions_applied)))
                body.addView(text(replacement.orEmpty()))
                undoButton = button(context.getString(R.string.text_actions_undo), R.id.btnTextActionUndo) { undo() }
                    .also { it.isEnabled = canUndo(); body.addView(it) }
                addAction(R.string.text_actions_copy, R.id.btnTextActionCopy) { copyResult() }
            }
        }
        if (screen == Screen.CHOOSE || screen == Screen.CUSTOM) fitChooserHeight()
    }

    /** A short chooser should expose all three actions instead of half a final button. */
    private fun fitChooserHeight() {
        if (!isVisible || root.width <= 0) return
        val width = View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY)
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val header = overlay.getChildAt(0)
        header.measure(width, unspecified)
        body.measure(width, unspecified)
        val screenHeight = context.resources.displayMetrics.heightPixels
        val available = (screenHeight - dp(80)).coerceAtLeast(dp(120)).coerceAtMost(screenHeight)
        val contentHeight = header.measuredHeight + body.measuredHeight
        val desired = maxOf(overlay.layoutParams.height, contentHeight).coerceAtMost(available)
        sheetHeight = desired
        if (overlay.layoutParams.height != desired) {
            overlay.layoutParams = FrameLayout.LayoutParams(-1, desired)
            root.requestLayout()
        }
    }

    private fun process(action: SelectedTextAction) {
        val captured = source ?: return
        val prefs = preferences ?: return
        if (!allowed() || selectionChanged || !captured.matches(readSelection())) {
            selectionChanged = true
            notice(R.string.text_actions_selection_changed)
            return
        }
        val instruction = instructionEditor.text.toString()
        if (action == SelectedTextAction.CUSTOM && instruction.isBlank()) return
        instructionEditor.clearFocus()
        screen = Screen.PROCESSING
        render()
        requestJob = scope.launch {
            try {
                val store = ServiceLocator.preferenceStore
                val vocabulary = TextDictionary.mergeVocabulary(
                    store.loadPostProcessingVocabulary(), store.loadVocabulary())
                val prompt = SelectedTextActionPrompts.build(action, captured.text, prefs, instruction, vocabulary)
                val model = if (action == SelectedTextAction.TRANSLATE) prefs.resolvedTranslateModel() else null
                val result = ServiceLocator.postProcessingClient.process(prompt, prefs, model).getOrThrow()
                if (result.isBlank()) {
                    screen = Screen.CHOOSE
                    notice(R.string.text_actions_failed)
                } else {
                    replacement = result
                    resultId = "text-action-${UUID.randomUUID()}"
                    preserved = false
                    screen = Screen.PREVIEW
                }
                if (!destroyed && isVisible) render()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DiagnosticLog.recordFailure("TextActions", "Could not process selection")
                screen = Screen.CHOOSE
                notice(R.string.text_actions_failed)
                if (!destroyed && isVisible) render()
            }
        }
    }

    private fun canApply(): Boolean = savingId != resultId && !selectionChanged &&
        allowed() && source?.matches(readSelection()) == true

    private fun canUndo(): Boolean = allowed() && appliedSelection?.matches(readSelection()) == true

    private fun refreshReplacementButtons() {
        applyButton?.isEnabled = canApply()
        undoButton?.isEnabled = canUndo()
    }

    private fun applyResult() {
        val captured = source ?: return
        val result = replacement ?: return
        if (!canApply()) {
            selectionChanged = true
            notice(R.string.text_actions_selection_changed)
            render()
            return
        }
        val connection = inputConnection() ?: return
        // Source selection is already verified. Do not move it to remembered offsets before
        // committing: that could replace text the user has changed while the request was running.
        screen = Screen.APPLIED
        var inserted = false
        try {
            connection.beginBatchEdit()
            inserted = connection.commitText(result, 1)
            if (inserted) {
                connection.setSelection(captured.start, captured.start + result.length)
            }
        } catch (_: Exception) {
            DiagnosticLog.recordFailure("TextActions", "Editor rejected replacement")
        } finally {
            try { connection.endBatchEdit() } catch (_: Exception) { /* Editor disconnected. */ }
        }
        if (inserted) {
            appliedSelection = captured.afterReplacement(result)
            lastSelectionStart = captured.start
            lastSelectionEnd = captured.start + result.length
        } else {
            screen = Screen.PREVIEW
            notice(R.string.text_actions_failed)
        }
        render()
    }

    private fun undo() {
        val captured = source ?: return
        if (!canUndo()) {
            notice(R.string.text_actions_selection_changed)
            return
        }
        val connection = inputConnection() ?: return
        var restored = false
        try {
            connection.beginBatchEdit()
            restored = connection.commitText(captured.text, 1)
            if (restored) {
                connection.setSelection(captured.start, captured.end)
            }
        } catch (_: Exception) {
            DiagnosticLog.recordFailure("TextActions", "Editor rejected undo")
        } finally {
            try { connection.endBatchEdit() } catch (_: Exception) { /* Editor disconnected. */ }
        }
        if (restored) {
            screen = Screen.PREVIEW
            appliedSelection = null
            lastSelectionStart = captured.start
            lastSelectionEnd = captured.end
            selectionChanged = false
        } else notice(R.string.text_actions_failed)
        render()
    }

    private fun readSelection(): SelectedTextSnapshot? {
        if (!allowed()) return null
        val session = editorSession() ?: return null
        val connection = inputConnection() ?: return null
        return try {
            val selection = connection.getSelectedText(0)?.toString() ?: return null
            if (selection.isEmpty() || selection.length > MAX_SELECTION_LENGTH) return null
            val extracted = connection.getExtractedText(ExtractedTextRequest().apply {
                hintMaxChars = MAX_SELECTION_LENGTH + CONTEXT_LENGTH * 2
                hintMaxLines = 100
            }, 0)
            val offsets = if (extracted != null && extracted.selectionStart >= 0 && extracted.selectionEnd >= 0) {
                (extracted.startOffset + minOf(extracted.selectionStart, extracted.selectionEnd)) to
                    (extracted.startOffset + maxOf(extracted.selectionStart, extracted.selectionEnd))
            } else minOf(lastSelectionStart, lastSelectionEnd) to maxOf(lastSelectionStart, lastSelectionEnd)
            if (offsets.first < 0 || offsets.second - offsets.first != selection.length) return null
            SelectedTextSnapshot(
                session, offsets.first, offsets.second, selection,
                connection.getTextBeforeCursor(CONTEXT_LENGTH, 0)?.toString() ?: return null,
                connection.getTextAfterCursor(CONTEXT_LENGTH, 0)?.toString() ?: return null
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun copyResult() {
        val result = replacement ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.text_actions_result), result))
        notice(R.string.text_actions_copied)
    }

    private fun saveAndClose() {
        val result = replacement ?: return
        if (savingId == resultId) return
        if (preserved) hide() else savePreview(resultId, result, closeOnSuccess = true)
    }

    private fun preservePreview() {
        val result = replacement ?: return
        if (screen == Screen.APPLIED || preserved || result.isBlank() || savingId == resultId) return
        savePreview(resultId, result, closeOnSuccess = false)
    }

    private fun savePreview(id: String, result: String, closeOnSuccess: Boolean) {
        savingId = id
        saveButton?.isEnabled = false
        applyButton?.isEnabled = false
        preservationScope.launch {
            try {
                ServiceLocator.pendingTranscriptStore.save(id, result, false, System.currentTimeMillis())
                if (resultId == id) preserved = true
                // Publication is already durable. A cleanup-marker failure must not report that
                // the text was lost or leave an insertable preview beside its saved duplicate.
                try {
                    ServiceLocator.pendingTranscriptStore.acknowledgeAudioCleanup(id)
                } catch (_: Exception) {
                    DiagnosticLog.recordFailure("TextActions", "Could not acknowledge text-only saved result")
                }
                notice(R.string.text_actions_saved)
                if (closeOnSuccess && !destroyed && resultId == id && isVisible) hide()
            } catch (_: Exception) {
                DiagnosticLog.recordFailure("TextActions", "Could not preserve completed preview")
                notice(R.string.text_actions_save_failed)
            } finally {
                if (savingId == id) savingId = null
                if (!destroyed && resultId == id && isVisible) {
                    saveButton?.isEnabled = true
                    refreshReplacementButtons()
                }
            }
        }
    }

    private fun loadPreferences() {
        preferencesLoadFailed = false
        preferenceJob?.cancel()
        preferenceJob = scope.launch {
            preferences = try {
                ServiceLocator.preferenceStore.loadPostProcessing()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                preferencesLoadFailed = true
                null
            }
            if (!destroyed && isVisible) render()
        }
    }

    private fun openPostProcessingSettings() {
        hide()
        context.startActivity(Intent(context, PostProcessingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun addAction(label: Int, id: Int, action: () -> Unit) {
        body.addView(button(context.getString(label), id, action))
    }

    private fun button(label: String, viewId: Int, action: () -> Unit) = Button(context).apply {
        id = viewId
        text = label
        isAllCaps = false
        textSize = 15f
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = 0
        minimumWidth = 0
        backgroundTintList = null
        val primary = viewId == R.id.btnTextActionApply || viewId == R.id.btnTextActionPreview
        setBackgroundResource(if (primary) R.drawable.control_primary_bg else R.drawable.control_key_bg)
        setTextColor(ContextCompat.getColorStateList(context,
            if (primary) R.color.control_primary_text else R.color.control_key_text))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }

    private fun text(value: String, size: Float = 16f) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(ContextCompat.getColor(context, R.color.key_text))
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }

    private fun notice(message: Int) = onNotice(context.getString(message))
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
