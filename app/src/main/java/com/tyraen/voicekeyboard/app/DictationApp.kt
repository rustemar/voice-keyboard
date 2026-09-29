package com.tyraen.voicekeyboard.app

import android.app.Application
import android.content.Context
import android.os.Build
import com.tyraen.voicekeyboard.BuildConfig
import com.tyraen.voicekeyboard.core.config.ThemeManager
import com.tyraen.voicekeyboard.core.locale.InterfaceLanguageManager
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import com.tyraen.voicekeyboard.core.logging.FaultCapture
import com.tyraen.voicekeyboard.feature.audio.MicrophoneCaptureSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        DiagnosticLog.record(
            "App",
            "Application started, v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), " +
                "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        )

        // Resume any recordings that failed to transcribe in a previous session, and keep retrying
        // them whenever validated internet becomes available. Registered exactly once, process-wide.
        ServiceLocator.connectivityMonitor.register {
            ServiceLocator.transcriptionQueue.onNetworkAvailable()
        }
        ServiceLocator.transcriptionQueue.bootstrap()

        CoroutineScope(Dispatchers.IO).launch {
            ServiceLocator.preferenceStore.normalizeStoredPrompts()
            MicrophoneCaptureSession.deleteLegacyRecordings(this@DictationApp)
        }
    }
}
