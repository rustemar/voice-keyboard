package com.tyraen.voicekeyboard.feature.vocabulary

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.app.ServiceLocator
import com.tyraen.voicekeyboard.core.locale.InterfaceLanguageManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class VocabularyActivity : AppCompatActivity() {

    private companion object {
        const val MAX_CHARS = 600
    }

    private lateinit var editWord: EditText
    private lateinit var btnAdd: Button
    private lateinit var txtCounter: TextView
    private lateinit var txtEmpty: TextView
    private lateinit var recycler: RecyclerView
    private val adapter = WordAdapter(::removeWord)

    private val words: MutableList<String> = mutableListOf()
    private val scope = MainScope()
    private val preferenceStore get() = ServiceLocator.preferenceStore

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(InterfaceLanguageManager.applyTo(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vocabulary)

        editWord = findViewById(R.id.editVocabularyWord)
        btnAdd = findViewById(R.id.btnVocabularyAdd)
        txtCounter = findViewById(R.id.txtVocabularyCounter)
        txtEmpty = findViewById(R.id.txtVocabularyEmpty)
        recycler = findViewById(R.id.recyclerVocabulary)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        btnAdd.setOnClickListener { addCurrentWord() }
        editWord.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateCounter() }
        })

        loadWords()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun loadWords() {
        scope.launch {
            val raw = preferenceStore.loadVocabulary()
            words.clear()
            words.addAll(raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() })
            adapter.submit(words.toList())
            updateCounter()
        }
    }

    private fun addCurrentWord() {
        val raw = editWord.text.toString().trim()
        if (raw.isEmpty()) return
        if (raw.contains('\n') || raw.contains(',')) {
            Toast.makeText(this, R.string.vocabulary_error_separator, Toast.LENGTH_SHORT).show()
            return
        }
        if (words.any { it.equals(raw, ignoreCase = false) }) {
            Toast.makeText(this, R.string.vocabulary_error_duplicate, Toast.LENGTH_SHORT).show()
            return
        }
        val newCharCount = currentCharCount() + raw.length + (if (words.isEmpty()) 0 else 1)
        if (newCharCount > MAX_CHARS) {
            Toast.makeText(this, R.string.vocabulary_error_limit, Toast.LENGTH_SHORT).show()
            return
        }

        words.add(raw)
        editWord.setText("")
        adapter.submit(words.toList())
        updateCounter()
        persist()
    }

    private fun removeWord(word: String) {
        if (words.remove(word)) {
            adapter.submit(words.toList())
            updateCounter()
            persist()
        }
    }

    private fun persist() {
        val joined = words.joinToString("\n")
        scope.launch {
            preferenceStore.saveVocabulary(joined)
        }
    }

    private fun currentCharCount(): Int {
        if (words.isEmpty()) return 0
        return words.sumOf { it.length } + (words.size - 1)
    }

    private fun updateCounter() {
        val pendingExtra = editWord.text.toString().trim().length
        val currentChars = currentCharCount()
        val projectedChars = if (pendingExtra > 0) {
            currentChars + pendingExtra + (if (words.isEmpty()) 0 else 1)
        } else {
            currentChars
        }
        txtCounter.text = getString(
            R.string.vocabulary_counter,
            words.size,
            projectedChars,
            MAX_CHARS
        )
        txtEmpty.visibility = if (words.isEmpty()) View.VISIBLE else View.GONE
    }

    private class WordAdapter(
        private val onDelete: (String) -> Unit
    ) : RecyclerView.Adapter<WordAdapter.VH>() {

        private val items = mutableListOf<String>()

        fun submit(list: List<String>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_vocabulary_word, parent, false)
            return VH(view, onDelete)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class VH(view: View, private val onDelete: (String) -> Unit) : RecyclerView.ViewHolder(view) {
            private val textView: TextView = view.findViewById(R.id.txtVocabularyItem)
            private val deleteButton: ImageButton = view.findViewById(R.id.btnVocabularyDelete)

            fun bind(word: String) {
                textView.text = word
                deleteButton.setOnClickListener { onDelete(word) }
            }
        }
    }
}
