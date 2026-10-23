package com.tyraen.voicekeyboard.core.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/** Keeps cancellation connected to the call until its response body has been consumed. */
internal suspend fun <T> Call.awaitResponse(read: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        if (!continuation.isActive) return@suspendCancellableCoroutine

        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use(read) }
                continuation.resumeWith(result)
            }
        })
    }
