package com.tyraen.voicekeyboard.feature.postprocessing

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.app.ServiceLocator
import com.tyraen.voicekeyboard.core.config.PostProcessingPreferences
import com.tyraen.voicekeyboard.core.config.ProviderPresets
import com.tyraen.voicekeyboard.core.locale.InterfaceLanguageManager
import com.tyraen.voicekeyboard.core.locale.TranscriptionLocale
import com.tyraen.voicekeyboard.core.network.ApiEndpoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PostProcessingActivity : AppCompatActivity() {

    private lateinit var scrollView: ScrollView
    private lateinit var switchEnabled: Switch
    private lateinit var checkTerminalVisible: CheckBox
    private lateinit var spinnerProvider: Spinner
    private lateinit var spinnerLlmPreset: Spinner
    private lateinit var editApiKey: EditText
    private lateinit var editEndpoint: EditText
    private lateinit var txtEndpointWarning: TextView
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
    private val providerLabels by lazy {
        listOf(getString(R.string.pp_provider_openai), getString(R.string.pp_provider_claude))
    }

    private var suppressProviderChange = false
    /** Position the code itself put the preset spinner at; its echo callback is ignored. */
    private var presetPosSetByCode = -1
    private val scope = MainScope()

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(InterfaceLanguageManager.applyTo(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_postprocessing)

        bindViews()
        setupProviderSpinner()
        setupPresetSpinner()
        setupTranslateLangSpinner()
        setupActions()
        loadPreferences()
    }

    private fun bindViews() {
        scrollView = findViewById(R.id.scrollPp)
        switchEnabled = findViewById(R.id.switchPpEnabled)
        checkTerminalVisible = findViewById(R.id.checkTerminalVisible)
        spinnerProvider = findViewById(R.id.spinnerProvider)
        spinnerLlmPreset = findViewById(R.id.spinnerLlmPreset)
        editApiKey = findViewById(R.id.editPpApiKey)
        editEndpoint = findViewById(R.id.editPpEndpoint)
        txtEndpointWarning = findViewById(R.id.txtPpEndpointWarning)
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
        val adapter = ArrayAdapter(this, R.layout.item_provider, providerLabels)
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

    /**
     * "Custom" plus the known providers. Picking one sets the dialect, address and a model that
     * exists there; editing the address by hand moves the spinner to whatever matches, or "Custom".
     */
    private fun setupPresetSpinner() {
        val labels = listOf(getString(R.string.preset_custom)) + ProviderPresets.postProcessing.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLlmPreset.adapter = adapter

        spinnerLlmPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == presetPosSetByCode) {
                    presetPosSetByCode = -1
                    return
                }
                presetPosSetByCode = -1
                val preset = ProviderPresets.postProcessing.getOrNull(position - 1) ?: return
                preset.provider?.let { spinnerProvider.setSelection(providers.indexOf(it)) }
                editEndpoint.setText(preset.endpoint)
                editModel.setText(preset.model)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        editEndpoint.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                txtEndpointWarning.isVisible = ApiEndpoint.isUnencrypted(s?.toString() ?: "")
                val pos = ProviderPresets.indexOf(ProviderPresets.postProcessing, s?.toString() ?: "") + 1
                if (spinnerLlmPreset.selectedItemPosition != pos) {
                    presetPosSetByCode = pos
                    spinnerLlmPreset.setSelection(pos, false)
                }
            }
        })
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
            scope.launch {
                val current = preferenceStore.loadPostProcessing()
                preferenceStore.savePostProcessing(current.copy(enabled = isChecked))
            }
        }
    }

    private fun loadPreferences() {
        scope.launch {
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
        var provider = providers[spinnerProvider.selectedItemPosition]
        val rawEndpoint = editEndpoint.text.toString().trim()

        // The address is the more deliberate input: the spinner defaults to Claude, yet people
        // paste OpenAI-style URLs (OpenRouter, Groq) and then see "invalid key" for what is really
        // a dialect mismatch. When the address clearly belongs to the other provider, follow it
        // and say so in the status line.
        var switchedNote: String? = null
        val detected = PostProcessingPreferences.providerFor(rawEndpoint)
        if (detected != null && detected != provider) {
            provider = detected
            spinnerProvider.setSelection(providers.indexOf(provider))
            switchedNote = getString(R.string.pp_provider_switched, providerLabels[providers.indexOf(provider)])
        }

        // A base URL ("…/api/v1") becomes the full request path; show the user what will be called.
        val endpoint = ApiEndpoint.complete(rawEndpoint, PostProcessingPreferences.defaultPath(provider))
        if (endpoint != rawEndpoint) editEndpoint.setText(endpoint)

        val tempText = editTemperature.text.toString().trim()
        val temperature = tempText.toFloatOrNull() ?: PostProcessingPreferences.DEFAULT_TEMPERATURE

        val prefs = PostProcessingPreferences(
            enabled = switchEnabled.isChecked,
            provider = provider,
            apiKey = editApiKey.text.toString().trim(),
            endpoint = endpoint,
            model = editModel.text.toString().trim(),
            temperature = temperature.coerceIn(0f, 2f),
            // The fields are prefilled with the defaults; saving that text verbatim would pin the
            // user to this version's wording. Blank means "follow the shipped default".
            promptFix = PostProcessingPreferences.normalizePrompt(editPromptFix.text.toString(), PostProcessingPreferences.DEFAULT_PROMPT_FIX),
            promptShorten = PostProcessingPreferences.normalizePrompt(editPromptShorten.text.toString(), PostProcessingPreferences.DEFAULT_PROMPT_SHORTEN),
            promptEmoji = PostProcessingPreferences.normalizePrompt(editPromptEmoji.text.toString(), PostProcessingPreferences.DEFAULT_PROMPT_EMOJI),
            promptSuffix = PostProcessingPreferences.normalizePrompt(editPromptSuffix.text.toString(), PostProcessingPreferences.DEFAULT_PROMPT_SUFFIX),
            translateLang = TranscriptionLocale.entries[spinnerTranslateLang.selectedItemPosition].code,
            translateModel = editTranslateModel.text.toString().trim(),
            terminalVisible = checkTerminalVisible.isChecked
        )

        val saved = listOfNotNull(switchedNote, getString(R.string.pp_saved)).joinToString("\n")

        btnApply.isEnabled = false
        showStatus(getString(R.string.pp_validating), Color.GRAY)

        scope.launch {
            preferenceStore.savePostProcessing(prefs)

            if (prefs.apiKey.isBlank()) {
                showStatus(saved, Color.parseColor("#4CAF50"))
                btnApply.isEnabled = true
                return@launch
            }

            val result = postProcessingClient.validateCredentials(prefs)

            result.onSuccess { msg ->
                showStatus("$saved $msg", Color.parseColor("#4CAF50"))
            }.onFailure { error ->
                showStatus("$saved Error: ${error.message}", Color.parseColor("#EF4444"))
            }

            btnApply.isEnabled = true
        }
    }

    private fun showStatus(message: String, color: Int) {
        txtStatus.text = message
        txtStatus.setTextColor(color)
        txtStatus.visibility = View.VISIBLE
        // The status sits below the Apply button, at the very end of a long form; without this
        // a multi-line error is cut off at the screen edge and the user never sees the reason.
        scrollView.post { scrollView.smoothScrollTo(0, scrollView.getChildAt(0).height) }
    }
}
