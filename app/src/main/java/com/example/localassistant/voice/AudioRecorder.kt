package com.example.localassistant.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Foreground-only PCM capture for offline recognition.
 *
 * Records 16 kHz mono when the device supports it and falls back to 44.1/48 kHz with a
 * linear resample. The buffer is bounded by [maxDurationMs]; nothing is written to disk and
 * nothing is logged beyond sizes and timings.
 */
class AudioRecorder {

    data class Capture(
        val samples: FloatArray,
        val durationMs: Long,
        val sourceSampleRate: Int,
    )

    @Volatile
    private var running = false

    @Volatile
    private var record: AudioRecord? = null

    val isRecording: Boolean
        get() = running

    /** Requests the capture loop to finish; safe from the main thread. */
    fun stop() {
        running = false
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
            // Already stopped or not started.
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun record(
        maxDurationMs: Int,
        onTick: (elapsedMs: Long) -> Unit,
    ): Capture = withContext(Dispatchers.IO) {
        val sampleRate = pickSampleRate()
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            throw IOException("Микрофон не поддерживает запись PCM")
        }
        val bufferBytes = maxOf(minBuffer * 2, sampleRate / 5 * 2)
        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (t: Throwable) {
            throw IOException("Не удалось открыть микрофон", t)
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw IOException("Микрофон недоступен")
        }

        record = audioRecord
        running = true
        val chunks = FloatSamples()
        val shorts = ShortArray(maxOf(sampleRate / 50, 320))
        val maxSamples = (maxDurationMs.toLong() * sampleRate / 1000L).toInt()
        val startedAt = System.nanoTime()
        var lastTickAt = 0L

        try {
            audioRecord.startRecording()
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IOException("Не удалось начать запись")
            }
            while (running && chunks.size < maxSamples) {
                coroutineContext.ensureActive()
                val read = audioRecord.read(shorts, 0, shorts.size)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_DEAD_OBJECT) {
                        throw IOException("Ошибка чтения с микрофона")
                    }
                    continue
                }
                chunks.add(shorts, read)
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000L
                if (elapsed - lastTickAt >= TICK_MS) {
                    lastTickAt = elapsed
                    onTick(elapsed.coerceAtMost(maxDurationMs.toLong()))
                }
            }
        } finally {
            running = false
            record = null
            try {
                audioRecord.stop()
            } catch (_: IllegalStateException) {
            }
            audioRecord.release()
        }

        val raw = chunks.toArray()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        val resampled = PcmResampler.resample(raw, sampleRate)
        val durationMs = if (sampleRate == 16_000) {
            elapsedMs
        } else {
            (resampled.size * 1000L / 16_000L)
        }
        Log.i(
            TAG,
            "captured sourceRate=$sampleRate samples=${resampled.size} duration=${durationMs}ms",
        )
        Capture(samples = resampled, durationMs = durationMs, sourceSampleRate = sampleRate)
    }

    private fun pickSampleRate(): Int {
        for (rate in intArrayOf(16_000, 48_000, 44_100)) {
            val min = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (min > 0) return rate
        }
        return 16_000
    }

    private class FloatSamples {
        private var data = FloatArray(16_000 * 5)
        var size: Int = 0
            private set

        fun add(shorts: ShortArray, count: Int) {
            ensure(size + count)
            for (i in 0 until count) {
                data[size + i] = shorts[i] / 32768f
            }
            size += count
        }

        fun toArray(): FloatArray = data.copyOf(size)

        private fun ensure(required: Int) {
            if (required <= data.size) return
            var capacity = data.size
            while (capacity < required) capacity *= 2
            data = data.copyOf(capacity)
        }
    }

    companion object {
        private const val TAG = "AudioRecorder"
        private const val TICK_MS = 200L
    }
}
