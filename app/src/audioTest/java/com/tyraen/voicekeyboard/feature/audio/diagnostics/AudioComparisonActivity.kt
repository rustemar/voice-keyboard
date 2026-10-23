package com.tyraen.voicekeyboard.feature.audio.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.core.config.ThemeManager
import com.tyraen.voicekeyboard.core.locale.InterfaceLanguageManager
import com.tyraen.voicekeyboard.core.network.ApiEndpoint
import kotlinx.coroutines.*
import java.util.Locale

/** Launcher of the isolated test package. No IME, updater or shared keyboard preferences. */
class AudioComparisonActivity : AppCompatActivity() {
    private enum class Phase { IDLE, RECORDING, ENCODING, RECOGNIZING, EXPORTING }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transcriber = DiagnosticTranscriber()
    private var phase = Phase.IDLE
    private var job: Job? = null
    private var session: DiagnosticAudioSession? = null
    private var comparison: DiagnosticAudioComparison? = null
    private var config: DiagnosticRecognitionConfig? = null
    private var runReference: String? = null
    private val results = mutableListOf<DiagnosticTranscription>()
    private val redact = mutableSetOf<String>()
    private lateinit var enabled: Switch
    private lateinit var panel: LinearLayout
    private lateinit var record: Button
    private lateinit var cancel: Button
    private lateinit var recognize: Button
    private lateinit var export: Button
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var key: EditText
    private lateinit var endpoint: EditText
    private lateinit var model: EditText
    private lateinit var language: EditText
    private lateinit var prompt: EditText
    private lateinit var reference: EditText

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && enabled.isChecked) startCapture()
        else message(R.string.audio_test_permission)
    }
    private val saveZip = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val current = comparison
        if (uri != null && current != null) {
            phase = Phase.EXPORTING
            syncButtons()
            val json = DiagnosticReport.json(current, config, results.toList(), runReference ?: reference.text.toString(), ::redact)
            job = scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val stream = contentResolver.openOutputStream(uri, "w") ?: error("Cannot open destination")
                        DiagnosticReport.writeZip(stream, current, json)
                    }
                    message(R.string.audio_test_saved)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure(e) }
                finally { phase = Phase.IDLE; syncButtons() }
            }
        }
    }

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(InterfaceLanguageManager.applyTo(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(24)) }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        root.label(getString(R.string.audio_test_app_name), 23f, true)
        root.label(getString(R.string.audio_test_intro))
        enabled = Switch(this).apply { text = getString(R.string.audio_test_enable); isChecked = false }
        root.addView(enabled)
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        root.addView(panel)
        panel.label(getString(R.string.audio_test_explanation))
        panel.label(getString(R.string.audio_test_sample_label), bold = true)
        panel.label(getString(R.string.audio_test_sample)).setTextIsSelectable(true)
        record = panel.button(R.string.audio_test_record) {
            if (phase == Phase.RECORDING) { session?.stopCapture(); message(R.string.audio_test_encoding) }
            else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startCapture()
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        }
        cancel = panel.button(R.string.audio_test_cancel) { cancelWork(); message(R.string.audio_test_cancelled) }
        status = panel.label("")
        export = panel.button(R.string.audio_test_export) { saveZip.launch("voice-audio-comparison.zip") }
        panel.label(getString(R.string.audio_test_settings), 18f, true)
        endpoint = panel.field(R.string.audio_test_endpoint, "https://api.groq.com/openai/v1/audio/transcriptions")
        model = panel.field(R.string.audio_test_model, "whisper-large-v3-turbo")
        language = panel.field(R.string.audio_test_language, getString(R.string.audio_test_language_default))
        key = panel.field(R.string.audio_test_key, "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            setSaveEnabled(false)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        prompt = panel.field(R.string.audio_test_prompt, "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        reference = panel.field(R.string.audio_test_reference, "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        panel.label(getString(R.string.audio_test_reference_hint))
        panel.button(R.string.audio_test_use_sample) { reference.setText(getString(R.string.audio_test_sample)) }
        panel.label(getString(R.string.audio_test_network_notice))
        recognize = panel.button(R.string.audio_test_recognize) { recognizeVariants() }
        panel.label(getString(R.string.audio_test_results), 18f, true)
        output = panel.label("").apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        panel.label(getString(R.string.audio_test_scope))
        enabled.setOnCheckedChangeListener { _, checked ->
            panel.visibility = if (checked) View.VISIBLE else View.GONE
            if (!checked) { cancelWork(); session?.close(); session = null; comparison = null; results.clear(); output.text = "" }
            syncButtons()
        }
        syncButtons()
    }

    private fun startCapture() {
        if (phase != Phase.IDLE) return
        session?.close()
        comparison = null; config = null; runReference = null; results.clear(); output.text = ""
        val current = try {
            DiagnosticAudioSession(this)
        } catch (e: Exception) {
            session = null
            failure(e)
            syncButtons()
            return
        }
        session = current
        phase = Phase.RECORDING
        syncButtons()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        job = scope.launch {
            try {
                val pcm = current.capture { progress ->
                    if (phase == Phase.RECORDING) status.text = getString(R.string.audio_test_recording, progress.elapsedMs / 1_000)
                }
                phase = Phase.ENCODING
                syncButtons(); message(R.string.audio_test_encoding)
                val encoded = current.encode(pcm) { variant -> output.append(describe(variant) + "\n") }
                comparison = encoded
                message(R.string.audio_test_ready)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure(e); current.close() }
            finally {
                phase = Phase.IDLE
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                syncButtons()
            }
        }
    }

    private fun recognizeVariants() {
        val current = comparison ?: return
        val apiKey = key.text.toString().trim()
        if (apiKey.isBlank()) { message(R.string.audio_test_key_missing); return }
        val settings = DiagnosticRecognitionConfig(
            ApiEndpoint.complete(endpoint.text.toString().trim(), "/audio/transcriptions"),
            model.text.toString().trim(), language.text.toString().trim(), prompt.text.toString().trim()
        )
        if (!settings.isValid()) { message(R.string.audio_test_endpoint_invalid); return }
        if (settings.endpoint.startsWith("http://")) {
            androidx.appcompat.app.AlertDialog.Builder(this).setMessage(R.string.audio_test_http_notice)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ -> beginRecognition(current, settings, apiKey) }.show()
        } else beginRecognition(current, settings, apiKey)
    }

    private fun beginRecognition(current: DiagnosticAudioComparison, settings: DiagnosticRecognitionConfig, apiKey: String) {
        if (phase != Phase.IDLE || !enabled.isChecked) return
        redact += apiKey
        config = settings; runReference = reference.text.toString(); results.clear(); render()
        phase = Phase.RECOGNIZING; syncButtons()
        val variants = current.variants.filter { it.status == DiagnosticVariantStatus.SUCCESS }.shuffled()
        job = scope.launch {
            try {
                for ((index, variant) in variants.withIndex()) {
                    status.text = getString(R.string.audio_test_running, variant.profile.label, index + 1, variants.size)
                    val result = transcriber.transcribe(variant, settings, apiKey)
                    results += result
                    render()
                }
                message(R.string.audio_test_done)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure(e) }
            finally { phase = Phase.IDLE; syncButtons() }
        }
    }

    private fun render() {
        output.text = comparison?.variants?.joinToString("\n\n") { variant ->
            buildString {
                append(describe(variant))
                results.firstOrNull { it.profileId == variant.profile.id }?.let {
                    append("\nHTTP ${it.httpCode ?: "—"}; total ${it.elapsedMs} ms; upload ${it.uploadMs ?: "—"} ms; wait ${it.responseWaitMs ?: "—"} ms")
                    append("\n" + (it.error ?: it.text.ifBlank { getString(R.string.audio_test_empty_result) }))
                    if (it.error == null) runCatching { DiagnosticWordErrors.compare(runReference.orEmpty(), it.text) }.getOrNull()?.let { score ->
                        append("\nWER ${String.format(Locale.ROOT, "%.1f", score.percent)}% (${score.errors}/${score.referenceWords})")
                    }
                }
            }
        }?.let(::redact).orEmpty()
    }

    private fun describe(v: DiagnosticAudioVariant): String =
        "${v.profile.label}: ${v.status}\n${v.sizeBytes} bytes; encode ${v.encodingMs} ms; ${v.actualSampleRate ?: "—"} Hz; channels ${v.actualChannelCount ?: "—"}\n" +
            "average ${v.averageBitRate ?: "—"} bps; codec ${v.codecName ?: "—"}" + (v.error?.let { "\n$it" } ?: "")

    private fun cancelWork() {
        val previous = phase
        job?.cancel()
        transcriber.cancel()
        if (previous == Phase.RECORDING || previous == Phase.ENCODING) {
            session?.close(); session = null; comparison = null
        }
        // Keep controls locked until the cancelled operation has released its resources.
        if (job?.isActive != true) {
            if (job?.isCompleted == true || job == null) phase = Phase.IDLE
        }
        syncButtons()
    }

    override fun onStop() {
        if (phase == Phase.RECORDING) { cancelWork(); message(R.string.audio_test_stopped) }
        super.onStop()
    }

    override fun onDestroy() {
        transcriber.cancel(); job?.cancel(); scope.cancel(); session?.close(); redact.clear()
        super.onDestroy()
    }

    private fun syncButtons() {
        if (!::record.isInitialized) return
        val busy = phase != Phase.IDLE
        record.isEnabled = phase == Phase.IDLE || phase == Phase.RECORDING
        record.setText(if (phase == Phase.RECORDING) R.string.audio_test_stop else R.string.audio_test_record)
        cancel.isEnabled = busy && phase != Phase.EXPORTING
        recognize.isEnabled = !busy && comparison?.variants?.any { it.status == DiagnosticVariantStatus.SUCCESS } == true
        export.isEnabled = !busy && comparison != null
        enabled.isEnabled = !busy
        if (::endpoint.isInitialized) listOf(endpoint, model, language, key, prompt, reference).forEach { it.isEnabled = !busy }
    }

    private fun redact(value: String): String = redact.fold(value) { text, secret -> text.replace(secret, "[redacted]") }
    private fun message(id: Int) { status.setText(id) }
    private fun failure(e: Exception) { status.text = getString(R.string.audio_test_error, redact(e.message ?: e.javaClass.simpleName)) }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun LinearLayout.label(value: String, size: Float = 15f, bold: Boolean = false): TextView = TextView(this@AudioComparisonActivity).apply {
        text = value; textSize = size; if (bold) setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(8)); this@label.addView(this)
    }
    private fun LinearLayout.button(id: Int, action: () -> Unit): Button = Button(this@AudioComparisonActivity).apply {
        setText(id); setOnClickListener { action() }; this@button.addView(this)
    }
    private fun LinearLayout.field(label: Int, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        label(getString(label), bold = true)
        return EditText(this@AudioComparisonActivity).apply {
            inputType = type; setText(value); maxLines = 5
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            this@field.addView(this)
        }
    }
}
