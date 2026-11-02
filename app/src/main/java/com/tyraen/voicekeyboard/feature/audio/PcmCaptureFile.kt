package com.tyraen.voicekeyboard.feature.audio

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One private PCM16 mono spool. Cancellation owns only this file, including after finish. */
internal class PcmCaptureFile(
    val file: File,
    private val sampleRate: Int = 16_000,
    private val writeHeader: (RandomAccessFile, ByteArray) -> Unit = { output, bytes ->
        output.seek(0)
        output.write(bytes)
    },
    private val syncFile: (RandomAccessFile) -> Unit = { it.fd.sync() }
) {
    private val lock = Any()
    private var writer: RandomAccessFile? = null
    private var cancelled = false
    private var finished = false
    private var writtenBytes = 0L

    init {
        require(sampleRate > 0)
        try {
            val output = RandomAccessFile(file, "rw")
            writer = output
            output.setLength(0)
            output.write(header(0, sampleRate))
        } catch (error: Exception) {
            runCatching { writer?.close() }
            file.delete()
            throw error
        }
    }

    val durationMs: Long get() = synchronized(lock) { writtenBytes * 1_000 / (sampleRate * 2) }

    /** False means an explicit cancellation already won; no data may be appended afterwards. */
    fun append(samples: ShortArray, count: Int): Boolean {
        require(count in 0..samples.size)
        val bytes = ByteArray(count * 2)
        for (index in 0 until count) {
            val sample = samples[index].toInt()
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample shr 8).toByte()
        }
        return synchronized(lock) {
            if (cancelled) return@synchronized false
            check(!finished) { "PCM capture is already finished" }
            if (writtenBytes + bytes.size > MAX_PCM_BYTES) throw IOException("PCM WAV size limit reached")
            checkNotNull(writer).write(bytes)
            // Count only complete writes. A failed append can leave a partial suffix which finish trims.
            writtenBytes += bytes.size
            true
        }
    }

    /** Repair sizes even after a read/write failure, retaining the complete samples already written. */
    fun finish(minimumDurationMs: Long): File? = synchronized(lock) {
        require(minimumDurationMs >= 0)
        if (cancelled) return@synchronized null
        if (finished) return@synchronized file.takeIf { it.isFile }
        if (writtenBytes == 0L || durationMs < minimumDurationMs) {
            cancel()
            return@synchronized null
        }
        try {
            checkNotNull(writer).apply {
                check(length() >= HEADER_BYTES + writtenBytes) { "PCM source is incomplete" }
                setLength(HEADER_BYTES + writtenBytes)
                writeHeader(this, header(writtenBytes, sampleRate))
                syncFile(this)
            }
            finished = true
            file
        } finally {
            runCatching { writer?.close() }
            writer = null
        }
    }

    /** Retry using a fresh handle. An unsuccessful repair deliberately leaves the source intact. */
    fun recover(minimumDurationMs: Long): File? = synchronized(lock) {
        require(minimumDurationMs >= 0)
        if (cancelled || writtenBytes == 0L || durationMs < minimumDurationMs) return@synchronized null
        runCatching { writer?.close() }
        writer = null
        val expectedHeader = header(writtenBytes, sampleRate)
        check(file.isFile && file.length() >= HEADER_BYTES + writtenBytes) { "PCM source is incomplete" }
        RandomAccessFile(file, "rw").use {
            it.setLength(HEADER_BYTES + writtenBytes)
            writeHeader(it, expectedHeader)
        }
        // A failed fsync need not make already-written PCM unusable. Only return a file whose
        // actual header/length agree after closing the repair handle.
        RandomAccessFile(file, "r").use {
            val observedHeader = ByteArray(HEADER_BYTES.toInt())
            it.readFully(observedHeader)
            check(it.length() == HEADER_BYTES + writtenBytes && observedHeader.contentEquals(expectedHeader)) {
                "PCM WAV header repair did not persist"
            }
        }
        finished = true
        file
    }

    fun cancel() = synchronized(lock) {
        cancelled = true
        runCatching { writer?.close() }
        writer = null
        file.delete()
        Unit
    }

    companion object {
        const val HEADER_BYTES = 44L
        private const val MAX_PCM_BYTES = 0xffff_ffffL - 36

        internal fun header(pcmBytes: Long, sampleRate: Int): ByteArray {
            require(pcmBytes in 0..MAX_PCM_BYTES && pcmBytes % 2 == 0L)
            require(sampleRate in 1..192_000)
            return ByteBuffer.allocate(HEADER_BYTES.toInt()).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt((36 + pcmBytes).toInt())
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(sampleRate)
                putInt(sampleRate * 2)
                putShort(2)
                putShort(16)
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(pcmBytes.toInt())
            }.array()
        }
    }
}
