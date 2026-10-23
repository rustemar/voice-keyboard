package com.tyraen.voicekeyboard.feature.setup

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.widget.Button
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.app.ServiceLocator
import com.tyraen.voicekeyboard.core.config.AdvancedPreferences
import com.tyraen.voicekeyboard.core.locale.InterfaceLanguageManager
import com.tyraen.voicekeyboard.core.ui.LinkOpener
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AdvancedSettingsActivity : AppCompatActivity() {
    private val scope = MainScope()
    private lateinit var options: AdvancedPreferences

    override fun attachBaseContext(newBase: Context) =
        super.attachBaseContext(InterfaceLanguageManager.applyTo(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced_settings)
        options = AdvancedPreferences(this)
        findViewById<Switch>(R.id.switchCompactTerminal).apply {
            isChecked = options.compactTerminal
            setOnCheckedChangeListener { _, enabled -> options.compactTerminal = enabled }
        }
        findViewById<Switch>(R.id.switchRecognitionApps).apply {
            isChecked = options.recognitionEnabled
            setOnCheckedChangeListener { _, enabled ->
                options.recognitionEnabled = enabled
                if (enabled && !MicPermission.isGranted(this@AdvancedSettingsActivity)) {
                    scope.launch {
                        MicPermission.requestOrOpenSettings(this@AdvancedSettingsActivity, ServiceLocator.preferenceStore)
                    }
                }
            }
        }
        findViewById<Button>(R.id.btnRecognitionSettings).setOnClickListener {
            // On some recent builds the public voice-input action only shows assistant settings.
            // These exported language/input screens retain the actual recognizer picker. Each
            // launch is guarded: OEM builds can omit the aliases or provide a different screen.
            val compatibilityScreens = listOf(
                Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.LanguageSettings")),
                Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$LanguageAndInputSettingsActivity"))
            )
            val publicScreen = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            val screens = if (Build.VERSION.SDK_INT >= 31) compatibilityScreens + publicScreen
                else listOf(publicScreen) + compatibilityScreens
            if (screens.none { screen -> runCatching { startActivity(screen); true }.getOrDefault(false) }) {
                Toast.makeText(this, R.string.recognition_select_hint, Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.btnRecognitionGuide).setOnClickListener {
            LinkOpener.open(this, "https://github.com/rustemar/voice-keyboard#voice-input-for-other-apps")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MicPermission.REQUEST_CODE) {
            scope.launch { MicPermission.recordResult(this@AdvancedSettingsActivity, ServiceLocator.preferenceStore, grantResults) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
