package com.tyraen.voicekeyboard.feature.ime

import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.tyraen.voicekeyboard.R
import kotlin.math.roundToInt

/** Rearranges the same bound controls without recreating the input view or its recorder. */
class CompactTerminalPanel(private val fullPanel: ConstraintLayout) {
    private val density = fullPanel.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).roundToInt()
    private fun <T : View> find(id: Int): T = fullPanel.findViewById(id)

    private val previous: ImageButton = find(R.id.btnPreviousKeyboard)
    private val mic: FrameLayout = find(R.id.micFrame)
    private val microphoneButton: ImageButton = find(R.id.btnMic)
    private val cancel: ImageButton = find(R.id.btnCancel)
    private val send: ImageButton = find(R.id.btnSend)
    private val backspace: ImageButton = find(R.id.btnBackspace)
    private val sideRail: LinearLayout = find(R.id.keyboardSideRail)
    private val enter: ImageButton = find(R.id.btnEnter)
    private val sizeToggle: ImageButton = find(R.id.btnPanelSize)
    private val status: TextView = find(R.id.statusText)
    private val retry: FrameLayout = find(R.id.retryFrame)
    private val saved: FrameLayout = find(R.id.pendingTextsFrame)
    private val experiments: LinearLayout = find(R.id.experimentalActionsBar)
    private val queueBadge: TextView = find(R.id.queueBadge)
    private val badgeTranslationX = queueBadge.translationX
    private val badgeTranslationY = queueBadge.translationY
    private val moved = listOf(previous, mic, cancel, backspace, enter, sizeToggle, status, retry, saved, experiments)
    private data class Slot(val view: View, val parent: ViewGroup, val index: Int, val params: ViewGroup.LayoutParams)
    private data class Style(
        val view: View, val params: ViewGroup.LayoutParams,
        val minimumWidth: Int, val minimumHeight: Int,
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        val background: Drawable?
    )
    private val slots = moved.map { view ->
        val parent = view.parent as ViewGroup
        Slot(view, parent, parent.indexOfChild(view), view.layoutParams)
    }
    private val styled = (moved + (0 until mic.childCount).map(mic::getChildAt) +
        (0 until retry.childCount).map(retry::getChildAt)).map { view ->
        Style(view, view.layoutParams, view.minimumWidth, view.minimumHeight,
            view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom, view.background)
    }
    private val originalStatusGravity = status.gravity
    private val originalExperimentOrientation = experiments.orientation
    private val compactPanel = LinearLayout(fullPanel.context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        visibility = View.GONE
    }
    private val statusRow = LinearLayout(fullPanel.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
    }
    private val cancelSlot = FrameLayout(fullPanel.context)
    private val controls = LinearLayout(fullPanel.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private var eligible = false
    private var manuallyExpanded = false
    private var widthPx = 0
    var isCompact: Boolean = false
        private set

    val root: FrameLayout = object : FrameLayout(fullPanel.context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            applyForWidth(View.MeasureSpec.getSize(widthMeasureSpec))
            val sheetIds = setOf(R.id.quickCorrectionOverlay, R.id.textActionsOverlay, R.id.pendingTextsOverlay)
            val sheet = (childCount - 1 downTo 0).map(::getChildAt).firstOrNull {
                it.id in sheetIds && it.visibility == View.VISIBLE && it.layoutParams.height > 0
            }
            val requestedHeight = sheet?.layoutParams?.height ?: 0
            if (sheet != null) {
                // The visible foreground sheet determines the IME height. Background panels must
                // neither leave unused space nor make a localized sheet exceed its window.
                val limit = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) {
                    requestedHeight
                } else View.MeasureSpec.getSize(heightMeasureSpec)
                val fittedHeight = minOf(requestedHeight, limit)
                val sheetParams = sheet.layoutParams
                // A fixed child height otherwise wins over an EXACT parent bound and clips its
                // bottom controls. Bound this measure only; retain the natural height for a
                // later, larger window. FrameLayout.onLayout uses the child's measured size.
                sheetParams.height = fittedHeight
                try {
                    super.onMeasure(widthMeasureSpec,
                        View.MeasureSpec.makeMeasureSpec(fittedHeight, View.MeasureSpec.EXACTLY))
                } finally {
                    sheetParams.height = requestedHeight
                }
            } else {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            fitNarrowVoiceControls()
        }
    }.apply {
        setBackgroundColor(ContextCompat.getColor(context, R.color.keyboard_bg))
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(fullPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        compactPanel.addView(statusRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        compactPanel.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        addView(compactPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    init { sizeToggle.setOnClickListener { toggleExpanded() } }

    fun updateOptions(enabled: Boolean, postProcessingEnabled: Boolean, terminalVisible: Boolean, terminalActive: Boolean) {
        eligible = CompactTerminalLayoutPolicy.eligible(enabled, postProcessingEnabled, terminalVisible, terminalActive)
        if (!eligible) manuallyExpanded = false
        applyForWidth(widthPx)
        root.requestLayout()
    }

    fun toggleExpanded() {
        if (!eligible || widthPx / density < CompactTerminalLayoutPolicy.MIN_WIDTH_DP) return
        manuallyExpanded = !manuallyExpanded
        applyForWidth(widthPx)
        root.requestLayout()
    }

    private fun applyForWidth(width: Int) {
        widthPx = width
        val widthDp = (width / density).toInt()
        val compact = CompactTerminalLayoutPolicy.compact(eligible, manuallyExpanded, widthDp)
        if (compact != isCompact) {
            if (compact) enterCompact() else leaveCompact()
            isCompact = compact
        }
        sizeToggle.visibility = if (eligible && widthDp >= CompactTerminalLayoutPolicy.MIN_WIDTH_DP) View.VISIBLE else View.GONE
        val description = if (compact) R.string.terminal_compact_expand else R.string.terminal_compact_show
        sizeToggle.contentDescription = fullPanel.context.getString(description)
        ViewCompat.setTooltipText(sizeToggle, sizeToggle.contentDescription)
        sizeToggle.setImageResource(if (compact) R.drawable.ic_expand_panel else R.drawable.ic_compact_panel)
    }

    private fun detach(view: View) { (view.parent as? ViewGroup)?.removeView(view) }

    private fun fitNarrowVoiceControls() {
        cancel.translationX = 0f
        send.translationX = 0f
        if (isCompact || fullPanel.visibility != View.VISIBLE) return

        fun bounds(view: View) = Rect().also {
            view.getDrawingRect(it)
            fullPanel.offsetDescendantRectToMyCoords(view, it)
        }

        val microphoneBounds = bounds(microphoneButton)
        val railBounds = bounds(sideRail)
        val backspaceBounds = bounds(backspace)
        val cancelBounds = bounds(cancel)
        val sendBounds = bounds(send)
        val rtl = fullPanel.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val gap = dp(4)

        // Reserve both side columns even when saved text or retry is hidden. Queue changes
        // must not move recording controls horizontally beneath the user's finger.
        if (cancel.width > 0) {
            val required = if (rtl) cancelBounds.right - railBounds.left + gap
                else railBounds.right + gap - cancelBounds.left
            val available = if (rtl) cancelBounds.left - microphoneBounds.right - gap
                else microphoneBounds.left - gap - cancelBounds.right
            val shift = minOf(required.coerceAtLeast(0), available.coerceAtLeast(0))
            cancel.translationX = if (rtl) -shift.toFloat() else shift.toFloat()
        }
        val required = if (rtl) backspaceBounds.right - sendBounds.left
            else sendBounds.right - backspaceBounds.left
        val available = if (rtl) microphoneBounds.left - sendBounds.right
            else sendBounds.left - microphoneBounds.right
        val shift = minOf(required.coerceAtLeast(0), available.coerceAtLeast(0))
        send.translationX = if (rtl) shift.toFloat() else -shift.toFloat()
    }

    private fun enterCompact() {
        moved.forEach(::detach)
        status.setPadding(0, 0, 0, 0)
        status.gravity = Gravity.CENTER
        // The full panel's shortcuts form a vertical rail; compact mode shares one status row.
        experiments.orientation = LinearLayout.HORIZONTAL
        statusRow.addView(saved, LinearLayout.LayoutParams(dp(48), dp(48)))
        statusRow.addView(experiments, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        statusRow.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusRow.addView(retry, LinearLayout.LayoutParams(dp(48), dp(48)))
        // Reserve its place even when the controller hides cancel: recording and stop must
        // remain under the same finger instead of moving with queue-state changes.
        cancelSlot.addView(cancel, FrameLayout.LayoutParams(dp(48), dp(48)))
        val keys = listOf(previous, mic, cancelSlot, backspace, enter, sizeToggle)
        keys.forEachIndexed { index, key ->
            key.minimumWidth = dp(48)
            key.minimumHeight = dp(48)
            val button = if (key === cancelSlot) cancel else key
            if (key !== mic) {
                button.setPadding(dp(10), dp(10), dp(10), dp(10))
                button.setBackgroundResource(R.drawable.key_bg)
            }
            val params = if (key === mic) LinearLayout.LayoutParams(0, dp(48), 1f)
                else LinearLayout.LayoutParams(dp(48), dp(48))
            if (index > 0) params.marginStart = dp(4)
            controls.addView(key, params)
        }
        for (index in 0 until mic.childCount) {
            val child = mic.getChildAt(index)
            val size = if (child.id == R.id.queueBadge) {
                maxOf(dp(20), ((child as TextView).textSize * 1.4f).roundToInt()).coerceAtMost(dp(48))
            } else dp(48)
            child.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            if (child === queueBadge) {
                // Anchor to the 48 dp microphone, not the wider weighted container.
                val offset = (dp(48) - size) / 2f
                queueBadge.translationX = if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL) -offset else offset
                queueBadge.translationY = -offset
            }
        }
        mic.findViewById<View>(R.id.btnMic).setPadding(dp(8), dp(8), dp(8), dp(8))
        val retryBadge = retry.findViewById<TextView>(R.id.retryBadge)
        val retryBadgeSize = maxOf(dp(20), (retryBadge.textSize * 1.4f).roundToInt()).coerceAtMost(dp(48))
        retryBadge.layoutParams = FrameLayout.LayoutParams(retryBadgeSize, retryBadgeSize, Gravity.TOP or Gravity.END)
        fullPanel.visibility = View.GONE
        compactPanel.visibility = View.VISIBLE
    }

    private fun leaveCompact() {
        moved.forEach(::detach)
        controls.removeAllViews()
        experiments.orientation = originalExperimentOrientation
        styled.forEach { style ->
            style.view.layoutParams = style.params
            style.view.minimumWidth = style.minimumWidth
            style.view.minimumHeight = style.minimumHeight
            style.view.setPadding(style.left, style.top, style.right, style.bottom)
            if (style.view in listOf(previous, cancel, backspace, enter, sizeToggle)) {
                style.view.background = style.background
            }
        }
        slots.groupBy { it.parent }.forEach { (parent, entries) ->
            entries.sortedBy { it.index }.forEach { slot ->
                parent.addView(slot.view, slot.index.coerceAtMost(parent.childCount), slot.params)
            }
        }
        status.gravity = originalStatusGravity
        queueBadge.translationX = badgeTranslationX
        queueBadge.translationY = badgeTranslationY
        compactPanel.visibility = View.GONE
        fullPanel.visibility = View.VISIBLE
    }
}
