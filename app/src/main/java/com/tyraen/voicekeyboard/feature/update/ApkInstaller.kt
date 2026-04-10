package com.tyraen.voicekeyboard.feature.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class ApkInstaller(private val http: OkHttpClient) {

    fun download(
        context: Context,
        url: String,
        version: String,
        onProgress: (Int) -> Unit
    ): File? {
        return try {
            val dir = File(context.cacheDir, "updates")
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }

            val outFile = File(dir, "VoiceKeyboard-$version.apk")

            val request = Request.Builder().url(url).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null

                val body = response.body ?: return null
                val contentLength = body.contentLength()
                var bytesRead = 0L

                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesRead += read
                            if (contentLength > 0) {
                                onProgress((bytesRead * 100 / contentLength).toInt())
                            }
                        }
                    }
                }
            }

            onProgress(100)
            outFile
        } catch (e: Exception) {
            DiagnosticLog.record("ApkInstaller", "Download failed: ${e.message}")
            null
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

        context.startActivity(intent)
    }
}
