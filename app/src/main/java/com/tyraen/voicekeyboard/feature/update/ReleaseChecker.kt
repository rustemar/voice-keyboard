package com.tyraen.voicekeyboard.feature.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

class ReleaseChecker(private val http: OkHttpClient) {

    companion object {
        private const val RELEASES_URL =
            "https://api.github.com/repos/rustemar/voice-keyboard/releases"
    }

    private val installer = ApkInstaller(http)

    suspend fun checkForUpdate(context: Context, showUpToDate: Boolean = false) {
        val currentVersion = getCurrentVersion(context) ?: return
        val releases = fetchNewerReleases(currentVersion)

        withContext(Dispatchers.Main) {
            when {
                releases == null && showUpToDate -> {
                    Toast.makeText(context, context.getString(R.string.update_check_failed), Toast.LENGTH_SHORT).show()
                }
                releases.isNullOrEmpty() && showUpToDate -> {
                    Toast.makeText(context, context.getString(R.string.update_no_updates), Toast.LENGTH_SHORT).show()
                }
                !releases.isNullOrEmpty() -> {
                    presentUpdateDialog(context, releases)
                }
            }
        }
    }

    private suspend fun fetchNewerReleases(currentVersion: String): List<ReleaseDetails>? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(RELEASES_URL)
                    .header("Accept", "application/vnd.github+json")
                    .build()

                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null

                    val jsonArray = JSONArray(response.body!!.string())
                    val newer = mutableListOf<ReleaseDetails>()

                    for (i in 0 until jsonArray.length()) {
                        val json = jsonArray.getJSONObject(i)
                        if (json.optBoolean("draft", false)) continue

                        val tagName = json.getString("tag_name").removePrefix("v")
                        if (!isVersionNewer(tagName, currentVersion)) continue

                        val htmlUrl = json.getString("html_url")
                        val body = json.optString("body", "")

                        var apkUrl: String? = null
                        val assets = json.optJSONArray("assets")
                        if (assets != null) {
                            for (j in 0 until assets.length()) {
                                val asset = assets.getJSONObject(j)
                                if (asset.getString("name").endsWith(".apk")) {
                                    apkUrl = asset.getString("browser_download_url")
                                    break
                                }
                            }
                        }

                        newer.add(ReleaseDetails(tagName, htmlUrl, apkUrl, body))
                    }

                    newer.sortedByDescending { it.version }
                }
            } catch (e: Exception) {
                DiagnosticLog.record("ReleaseChecker", "Failed to check for updates: ${e.message}")
                null
            }
        }

    private fun getCurrentVersion(context: Context): String? {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
    }

    internal fun isVersionNewer(remote: String, local: String): Boolean {
        val remoteParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val localParts = local.split(".").mapNotNull { it.toIntOrNull() }

        for (i in 0 until maxOf(remoteParts.size, localParts.size)) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    private fun presentUpdateDialog(context: Context, releases: List<ReleaseDetails>) {
        val latest = releases.first()

        val message = buildString {
            append(context.getString(R.string.update_new_version, latest.version))
            for (release in releases) {
                append("\n\n--- v${release.version} ---\n")
                if (release.changeNotes.isNotBlank()) {
                    append(release.changeNotes)
                }
            }
        }

        val spanned = renderClickableLinks(context, message)

        val textView = TextView(context).apply {
            text = spanned
            movementMethod = LinkMovementMethod.getInstance()
            setTextIsSelectable(true)
            setPadding(48, 32, 48, 16)
            setTextColor(context.resources.getColor(R.color.text_primary, context.theme))
            textSize = 15f
        }

        val builder = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.update_available))
            .setView(textView)
            .setNegativeButton(context.getString(R.string.update_later), null)

        if (latest.directDownloadUrl != null) {
            builder.setPositiveButton(context.getString(R.string.update_button)) { _, _ ->
                startDownload(context, latest.directDownloadUrl, latest.version)
            }
        } else {
            builder.setPositiveButton(context.getString(R.string.update_open_browser)) { _, _ ->
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(latest.pageUrl)))
            }
        }

        builder.show()
    }

    private fun renderClickableLinks(context: Context, text: String): SpannableStringBuilder {
        val mdPattern = Regex("""\[([^\]]+)]\(([^)]+)\)""")
        val urlPattern = Regex("""(?<!\()(https?://\S+)""")

        val result = SpannableStringBuilder()

        val sb = StringBuilder(text)
        data class PendingSpan(val start: Int, val end: Int, val url: String)
        val pending = mutableListOf<PendingSpan>()

        val mdMatches = mdPattern.findAll(text).toList().reversed()
        for (match in mdMatches) {
            val displayText = match.groupValues[1]
            val url = match.groupValues[2]
            sb.replace(match.range.first, match.range.last + 1, displayText)
            pending.add(PendingSpan(match.range.first, match.range.first + displayText.length, url))
        }

        result.append(sb.toString())

        for (span in pending) {
            val url = span.url
            result.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }, span.start, span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val plainMatches = urlPattern.findAll(result.toString())
        for (match in plainMatches) {
            val existing = result.getSpans(match.range.first, match.range.last + 1, ClickableSpan::class.java)
            if (existing.isEmpty()) {
                val url = match.value
                result.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                }, match.range.first, match.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        return result
    }

    private fun startDownload(context: Context, apkUrl: String, version: String) {
        val progressText = TextView(context).apply {
            text = context.getString(R.string.update_downloading_progress, 0)
            setPadding(48, 32, 48, 0)
            setTextColor(0xFFCCCCCC.toInt())
        }

        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            setPadding(48, 16, 48, 32)
            max = 100
            progress = 0
        }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(progressText)
            addView(progressBar)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.update_downloading))
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton(context.getString(R.string.update_cancel), null)
            .show()

        val activity = context as? Activity ?: return

        CoroutineScope(Dispatchers.Main).launch {
            val file = withContext(Dispatchers.IO) {
                installer.download(context, apkUrl, version) { percent ->
                    activity.runOnUiThread {
                        progressBar.progress = percent
                        progressText.text = context.getString(R.string.update_downloading_progress, percent)
                    }
                }
            }

            dialog.dismiss()

            if (file != null) {
                installer.promptInstall(context, file)
            } else {
                AlertDialog.Builder(context)
                    .setTitle(context.getString(R.string.update_download_failed))
                    .setMessage(context.getString(R.string.update_download_failed_message))
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }
}
