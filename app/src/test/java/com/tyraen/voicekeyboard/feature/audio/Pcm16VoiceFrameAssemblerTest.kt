package com.tyraen.voicekeyboard.feature.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Pcm16VoiceFrameAssemblerTest {
    @Test fun `partial reads produce one exact frame without dropping or repeating samples`() {
        val assembler = Pcm16VoiceFrameAssembler()
        val pcm = ShortArray(512) { (it - 256).toShort() }
        val output = mutableListOf<Pair<FloatArray, Long>>()
        val collect: (FloatArray, Long) -> Unit = { samples, time -> output += samples.copyOf() to time }

        assembler.accept(pcm.copyOfRange(0, 100), 100, 6, collect)
        assertEquals(0, output.size)
        assembler.accept(pcm.copyOfRange(100, 512), 412, 32, collect)

        assertEquals(1, output.size)
        assertArrayEquals(FloatArray(512) { pcm[it] / 32768f }, output.single().first, 0f)
        assertEquals(32L, output.single().second)
    }

    @Test fun `several buffered frames retain separate capture endpoints`() {
        val assembler = Pcm16VoiceFrameAssembler()
        val times = mutableListOf<Long>()
        val peaks = mutableListOf<Float>()
        val pcm = ShortArray(1536) { if (it < 512) Short.MIN_VALUE else Short.MAX_VALUE }

        assembler.accept(pcm, pcm.size, 96) { frame, time ->
            times += time
            peaks += frame.first()
        }

        assertEquals(listOf(32L, 64L, 96L), times)
        assertEquals(listOf(-1f, 32767f / 32768, 32767f / 32768), peaks)
    }

    @Test fun `zero reads and unused buffer tail never become artificial quiet frames`() {
        val assembler = Pcm16VoiceFrameAssembler()
        var frames = 0
        val consume: (FloatArray, Long) -> Unit = { _, _ -> frames++ }
        assembler.accept(ShortArray(2048), 0, 500, consume)
        assembler.accept(ShortArray(2048), 511, 1_000, consume)
        assertEquals(0, frames)
        assembler.accept(shortArrayOf(123), 1, 1_001, consume)
        assertEquals(1, frames)
    }

    @Test fun `a capture gap remains a gap and reset drops the preceding capture fragment`() {
        val assembler = Pcm16VoiceFrameAssembler()
        val times = mutableListOf<Long>()
        assembler.accept(ShortArray(512), 512, 32) { _, time -> times += time }
        assembler.accept(ShortArray(512), 512, 1_032) { _, time -> times += time }
        assertEquals(listOf(32L, 1_032L), times)
        assembler.accept(ShortArray(500) { 123 }, 500, 1_100) { _, _ -> error("Incomplete frame") }
        assembler.reset()
        var frames = 0
        assembler.accept(ShortArray(12), 12, 1_101) { _, _ -> frames++ }
        assertEquals(0, frames)
    }
}
