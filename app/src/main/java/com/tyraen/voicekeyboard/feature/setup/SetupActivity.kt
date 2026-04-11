package com.tyraen.voicekeyboard.feature.setup

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.app.ServiceLocator
import com.tyraen.voicekeyboard.core.config.PreferenceStore
import com.tyraen.voicekeyboard.core.config.ThemeManager
import com.tyraen.voicekeyboard.core.config.UserPreferences
import com.tyraen.voicekeyboard.core.locale.InterfaceLanguageManager
import com.tyraen.voicekeyboard.core.locale.TranscriptionLocale
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import com.tyraen.voicekeyboard.core.logging.FaultCapture
import com.tyraen.voicekeyboard.feature.audio.MicrophoneCaptureSession
import com.tyraen.voicekeyboard.feature.postprocessing.PostProcessingActivity
import kotlinx.coroutines.*

class SetupActivity : AppCompatActivity() {

    private lateinit var spinnerTheme: Spinner
    private lateinit var spinnerLanguage: Spinner
    private lateinit var editApiKey: EditText
    private lateinit var editEndpoint: EditText
    private lateinit var editModel: EditText
    private lateinit var editLanguage: EditText
    private lateinit var editPrompt: EditText
    private lateinit var switchAutoRecord: Switch
    private lateinit var switchAddSpace: Switch
    private lateinit var txtApiStatus: TextView
    private lateinit var txtTestResult: TextView
    private lateinit var txtTestStatus: TextView
    private lateinit var btnTestRecord: Button
    private lateinit var btnTestClear: Button
    private lateinit var btnApply: Button
    private lateinit var btnSaveLogs: Button
    private lateinit var btnClearLogs: Button
    private lateinit var btnCheckUpdate: Button
    private lateinit var btnPostProcessing: Button
    private lateinit var txtVersion: TextView

    private val preferenceStore: PreferenceStore get() = ServiceLocator.preferenceStore
    private val speechClient get() = ServiceLocator.speechToTextClient
    private val releaseChecker get() = ServiceLocator.releaseChecker

    private var capture: MicrophoneCaptureSession? = null
    private var isTestRecording = false
    private var activeJob: Job? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(InterfaceLanguageManager.applyTo(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        bindViews()
        setupThemeSpinner()
        setupLanguageSpinner()
        setupActions()
        loadCurrentPreferences()
        requestMicPermission()
        checkPendingCrashReport()
        checkForUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        activeJob?.cancel()
        if (isTestRecording) capture?.abort()
    }

    private fun bindViews() {
        spinnerTheme = findViewById(R.id.spinnerTheme)
        spinnerLanguage = findViewById(R.id.spinnerLanguage)
        editApiKey = findViewById(R.id.editApiKey)
        editEndpoint = findViewById(R.id.editEndpoint)
        editModel = findViewById(R.id.editModel)
        editLanguage = findViewById(R.id.editLanguage)
        editPrompt = findViewById(R.id.editPrompt)
        switchAutoRecord = findViewById(R.id.switchAutoRecord)
        switchAddSpace = findViewById(R.id.switchAddSpace)
        txtApiStatus = findViewById(R.id.txtApiStatus)
        txtTestResult = findViewById(R.id.txtTestResult)
        txtTestStatus = findViewById(R.id.txtTestStatus)
        btnTestRecord = findViewById(R.id.btnTestRecord)
        btnTestClear = findViewById(R.id.btnTestClear)
        btnApply = findViewById(R.id.btnApply)
        btnSaveLogs = findViewById(R.id.btnSaveLogs)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        btnPostProcessing = findViewById(R.id.btnPostProcessing)
        txtVersion = findViewById(R.id.txtVersion)

        val txtGetApiKey: TextView = findViewById(R.id.txtGetApiKey)
        val txtGithub: TextView = findViewById(R.id.txtGithub)

        try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            txtVersion.text = "v$versionName"
        } catch (_: Exception) {}

        setupLink(txtGetApiKey, "https://console.groq.com/keys")
        setupLink(txtGithub, "https://github.com/rustemar/voice-keyboard")
    }

    private fun setupActions() {
        btnApply.setOnClickListener { saveAndValidate() }

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
                Toast.makeText(this, "Saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "No logs to save", Toast.LENGTH_SHORT).show()
            }
        }

        btnClearLogs.setOnClickListener {
            DiagnosticLog.purge(this)
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
        }

        btnCheckUpdate.setOnClickListener { checkForUpdates(showUpToDate = true) }

        btnPostProcessing.setOnClickListener {
            startActivity(Intent(this, PostProcessingActivity::class.java))
        }
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
                editLanguage.setText(locale.code)
                editPrompt.setText(locale.defaultPrompt)

                val prefs = buildPreferences()
                CoroutineScope(Dispatchers.Main).launch {
                    preferenceStore.save(prefs)
                    recreate()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadCurrentPreferences() {
        CoroutineScope(Dispatchers.Main).launch {
            val p = preferenceStore.load()
            editApiKey.setText(p.apiKey)
            editEndpoint.setText(p.endpoint)
            editModel.setText(p.model)
            editLanguage.setText(p.language)
            editPrompt.setText(p.prompt)
            switchAutoRecord.isChecked = p.autoRecord
            switchAddSpace.isChecked = p.addTrailingSpace
        }
    }

    private fun saveAndValidate() {
        val prefs = buildPreferences()

        btnApply.isEnabled = false
        showApiStatus("Saving and validating API key...", Color.GRAY)

        activeJob = CoroutineScope(Dispatchers.Main).launch {
            preferenceStore.save(prefs)

            val result = speechClient.validateCredentials(
                apiKey = prefs.apiKey,
                endpoint = prefs.endpoint,
                model = prefs.model,
                cacheDir = cacheDir
            )

            result.onSuccess { msg ->
                showApiStatus("Settings saved. $msg", Color.parseColor("#4CAF50"))
            }.onFailure { error ->
                showApiStatus("Settings saved. Error: ${error.message}", Color.parseColor("#EF4444"))
            }

            btnApply.isEnabled = true
        }
    }

    private fun startTestRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            showTestStatus("Microphone permission required", Color.parseColor("#EF4444"))
            requestMicPermission()
            return
        }

        if (editApiKey.text.toString().trim().isBlank()) {
            showTestStatus("Set API key first and press Apply", Color.parseColor("#EF4444"))
            return
        }

        capture = MicrophoneCaptureSession(this)
        capture?.begin { }

        isTestRecording = true
        btnTestRecord.text = getString(R.string.test_stop)
        btnTestRecord.backgroundTintList = ContextCompat.getColorStateList(this, R.color.mic_transcribing)
        showTestStatus("Recording... Tap \"Stop & Transcribe\" when done", Color.parseColor("#FBBF24"))
    }

    private fun stopTestAndTranscribe() {
        val file = capture?.finalize()
        isTestRecording = false
        btnTestRecord.text = getString(R.string.test_record)
        btnTestRecord.backgroundTintList = ContextCompat.getColorStateList(this, R.color.mic_recording)

        if (file == null || !file.exists()) {
            showTestStatus("Recording failed", Color.parseColor("#EF4444"))
            return
        }

        btnTestRecord.isEnabled = false
        showTestStatus("Transcribing...", Color.GRAY)

        val prefs = buildPreferences()

        activeJob = CoroutineScope(Dispatchers.Main).launch {
            val config = com.tyraen.voicekeyboard.feature.transcription.TranscriptionConfig(
                apiKey = prefs.apiKey,
                endpoint = prefs.endpoint,
                model = prefs.model,
                language = prefs.language,
                prompt = prefs.prompt
            )

            val result = speechClient.transcribe(file, config)

            result.onSuccess { text ->
                if (text.isNotBlank()) {
                    txtTestResult.text = text
                    showTestStatus("Success", Color.parseColor("#4CAF50"))
                } else {
                    txtTestResult.text = ""
                    showTestStatus("No speech detected. Try speaking louder.", Color.parseColor("#FBBF24"))
                }
            }.onFailure { error ->
                showTestStatus("Error: ${error.message}", Color.parseColor("#EF4444"))
            }

            btnTestRecord.isEnabled = true
        }
    }

    private fun buildPreferences() = UserPreferences(
        apiKey = editApiKey.text.toString().trim(),
        endpoint = editEndpoint.text.toString().trim(),
        model = editModel.text.toString().trim(),
        language = editLanguage.text.toString().trim(),
        autoRecord = switchAutoRecord.isChecked,
        addTrailingSpace = switchAddSpace.isChecked,
        prompt = editPrompt.text.toString().trim()
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

    private fun checkPendingCrashReport() {
        if (!FaultCapture.hasPendingReport(this)) return

        val report = FaultCapture.retrieveReport(this) ?: return

        AlertDialog.Builder(this)
            .setTitle("App crashed last time")
            .setMessage("Save crash report to file for debugging?")
            .setPositiveButton("Save") { _, _ ->
                val file = FaultCapture.exportReport(this, report)
                if (file != null) {
                    Toast.makeText(this, "Saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Failed to save report", Toast.LENGTH_SHORT).show()
                }
                FaultCapture.dismissReport(this)
            }
            .setNegativeButton("Dismiss") { _, _ ->
                FaultCapture.dismissReport(this)
            }
            .setCancelable(false)
            .show()
    }

    private fun checkForUpdates(showUpToDate: Boolean = false) {
        CoroutineScope(Dispatchers.Main).launch {
            releaseChecker.checkForUpdate(this@SetupActivity, showUpToDate)
        }
    }

    private fun requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
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
