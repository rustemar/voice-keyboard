package com.tyraen.voicekeyboard.feature.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest

/**
 * Offline Silero VAD over mono PCM16 at 16 kHz. Create, feed and close on the recording worker.
 * The recurrent state and the preceding 64 samples belong to one capture and never cross files.
 * Model contract: snakers4/silero-vad, utils_vad.OnnxWrapper (MIT), revision 066409f3d9db.
 */
internal class SileroVoiceDetector private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession
) : AutoCloseable {
    companion object {
        private const val ASSET = "silero_vad.onnx"
        private const val MODEL_SHA256 =
            "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3"
        private const val CONTEXT_SAMPLES = 64
        private const val STATE_FLOATS = 2 * 128

        fun create(context: Context): SileroVoiceDetector {
            val bytes = context.assets.open(ASSET).use { it.readBytes() }
            val actualHash = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(actualHash == MODEL_SHA256) { "Speech detector model does not match its contract" }
            // OrtEnvironment is process-wide; each detector closes only its own session/tensors.
            val environment = OrtEnvironment.getEnvironment()
            val session = OrtSession.SessionOptions().use { options ->
                options.setInterOpNumThreads(1)
                options.setIntraOpNumThreads(1)
                environment.createSession(bytes, options)
            }
            try {
                check(session.inputNames.containsAll(setOf("input", "state", "sr")))
                check(session.outputNames.containsAll(setOf("output", "stateN")))
                return SileroVoiceDetector(environment, session)
            } catch (failure: Throwable) {
                session.close()
                throw failure
            }
        }
    }

    private val frames = Pcm16VoiceFrameAssembler()
    private val input = FloatArray(CONTEXT_SAMPLES + Pcm16VoiceFrameAssembler.FRAME_SAMPLES)
    private val state = FloatArray(STATE_FLOATS)
    private val sampleRate = OnnxTensor.createTensor(
        environment, LongBuffer.wrap(longArrayOf(16_000L)), longArrayOf()
    )
    private var closed = false

    fun accept(
        samples: ShortArray,
        count: Int,
        observedAtMs: Long,
        onProbability: (Float, Long) -> Unit
    ) {
        check(!closed) { "Speech detector is closed" }
        frames.accept(samples, count, observedAtMs) { frame, atMs ->
            frame.copyInto(input, CONTEXT_SAMPLES)
            val probability = inferFrame()
            input.copyInto(input, 0, input.size - CONTEXT_SAMPLES, input.size)
            onProbability(probability, atMs)
        }
    }

    private fun inferFrame(): Float {
        OnnxTensor.createTensor(
            environment, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong())
        ).use { audio ->
            OnnxTensor.createTensor(
                environment, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)
            ).use { recurrent ->
                session.run(mapOf("input" to audio, "state" to recurrent, "sr" to sampleRate)).use { result ->
                    val probability = (result.get("output").get() as OnnxTensor).floatBuffer.get()
                    val nextState = (result.get("stateN").get() as OnnxTensor).floatBuffer
                    check(nextState.remaining() == STATE_FLOATS) { "Unexpected speech detector state" }
                    check(probability.isFinite() && probability in 0f..1f) {
                        "Invalid speech detector probability"
                    }
                    nextState.get(state)
                    return probability
                }
            }
        }
    }

    fun reset() {
        check(!closed) { "Speech detector is closed" }
        frames.reset()
        input.fill(0f)
        state.fill(0f)
    }

    override fun close() {
        if (closed) return
        closed = true
        frames.reset()
        input.fill(0f)
        state.fill(0f)
        try {
            sampleRate.close()
        } finally {
            session.close()
        }
    }
}
