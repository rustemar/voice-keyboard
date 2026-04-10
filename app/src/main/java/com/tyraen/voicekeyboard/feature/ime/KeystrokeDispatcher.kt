package com.tyraen.voicekeyboard.feature.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.inputmethod.InputConnection

class KeystrokeDispatcher(private val connectionProvider: () -> InputConnection?) {

    private val handler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    fun deleteBack() {
        connectionProvider()?.let { ic ->
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
    }

    fun startBackspaceRepeat() {
        deleteBack()
        repeatRunnable = object : Runnable {
            override fun run() {
                deleteBack()
                handler.postDelayed(this, 80)
            }
        }
        handler.postDelayed(repeatRunnable!!, 500)
    }

    fun stopBackspaceRepeat() {
        repeatRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable = null
    }

    fun insertText(text: String) {
        connectionProvider()?.commitText(text, 1)
    }

    fun sendEnter() {
        connectionProvider()?.let { ic ->
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    fun cutAll(context: Context): Boolean {
        val ic = connectionProvider() ?: return false
        val extracted = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        val text = extracted?.text?.toString() ?: return false
        if (text.isEmpty()) return false

        ic.performContextMenuAction(android.R.id.selectAll)
        ic.performContextMenuAction(android.R.id.cut)

        return true
    }

    fun pasteFromClipboard(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(context)
            if (text.isNotEmpty()) {
                insertText(text.toString())
            }
        }
    }
}
