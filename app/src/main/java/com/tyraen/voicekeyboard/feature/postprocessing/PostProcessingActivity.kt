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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PostProcessingActivity : AppCompatActivity() {

    private lateinit var switchEnabled: Switch
    private lateinit var spinnerProvider: Spinner
    private lateinit var editApiKey: EditText
    private lateinit var editEndpoint: EditText
    private lateinit var editModel: EditText
    private lateinit var editTemperature: EditText
    private lateinit var editPromptFix: EditText
    private lateinit var editPromptShorten: EditText
    private lateinit var editPromptEmoji: EditText
    private lateinit var editPromptSuffix: EditText
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
        setupActions()
        loadPreferences()
    }

    private fun bindViews() {
        switchEnabled = findViewById(R.id.switchPpEnabled)
        spinnerProvider = findViewById(R.id.spinnerProvider)
        editApiKey = findViewById(R.id.editPpApiKey)
        editEndpoint = findViewById(R.id.editPpEndpoint)
        editModel = findViewById(R.id.editPpModel)
        editTemperature = findViewById(R.id.editPpTemperature)
        editPromptFix = findViewById(R.id.editPromptFix)
        editPromptShorten = findViewById(R.id.editPromptShorten)
        editPromptEmoji = findViewById(R.id.editPromptEmoji)
        editPromptSuffix = findViewById(R.id.editPromptSuffix)
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

            editPromptFix.setText(pp.promptFix)
            editPromptFix.hint = PostProcessingPreferences.DEFAULT_PROMPT_FIX
            editPromptShorten.setText(pp.promptShorten)
            editPromptShorten.hint = PostProcessingPreferences.DEFAULT_PROMPT_SHORTEN
            editPromptEmoji.setText(pp.promptEmoji)
            editPromptEmoji.hint = PostProcessingPreferences.DEFAULT_PROMPT_EMOJI
            editPromptSuffix.setText(pp.promptSuffix)
            editPromptSuffix.hint = PostProcessingPreferences.DEFAULT_PROMPT_SUFFIX
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
            promptSuffix = editPromptSuffix.text.toString().trim()
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
