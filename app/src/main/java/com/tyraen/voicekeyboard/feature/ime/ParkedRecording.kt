package com.tyraen.voicekeyboard.feature.ime

import com.tyraen.voicekeyboard.core.config.PostProcessingPreferences
import com.tyraen.voicekeyboard.feature.transcription.TranscriptionConfig
import org.json.JSONObject
import java.io.File

/**
 * A recording that could not be transcribed yet, in a form that can be persisted to disk so it
 * survives input-view rebuilds and process death.
 *
 * Secrets are deliberately NOT stored here: the Whisper and post-processing API keys are re-read
 * from [com.tyraen.voicekeyboard.core.config.PreferenceStore] at retry time via [toQueueItem].
 * This keeps a single source of truth for keys (rotation works on retry) and avoids duplicating
 * them into a second on-disk file. Everything else is a faithful snapshot of the toggle/config
 * state at record time, so a delayed retry produces the same output the immediate run would have.
 */
data class ParkedRecording(
    val id: String,
    val audioPath: String,
    val state: State,
    val attemptCount: Int,
    val addTrailingSpace: Boolean,
    val singleWordStripPunctuation: Boolean,
    val vocabulary: String,
    val endpoint: String,
    val model: String,
    val language: String,
    val prompt: String,
    val recordingDurationMs: Long,
    val pp: Pp?,
    val flags: Flags
) {
    enum class State {
        /** Transient failure — keep auto-retrying whenever the network is available. */
        WAITING_NETWORK,

        /** Permanent failure (bad/missing API key, 4xx). Stop auto-retrying; wait for the user to
         *  fix settings and tap resend, which promotes it back to [WAITING_NETWORK]. */
        NEEDS_ATTENTION
    }

    /** Non-secret post-processing config snapshot. */
    data class Pp(
        val provider: String,
        val endpoint: String,
        val model: String,
        val temperature: Float,
        val promptFix: String,
        val promptShorten: String,
        val promptEmoji: String,
        val promptSuffix: String,
        val translateLang: String,
        val translateModel: String,
        val terminalVisible: Boolean
    )

    data class Flags(
        val fix: Boolean,
        val shorten: Boolean,
        val emoji: Boolean,
        val rhyme: Boolean,
        val translate: Boolean,
        val terminal: Boolean
    )

    fun toJson(): String {
        val o = JSONObject()
        o.put("id", id)
        o.put("audioPath", audioPath)
        o.put("state", state.name)
        o.put("attemptCount", attemptCount)
        o.put("addTrailingSpace", addTrailingSpace)
        o.put("singleWordStripPunctuation", singleWordStripPunctuation)
        o.put("vocabulary", vocabulary)
        o.put("endpoint", endpoint)
        o.put("model", model)
        o.put("language", language)
        o.put("prompt", prompt)
        o.put("recordingDurationMs", recordingDurationMs)
        pp?.let {
            val p = JSONObject()
            p.put("provider", it.provider)
            p.put("endpoint", it.endpoint)
            p.put("model", it.model)
            p.put("temperature", it.temperature.toDouble())
            p.put("promptFix", it.promptFix)
            p.put("promptShorten", it.promptShorten)
            p.put("promptEmoji", it.promptEmoji)
            p.put("promptSuffix", it.promptSuffix)
            p.put("translateLang", it.translateLang)
            p.put("translateModel", it.translateModel)
            p.put("terminalVisible", it.terminalVisible)
            o.put("pp", p)
        }
        val f = JSONObject()
        f.put("fix", flags.fix)
        f.put("shorten", flags.shorten)
        f.put("emoji", flags.emoji)
        f.put("rhyme", flags.rhyme)
        f.put("translate", flags.translate)
        f.put("terminal", flags.terminal)
        o.put("flags", f)
        return o.toString()
    }

    /** Rebuild a processable [ProcessingQueue.QueueItem], re-injecting the keys read from prefs. */
    fun toQueueItem(apiKey: String, ppApiKey: String): ProcessingQueue.QueueItem {
        val config = TranscriptionConfig(
            apiKey = apiKey,
            endpoint = endpoint,
            model = model,
            language = language,
            prompt = prompt,
            vocabulary = vocabulary,
            recordingDurationMs = recordingDurationMs
        )
        val ppPreferences = pp?.let {
            PostProcessingPreferences(
                enabled = true,
                provider = it.provider,
                apiKey = ppApiKey,
                endpoint = it.endpoint,
                model = it.model,
                temperature = it.temperature,
                promptFix = it.promptFix,
                promptShorten = it.promptShorten,
                promptEmoji = it.promptEmoji,
                promptSuffix = it.promptSuffix,
                translateLang = it.translateLang,
                translateModel = it.translateModel,
                terminalVisible = it.terminalVisible
            )
        }
        return ProcessingQueue.QueueItem(
            audioFile = File(audioPath),
            transcriptionConfig = config,
            addTrailingSpace = addTrailingSpace,
            singleWordStripPunctuation = singleWordStripPunctuation,
            vocabulary = vocabulary,
            ppPreferences = ppPreferences,
            ppFix = flags.fix,
            ppShorten = flags.shorten,
            ppEmoji = flags.emoji,
            ppRhyme = flags.rhyme,
            ppTranslate = flags.translate,
            ppTerminal = flags.terminal,
            parkedId = id
        )
    }

    companion object {
        fun fromJson(text: String): ParkedRecording? = try {
            val o = JSONObject(text)
            val ppObj = o.optJSONObject("pp")
            val pp = ppObj?.let {
                Pp(
                    provider = it.optString("provider"),
                    endpoint = it.optString("endpoint"),
                    model = it.optString("model"),
                    temperature = it.optDouble("temperature", PostProcessingPreferences.DEFAULT_TEMPERATURE.toDouble()).toFloat(),
                    promptFix = it.optString("promptFix"),
                    promptShorten = it.optString("promptShorten"),
                    promptEmoji = it.optString("promptEmoji"),
                    promptSuffix = it.optString("promptSuffix"),
                    translateLang = it.optString("translateLang", "en"),
                    translateModel = it.optString("translateModel"),
                    terminalVisible = it.optBoolean("terminalVisible")
                )
            }
            val f = o.getJSONObject("flags")
            ParkedRecording(
                id = o.getString("id"),
                audioPath = o.getString("audioPath"),
                state = runCatching { State.valueOf(o.optString("state")) }.getOrDefault(State.WAITING_NETWORK),
                attemptCount = o.optInt("attemptCount", 0),
                addTrailingSpace = o.optBoolean("addTrailingSpace", true),
                singleWordStripPunctuation = o.optBoolean("singleWordStripPunctuation", false),
                vocabulary = o.optString("vocabulary"),
                endpoint = o.optString("endpoint"),
                model = o.optString("model"),
                language = o.optString("language"),
                prompt = o.optString("prompt"),
                recordingDurationMs = o.optLong("recordingDurationMs", 0L),
                pp = pp,
                flags = Flags(
                    fix = f.optBoolean("fix"),
                    shorten = f.optBoolean("shorten"),
                    emoji = f.optBoolean("emoji"),
                    rhyme = f.optBoolean("rhyme"),
                    translate = f.optBoolean("translate"),
                    terminal = f.optBoolean("terminal")
                )
            )
        } catch (e: Exception) {
            null
        }

        fun fromQueueItem(
            item: ProcessingQueue.QueueItem,
            id: String,
            audioPath: String,
            state: State,
            attemptCount: Int
        ): ParkedRecording {
            val c = item.transcriptionConfig
            val ppPref = item.ppPreferences
            val pp = ppPref?.let {
                Pp(
                    provider = it.provider,
                    endpoint = it.endpoint,
                    model = it.model,
                    temperature = it.temperature,
                    promptFix = it.promptFix,
                    promptShorten = it.promptShorten,
                    promptEmoji = it.promptEmoji,
                    promptSuffix = it.promptSuffix,
                    translateLang = it.translateLang,
                    translateModel = it.translateModel,
                    terminalVisible = it.terminalVisible
                )
            }
            return ParkedRecording(
                id = id,
                audioPath = audioPath,
                state = state,
                attemptCount = attemptCount,
                addTrailingSpace = item.addTrailingSpace,
                singleWordStripPunctuation = item.singleWordStripPunctuation,
                vocabulary = item.vocabulary,
                endpoint = c.endpoint,
                model = c.model,
                language = c.language,
                prompt = c.prompt,
                recordingDurationMs = c.recordingDurationMs,
                pp = pp,
                flags = Flags(
                    fix = item.ppFix,
                    shorten = item.ppShorten,
                    emoji = item.ppEmoji,
                    rhyme = item.ppRhyme,
                    translate = item.ppTranslate,
                    terminal = item.ppTerminal
                )
            )
        }
    }
}
