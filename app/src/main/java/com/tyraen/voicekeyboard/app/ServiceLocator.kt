package com.tyraen.voicekeyboard.app

import android.content.Context
import com.tyraen.voicekeyboard.core.config.PreferenceStore
import com.tyraen.voicekeyboard.core.network.HttpClientProvider
import com.tyraen.voicekeyboard.feature.transcription.SpeechToTextClient
import com.tyraen.voicekeyboard.feature.transcription.WhisperApiClient
import com.tyraen.voicekeyboard.feature.update.ReleaseChecker
import okhttp3.OkHttpClient

object ServiceLocator {

    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    val httpClient: OkHttpClient by lazy { HttpClientProvider.create() }

    val preferenceStore: PreferenceStore by lazy { PreferenceStore(appContext) }

    val speechToTextClient: SpeechToTextClient by lazy { WhisperApiClient(httpClient) }

    val releaseChecker: ReleaseChecker by lazy { ReleaseChecker(httpClient) }
}
