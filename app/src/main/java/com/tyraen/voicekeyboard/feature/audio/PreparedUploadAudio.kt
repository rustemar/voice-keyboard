package com.tyraen.voicekeyboard.feature.audio

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns one handover's source and optional compressed derivative until queue admission.
 * None of the ownership transitions performs disk IO on the caller's thread.
 */
class PreparedUploadAudio internal constructor(
    private val source: File,
    val file: File,
    private val cleanup: (List<File>) -> Unit = { files ->
        cleanupScope.launch { files.forEach { runCatching { it.delete() } } }
    }
) {
    private enum class Owner { HANDOVER, QUEUE, RETAINED, CANCELLED }
    private val lock = Any()
    private var owner = Owner.HANDOVER

    /** Call immediately after non-suspending queue admission; never throws after that admission. */
    fun markEnqueued() = transition(Owner.QUEUE) {
        if (source == file) emptyList() else listOf(source)
    }

    /** An explicit cancellation removes only this handover's files, unless the queue owns them. */
    fun cancel() = transition(Owner.CANCELLED) { listOf(source, file).distinct() }

    /** An unexpected handover error leaves recoverable PCM while discarding its derivative. */
    fun retainSource() = transition(Owner.RETAINED) {
        if (source == file) emptyList() else listOf(file)
    }

    private fun transition(next: Owner, files: () -> List<File>) {
        val discarded = synchronized(lock) {
            if (owner != Owner.HANDOVER) return
            owner = next
            files()
        }
        // Cleanup failure must never turn an admitted recording into a handover failure.
        if (discarded.isNotEmpty()) runCatching { cleanup(discarded) }
    }

    private companion object {
        val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }
}
