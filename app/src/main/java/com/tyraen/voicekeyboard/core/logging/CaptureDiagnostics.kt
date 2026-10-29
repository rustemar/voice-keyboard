package com.tyraen.voicekeyboard.core.logging

import android.os.Process
import com.tyraen.voicekeyboard.BuildConfig
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Technical capture traces for the opt-in test APK, without audio, editor text or credentials. */
object CaptureDiagnostics {
    private val processRun = UUID.randomUUID().toString().take(12)
    private val instances = AtomicLong()
    private val events = AtomicLong()

    fun newId(prefix: String): String = "$prefix-${instances.incrementAndGet()}"

    fun initialize() {
        environment("started")
    }

    fun logCleared() {
        environment("log_cleared")
    }

    private fun environment(eventName: String) {
        if (!BuildConfig.CAPTURE_DIAGNOSTICS) return
        event("Process", eventName, "pid=${Process.myPid()} build=${BuildConfig.BUILD_TYPE} " +
            "version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE}")
    }

    /** Call sites supply only fixed reason names, instance IDs, state names and numeric metadata. */
    fun event(component: String, event: String, details: String = "") {
        if (!BuildConfig.CAPTURE_DIAGNOSTICS) return
        val sequence = events.incrementAndGet()
        val monotonicMs = System.nanoTime() / 1_000_000
        DiagnosticLog.record("CaptureTrace", "run=$processRun seq=$sequence monoMs=$monotonicMs " +
            "thread=${Thread.currentThread().id} component=$component event=$event" +
            if (details.isBlank()) "" else " $details")
    }
}
