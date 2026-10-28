package com.tyraen.voicekeyboard.feature.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.core.locale.InterfaceLanguageManager
import kotlin.math.abs
import kotlin.math.min

/** Direct keys live in our IME window, keeping the destination editor connection intact. */
class QuickCorrectionPanel(
    private val root: FrameLayout,
    private val getInputConnection: () -> InputConnection?,
    private val onEnter: () -> Unit,
    private val onShown: (Boolean) -> Unit,
    private val onNotice: (String) -> Unit
) {
    private enum class Page { KEYS, LAYOUTS, CHARACTERS }

    private val context = root.context
    private val keystrokes = KeystrokeDispatcher { connection() }
    private val preferences = context.getSharedPreferences("quick_correction", Context.MODE_PRIVATE)
    private var layout = QuickCorrectionLayouts.initial(
        preferences.getString("layout", null), InterfaceLanguageManager.resolveActive(context)
    )
    private var uppercase = false
    private var capsLock = false
    private var symbols = false
    private var page = Page.KEYS
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
    private val language = KeyButton(15f)
    private val close = ImageButton(context)
    private val paste = ImageButton(context)
    private val more = ImageButton(context)
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val body = LinearLayout(context).apply {
        id = R.id.quickCorrectionKeys
        orientation = LinearLayout.VERTICAL
        // Physical key positions do not mirror when the interface language is right-to-left.
        layoutDirection = View.LAYOUT_DIRECTION_LTR
    }
    private val footer = FrameLayout(context).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
    private val scroll = ScrollView(context).apply {
        isFillViewport = false
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(body, FrameLayout.LayoutParams(-1, -2))
    }
    private val overlay = LinearLayout(context).apply {
        id = R.id.quickCorrectionOverlay
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(6))
        setBackgroundColor(ContextCompat.getColor(context, R.color.keyboard_bg))
        visibility = View.GONE
        elevation = dp(12).toFloat()
        isClickable = true
    }

    val visible: Boolean get() = overlay.visibility == View.VISIBLE
    val isInstructionMode: Boolean get() = instructionEditor != null

    private val compactHeight: Boolean
        get() = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            context.resources.configuration.screenHeightDp in 1..449
    private val keyHeight: Int get() = dp(if (compactHeight) 42 else 48)
    private val headerHeight: Int get() = dp(if (compactHeight) 42 else 48)
    private val rowGap: Int get() = dp(4)

    init {
        language.apply {
            id = R.id.btnQuickCorrectionLayout
            setBackgroundResource(R.drawable.correction_control_key)
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_section_expand, 0)
            compoundDrawablePadding = dp(4)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { if (page == Page.LAYOUTS) render() else showLayouts() }
        }
        header.addView(language, LinearLayout.LayoutParams(0, -1, 1f))
        configureHeaderIcon(more, R.id.btnQuickCorrectionCharacters, R.drawable.ic_correction_more,
            R.string.qc_more_characters) { if (page == Page.CHARACTERS) render() else showCharacters() }
        configureHeaderIcon(paste, R.id.btnQuickCorrectionPaste, R.drawable.ic_paste,
            android.R.string.paste) { keystrokes.pasteFromClipboard(context) }
        configureHeaderIcon(close, R.id.btnQuickCorrectionClose, R.drawable.ic_close,
            R.string.qc_return_to_voice) { finishEditing() }
        overlay.addView(header, LinearLayout.LayoutParams(-1, headerHeight).apply { bottomMargin = rowGap })
        overlay.addView(instructionHost, LinearLayout.LayoutParams(-1, -2))
        overlay.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        overlay.addView(footer, LinearLayout.LayoutParams(-1, keyHeight))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -2))
        render()
    }

    private fun configureHeaderIcon(button: ImageButton, viewId: Int, drawable: Int, label: Int, action: () -> Unit) {
        button.apply {
            id = viewId
            setImageResource(drawable)
            imageTintList = ContextCompat.getColorStateList(context, R.color.key_text)
            backgroundTintList = null
            setBackgroundResource(R.drawable.correction_control_key)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            contentDescription = context.getString(label)
            ViewCompat.setTooltipText(this, contentDescription)
            setOnClickListener { action() }
        }
        header.addView(button, LinearLayout.LayoutParams(dp(48), -1).apply { marginStart = dp(4) })
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()

    fun toggle() { if (visible) hide() else show() }

    /** A held key belongs to the editor that received its initial touch. */
    fun stopRepeatingKeys() { keystrokes.stopBackspaceRepeat() }

    private fun show() {
        if (destroyed || visible) return
        ownAccessibilityState = overlay.importantForAccessibility
        overlay.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child === overlay) continue
            accessibilityState[child] = child.importantForAccessibility
            child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        render()
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        root.requestLayout()
        onShown(true)
        language.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    }

    private fun instructionHeight(): Int = instructionEditor?.let { editor ->
        maxOf(dp(80), editor.lineHeight * 2 + editor.compoundPaddingTop + editor.compoundPaddingBottom)
    } ?: 0

    private fun resize() {
        header.layoutParams.height = headerHeight
        footer.layoutParams.height = keyHeight
        instructionEditor?.layoutParams?.height = instructionHeight()
        // The host measures this foreground sheet by its own content, not the previous panel.
        // Keep the bottom action row pinned while the alphabet scrolls in short windows.
        val displayHeight = context.resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val configuredHeight = context.resources.configuration.screenHeightDp
            .takeIf { it > 0 }?.let(::dp) ?: displayHeight
        // Leave space for the receiving editor in landscape and short split windows. The
        // alphabet can scroll; its pinned action row should not replace the whole host app.
        val available = CorrectionKeyboardGeometry.availableHeight(
            viewport = minOf(displayHeight, configuredHeight),
            constrained = compactHeight,
            editorReserve = dp(96)
        )
        val rows = if (symbols) QuickCorrectionLayouts.symbols.size else layout.rows.size + 1
        overlay.layoutParams = FrameLayout.LayoutParams(-1, CorrectionKeyboardGeometry.sheetHeight(
            header = headerHeight + rowGap,
            instruction = instructionHeight(),
            keyRows = rows,
            keyHeight = keyHeight,
            rowGap = rowGap,
            padding = dp(10) + keyHeight,
            available = available
        ))
        root.requestLayout()
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
        instructionHost.addView(editor, LinearLayout.LayoutParams(-1, instructionHeight()).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
        })
        instructionDone = onDone
        editor.requestFocus()
        instructionConnection = editor.onCreateInputConnection(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        })
        show()
        editor.setSelection(editor.text.length)
    }

    private fun connection(): InputConnection? =
        if (instructionEditor != null) instructionConnection else getInputConnection()

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
        page = Page.KEYS
        if (wasVisible) onShown(false)
        root.requestLayout()
    }

    fun destroy() {
        hide()
        destroyed = true
        root.removeView(overlay)
    }

    private fun render() {
        keystrokes.stopBackspaceRepeat()
        page = Page.KEYS
        paste.visibility = if (instructionEditor != null) View.VISIBLE else View.GONE
        more.visibility = if (!symbols && layout.extraCharacters.isNotEmpty()) View.VISIBLE else View.GONE
        close.contentDescription = context.getString(if (instructionEditor != null) R.string.qc_done else R.string.qc_return_to_voice)
        ViewCompat.setTooltipText(close, close.contentDescription)
        language.text = layout.nativeName.substringBefore(" ·")
        language.contentDescription = context.getString(R.string.qc_choose_layout, layout.nativeName)
        body.removeAllViews()
        footer.removeAllViews()

        if (symbols) {
            val rows = QuickCorrectionLayouts.symbols
            rows.forEachIndexed { index, values ->
                addBodyRow(values.map { value -> keyButton(value, size = if (index == 0) 19f else 22f,
                    references = values) { commit(value) } }, 10,
                    rowId = if (index == 0) R.id.quickCorrectionNumberRow else R.id.quickCorrectionLetterRow)
            }
        } else {
            val digits = "1234567890".map { it.toString() }
            addBodyRow(digits.map { digit -> keyButton(digit, size = 19f, references = digits) { commit(digit) } },
                10, rowId = R.id.quickCorrectionNumberRow)
            val flankFinalRow = layout.rows.size <= 3
            val columns = CorrectionKeyboardGeometry.columns(layout.rows, flankFinalRow)
            val references = layout.rows.flatten().map { layout.letter(it, uppercase) }
            layout.rows.forEachIndexed { index, letters ->
                val keys = letters.map { value ->
                    val letter = layout.letter(value, uppercase)
                    keyButton(QuickCorrectionLayouts.label(letter), references = references) { commit(letter) }.apply {
                        if (this@QuickCorrectionPanel.layout.extraCharacters.isNotEmpty()) {
                            setOnLongClickListener { showCharacters(); true }
                            ViewCompat.setTooltipText(this, context.getString(R.string.qc_more_characters))
                        }
                    }
                }
                if (flankFinalRow && index == layout.rows.lastIndex) {
                    val side = CorrectionKeyboardGeometry.sideUnits(columns, letters.size)
                    val start = if (layout.hasCase) shiftButton() else moreButton()
                    val backspace = backspaceButton().apply {
                        if (compactHeight) {
                            id = R.id.btnQuickCorrectionInlineBackspace
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }
                    }
                    addBodyRow(listOf(start) + keys + backspace, columns,
                        listOf(side) + List(keys.size) { 1f } + side)
                } else addBodyRow(keys, columns)
            }
        }
        renderFooter()
        resize()
        scroll.scrollTo(0, 0)
    }

    private fun renderFooter() {
        val toggle = if (symbols) iconButton(R.drawable.ic_quick_correction, R.string.qc_letters) {
            symbols = false
            render()
        } else keyButton("?123", R.string.qc_symbols, size = 16f, control = true) {
            symbols = true
            render()
        }
        toggle.id = R.id.btnQuickCorrectionSymbols
        val space = keyButton(layout.nativeName.substringBefore(" ·"), R.string.qc_space, size = 14f) {
            commit(" ")
        }.apply { id = R.id.btnQuickCorrectionSpace }
        val enter = iconButton(
            if (instructionEditor != null) R.drawable.ic_action_done else R.drawable.ic_enter,
            if (instructionEditor != null) R.string.qc_done else R.string.qc_enter
        ) {
            if (instructionEditor != null) finishEditing() else onEnter()
        }.apply {
            id = R.id.btnQuickCorrectionEnter
            setBackgroundResource(R.drawable.correction_enter_key)
            imageTintList = ContextCompat.getColorStateList(context, R.color.white)
        }
        val comma = keyButton(",", size = 20f) { commit(",") }
        val period = keyButton(".", size = 20f) { commit(".") }
        val needsBackspace = symbols || layout.rows.size > 3 || compactHeight
        val keys = if (needsBackspace) listOf(toggle, comma, space, period, backspaceButton(), enter)
            else listOf(toggle, comma, space, period, enter)
        val units = if (needsBackspace) listOf(1.5f, 1f, 3.75f, 1f, 1.25f, 1.5f)
            else listOf(1.5f, 1f, 5f, 1f, 1.5f)
        footer.addView(KeyboardRow(keys, 10, units, 0).apply { id = R.id.quickCorrectionBottomRow },
            FrameLayout.LayoutParams(-1, keyHeight))
    }

    private fun shiftButton(): ImageButton = iconButton(
        if (capsLock) R.drawable.ic_correction_caps_lock else R.drawable.ic_correction_shift,
        R.string.qc_shift
    ) {
        uppercase = !uppercase
        capsLock = false
        render()
    }.apply {
        id = R.id.btnQuickCorrectionShift
        isSelected = uppercase
        imageTintList = ContextCompat.getColorStateList(context, if (uppercase) R.color.white else R.color.key_text)
        setOnLongClickListener {
            capsLock = !capsLock
            uppercase = capsLock
            render()
            true
        }
    }

    private fun moreButton() = iconButton(R.drawable.ic_correction_more, R.string.qc_more_characters) {
        showCharacters()
    }

    private fun backspaceButton(): ImageButton = iconButton(R.drawable.ic_backspace, R.string.qc_backspace) {
        keystrokes.deleteBack()
    }.apply {
        id = R.id.btnQuickCorrectionBackspace
        var handledTouch = false
        setOnClickListener { if (!handledTouch) keystrokes.deleteBack() }
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    keystrokes.startBackspaceRepeat()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    keystrokes.stopBackspaceRepeat()
                    handledTouch = true
                    try { view.performClick() } finally { handledTouch = false }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    keystrokes.stopBackspaceRepeat()
                    true
                }
                else -> false
            }
        }
    }

    private fun showLayouts() {
        keystrokes.stopBackspaceRepeat()
        page = Page.LAYOUTS
        language.text = context.getString(R.string.qc_layouts)
        body.removeAllViews()
        footer.removeAllViews()
        QuickCorrectionLayouts.all.forEach { candidate ->
            val button = keyButton(candidate.nativeName, size = 16f) {
                layout = candidate
                preferences.edit().putString("layout", candidate.code).apply()
                uppercase = false
                capsLock = false
                symbols = false
                render()
            }
            body.addView(button, LinearLayout.LayoutParams(-1, dp(50)).apply {
                marginStart = dp(2); marginEnd = dp(2); bottomMargin = rowGap
            })
        }
        body.addView(TextView(context).apply {
            text = context.getString(R.string.qc_layout_limits)
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.status_text))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }, LinearLayout.LayoutParams(-1, -2))
        renderReturnFooter()
        resize()
        scroll.scrollTo(0, 0)
    }

    private fun showCharacters() {
        keystrokes.stopBackspaceRepeat()
        page = Page.CHARACTERS
        body.removeAllViews()
        footer.removeAllViews()
        language.text = context.getString(R.string.qc_more_characters)
        val references = layout.extraCharacters.map { QuickCorrectionLayouts.label(layout.letter(it, uppercase)) }
        layout.extraCharacters.chunked(7).forEach { values ->
            addBodyRow(values.map { key ->
                val value = layout.letter(key, uppercase)
                keyButton(QuickCorrectionLayouts.label(value), references = references) { commit(value); render() }
            }, 7)
        }
        renderReturnFooter()
        resize()
        scroll.scrollTo(0, 0)
    }

    private fun renderReturnFooter() {
        footer.addView(keyButton(context.getString(R.string.qc_letters), size = 16f, control = true) {
            symbols = false
            render()
        },
            FrameLayout.LayoutParams(-1, keyHeight).apply { marginStart = dp(2); marginEnd = dp(2) })
    }

    private fun commit(value: String) {
        val inserted = try { connection()?.commitText(value, 1) == true } catch (_: RuntimeException) { false }
        if (!inserted) onNotice(context.getString(R.string.qc_editor_unavailable))
        if (uppercase && !capsLock && !symbols) {
            uppercase = false
            if (page == Page.KEYS) render()
        }
    }

    private fun keyButton(
        label: String, description: Int? = null, size: Float = 22f,
        control: Boolean = false, references: List<String> = listOf(label), onClick: () -> Unit
    ): Button = KeyButton(size, references).apply {
        text = label
        setBackgroundResource(if (control) R.drawable.correction_control_key else R.drawable.correction_letter_key)
        if (description != null) contentDescription = context.getString(description)
        setOnClickListener { onClick() }
    }

    /**
     * The IME uses native widgets with explicit theme colors. Native Button autosizing is
     * unavailable on API 24, so measured font fitting is shared across all supported versions.
     */
    @SuppressLint("AppCompatCustomView")
    private inner class KeyButton(private val desiredSp: Float, private val references: List<String> = emptyList()) :
        Button(context, null, 0) {
        init {
            isAllCaps = false
            transformationMethod = null
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            letterSpacing = 0f
            gravity = Gravity.CENTER
            setSingleLine()
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(dp(2), dp(2), dp(2), dp(2))
            backgroundTintList = null
            stateListAnimator = null
            setTextColor(ContextCompat.getColor(context, R.color.key_text))
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight
            val height = MeasureSpec.getSize(heightMeasureSpec) - compoundPaddingTop - compoundPaddingBottom
            val desired = desiredSp * resources.displayMetrics.scaledDensity
            val sample = Paint(paint).apply { textSize = desired }
            val values = references.ifEmpty { listOf(text.toString()) }
            val widest = values.maxOfOrNull { sample.measureText(it) }?.coerceAtLeast(1f) ?: 1f
            val font = sample.fontMetrics
            var fitted = min(desired, desired * width.coerceAtLeast(0) / widest)
            fitted = min(fitted, desired * height.coerceAtLeast(0) / (font.bottom - font.top).coerceAtLeast(1f))
            if (fitted > 0 && abs(textSize - fitted) > .1f) {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fitted)
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private fun iconButton(icon: Int, description: Int, onClick: () -> Unit): ImageButton =
        ImageButton(context).apply {
            setImageResource(icon)
            imageTintList = ContextCompat.getColorStateList(context, R.color.key_text)
            backgroundTintList = null
            stateListAnimator = null
            setBackgroundResource(R.drawable.correction_control_key)
            contentDescription = context.getString(description)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(4), dp(10), dp(4), dp(10))
            setOnClickListener { onClick() }
        }

    private fun addBodyRow(
        keys: List<View>, columns: Int, units: List<Float> = List(keys.size) { 1f },
        rowId: Int = R.id.quickCorrectionLetterRow
    ) {
        body.addView(KeyboardRow(keys, columns, units, rowGap).apply { id = rowId },
            LinearLayout.LayoutParams(-1, keyHeight + rowGap))
    }

    private inner class KeyboardRow(
        keys: List<View>, private val columns: Int, private val units: List<Float>, private val after: Int
    ) : ViewGroup(context) {
        private var bounds = emptyList<CorrectionKeyboardGeometry.KeyBounds>()
        init { keys.forEach { addView(it) }; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            bounds = CorrectionKeyboardGeometry.row(width, columns, units, dp(2), dp(4)).keys
            for (index in 0 until childCount) {
                getChildAt(index).measure(MeasureSpec.makeMeasureSpec(bounds[index].width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec((height - after).coerceAtLeast(0), MeasureSpec.EXACTLY))
            }
            setMeasuredDimension(width, height)
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            for (index in 0 until childCount) {
                val key = bounds[index]
                getChildAt(index).layout(key.left, 0, key.right, getChildAt(index).measuredHeight)
            }
        }
    }
}
