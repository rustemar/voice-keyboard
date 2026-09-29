package com.tyraen.voicekeyboard.core.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog

/**
 * Opens a web link in the browser. A device without any browser (some test and kiosk images)
 * answers with ActivityNotFoundException, which used to close the settings screen; the link is
 * copied instead, so it can still be pasted somewhere.
 */
object LinkOpener {

    fun open(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: RuntimeException) {
            DiagnosticLog.record("LinkOpener", "No app to open a link: ${e.javaClass.simpleName}")
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
            Toast.makeText(context, R.string.no_browser_link_copied, Toast.LENGTH_LONG).show()
        }
    }
}
