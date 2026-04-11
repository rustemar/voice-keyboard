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

        // Post-processing config
        val PP_ENABLED = booleanPreferencesKey("pp_enabled")
        val PP_PROVIDER = stringPreferencesKey("pp_provider")
        val PP_API_KEY = stringPreferencesKey("pp_api_key")
        val PP_ENDPOINT = stringPreferencesKey("pp_endpoint")
        val PP_MODEL = stringPreferencesKey("pp_model")

        // Post-processing toggle states (persist between sessions)
        val PP_FIX_ACTIVE = booleanPreferencesKey("pp_fix_active")
        val PP_SHORTEN_ACTIVE = booleanPreferencesKey("pp_shorten_active")
        val PP_EMOJI_ACTIVE = booleanPreferencesKey("pp_emoji_active")
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

    suspend fun loadPostProcessing(): PostProcessingPreferences {
        val prefs = context.store.data.first()
        return PostProcessingPreferences(
            enabled = prefs[Keys.PP_ENABLED] ?: false,
            provider = prefs[Keys.PP_PROVIDER] ?: PostProcessingPreferences.PROVIDER_OPENAI,
            apiKey = prefs[Keys.PP_API_KEY] ?: "",
            endpoint = prefs[Keys.PP_ENDPOINT] ?: "",
            model = prefs[Keys.PP_MODEL] ?: ""
        )
    }

    suspend fun savePostProcessing(prefs: PostProcessingPreferences) {
        context.store.edit { data ->
            data[Keys.PP_ENABLED] = prefs.enabled
            data[Keys.PP_PROVIDER] = prefs.provider
            data[Keys.PP_API_KEY] = prefs.apiKey
            data[Keys.PP_ENDPOINT] = prefs.endpoint
            data[Keys.PP_MODEL] = prefs.model
        }
    }

    suspend fun loadToggleStates(): ToggleStates {
        val prefs = context.store.data.first()
        return ToggleStates(
            fixActive = prefs[Keys.PP_FIX_ACTIVE] ?: false,
            shortenActive = prefs[Keys.PP_SHORTEN_ACTIVE] ?: false,
            emojiActive = prefs[Keys.PP_EMOJI_ACTIVE] ?: false
        )
    }

    suspend fun saveToggleStates(states: ToggleStates) {
        context.store.edit { data ->
            data[Keys.PP_FIX_ACTIVE] = states.fixActive
            data[Keys.PP_SHORTEN_ACTIVE] = states.shortenActive
            data[Keys.PP_EMOJI_ACTIVE] = states.emojiActive
        }
    }

    data class ToggleStates(
        val fixActive: Boolean = false,
        val shortenActive: Boolean = false,
        val emojiActive: Boolean = false
    )
}
