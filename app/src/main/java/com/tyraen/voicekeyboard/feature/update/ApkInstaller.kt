package com.tyraen.voicekeyboard.feature.update

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class ApkInstaller(private val http: OkHttpClient) {

    /** The transfer in progress, so Cancel can break a read that is blocked on the network. */
    @Volatile private var activeCall: Call? = null

    /** Stops the running download at once instead of after its next chunk arrives. */
    fun cancelDownload() {
        activeCall?.cancel()
    }

    /**
     * Downloads the APK into the cache. Cancelling the calling coroutine (together with
     * [cancelDownload]) stops the transfer; the [CancellationException] is rethrown, not reported
     * as a failure. The bytes go to a uniquely named part file that is renamed only when
     * complete, so a cancelled transfer that winds down late cannot delete a newer download.
     */
    suspend fun download(
        context: Context,
        url: String,
        version: String,
        onProgress: (Int) -> Unit
    ): File? {
        val dir = File(context.cacheDir, "updates")
        val outFile = File(dir, "VoiceKeyboard-$version.apk")
        val partFile = File(dir, "VoiceKeyboard-$version-${System.nanoTime()}.part")
        val call = http.newCall(Request.Builder().url(url).build())
        activeCall = call
        return try {
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }

            call.execute().use { response ->
                if (!response.isSuccessful) return null

                val body = response.body ?: return null
                val contentLength = body.contentLength()
                var bytesRead = 0L

                body.byteStream().use { input ->
                    partFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            currentCoroutineContext().ensureActive()
                            output.write(buffer, 0, read)
                            bytesRead += read
                            if (contentLength > 0) {
                                onProgress((bytesRead * 100 / contentLength).toInt())
                            }
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (!partFile.renameTo(outFile)) return null

            onProgress(100)
            outFile
        } catch (e: CancellationException) {
            DiagnosticLog.record("ApkInstaller", "Download cancelled")
            throw e
        } catch (e: Exception) {
            DiagnosticLog.record("ApkInstaller", "Download failed: ${e.message}")
            null
        } finally {
            partFile.delete()
            if (activeCall === call) activeCall = null
        }
    }

    fun promptInstall(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // A device with its package installer disabled answers with ActivityNotFoundException.
        try {
            context.startActivity(intent)
        } catch (e: RuntimeException) {
            DiagnosticLog.record("ApkInstaller", "No installer: ${e.javaClass.simpleName}")
            Toast.makeText(context, R.string.update_download_failed, Toast.LENGTH_LONG).show()
        }
    }
}
