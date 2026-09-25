package com.tyraen.voicekeyboard.feature.setup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.app.ServiceLocator
import com.tyraen.voicekeyboard.core.config.PreferenceStore
import com.tyraen.voicekeyboard.core.config.ProviderPresets
import com.tyraen.voicekeyboard.core.config.ThemeManager
import com.tyraen.voicekeyboard.core.config.UserPreferences
import com.tyraen.voicekeyboard.core.locale.InterfaceLanguageManager
import com.tyraen.voicekeyboard.core.locale.TranscriptionLocale
import com.tyraen.voicekeyboard.core.network.ApiEndpoint
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import com.tyraen.voicekeyboard.core.logging.FaultCapture
import com.tyraen.voicekeyboard.feature.audio.MicrophoneCaptureSession
import com.tyraen.voicekeyboard.feature.postprocessing.PostProcessingActivity
import com.tyraen.voicekeyboard.feature.transcription.SpeechToTextClient
import com.tyraen.voicekeyboard.feature.transcription.WhisperPromptBuilder
import com.tyraen.voicekeyboard.feature.vocabulary.VocabularyActivity
import kotlinx.coroutines.*

class SetupActivity : AppCompatActivity() {

    companion object {
        private const val COLOR_OK = "#4CAF50"
        private const val COLOR_ERROR = "#EF4444"
        private const val COLOR_WARN = "#FBBF24"
    }

    private lateinit var spinnerTheme: Spinner
    private lateinit var spinnerLanguage: Spinner
    private lateinit var spinnerSttPreset: Spinner
    private lateinit var editApiKey: EditText
    private lateinit var editEndpoint: EditText
    private lateinit var editModel: EditText
    private lateinit var editLanguage: EditText
    private lateinit var editPrompt: EditText
    private lateinit var switchAutoRecord: Switch
    private lateinit var switchReturnToPreviousKeyboard: Switch
    private lateinit var switchAddSpace: Switch
    private lateinit var switchSingleWordStripPunct: Switch
    private lateinit var switchUpdateCheck: Switch
    private lateinit var txtApiStatus: TextView
    private lateinit var txtTestResult: TextView
    private lateinit var txtTestStatus: TextView
    private lateinit var txtKeyboardStatus: TextView
    private lateinit var txtActiveKeyboardHint: TextView
    private lateinit var btnEnableKeyboard: Button
    private lateinit var btnSwitchKeyboard: Button
    private lateinit var btnTestRecord: Button
    private lateinit var btnTestClear: Button
    private lateinit var btnApply: Button
    private lateinit var btnSaveLogs: Button
    private lateinit var btnShareLogs: Button
    private lateinit var btnClearLogs: Button
    private lateinit var btnCheckUpdate: Button
    private lateinit var btnPostProcessing: Button
    private lateinit var btnVocabulary: Button
    private lateinit var txtVersion: TextView

    private val preferenceStore: PreferenceStore get() = ServiceLocator.preferenceStore
    private val speechClient get() = ServiceLocator.speechToTextClient
    private val releaseChecker get() = ServiceLocator.releaseChecker

    /** Remembered so editing the language list here doesn't reset the keyboard's current pick. */
    private var activeLanguage: String = ""

    private var capture: MicrophoneCaptureSession? = null
    private var isTestRecording = false
    private var activeJob: Job? = null
    private val scope = MainScope()

    /** Position the code itself put the preset spinner at; its echo callback is ignored. */
    private var presetPosSetByCode = -1

    /** True while the code sets the update switch, so its listener ignores that echo. */
    private var bindingUpdateSwitch = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(InterfaceLanguageManager.applyTo(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        bindViews()
        setupThemeSpinner()
        setupLanguageSpinner()
        setupPresetSpinner()
        setupActions()
        loadCurrentPreferences()
        showMicDisclosureIfNeeded()
        checkPendingCrashReport()
        resolveUpdateConsent()
    }

    override fun onResume() {
        super.onResume()
        refreshKeyboardStatus()
    }

    /** singleTop: the cog re-enters this instance, so pick up what the keyboard changed meanwhile. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        loadCurrentPreferences()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MicPermission.REQUEST_CODE) {
            scope.launch { MicPermission.recordResult(this@SetupActivity, preferenceStore, grantResults) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        capture?.release()
    }

    private fun bindViews() {
        spinnerTheme = findViewById(R.id.spinnerTheme)
        spinnerLanguage = findViewById(R.id.spinnerLanguage)
        spinnerSttPreset = findViewById(R.id.spinnerSttPreset)
        editApiKey = findViewById(R.id.editApiKey)
        editEndpoint = findViewById(R.id.editEndpoint)
        editModel = findViewById(R.id.editModel)
        editLanguage = findViewById(R.id.editLanguage)
        editPrompt = findViewById(R.id.editPrompt)
        switchAutoRecord = findViewById(R.id.switchAutoRecord)
        switchReturnToPreviousKeyboard = findViewById(R.id.switchReturnToPreviousKeyboard)
        switchAddSpace = findViewById(R.id.switchAddSpace)
        switchSingleWordStripPunct = findViewById(R.id.switchSingleWordStripPunct)
        switchUpdateCheck = findViewById(R.id.switchUpdateCheck)
        txtApiStatus = findViewById(R.id.txtApiStatus)
        txtTestResult = findViewById(R.id.txtTestResult)
        txtTestStatus = findViewById(R.id.txtTestStatus)
        txtKeyboardStatus = findViewById(R.id.txtKeyboardStatus)
        txtActiveKeyboardHint = findViewById(R.id.txtActiveKeyboardHint)
        btnEnableKeyboard = findViewById(R.id.btnEnableKeyboard)
        btnSwitchKeyboard = findViewById(R.id.btnSwitchKeyboard)
        btnTestRecord = findViewById(R.id.btnTestRecord)
        btnTestClear = findViewById(R.id.btnTestClear)
        btnApply = findViewById(R.id.btnApply)
        btnSaveLogs = findViewById(R.id.btnSaveLogs)
        btnShareLogs = findViewById(R.id.btnShareLogs)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        btnPostProcessing = findViewById(R.id.btnPostProcessing)
        btnVocabulary = findViewById(R.id.btnVocabulary)
        txtVersion = findViewById(R.id.txtVersion)

        val txtGetApiKey: TextView = findViewById(R.id.txtGetApiKey)
        val txtGetApiKeyMistral: TextView = findViewById(R.id.txtGetApiKeyMistral)
        val txtGithub: TextView = findViewById(R.id.txtGithub)

        try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            txtVersion.text = "v$versionName"
        } catch (_: Exception) {}

        setupLink(txtGetApiKey, "https://console.groq.com/keys")
        setupLink(txtGetApiKeyMistral, "https://console.mistral.ai/api-keys")
        setupLink(txtGithub, "https://github.com/rustemar/voice-keyboard")
    }

    private fun setupActions() {
        btnApply.setOnClickListener { saveAndValidate() }

        btnEnableKeyboard.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.settings_enable_instructions, Toast.LENGTH_LONG).show()
            }
        }
        btnSwitchKeyboard.setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }

        btnTestRecord.setOnClickListener {
            if (isTestRecording) stopTestAndTranscribe() else startTestRecording()
        }

        btnTestClear.setOnClickListener {
            txtTestResult.text = ""
            txtTestStatus.visibility = View.GONE
        }

        btnSaveLogs.setOnClickListener {
            val file = DiagnosticLog.exportToFile(this)
            if (file != null) {
                Toast.makeText(this, getString(R.string.logs_saved_to, file.absolutePath), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, R.string.logs_none, Toast.LENGTH_SHORT).show()
            }
        }

        btnShareLogs.setOnClickListener {
            if (DiagnosticLog.hasEntries(this)) {
                shareText("Voice Keyboard logs", DiagnosticLog.readEntries(this))
            } else {
                Toast.makeText(this, R.string.logs_none, Toast.LENGTH_SHORT).show()
            }
        }

        btnClearLogs.setOnClickListener {
            DiagnosticLog.purge(this)
            Toast.makeText(this, R.string.logs_cleared, Toast.LENGTH_SHORT).show()
        }

        btnCheckUpdate.setOnClickListener { checkForUpdates(showUpToDate = true) }

        // Not gated on isPressed: that is false for TalkBack, Switch Access and a hardware
        // keyboard, which silently dropped the change and left no way to revoke consent.
        switchUpdateCheck.setOnCheckedChangeListener { _, isChecked ->
            if (bindingUpdateSwitch) return@setOnCheckedChangeListener
            scope.launch { preferenceStore.setUpdateCheckEnabled(isChecked) }
        }

        btnPostProcessing.setOnClickListener {
            startActivity(Intent(this, PostProcessingActivity::class.java))
        }

        btnVocabulary.setOnClickListener {
            startActivity(Intent(this, VocabularyActivity::class.java))
        }
    }

    /**
     * The panel this keyboard shows has no letter keys, so while it is the active input method
     * the fields on this very screen cannot be typed into. Say so, and offer the way out.
     */
    private fun refreshKeyboardStatus() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = try {
            imm.enabledInputMethodList.any { it.packageName == packageName }
        } catch (_: Exception) {
            false
        }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val active = current.startsWith("$packageName/")

        txtKeyboardStatus.setText(if (enabled) R.string.keyboard_status_enabled else R.string.keyboard_status_disabled)
        txtKeyboardStatus.setTextColor(Color.parseColor(if (enabled) COLOR_OK else COLOR_WARN))
        txtActiveKeyboardHint.visibility = if (active) View.VISIBLE else View.GONE
    }

    private val themeValues = listOf(ThemeManager.THEME_AUTO, ThemeManager.THEME_LIGHT, ThemeManager.THEME_DARK)

    private fun setupThemeSpinner() {
        val themeLabels = listOf(
            getString(R.string.theme_auto),
            getString(R.string.theme_light),
            getString(R.string.theme_dark)
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, themeLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTheme.adapter = adapter

        val currentTheme = ThemeManager.current(this)
        spinnerTheme.setSelection(themeValues.indexOf(currentTheme).coerceAtLeast(0))

        spinnerTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = themeValues[position]
                if (selected == ThemeManager.current(this@SetupActivity)) return
                ThemeManager.persist(this@SetupActivity, selected)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupLanguageSpinner() {
        val locales = TranscriptionLocale.entries
        val displayNames = locales.map { "${it.displayName} (${it.code})" }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLanguage.adapter = adapter

        val savedCode = InterfaceLanguageManager.resolveActive(this)
        spinnerLanguage.setSelection(TranscriptionLocale.positionOf(savedCode))

        spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val locale = locales[position]
                val currentCode = InterfaceLanguageManager.resolveActive(this@SetupActivity)

                if (locale.code == currentCode) return

                InterfaceLanguageManager.persist(this@SetupActivity, locale.code)
                // Promote the newly picked language to the front of the dictation list instead of
                // replacing it — a user dictating in several languages shouldn't lose the others
                // just because they changed the UI language.
                val codes = TranscriptionLocale.parseCodes(editLanguage.text.toString())
                val reordered = listOf(locale.code) + codes.filter { it != locale.code }
                editLanguage.setText(TranscriptionLocale.formatCodes(reordered))
                activeLanguage = locale.code
                editPrompt.setText(locale.defaultPrompt)

                val prefs = buildPreferences()
                scope.launch {
                    preferenceStore.save(prefs)
                    recreate()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * "Custom" plus the known speech-to-text providers. Picking one fills the address and model;
     * editing the address by hand moves the spinner back to whatever matches, or to "Custom".
     */
    private fun setupPresetSpinner() {
        val labels = listOf(getString(R.string.preset_custom)) + ProviderPresets.speechToText.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSttPreset.adapter = adapter

        spinnerSttPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == presetPosSetByCode) {
                    presetPosSetByCode = -1
                    return
                }
                presetPosSetByCode = -1
                val preset = ProviderPresets.speechToText.getOrNull(position - 1) ?: return
                editEndpoint.setText(preset.endpoint)
                editModel.setText(preset.model)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        editEndpoint.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val pos = ProviderPresets.indexOf(ProviderPresets.speechToText, s?.toString() ?: "") + 1
                if (spinnerSttPreset.selectedItemPosition != pos) {
                    presetPosSetByCode = pos
                    spinnerSttPreset.setSelection(pos, false)
                }
            }
        })
    }

    private fun loadCurrentPreferences() {
        scope.launch {
            val p = preferenceStore.load()
            editApiKey.setText(p.apiKey)
            editEndpoint.setText(p.endpoint)
            editModel.setText(p.model)
            editLanguage.setText(p.languages)
            activeLanguage = p.effectiveLanguage
            editPrompt.setText(p.prompt)
            switchAutoRecord.isChecked = p.autoRecord
            switchReturnToPreviousKeyboard.isChecked = p.returnToPreviousKeyboard
            switchAddSpace.isChecked = p.addTrailingSpace
            switchSingleWordStripPunct.isChecked = p.singleWordStripPunctuation
            setUpdateSwitch(preferenceStore.isUpdateCheckEnabled())
        }
    }

    private fun saveAndValidate() {
        val prefs = buildPreferences()
        if (prefs.endpoint != editEndpoint.text.toString().trim()) editEndpoint.setText(prefs.endpoint)

        btnApply.isEnabled = false
        showApiStatus(getString(R.string.pp_validating), Color.GRAY)

        activeJob = scope.launch {
            preferenceStore.save(prefs)

            val result = speechClient.validateCredentials(
                apiKey = prefs.apiKey,
                endpoint = prefs.endpoint,
                model = prefs.model,
                cacheDir = cacheDir
            )

            val saved = getString(R.string.settings_saved)
            result.onSuccess { msg ->
                showApiStatus("$saved $msg", Color.parseColor(COLOR_OK))
            }.onFailure { error ->
                showApiStatus("$saved ${getString(R.string.status_error, error.message)}", Color.parseColor(COLOR_ERROR))
            }

            btnApply.isEnabled = true
        }
    }

    private fun startTestRecording() {
        if (!MicPermission.isGranted(this)) {
            showTestStatus(getString(R.string.error_mic_permission), Color.parseColor(COLOR_ERROR))
            scope.launch { MicPermission.requestOrOpenSettings(this@SetupActivity, preferenceStore) }
            return
        }

        if (editApiKey.text.toString().trim().isBlank()) {
            showTestStatus(getString(R.string.test_status_set_key), Color.parseColor(COLOR_ERROR))
            return
        }

        val session = MicrophoneCaptureSession(this)
        if (!session.begin { }) {
            showTestStatus(getString(R.string.error_mic_unavailable), Color.parseColor(COLOR_ERROR))
            return
        }
        capture = session

        isTestRecording = true
        btnTestRecord.text = getString(R.string.test_stop)
        btnTestRecord.backgroundTintList = ContextCompat.getColorStateList(this, R.color.mic_transcribing)
        showTestStatus(getString(R.string.test_status_recording), Color.parseColor(COLOR_WARN))
    }

    private fun stopTestAndTranscribe() {
        val file = capture?.finalize()
        isTestRecording = false
        btnTestRecord.text = getString(R.string.test_record)
        btnTestRecord.backgroundTintList = ContextCompat.getColorStateList(this, R.color.mic_recording)

        if (file == null || !file.exists()) {
            showTestStatus(getString(R.string.test_status_failed), Color.parseColor(COLOR_ERROR))
            return
        }

        btnTestRecord.isEnabled = false
        showTestStatus(getString(R.string.status_transcribing), Color.GRAY)

        val prefs = buildPreferences()

        activeJob = scope.launch {
            val vocabulary = preferenceStore.loadVocabulary()
            val config = com.tyraen.voicekeyboard.feature.transcription.TranscriptionConfig(
                apiKey = prefs.apiKey,
                endpoint = prefs.endpoint,
                model = prefs.model,
                language = prefs.effectiveLanguage,
                prompt = WhisperPromptBuilder.build(
                    TranscriptionLocale.promptFor(prefs.effectiveLanguage, prefs.prompt), vocabulary
                )
            )

            val result = speechClient.transcribe(file, config)
            file.delete()

            result.onSuccess { text ->
                if (text.isNotBlank()) {
                    txtTestResult.text = text
                    showTestStatus(getString(R.string.test_status_success), Color.parseColor(COLOR_OK))
                } else {
                    txtTestResult.text = ""
                    showTestStatus(getString(R.string.test_status_no_speech), Color.parseColor(COLOR_WARN))
                }
            }.onFailure { error ->
                showTestStatus(getString(R.string.status_error, error.message), Color.parseColor(COLOR_ERROR))
            }

            btnTestRecord.isEnabled = true
        }
    }

    private fun buildPreferences() = UserPreferences(
        apiKey = editApiKey.text.toString().trim(),
        // A pasted base URL ("api.mistral.ai/v1") or a scheme-less one is completed here, and an
        // empty field falls back to the default instead of saving an address that cannot work.
        endpoint = ApiEndpoint.complete(editEndpoint.text.toString(), SpeechToTextClient.REQUEST_PATH)
            .ifBlank { getString(R.string.default_endpoint) },
        model = editModel.text.toString().trim(),
        languages = TranscriptionLocale.formatCodes(
            TranscriptionLocale.parseCodes(editLanguage.text.toString())
        ),
        activeLanguage = activeLanguage,
        autoRecord = switchAutoRecord.isChecked,
        addTrailingSpace = switchAddSpace.isChecked,
        prompt = editPrompt.text.toString().trim(),
        singleWordStripPunctuation = switchSingleWordStripPunct.isChecked,
        returnToPreviousKeyboard = switchReturnToPreviousKeyboard.isChecked
    )

    private fun showApiStatus(message: String, color: Int) {
        txtApiStatus.text = message
        txtApiStatus.setTextColor(color)
        txtApiStatus.visibility = View.VISIBLE
    }

    private fun showTestStatus(message: String, color: Int) {
        txtTestStatus.text = message
        txtTestStatus.setTextColor(color)
        txtTestStatus.visibility = View.VISIBLE
    }

    private fun shareText(subject: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(intent, subject))
        } catch (_: Exception) {}
    }

    private fun checkPendingCrashReport() {
        if (!FaultCapture.hasPendingReport(this)) return

        val report = FaultCapture.retrieveReport(this) ?: return

        AlertDialog.Builder(this)
            .setTitle(R.string.crash_title)
            .setMessage(R.string.crash_message)
            .setPositiveButton(R.string.crash_share) { _, _ ->
                shareText("Voice Keyboard crash report", report)
                FaultCapture.dismissReport(this)
            }
            .setNeutralButton(R.string.crash_save) { _, _ ->
                val file = FaultCapture.exportReport(this, report)
                if (file != null) {
                    Toast.makeText(this, getString(R.string.logs_saved_to, file.absolutePath), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, R.string.crash_save_failed, Toast.LENGTH_SHORT).show()
                }
                FaultCapture.dismissReport(this)
            }
            .setNegativeButton(R.string.crash_dismiss) { _, _ ->
                FaultCapture.dismissReport(this)
            }
            .setCancelable(false)
            .show()
    }

    /**
     * The app may not poll GitHub on its own until the user says so. On the first launch this
     * asks once, with the two buttons carrying equal weight and the text spelling out that
     * updates taken this way skip whatever review the install source performs. Declining is
     * remembered; the "Check for updates" button stays available either way, and every offer
     * repeats the disclosure before anything is downloaded.
     */
    private fun resolveUpdateConsent() {
        scope.launch {
            when {
                preferenceStore.isUpdateCheckEnabled() -> checkForUpdates()
                // The microphone disclosure owns the first launch. Stacking this dialog on top of
                // it buries the one the user actually has to read; ask on a later launch instead.
                !preferenceStore.isMicDisclosureAccepted() -> Unit
                !preferenceStore.isUpdateConsentShown() -> showUpdateConsentDialog()
            }
        }
    }

    /** Write the switch without the listener mistaking it for a user action. */
    private fun setUpdateSwitch(value: Boolean) {
        bindingUpdateSwitch = true
        switchUpdateCheck.isChecked = value
        bindingUpdateSwitch = false
    }

    private fun showUpdateConsentDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.update_consent_title)
            .setMessage(R.string.update_consent_body)
            // Same neutral button as the microphone disclosure: the decision is only informed if
            // the policy is reachable from the dialog that asks for it.
            .setNeutralButton(R.string.mic_disclosure_privacy) { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/rustemar/voice-keyboard/blob/main/PRIVACY.md")
                        )
                    )
                } catch (_: Exception) {}
                showUpdateConsentDialog()
            }
            .setPositiveButton(R.string.update_consent_enable) { _, _ ->
                scope.launch {
                    preferenceStore.setUpdateCheckEnabled(true)
                    setUpdateSwitch(true)
                    checkForUpdates()
                }
            }
            .setNegativeButton(R.string.update_consent_decline) { _, _ ->
                scope.launch { preferenceStore.setUpdateConsentShown() }
            }
            .setOnCancelListener {
                // Dismissing is a "no": nothing is enabled, and we do not nag again.
                scope.launch { preferenceStore.setUpdateConsentShown() }
            }
            .show()
    }

    private fun checkForUpdates(showUpToDate: Boolean = false) {
        scope.launch {
            releaseChecker.checkForUpdate(this@SetupActivity, showUpToDate)
        }
    }

    /**
     * First launch only: explain what the microphone is for, then ask once. Later launches never
     * re-prompt on their own; the test button and the keyboard's mic ask when the user acts.
     */
    private fun showMicDisclosureIfNeeded() {
        scope.launch {
            if (!preferenceStore.isMicDisclosureAccepted()) showMicDisclosure()
        }
    }

    private fun showMicDisclosure() {
        AlertDialog.Builder(this)
            .setTitle(R.string.mic_disclosure_title)
            .setMessage(R.string.mic_disclosure_body)
            .setCancelable(false)
            .setPositiveButton(R.string.mic_disclosure_continue) { _, _ ->
                scope.launch {
                    preferenceStore.setMicDisclosureAccepted()
                    MicPermission.requestOrOpenSettings(this@SetupActivity, preferenceStore)
                }
            }
            .setNeutralButton(R.string.mic_disclosure_privacy) { _, _ ->
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/rustemar/voice-keyboard/blob/main/PRIVACY.md")
                    )
                )
                showMicDisclosure()
            }
            .show()
    }

    private fun setupLink(view: TextView, url: String) {
        view.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
        view.setOnLongClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
            Toast.makeText(this, getString(R.string.link_copied), Toast.LENGTH_SHORT).show()
            true
        }
    }
}
