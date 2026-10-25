package com.example.localassistant.voice

import kotlin.math.roundToInt

/** Linear resampling. Adequate for 16 kHz speech input; no anti-aliasing filter. */
object PcmResampler {

    fun resample(input: FloatArray, inputRate: Int, outputRate: Int = 16_000): FloatArray {
        if (inputRate == outputRate || input.isEmpty()) return input
        val ratio = inputRate.toDouble() / outputRate.toDouble()
        val outputLength = (input.size / ratio).roundToInt()
        if (outputLength <= 0) return FloatArray(0)
        val output = FloatArray(outputLength)
        for (i in output.indices) {
            val position = i * ratio
            val index = position.toInt()
            val fraction = (position - index).toFloat()
            val first = input[index]
            val second = if (index + 1 < input.size) input[index + 1] else first
            output[i] = first + (second - first) * fraction
        }
        return output
    }
}
