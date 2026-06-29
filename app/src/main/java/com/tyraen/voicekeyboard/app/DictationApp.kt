package com.tyraen.voicekeyboard.app

import android.app.Application
import android.content.Context
import com.tyraen.voicekeyboard.core.config.ThemeManager
import com.tyraen.voicekeyboard.core.locale.InterfaceLanguageManager
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import com.tyraen.voicekeyboard.core.logging.FaultCapture

class DictationApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(InterfaceLanguageManager.applyTo(base))
    }

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.initialize(this)
        ThemeManager.apply(this)
        FaultCapture.attach(this)
        DiagnosticLog.init(this)
        DiagnosticLog.record("App", "Application started")

        // Resume any recordings that failed to transcribe in a previous session, and keep retrying
        // them whenever validated internet becomes available. Registered exactly once, process-wide.
        ServiceLocator.connectivityMonitor.register {
            ServiceLocator.transcriptionQueue.onNetworkAvailable()
        }
        ServiceLocator.transcriptionQueue.bootstrap()
    }
}
