package com.tyraen.voicekeyboard.feature.postprocessing

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.app.ServiceLocator
import com.tyraen.voicekeyboard.core.config.PostProcessingPreferences
import com.tyraen.voicekeyboard.core.locale.InterfaceLanguageManager
import com.tyraen.voicekeyboard.core.locale.TranscriptionLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PostProcessingActivity : AppCompatActivity() {

    private lateinit var switchEnabled: Switch
    private lateinit var checkTerminalVisible: CheckBox
    private lateinit var spinnerProvider: Spinner
    private lateinit var editApiKey: EditText
    private lateinit var editEndpoint: EditText
    private lateinit var editModel: EditText
    private lateinit var editTemperature: EditText
    private lateinit var editPromptFix: EditText
    private lateinit var editPromptShorten: EditText
    private lateinit var editPromptEmoji: EditText
    private lateinit var editPromptSuffix: EditText
    private lateinit var spinnerTranslateLang: Spinner
    private lateinit var editTranslateModel: EditText
    private lateinit var btnApply: Button
    private lateinit var txtStatus: TextView

    private val preferenceStore get() = ServiceLocator.preferenceStore
    private val postProcessingClient get() = ServiceLocator.postProcessingClient

    private val providers = listOf(
        PostProcessingPreferences.PROVIDER_OPENAI,
        PostProcessingPreferences.PROVIDER_CLAUDE
    )
    private val providerLabels = listOf("OpenAI API", "Claude API")

    private var suppressProviderChange = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(InterfaceLanguageManager.applyTo(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_postprocessing)

        bindViews()
        setupProviderSpinner()
        setupTranslateLangSpinner()
        setupActions()
        loadPreferences()
    }

    private fun bindViews() {
        switchEnabled = findViewById(R.id.switchPpEnabled)
        checkTerminalVisible = findViewById(R.id.checkTerminalVisible)
        spinnerProvider = findViewById(R.id.spinnerProvider)
        editApiKey = findViewById(R.id.editPpApiKey)
        editEndpoint = findViewById(R.id.editPpEndpoint)
        editModel = findViewById(R.id.editPpModel)
        editTemperature = findViewById(R.id.editPpTemperature)
        editPromptFix = findViewById(R.id.editPromptFix)
        editPromptShorten = findViewById(R.id.editPromptShorten)
        editPromptEmoji = findViewById(R.id.editPromptEmoji)
        editPromptSuffix = findViewById(R.id.editPromptSuffix)
        spinnerTranslateLang = findViewById(R.id.spinnerTranslateLang)
        editTranslateModel = findViewById(R.id.editTranslateModel)
        btnApply = findViewById(R.id.btnPpApply)
        txtStatus = findViewById(R.id.txtPpStatus)
    }

    private fun setupProviderSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, providerLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerProvider.adapter = adapter

        spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressProviderChange) return
                val provider = providers[position]
                editEndpoint.hint = PostProcessingPreferences.defaultEndpoint(provider)
                editModel.hint = PostProcessingPreferences.defaultModel(provider)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupTranslateLangSpinner() {
        val locales = TranscriptionLocale.entries
        val displayNames = locales.map { "${it.displayName} (${it.code})" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTranslateLang.adapter = adapter
    }

    private fun setupActions() {
        btnApply.setOnClickListener { saveAndValidate() }

        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            CoroutineScope(Dispatchers.Main).launch {
                val current = preferenceStore.loadPostProcessing()
                preferenceStore.savePostProcessing(current.copy(enabled = isChecked))
            }
        }
    }

    private fun loadPreferences() {
        CoroutineScope(Dispatchers.Main).launch {
            val pp = preferenceStore.loadPostProcessing()
            switchEnabled.isChecked = pp.enabled
            checkTerminalVisible.isChecked = pp.terminalVisible

            suppressProviderChange = true
            val providerIndex = providers.indexOf(pp.provider).coerceAtLeast(0)
            spinnerProvider.setSelection(providerIndex)
            suppressProviderChange = false

            editApiKey.setText(pp.apiKey)
            editEndpoint.setText(pp.endpoint)
            editModel.setText(pp.model)
            editTemperature.setText(pp.temperature.toString())

            editEndpoint.hint = PostProcessingPreferences.defaultEndpoint(pp.provider)
            editModel.hint = PostProcessingPreferences.defaultModel(pp.provider)

            editPromptFix.setText(pp.promptFix.ifBlank { PostProcessingPreferences.DEFAULT_PROMPT_FIX })
            editPromptShorten.setText(pp.promptShorten.ifBlank { PostProcessingPreferences.DEFAULT_PROMPT_SHORTEN })
            editPromptEmoji.setText(pp.promptEmoji.ifBlank { PostProcessingPreferences.DEFAULT_PROMPT_EMOJI })
            editPromptSuffix.setText(pp.promptSuffix.ifBlank { PostProcessingPreferences.DEFAULT_PROMPT_SUFFIX })

            spinnerTranslateLang.setSelection(TranscriptionLocale.positionOf(pp.translateLang))
            editTranslateModel.setText(pp.translateModel)
            editTranslateModel.hint = PostProcessingPreferences.defaultTranslateModel(pp.provider)
        }
    }

    private fun saveAndValidate() {
        val providerIndex = spinnerProvider.selectedItemPosition
        val tempText = editTemperature.text.toString().trim()
        val temperature = tempText.toFloatOrNull() ?: PostProcessingPreferences.DEFAULT_TEMPERATURE

        val prefs = PostProcessingPreferences(
            enabled = switchEnabled.isChecked,
            provider = providers[providerIndex],
            apiKey = editApiKey.text.toString().trim(),
            endpoint = editEndpoint.text.toString().trim(),
            model = editModel.text.toString().trim(),
            temperature = temperature.coerceIn(0f, 2f),
            promptFix = editPromptFix.text.toString().trim(),
            promptShorten = editPromptShorten.text.toString().trim(),
            promptEmoji = editPromptEmoji.text.toString().trim(),
            promptSuffix = editPromptSuffix.text.toString().trim(),
            translateLang = TranscriptionLocale.entries[spinnerTranslateLang.selectedItemPosition].code,
            translateModel = editTranslateModel.text.toString().trim(),
            terminalVisible = checkTerminalVisible.isChecked
        )

        btnApply.isEnabled = false
        showStatus(getString(R.string.pp_validating), Color.GRAY)

        CoroutineScope(Dispatchers.Main).launch {
            preferenceStore.savePostProcessing(prefs)

            if (prefs.apiKey.isBlank()) {
                showStatus(getString(R.string.pp_saved), Color.parseColor("#4CAF50"))
                btnApply.isEnabled = true
                return@launch
            }

            val result = postProcessingClient.validateCredentials(prefs)

            result.onSuccess { msg ->
                showStatus("${getString(R.string.pp_saved)} $msg", Color.parseColor("#4CAF50"))
            }.onFailure { error ->
                showStatus("${getString(R.string.pp_saved)} Error: ${error.message}", Color.parseColor("#EF4444"))
            }

            btnApply.isEnabled = true
        }
    }

    private fun showStatus(message: String, color: Int) {
        txtStatus.text = message
        txtStatus.setTextColor(color)
        txtStatus.visibility = View.VISIBLE
    }
}
