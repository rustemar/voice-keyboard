package com.tyraen.voicekeyboard.app

import android.content.Context
import com.tyraen.voicekeyboard.core.config.PreferenceStore
import com.tyraen.voicekeyboard.core.network.ApiEndpoint
import com.tyraen.voicekeyboard.core.network.ConnectivityMonitor
import com.tyraen.voicekeyboard.core.network.HttpClientProvider
import com.tyraen.voicekeyboard.feature.ime.ParkedRecordingStore
import com.tyraen.voicekeyboard.feature.ime.ProcessingQueue
import com.tyraen.voicekeyboard.feature.postprocessing.PostProcessingClient
import com.tyraen.voicekeyboard.feature.transcription.ChatCompletionsSpeechClient
import com.tyraen.voicekeyboard.feature.transcription.SpeechToTextClient
import com.tyraen.voicekeyboard.feature.transcription.TranscriptionConfig
import com.tyraen.voicekeyboard.feature.transcription.WhisperApiClient
import com.tyraen.voicekeyboard.feature.update.ReleaseChecker
import okhttp3.OkHttpClient
import java.io.File

object ServiceLocator {

    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    val httpClient: OkHttpClient by lazy { HttpClientProvider.create() }

    val preferenceStore: PreferenceStore by lazy { PreferenceStore(appContext) }

    val speechToTextClient: SpeechToTextClient by lazy {
        val whisper = WhisperApiClient(httpClient)
        val chat = ChatCompletionsSpeechClient(httpClient)
        object : SpeechToTextClient {
            private fun forEndpoint(endpoint: String) =
                if (ApiEndpoint.usesChatCompletions(endpoint)) chat else whisper

            override suspend fun transcribe(audioFile: File, config: TranscriptionConfig) =
                forEndpoint(config.endpoint).transcribe(audioFile, config)

            override suspend fun validateCredentials(apiKey: String, endpoint: String, model: String, cacheDir: File) =
                forEndpoint(endpoint).validateCredentials(apiKey, endpoint, model, cacheDir)
        }
    }

    val postProcessingClient: PostProcessingClient by lazy { PostProcessingClient(httpClient) }

    val releaseChecker: ReleaseChecker by lazy { ReleaseChecker(httpClient) }

    val connectivityMonitor: ConnectivityMonitor by lazy { ConnectivityMonitor(appContext) }

    val parkedRecordingStore: ParkedRecordingStore by lazy { ParkedRecordingStore(appContext) }

    /** Process-wide transcription engine. Survives IME view rebuilds so work is never orphaned. */
    val transcriptionQueue: ProcessingQueue by lazy {
        ProcessingQueue(
            speechClient = speechToTextClient,
            postProcessingClient = postProcessingClient,
            store = parkedRecordingStore,
            connectivity = connectivityMonitor,
            preferenceStore = preferenceStore,
            appContext = appContext
        )
    }
}
