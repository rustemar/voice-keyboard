package com.tyraen.voicekeyboard.feature.vocabulary

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.BidiFormatter
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.app.ServiceLocator
import com.tyraen.voicekeyboard.core.config.TextDictionaryPreferences
import com.tyraen.voicekeyboard.core.locale.InterfaceLanguageManager
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VocabularyActivity : AppCompatActivity() {
    private companion object {
        const val MAX_RECOGNITION_CHARS = 600
        const val SECTION = "dictionary.section"
        const val WORD_DRAFT = "dictionary.word"
        const val POST_DRAFT = "dictionary.post"
        const val FROM_DRAFT = "dictionary.from"
        const val TO_DRAFT = "dictionary.to"
        const val SCROLL_STATE = "dictionary.scroll"
        // A preference write must survive Activity destruction, and snapshots keep rapid edits ordered.
        val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val saveMutex = Mutex()
    }

    private val scope = MainScope()
    private val preferenceStore get() = ServiceLocator.preferenceStore
    private val words = mutableListOf<String>()
    private val replacements = mutableListOf<TextReplacementRules.Rule>()
    private var section = 0
    private var loaded = false
    private var savingPost = false
    private var savedPost = ""
    private var restoredPost: String? = null
    private var restoredScroll: Parcelable? = null

    private lateinit var spinner: Spinner
    private lateinit var recycler: RecyclerView
    private lateinit var headers: List<View>
    private lateinit var headerAdapter: HeaderAdapter
    private val entries = EntryAdapter(::removeEntry)
    private lateinit var editWord: EditText
    private lateinit var addWord: Button
    private lateinit var wordCounter: TextView
    private lateinit var wordEmpty: TextView
    private lateinit var editPost: EditText
    private lateinit var savePost: Button
    private lateinit var postCounter: TextView
    private lateinit var editFrom: EditText
    private lateinit var editTo: EditText
    private lateinit var addReplacement: Button
    private lateinit var replacementCounter: TextView
    private lateinit var replacementEmpty: TextView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(InterfaceLanguageManager.applyTo(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vocabulary)
        section = savedInstanceState?.getInt(SECTION, 0)?.coerceIn(0, 2) ?: 0
        restoredPost = savedInstanceState?.getString(POST_DRAFT)
        @Suppress("DEPRECATION")
        restoredScroll = savedInstanceState?.getParcelable(SCROLL_STATE)
        recycler = findViewById(R.id.recyclerVocabulary)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.itemAnimator = null
        headers = listOf(R.layout.header_vocabulary_recognition, R.layout.header_vocabulary_postprocessing,
            R.layout.header_vocabulary_replacements).map { layoutInflater.inflate(it, recycler, false) }
        headers.forEach {
            ViewCompat.setAccessibilityHeading(it.findViewById(R.id.txtDictionaryHeading), true)
        }
        val sectionHeader = layoutInflater.inflate(R.layout.header_vocabulary_sections, recycler, false)
        bindEditors(savedInstanceState)
        headerAdapter = HeaderAdapter(headers[section])
        recycler.adapter = ConcatAdapter(HeaderAdapter(sectionHeader), headerAdapter, entries)
        spinner = sectionHeader.findViewById(R.id.spinnerDictionarySection)
        spinner.isSaveEnabled = false
        val labels = listOf(getString(R.string.dictionary_section_recognition),
            getString(R.string.dictionary_section_postprocessing), getString(R.string.dictionary_section_replacements))
        spinner.adapter = SectionAdapter(this, labels)
        spinner.setSelection(section, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == section) return
                currentFocus?.clearFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(spinner.windowToken, 0)
                section = position
                renderSection()
                recycler.scrollToPosition(0)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = requestLeave()
        })
        updateControls()
        loadDictionaries()
    }

    private fun bindEditors(state: Bundle?) {
        editWord = headers[0].findViewById(R.id.editVocabularyWord)
        addWord = headers[0].findViewById(R.id.btnVocabularyAdd)
        wordCounter = headers[0].findViewById(R.id.txtVocabularyCounter)
        wordEmpty = headers[0].findViewById(R.id.txtVocabularyEmpty)
        editPost = headers[1].findViewById(R.id.editPostProcessingVocabulary)
        savePost = headers[1].findViewById(R.id.btnSavePostProcessingVocabulary)
        postCounter = headers[1].findViewById(R.id.txtPostProcessingVocabularyCounter)
        editFrom = headers[2].findViewById(R.id.editReplacementFrom)
        editTo = headers[2].findViewById(R.id.editReplacementTo)
        addReplacement = headers[2].findViewById(R.id.btnAddReplacement)
        replacementCounter = headers[2].findViewById(R.id.txtReplacementCounter)
        replacementEmpty = headers[2].findViewById(R.id.txtReplacementEmpty)
        val editors = listOf(editWord, editPost, editFrom, editTo)
        // Detached section headers are not all in Android's saved view hierarchy.
        editors.forEach { editor ->
            editor.isSaveEnabled = false
            editor.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    editor.error = null
                    if (::spinner.isInitialized) updateControls()
                }
            })
        }
        editWord.setText(state?.getString(WORD_DRAFT) ?: "")
        editPost.setText(restoredPost ?: "")
        editFrom.setText(state?.getString(FROM_DRAFT) ?: "")
        editTo.setText(state?.getString(TO_DRAFT) ?: "")
        addWord.setOnClickListener { addRecognitionWord() }
        savePost.setOnClickListener { savePostProcessing(false) }
        addReplacement.setOnClickListener { addReplacementRule() }
    }

    private fun loadDictionaries() {
        val store = preferenceStore
        scope.launch {
            try {
                val values = saveMutex.withLock {
                    Triple(store.loadVocabulary(), store.loadPostProcessingVocabulary(), store.loadTextReplacements())
                }
                words.clear()
                words.addAll(values.first.split('\n').map { it.trim() }.filter { it.isNotEmpty() })
                savedPost = values.second
                editPost.setText(restoredPost ?: savedPost)
                restoredPost = null
                replacements.clear()
                replacements.addAll(values.third)
                loaded = true
                renderSection()
                updateControls()
                restoredScroll?.let { scroll -> recycler.post {
                    recycler.layoutManager?.onRestoreInstanceState(scroll)
                } }
                restoredScroll = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DiagnosticLog.recordFailure("Dictionary", "Could not load dictionaries", error)
                toast(R.string.saved_texts_action_failed)
                finish()
            }
        }
    }

    private fun renderSection() {
        headerAdapter.setHeader(headers[section])
        entries.submit(when (section) {
            0 -> words.map { Entry.Word(it) }
            2 -> replacements.map { Entry.Replacement(it) }
            else -> emptyList()
        })
        updateControls()
    }

    private fun updateControls() {
        val ready = loaded && !savingPost
        spinner.isEnabled = ready
        listOf(editWord, editPost, editFrom, editTo).forEach { it.isEnabled = ready }
        val pendingWord = editWord.text.toString().trim()
        val wordChars = words.sumOf { it.length } + (words.size - 1).coerceAtLeast(0)
        val projectedWords = wordChars + if (pendingWord.isEmpty()) 0 else pendingWord.length + if (words.isEmpty()) 0 else 1
        wordCounter.text = getString(R.string.vocabulary_counter, words.size, projectedWords, MAX_RECOGNITION_CHARS)
        wordCounter.setTextColor(ContextCompat.getColor(this,
            if (projectedWords > MAX_RECOGNITION_CHARS) R.color.text_primary else R.color.status_text))
        wordEmpty.visibility = if (loaded && words.isEmpty()) View.VISIBLE else View.GONE
        addWord.isEnabled = ready && pendingWord.isNotEmpty()
        val postSize = editPost.text.length
        val postLimit = TextDictionaryPreferences.MAX_POSTPROCESSING_CHARS
        postCounter.text = getString(R.string.dictionary_postprocessing_counter, postSize, postLimit)
        postCounter.setTextColor(ContextCompat.getColor(this,
            if (postSize > postLimit) R.color.text_primary else R.color.status_text))
        savePost.isEnabled = ready && postSize <= postLimit && editPost.text.toString() != savedPost
        val source = editFrom.text.toString().trim()
        val target = editTo.text.toString().trim()
        val ruleChars = replacements.sumOf { it.source.length + it.target.length + 4 }
        val projectedRules = ruleChars + if (source.isEmpty() && target.isEmpty()) 0 else source.length + target.length + 4
        replacementCounter.text = getString(R.string.dictionary_replacements_counter,
            replacements.size, projectedRules, TextReplacementRules.MAX_CHARS)
        replacementCounter.setTextColor(ContextCompat.getColor(this,
            if (projectedRules > TextReplacementRules.MAX_CHARS) R.color.text_primary else R.color.status_text))
        replacementEmpty.visibility = if (loaded && replacements.isEmpty()) View.VISIBLE else View.GONE
        addReplacement.isEnabled = ready && source.isNotEmpty() && target.isNotEmpty()
    }

    private fun addRecognitionWord() {
        if (!loaded || savingPost) return
        val word = editWord.text.toString().trim()
        if (word.isEmpty()) return
        if ('\n' in word || ',' in word) {
            editWord.error = getString(R.string.vocabulary_error_separator)
            return
        }
        if (word in words) {
            editWord.error = getString(R.string.vocabulary_error_duplicate)
            return
        }
        val candidate = words + word
        if (candidate.joinToString("\n").length > MAX_RECOGNITION_CHARS) {
            editWord.error = getString(R.string.vocabulary_error_limit)
            return
        }
        words.add(word)
        editWord.setText("")
        renderSection()
        persistWords()
    }

    private fun addReplacementRule() {
        if (!loaded || savingPost) return
        val rule = TextReplacementRules.Rule(editFrom.text.toString().trim(), editTo.text.toString().trim())
        if (rule.source == rule.target) {
            editTo.error = getString(R.string.dictionary_replacement_invalid)
            return
        }
        val errors = TextReplacementRules.validate(replacements + rule)
        if (errors.isNotEmpty()) {
            val error = errors.first().kind
            val message = when (error) {
                TextReplacementRules.ErrorKind.DUPLICATE_SOURCE -> R.string.vocabulary_error_duplicate
                TextReplacementRules.ErrorKind.INPUT_TOO_LONG, TextReplacementRules.ErrorKind.TOO_MANY_RULES,
                TextReplacementRules.ErrorKind.SOURCE_TOO_LONG, TextReplacementRules.ErrorKind.TARGET_TOO_LONG ->
                    R.string.dictionary_replacement_limit
                else -> R.string.dictionary_replacement_invalid
            }
            if (error == TextReplacementRules.ErrorKind.EMPTY_TARGET) editTo.error = getString(message)
            else editFrom.error = getString(message)
            return
        }
        replacements.add(rule)
        editFrom.setText("")
        editTo.setText("")
        renderSection()
        persistReplacements()
    }

    private fun removeEntry(entry: Entry) {
        if (!loaded || savingPost) return
        when (entry) {
            is Entry.Word -> if (words.remove(entry.value)) persistWords()
            is Entry.Replacement -> if (replacements.remove(entry.value)) persistReplacements()
        }
        renderSection()
    }

    private fun persistWords() {
        val snapshot = words.joinToString("\n")
        val store = preferenceStore
        enqueueWrite({ store.saveVocabulary(snapshot) })
    }

    private fun persistReplacements() {
        val snapshot = replacements.toList()
        val store = preferenceStore
        enqueueWrite({ store.saveTextReplacements(snapshot) })
    }

    private fun savePostProcessing(finishAfter: Boolean) {
        if (!loaded || savingPost) return
        val snapshot = editPost.text.toString()
        if (snapshot.length > TextDictionaryPreferences.MAX_POSTPROCESSING_CHARS) {
            editPost.error = getString(R.string.vocabulary_error_limit)
            return
        }
        savingPost = true
        updateControls()
        val store = preferenceStore
        enqueueWrite({ store.savePostProcessingVocabulary(snapshot) }, onSuccess = {
            savedPost = TextDictionary.normalizeVocabulary(snapshot)
            savingPost = false
            if (!isDestroyed && !isFinishing) {
                editPost.setText(savedPost)
                updateControls()
                toast(R.string.settings_saved)
                if (finishAfter) finish()
            }
        }, onFailure = {
            savingPost = false
            if (!isDestroyed && !isFinishing) updateControls()
        })
    }

    private fun enqueueWrite(write: suspend () -> Unit, onSuccess: () -> Unit = {}, onFailure: () -> Unit = {}) {
        val context = applicationContext
        val failed = getString(R.string.saved_texts_action_failed)
        saveScope.launch {
            try {
                saveMutex.withLock { write() }
                onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DiagnosticLog.recordFailure("Dictionary", "Could not save dictionary settings", error)
                onFailure()
                Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestLeave() {
        if (savingPost) return
        if (!loaded || editPost.text.toString() == savedPost) {
            finish()
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dictionary_unsaved_title)
            .setMessage(R.string.dictionary_unsaved_message)
            .setPositiveButton(R.string.dictionary_save, null)
            .setNeutralButton(R.string.dictionary_discard) { _, _ -> finish() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = editPost.text.length <= TextDictionaryPreferences.MAX_POSTPROCESSING_CHARS
                setOnClickListener {
                    dialog.dismiss()
                    savePostProcessing(true)
                }
            }
        }
        dialog.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(SECTION, section)
        outState.putString(WORD_DRAFT, editWord.text.toString())
        val postDraft = editPost.text.toString()
        outState.putString(POST_DRAFT, if (savingPost) TextDictionary.normalizeVocabulary(postDraft) else postDraft)
        outState.putString(FROM_DRAFT, editFrom.text.toString())
        outState.putString(TO_DRAFT, editTo.text.toString())
        outState.putParcelable(SCROLL_STATE, recycler.layoutManager?.onSaveInstanceState())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        scope.cancel()
        recycler.adapter = null
        super.onDestroy()
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private sealed interface Entry {
        data class Word(val value: String) : Entry
        data class Replacement(val value: TextReplacementRules.Rule) : Entry
    }

    private class HeaderAdapter(private var headerView: View) : RecyclerView.Adapter<HeaderAdapter.Holder>() {
        fun setHeader(value: View) {
            if (headerView === value) return
            headerView = value
            notifyItemChanged(0)
        }
        override fun getItemCount() = 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.host.removeAllViews()
            (headerView.parent as? ViewGroup)?.removeView(headerView)
            holder.host.addView(headerView, FrameLayout.LayoutParams(-1, -2))
        }
        class Holder(val host: FrameLayout) : RecyclerView.ViewHolder(host)
    }

    private class EntryAdapter(private val onDelete: (Entry) -> Unit) : RecyclerView.Adapter<EntryAdapter.Holder>() {
        private var items: List<Entry> = emptyList()
        fun submit(values: List<Entry>) {
            items = values
            notifyDataSetChanged()
        }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_vocabulary_word, parent, false)
            return Holder(view) { position -> items.getOrNull(position)?.let(onDelete) }
        }
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
        class Holder(view: View, onDelete: (Int) -> Unit) : RecyclerView.ViewHolder(view) {
            private val text: TextView = view.findViewById(R.id.txtVocabularyItem)
            private val remove: ImageButton = view.findViewById(R.id.btnVocabularyDelete)
            init {
                remove.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) onDelete(position)
                }
            }
            fun bind(entry: Entry) {
                // A replacement is a directional formula: source → target, even in an RTL UI.
                // Wrap each field separately so Arabic text keeps its own reading direction.
                text.textDirection = if (entry is Entry.Replacement) View.TEXT_DIRECTION_LTR else View.TEXT_DIRECTION_LOCALE
                val bidi = BidiFormatter.getInstance(false)
                text.text = when (entry) {
                    is Entry.Word -> entry.value
                    is Entry.Replacement -> bidi.unicodeWrap(entry.value.source) + " → " + bidi.unicodeWrap(entry.value.target)
                }
                text.maxLines = if (entry is Entry.Replacement) 4 else Int.MAX_VALUE
                text.ellipsize = if (entry is Entry.Replacement) TextUtils.TruncateAt.END else null
                remove.setContentDescription(remove.context.getString(
                    if (entry is Entry.Replacement) R.string.dictionary_remove_replacement else R.string.vocabulary_delete_cd))
            }
        }
    }

    private class SectionAdapter(context: Context, labels: List<String>) :
        ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, labels) {
        init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            style(super.getView(position, convertView, parent), 2)
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            style(super.getDropDownView(position, convertView, parent), 3)
        private fun style(view: View, lines: Int): View = view.apply {
            (this as? TextView)?.apply {
                setSingleLine(false)
                maxLines = lines
                ellipsize = TextUtils.TruncateAt.END
                minHeight = (48 * resources.displayMetrics.density + .5f).toInt()
                val padding = (8 * resources.displayMetrics.density + .5f).toInt()
                setPaddingRelative(padding, padding, padding, padding)
                textSize = 16f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }
        }
    }
}
