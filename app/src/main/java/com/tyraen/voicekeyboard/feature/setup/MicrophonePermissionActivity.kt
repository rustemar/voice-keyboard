package com.tyraen.voicekeyboard.feature.setup

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.app.ServiceLocator
import com.tyraen.voicekeyboard.core.locale.InterfaceLanguageManager
import com.tyraen.voicekeyboard.core.ui.LinkOpener
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Invisible trampoline the keyboard launches when the mic is tapped without RECORD_AUDIO. An
 * InputMethodService cannot request runtime permissions, and sending people to the settings
 * screen to find out why nothing happens was the previous, silent dead end. Shows the microphone
 * disclosure first on a fresh install, then the system prompt, and closes as soon as that is
 * answered; the keyboard stays where it was.
 */
class MicrophonePermissionActivity : AppCompatActivity() {

    private val scope = MainScope()
    private val preferenceStore get() = ServiceLocator.preferenceStore

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(InterfaceLanguageManager.applyTo(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (MicPermission.isGranted(this)) {
            finish()
            return
        }
        // Relaunched (e.g. a configuration change the manifest does not absorb) while the
        // system prompt is up: its result reaches this instance, nothing to ask again.
        if (savedInstanceState != null) return
        scope.launch {
            if (preferenceStore.isMicDisclosureAccepted()) request() else showDisclosure()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun showDisclosure() {
        AlertDialog.Builder(this)
            .setTitle(R.string.mic_disclosure_title)
            .setMessage(R.string.mic_disclosure_body)
            .setCancelable(false)
            .setPositiveButton(R.string.mic_disclosure_continue) { _, _ ->
                scope.launch {
                    preferenceStore.setMicDisclosureAccepted()
                    request()
                }
            }
            .setNeutralButton(R.string.mic_disclosure_privacy) { _, _ ->
                LinkOpener.open(this, "https://github.com/rustemar/voice-keyboard/blob/main/PRIVACY.md")
                showDisclosure()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .show()
    }

    private suspend fun request() {
        // The settings page never calls back, so there is nothing to wait for in that case.
        if (!MicPermission.requestOrOpenSettings(this, preferenceStore)) finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        scope.launch {
            MicPermission.recordResult(this@MicrophonePermissionActivity, preferenceStore, grantResults)
            finish()
        }
    }
}
