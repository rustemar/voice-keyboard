package com.tyraen.voicekeyboard.feature.ime

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.core.locale.InterfaceLanguageManager

/** Direct keys live in our IME window, keeping the destination editor connection intact. */
class QuickCorrectionPanel(
    private val root: FrameLayout,
    private val getInputConnection: () -> InputConnection?,
    private val onEnter: () -> Unit,
    private val onShown: (Boolean) -> Unit,
    private val onNotice: (String) -> Unit
) {
    private val context = root.context
    private val preferences = context.getSharedPreferences("quick_correction", Context.MODE_PRIVATE)
    private var layout = QuickCorrectionLayouts.initial(
        preferences.getString("layout", null), InterfaceLanguageManager.resolveActive(context)
    )
    private var uppercase = false
    private var capsLock = false
    private var symbols = false
    private var showingChoices = false
    private var destroyed = false
    private var ownAccessibilityState: Int? = null
    private val accessibilityState = HashMap<View, Int>()
    private var instructionEditor: EditText? = null
    private var instructionConnection: InputConnection? = null
    private var instructionParent: ViewGroup? = null
    private var instructionIndex = -1
    private var instructionParams: ViewGroup.LayoutParams? = null
    private var instructionDone: (() -> Unit)? = null
    private val instructionHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val language = Button(context)
    private val close = ImageButton(context)
    private val paste = ImageButton(context)
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        // A layout's physical ordering is independent of the interface's writing direction.
        layoutDirection = View.LAYOUT_DIRECTION_LTR
    }
    private val scroll = ScrollView(context).apply {
        isFillViewport = false
        addView(body, FrameLayout.LayoutParams(-1, -2))
    }
    private val overlay = LinearLayout(context).apply {
        id = R.id.quickCorrectionOverlay
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.keyboard_bg))
        visibility = View.GONE
        elevation = dp(12).toFloat()
        isClickable = true
    }

    val visible: Boolean get() = overlay.visibility == View.VISIBLE
    val isInstructionMode: Boolean get() = instructionEditor != null

    init {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        language.apply {
            id = R.id.btnQuickCorrectionLayout
            isAllCaps = false
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.key_text))
            setBackgroundResource(R.drawable.borderless_ripple)
            setOnClickListener { showLayouts() }
        }
        header.addView(language, LinearLayout.LayoutParams(0, dp(48), 1f))
        paste.apply {
            id = R.id.btnQuickCorrectionPaste
            setImageResource(R.drawable.ic_paste)
            imageTintList = ContextCompat.getColorStateList(context, R.color.key_text)
            setBackgroundResource(R.drawable.borderless_ripple)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            contentDescription = context.getString(android.R.string.paste)
            setOnClickListener { keystrokes.pasteFromClipboard(context) }
        }
        header.addView(paste, LinearLayout.LayoutParams(dp(48), dp(48)))
        close.apply {
            id = R.id.btnQuickCorrectionClose
            setImageResource(R.drawable.ic_close)
            imageTintList = ContextCompat.getColorStateList(context, R.color.key_text)
            setBackgroundResource(R.drawable.borderless_ripple)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            contentDescription = context.getString(R.string.qc_return_to_voice)
            setOnClickListener { finishEditing() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
        overlay.addView(header, LinearLayout.LayoutParams(-1, dp(48)))
        overlay.addView(instructionHost, LinearLayout.LayoutParams(-1, -2))
        overlay.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        render()
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()

    fun toggle() { if (visible) hide() else show() }

    private fun show() {
        if (destroyed || visible) return
        // Another sheet may have hidden this GONE sibling from accessibility. Becoming the
        // foreground sheet must expose our own subtree as well as hide the background sheets.
        ownAccessibilityState = overlay.importantForAccessibility
        overlay.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child === overlay) continue
            accessibilityState[child] = child.importantForAccessibility
            child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        showingChoices = false
        resize()
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        render()
        onShown(true)
        language.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    }

    private fun resize() {
        // Compact and landscape panels get a scrollable sheet, not clipped or shrunken keys.
        val available = context.resources.displayMetrics.heightPixels * 2 / 3
        val desired = dp(if (instructionEditor == null) 310 else 390).coerceAtMost(available)
        // The foreground sheet must cover the existing keyboard even when its established
        // landscape height exceeds our preferred size. A shorter sheet exposes live controls
        // from the background panel beneath its bottom edge.
        overlay.layoutParams = FrameLayout.LayoutParams(-1,
            maxOf(root.height, desired).coerceAtMost(context.resources.displayMetrics.heightPixels))
    }

    /** Temporarily use the same keys for an instruction field without switching Android IMEs. */
    fun showForInstruction(editor: EditText, onDone: () -> Unit) {
        if (destroyed) return
        hide()
        instructionEditor = editor
        instructionParent = editor.parent as? ViewGroup
        instructionIndex = instructionParent?.indexOfChild(editor) ?: -1
        instructionParams = editor.layoutParams
        instructionParent?.removeView(editor)
        editor.showSoftInputOnFocus = false
        instructionHost.addView(editor, LinearLayout.LayoutParams(-1, dp(80)).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        })
        instructionDone = onDone
        editor.requestFocus()
        instructionConnection = editor.onCreateInputConnection(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        })
        show()
        // Moving the view can reset cursor placement on older Android widgets.
        editor.setSelection(editor.text.length)
    }

    private fun connection(): InputConnection? =
        if (instructionEditor != null) instructionConnection else getInputConnection()

    private val keystrokes = KeystrokeDispatcher { connection() }

    private fun updateInstructionControls() {
        val editing = instructionEditor != null
        paste.visibility = if (editing) View.VISIBLE else View.GONE
        close.contentDescription = context.getString(if (editing) R.string.qc_done else R.string.qc_return_to_voice)
    }

    private fun finishEditing() {
        val callback = instructionDone
        hide()
        callback?.invoke()
    }

    fun hide() {
        val wasVisible = visible
        keystrokes.stopBackspaceRepeat()
        overlay.visibility = View.GONE
        ownAccessibilityState?.let { overlay.importantForAccessibility = it }
        ownAccessibilityState = null
        accessibilityState.forEach { (view, state) -> view.importantForAccessibility = state }
        accessibilityState.clear()
        instructionEditor?.let { editor ->
            instructionHost.removeView(editor)
            instructionParent?.let { parent ->
                parent.addView(editor, instructionIndex.coerceIn(0, parent.childCount), instructionParams)
            }
        }
        instructionEditor = null
        instructionConnection = null
        instructionParent = null
        instructionParams = null
        instructionDone = null
        showingChoices = false
        if (wasVisible) onShown(false)
    }

    fun destroy() {
        hide()
        destroyed = true
        root.removeView(overlay)
    }

    private fun render() {
        updateInstructionControls()
        showingChoices = false
        language.text = layout.nativeName
        language.contentDescription = context.getString(R.string.qc_choose_layout, layout.nativeName)
        body.removeAllViews()
        val rows = if (symbols) QuickCorrectionLayouts.symbols else layout.rows
        rows.forEach { letters ->
            row(letters.map { key ->
                val value = if (symbols) key else layout.letter(key, uppercase)
                keyButton(QuickCorrectionLayouts.label(value)) { commit(value) }.apply {
                    if (!symbols && this@QuickCorrectionPanel.layout.extraCharacters.isNotEmpty()) {
                        setOnLongClickListener { showCharacters(); true }
                        ViewCompat.setTooltipText(this, context.getString(R.string.qc_more_characters))
                    }
                }
            })
        }
        val tools = mutableListOf<View>()
        if (!symbols && layout.hasCase) {
            tools += keyButton(if (capsLock) "⇧⇧" else "⇧", R.string.qc_shift) {
                uppercase = !uppercase
                capsLock = false
                render()
            }.apply {
                id = R.id.btnQuickCorrectionShift
                isSelected = uppercase
                setOnLongClickListener {
                    capsLock = !capsLock
                    uppercase = capsLock
                    render()
                    true
                }
            }
        }
        tools += keyButton(if (symbols) "…" else "?123", if (symbols) R.string.qc_letters else R.string.qc_symbols) {
            symbols = !symbols
            render()
        }.apply { id = R.id.btnQuickCorrectionSymbols }
        if (!symbols && layout.extraCharacters.isNotEmpty()) {
            tools += keyButton("…", R.string.qc_more_characters) { showCharacters() }
                .apply { id = R.id.btnQuickCorrectionCharacters }
        }
        tools += keyButton(",") { commit(",") }
        tools += keyButton(".") { commit(".") }
        tools += iconButton(R.drawable.ic_backspace, R.string.qc_backspace) { keystrokes.deleteBack() }
            .apply { id = R.id.btnQuickCorrectionBackspace }
        row(tools)

        val space = keyButton(" ", R.string.qc_space) { commit(" ") }.apply {
            id = R.id.btnQuickCorrectionSpace
            text = this@QuickCorrectionPanel.layout.nativeName.substringBefore(" ·")
            textSize = 14f
        }
        val enter = iconButton(
            if (instructionEditor != null) R.drawable.ic_action_done else R.drawable.ic_enter,
            if (instructionEditor != null) R.string.qc_done else R.string.qc_enter
        ) {
            if (instructionEditor != null) finishEditing() else onEnter()
        }.apply { id = R.id.btnQuickCorrectionEnter }
        row(listOf(space, enter), listOf(4f, 1f))
    }

    private fun showLayouts() {
        showingChoices = true
        body.removeAllViews()
        language.text = context.getString(R.string.qc_layouts)
        QuickCorrectionLayouts.all.forEach { candidate ->
            val button = keyButton(candidate.nativeName) {
                layout = candidate
                preferences.edit().putString("layout", candidate.code).apply()
                uppercase = false
                capsLock = false
                symbols = false
                render()
            }
            body.addView(button, LinearLayout.LayoutParams(-1, dp(50)))
        }
        body.addView(TextView(context).apply {
            text = context.getString(R.string.qc_layout_limits)
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.status_text))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }, LinearLayout.LayoutParams(-1, -2))
        scroll.scrollTo(0, 0)
    }

    private fun showCharacters() {
        showingChoices = true
        body.removeAllViews()
        language.text = context.getString(R.string.qc_more_characters)
        layout.extraCharacters.chunked(7).forEach { characters ->
            row(characters.map { key ->
                val value = layout.letter(key, uppercase)
                keyButton(QuickCorrectionLayouts.label(value)) { commit(value); render() }
            })
        }
        row(listOf(keyButton(context.getString(R.string.qc_letters)) { render() }))
        scroll.scrollTo(0, 0)
    }

    private fun commit(value: String) {
        val inserted = try { connection()?.commitText(value, 1) == true } catch (_: RuntimeException) { false }
        if (!inserted) onNotice(context.getString(R.string.qc_editor_unavailable))
        if (uppercase && !capsLock && !symbols) {
            uppercase = false
            if (!showingChoices) render()
        }
    }

    private fun keyButton(label: String, description: Int? = null, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(2), 0, dp(2), 0)
            setTextColor(ContextCompat.getColor(context, R.color.key_text))
            setBackgroundResource(R.drawable.key_bg)
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 12, 21, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP)
            if (description != null) contentDescription = context.getString(description)
            setOnClickListener { onClick() }
        }

    private fun iconButton(icon: Int, description: Int, onClick: () -> Unit): ImageButton =
        ImageButton(context).apply {
            setImageResource(icon)
            imageTintList = ContextCompat.getColorStateList(context, R.color.key_text)
            setBackgroundResource(R.drawable.key_bg)
            contentDescription = context.getString(description)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { onClick() }
        }

    private fun row(keys: List<View>, weights: List<Float> = List(keys.size) { 1f }) {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(2), dp(2), dp(2), 0)
        }
        keys.forEachIndexed { index, key ->
            line.addView(key, LinearLayout.LayoutParams(0, dp(48), weights[index]).apply {
                marginStart = dp(1)
                marginEnd = dp(1)
            })
        }
        body.addView(line, LinearLayout.LayoutParams(-1, dp(50)))
    }
}
