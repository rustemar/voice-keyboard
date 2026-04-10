package com.tyraen.voicekeyboard.core.logging

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
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
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

            val report = buildString {
                appendLine("=== Voice Keyboard Crash Report ===")
                appendLine("Time: $stamp")
                appendLine("Thread: ${thread.name}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine()
                appendLine("--- Stack Trace ---")
                appendLine(trace.toString())
            }

            File(context.filesDir, PENDING_NAME).writeText(report)
        } catch (_: Exception) {}
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
