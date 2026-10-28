package com.tyraen.voicekeyboard.feature.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Saved results stay inside the keyboard window, so opening them never changes editor focus. */
class PendingTranscriptsPanel(
    private val root: FrameLayout,
    private val store: PendingTranscriptStore,
    private val onInsert: (PendingTranscript) -> Boolean,
    private val onNotice: (String) -> Unit
) {
    companion object {
        // UI callbacks and these claims are Main-confined. A view replacement must not make a
        // result insertable again while the preceding view is still committing its removal.
        private val claimedIds = MutableStateFlow<Set<String>>(emptySet())
        private val removalScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    private val context = root.context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val frame: FrameLayout = root.findViewById(R.id.pendingTextsFrame)
    private val button: ImageButton = root.findViewById(R.id.btnPendingTexts)
    private val badge: TextView = root.findViewById(R.id.pendingTextsBadge)
    private val title = TextView(context)
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        isFillViewport = false
        addView(list, FrameLayout.LayoutParams(-1, -2))
    }
    private val overlay = LinearLayout(context).apply {
        id = R.id.pendingTextsOverlay
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.keyboard_bg))
        visibility = View.GONE
        elevation = dp(8).toFloat()
        isClickable = true
    }
    private var transcripts = emptyList<PendingTranscript>()
    private var confirmingDelete: String? = null
    private val accessibilityState = HashMap<View, Int>()
    private var destroyed = false

    init {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        title.apply {
            textSize = 17f
            setTextColor(ContextCompat.getColor(context, R.color.key_text))
            ViewCompat.setAccessibilityHeading(this, true)
        }
        val close = ImageButton(context).apply {
            id = R.id.btnPendingClose
            setImageResource(R.drawable.ic_close)
            imageTintList = ContextCompat.getColorStateList(context, R.color.key_text)
            setBackgroundResource(R.drawable.borderless_ripple)
            contentDescription = context.getString(R.string.saved_texts_close)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { hide() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        title.gravity = Gravity.CENTER_VERTICAL
        header.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
        overlay.addView(header, LinearLayout.LayoutParams(-1, dp(48)))
        overlay.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        button.setOnClickListener { show() }
        // A failed initial disk read must not disconnect the badge from later successful saves.
        // Observation lives independently of loading, which the queue can retry when it saves.
        scope.launch {
            store.transcripts.collect { saved ->
                transcripts = saved
                updateBadge()
                if (overlay.visibility == View.VISIBLE) render()
            }
        }
        scope.launch {
            claimedIds.collect {
                if (overlay.visibility == View.VISIBLE) render()
            }
        }
        scope.launch {
            try {
                store.ensureLoaded()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DiagnosticLog.recordFailure("SavedTexts", "Could not load saved texts", error)
                if (!destroyed) onNotice(context.getString(R.string.saved_texts_action_failed))
            }
        }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun updateBadge() {
        val count = transcripts.size
        frame.visibility = if (count == 0) View.GONE else View.VISIBLE
        badge.text = if (count > 99) "99+" else count.toString()
        button.contentDescription = context.getString(R.string.saved_texts_count, count)
        ViewCompat.setTooltipText(button, button.contentDescription)
    }

    private fun show() {
        if (overlay.visibility == View.VISIBLE) return
        confirmingDelete = null
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child === overlay) continue
            accessibilityState[child] = child.importantForAccessibility
            child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        render()
        // Compact controls are too short for a scrollable text card plus accessible actions.
        // Expand only this sheet; making it GONE restores the ordinary compact height. An
        // explicit height also keeps MATCH_PARENT from consuming the whole available screen.
        val minimumHeight = dp(240).coerceAtMost(context.resources.displayMetrics.heightPixels)
        overlay.layoutParams = FrameLayout.LayoutParams(-1, maxOf(root.height, minimumHeight))
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        title.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    }

    /** Also used when the keyboard hides: reopening it starts with the dictation controls. */
    fun hide() {
        overlay.visibility = View.GONE
        confirmingDelete = null
        accessibilityState.forEach { (view, value) -> view.importantForAccessibility = value }
        accessibilityState.clear()
    }

    private fun render() {
        title.text = context.getString(R.string.saved_texts_count, transcripts.size)
        list.removeAllViews()
        list.setPadding(dp(8), 0, dp(8), dp(8))
        if (transcripts.isEmpty()) {
            list.addView(TextView(context).apply {
                text = context.getString(R.string.saved_texts_empty)
                textSize = 16f
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setTextColor(ContextCompat.getColor(context, R.color.status_text))
            })
            return
        }
        transcripts.forEach { transcript ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), 0)
                setBackgroundResource(R.drawable.key_bg)
            }
            val preview = TextView(context).apply {
                id = R.id.pendingTranscriptText
                text = transcript.text
                textSize = 16f
                setTextIsSelectable(true)
                setTextColor(ContextCompat.getColor(context, R.color.key_text))
                setPadding(dp(4), 0, dp(4), dp(4))
            }
            card.addView(preview, LinearLayout.LayoutParams(-1, -2))
            if (confirmingDelete == transcript.id) {
                card.addView(TextView(context).apply {
                    id = R.id.pendingDeletePrompt
                    text = context.getString(R.string.saved_texts_delete_confirm)
                    setTextColor(ContextCompat.getColor(context, R.color.key_text))
                    setPadding(dp(4), dp(8), dp(4), 0)
                })
            }
            val actions = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            if (confirmingDelete == transcript.id) {
                actions.addView(action(android.R.string.cancel, View.NO_ID, transcript) {
                    confirmingDelete = null
                    render()
                }, LinearLayout.LayoutParams(0, -2, 1f))
                actions.addView(action(R.string.saved_texts_delete, R.id.btnPendingDelete, transcript) {
                    remove(transcript)
                }, LinearLayout.LayoutParams(0, -2, 1f))
            } else {
                actions.addView(action(R.string.saved_texts_insert, R.id.btnPendingInsert, transcript) {
                    insert(transcript)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                actions.addView(action(R.string.saved_texts_copy, R.id.btnPendingCopy, transcript) {
                    copy(transcript)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                actions.addView(action(R.string.saved_texts_delete, R.id.btnPendingDelete, transcript) {
                    confirmingDelete = transcript.id
                    render()
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            card.addView(actions, LinearLayout.LayoutParams(-1, -2))
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun action(label: Int, viewId: Int, transcript: PendingTranscript, click: () -> Unit) =
        Button(context).apply {
            id = viewId
            setText(label)
            isAllCaps = false
            textSize = 14f
            minHeight = dp(48)
            minimumWidth = 0
            setPadding(dp(4), 0, dp(4), 0)
            isEnabled = transcript.id !in claimedIds.value
            setOnClickListener { click() }
        }

    private fun insert(transcript: PendingTranscript) {
        if (!claim(transcript)) return
        val inserted = try {
            onInsert(transcript)
        } catch (error: RuntimeException) {
            release(transcript.id)
            DiagnosticLog.recordFailure("SavedTexts", "Could not insert saved text", error)
            if (!destroyed) onNotice(context.getString(R.string.saved_texts_action_failed))
            return
        }
        if (!inserted) {
            release(transcript.id)
            if (!destroyed) onNotice(context.getString(R.string.saved_texts_insert_failed))
            return
        }
        hide()
        removeClaimed(transcript)
    }

    private fun copy(transcript: PendingTranscript) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.saved_texts_title), transcript.text))
            onNotice(context.getString(R.string.saved_texts_copied))
        } catch (error: RuntimeException) {
            DiagnosticLog.recordFailure("SavedTexts", "Could not copy saved text", error)
            onNotice(context.getString(R.string.saved_texts_action_failed))
        }
    }

    private fun remove(transcript: PendingTranscript) {
        if (!claim(transcript)) return
        removeClaimed(transcript)
    }

    private fun claim(transcript: PendingTranscript): Boolean {
        if (destroyed || transcript.id in claimedIds.value) return false
        // StateFlow collectors may still be rendering a just-removed item. Never act on that
        // stale card after another panel has already completed its durable consumption.
        if (store.transcripts.value.none { it.id == transcript.id }) return false
        claimedIds.value = claimedIds.value + transcript.id
        return true
    }

    private fun release(id: String) {
        claimedIds.value = claimedIds.value - id
    }

    private fun removeClaimed(transcript: PendingTranscript) {
        removalScope.launch {
            try {
                // Main.immediate enters this block before the click handler yields. Once an
                // insertion/deletion is accepted, an IME teardown must not resurrect the result.
                withContext(NonCancellable) { store.remove(transcript.id) }
                if (confirmingDelete == transcript.id) confirmingDelete = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DiagnosticLog.recordFailure("SavedTexts", "Could not remove saved text", error)
                if (!destroyed) onNotice(context.getString(R.string.saved_texts_action_failed))
            } finally {
                release(transcript.id)
                if (!destroyed && overlay.visibility == View.VISIBLE) render()
            }
        }
    }

    fun destroy() {
        destroyed = true
        hide()
        button.setOnClickListener(null)
        scope.cancel()
        root.removeView(overlay)
    }
}
