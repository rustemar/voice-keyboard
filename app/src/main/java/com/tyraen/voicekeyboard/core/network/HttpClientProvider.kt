package com.tyraen.voicekeyboard.core.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClientProvider {

    fun create(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Overall ceiling per request. Without this, a large multipart upload stalling on a dying
        // network can hang for minutes (the connect/read/write timeouts are per-phase, not total).
        // Generous enough that a slow-but-working upload still completes; a true stall fails fast
        // and re-enters the connectivity-gated retry path instead of blocking the queue.
        .callTimeout(180, TimeUnit.SECONDS)
        .build()
}
