package com.tyraen.voicekeyboard.feature.audio

import java.io.File
import kotlinx.coroutines.Deferred

/** Detached capture handoff: a later view/session release must not remove its pending file. */
class CaptureFinalization internal constructor(
    val durationMs: Long,
    val result: Deferred<File?>,
    private val cancelAction: () -> Unit
) {
    /** Cancels this handoff only; safe before, during or after asynchronous WAV finalization. */
    fun cancel() = cancelAction()
}
