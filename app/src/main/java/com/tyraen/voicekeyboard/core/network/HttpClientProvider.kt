package com.tyraen.voicekeyboard.core.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClientProvider {

    fun create(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
}
