package com.tyraen.voicekeyboard.core.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.core.locale.TranscriptionLocale
import kotlinx.coroutines.flow.first
import java.util.Locale

private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "settings")

class PreferenceStore(private val context: Context) {

    private object Keys {
        val API_KEY = stringPreferencesKey("api_key")
        val ENDPOINT = stringPreferencesKey("endpoint")
        val MODEL = stringPreferencesKey("model")
        val LANGUAGE = stringPreferencesKey("language")
        val AUTO_RECORD = booleanPreferencesKey("auto_record")
        val ADD_TRAILING_SPACE = booleanPreferencesKey("add_trailing_space")
        val PROMPT = stringPreferencesKey("prompt")
    }

    suspend fun load(): UserPreferences {
        val prefs = context.store.data.first()

        val systemLang = Locale.getDefault().language
        val systemEntry = TranscriptionLocale.resolve(systemLang)
        val defaultLang = systemEntry?.code ?: context.getString(R.string.default_language)
        val defaultPrompt = systemEntry?.defaultPrompt ?: context.getString(R.string.default_prompt)

        return UserPreferences(
            apiKey = prefs[Keys.API_KEY] ?: "",
            endpoint = prefs[Keys.ENDPOINT] ?: context.getString(R.string.default_endpoint),
            model = prefs[Keys.MODEL] ?: context.getString(R.string.default_model),
            language = prefs[Keys.LANGUAGE] ?: defaultLang,
            autoRecord = prefs[Keys.AUTO_RECORD] ?: false,
            addTrailingSpace = prefs[Keys.ADD_TRAILING_SPACE] ?: true,
            prompt = prefs[Keys.PROMPT] ?: defaultPrompt
        )
    }

    suspend fun save(prefs: UserPreferences) {
        context.store.edit { data ->
            data[Keys.API_KEY] = prefs.apiKey
            data[Keys.ENDPOINT] = prefs.endpoint
            data[Keys.MODEL] = prefs.model
            data[Keys.LANGUAGE] = prefs.language
            data[Keys.AUTO_RECORD] = prefs.autoRecord
            data[Keys.ADD_TRAILING_SPACE] = prefs.addTrailingSpace
            data[Keys.PROMPT] = prefs.prompt
        }
    }
}
