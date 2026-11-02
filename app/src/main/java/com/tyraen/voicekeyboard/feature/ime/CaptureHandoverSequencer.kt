package com.tyraen.voicekeyboard.feature.ime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Main-confined ordering barrier shared by captures from every input-view instance. */
internal class CaptureHandoverSequencer(private val scope: CoroutineScope) {
    private var tail: CompletableDeferred<Unit>? = null

    /** Register before starting, so cancellation already owns the detached capture. */
    fun reserve(block: suspend () -> Unit): Job {
        val previous = tail
        val barrier = CompletableDeferred<Unit>()
        val next = scope.launch(start = CoroutineStart.LAZY) {
            previous?.await()
            ensureActive()
            block()
        }
        tail = barrier
        next.invokeOnCompletion {
            val release = {
                barrier.complete(Unit)
                if (tail === barrier) tail = null
            }
            // A slot cancelled before it starts still retains its predecessor's ordering fence.
            if (previous == null || previous.isCompleted) release()
            else previous.invokeOnCompletion { release() }
        }
        return next
    }
}
