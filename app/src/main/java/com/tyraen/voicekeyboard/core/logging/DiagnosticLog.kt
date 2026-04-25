package com.tyraen.voicekeyboard.core.logging

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLog {

    private const val ENTRIES_FILE = "app_log.txt"
    private const val CAPACITY = 500
    private const val TRIM_CHECK_EVERY = 50

    @Volatile
    private var target: File? = null
    private val lock = Any()
    private var writesSinceTrim = 0

    fun init(context: Context) {
        target = File(context.filesDir, ENTRIES_FILE)
    }

    fun record(tag: String, message: String) {
        val file = target ?: return
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        synchronized(lock) {
            try {
                file.appendText("$stamp [$tag] $message\n")
                writesSinceTrim++
                if (writesSinceTrim >= TRIM_CHECK_EVERY) {
                    writesSinceTrim = 0
                    enforce(file)
                }
            } catch (_: Exception) {}
        }
    }

    fun recordFailure(tag: String, message: String, cause: Throwable? = null) {
        val detail = if (cause != null) "$message: ${cause.message}" else message
        record(tag, "ERROR $detail")
    }

    fun readEntries(context: Context): String {
        val file = File(context.filesDir, ENTRIES_FILE)
        return synchronized(lock) {
            if (file.exists()) file.readText() else "(no logs)"
        }
    }

    fun purge(context: Context) {
        synchronized(lock) {
            File(context.filesDir, ENTRIES_FILE).delete()
        }
    }

    fun exportToFile(context: Context): File? {
        return try {
            val dir = File(context.getExternalFilesDir(null), "logs")
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dest = File(dir, "voicekeyboard_log_$stamp.txt")
            dest.writeText(readEntries(context))
            dest
        } catch (_: Exception) {
            null
        }
    }

    private fun enforce(file: File) {
        try {
            val lines = file.readLines()
            if (lines.size > CAPACITY) {
                file.writeText(lines.takeLast(CAPACITY).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {}
    }
}
