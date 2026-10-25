package com.example.localassistant.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmResamplerTest {

    @Test
    fun `48 kHz input becomes 16 kHz output of the expected length`() {
        val input = FloatArray(48_000) { 0.25f }

        val output = PcmResampler.resample(input, 48_000)

        assertEquals(16_000, output.size)
        assertEquals(0.25f, output[0], 0.0001f)
        assertEquals(0.25f, output.last(), 0.0001f)
    }

    @Test
    fun `16 kHz input is returned unchanged`() {
        val input = floatArrayOf(0.1f, -0.2f, 0.3f)

        val output = PcmResampler.resample(input, 16_000)

        assertEquals(input.size, output.size)
        assertEquals(input[1], output[1], 0.0f)
    }

    @Test
    fun `44 point 1 kHz input keeps duration within one sample`() {
        val input = FloatArray(44_100)

        val output = PcmResampler.resample(input, 44_100)

        assertEquals(16_000, output.size)
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals(0, PcmResampler.resample(FloatArray(0), 48_000).size)
    }
}
