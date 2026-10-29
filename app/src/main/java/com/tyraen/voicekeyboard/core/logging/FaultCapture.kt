package com.tyraen.voicekeyboard.core.logging

import android.content.Context
import android.os.Build
import com.tyraen.voicekeyboard.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale

object FaultCapture {

    private const val REPORT_DIR = "crash_logs"
    private const val PENDING_NAME = "pending_crash.txt"

    fun attach(context: Context) {
        val appCtx = context.applicationContext
        val upstream = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            persist(appCtx, thread, error)
            upstream?.uncaughtException(thread, error)
        }
    }

    private fun persist(context: Context, thread: Thread, error: Throwable) {
        try {
            val trace = technicalStackTrace(error)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

            val report = buildString {
                appendLine("=== Voice Keyboard Crash Report ===")
                appendLine("Time: $stamp")
                appendLine("App: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Thread ID: ${thread.id}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine()
                appendLine("--- Stack Trace ---")
                appendLine(trace)
            }

            File(context.filesDir, PENDING_NAME).writeText(report)
        } catch (_: Exception) {}
    }

    /** Retain crash locations and exception types without provider/parser error messages. */
    internal fun technicalStackTrace(error: Throwable): String = buildString {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var remaining = 32

        fun appendError(current: Throwable, indent: String, caption: String) {
            if (!seen.add(current)) {
                appendLine("$indent$caption[circular reference: ${current.javaClass.name}]")
                return
            }
            if (remaining-- <= 0) {
                appendLine("$indent[additional exceptions omitted]")
                return
            }
            appendLine("$indent$caption${current.javaClass.name}")
            for (frame in current.stackTrace.take(128)) appendLine("$indent\tat $frame")
            if (current.stackTrace.size > 128) appendLine("$indent\t[additional frames omitted]")
            for (suppressed in current.suppressed) {
                if (remaining <= 0) {
                    appendLine("$indent\t[additional suppressed exceptions omitted]")
                    break
                }
                appendError(suppressed, "$indent\t", "Suppressed: ")
            }
            current.cause?.let { appendError(it, indent, "Caused by: ") }
        }

        appendError(error, "", "")
    }

    fun hasPendingReport(context: Context): Boolean =
        File(context.filesDir, PENDING_NAME).exists()

    fun retrieveReport(context: Context): String? {
        val file = File(context.filesDir, PENDING_NAME)
        return if (file.exists()) file.readText() else null
    }

    fun dismissReport(context: Context) {
        File(context.filesDir, PENDING_NAME).delete()
    }

    fun exportReport(context: Context, report: String): File? {
        return try {
            val dir = File(context.getExternalFilesDir(null), REPORT_DIR)
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "crash_$stamp.txt")
            file.writeText(report)
            file
        } catch (_: Exception) {
            null
        }
    }
}
