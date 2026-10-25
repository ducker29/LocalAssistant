package com.example.localassistant.asr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level check that "Отменить" actually stops whisper's decoder instead of only
 * hiding the UI state, and that the same engine can transcribe again afterwards.
 *
 * Requires the pinned ggml-base.bin in app storage.
 */
@RunWith(AndroidJUnit4::class)
class WhisperCancellationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var engine: WhisperEngine? = null

    @After
    fun tearDown() {
        engine?.let { runBlocking { it.unload() } }
        engine?.close()
        engine = null
    }

    @Test
    fun `cancellation_returns_null_and_engine_stays_usable`() = runBlocking {
        val model = File(context.filesDir, "whisper/${WhisperModelSpec.FILE_NAME}")
        assumeTrue("Whisper model is not provisioned on this device", model.isFile)

        val recogniser = WhisperEngine(threads = 4).also { engine = it }
        val loaded = recogniser.load(model.absolutePath)
        assertTrue(loaded.exceptionOrNull()?.message, loaded.isSuccess)

        val fixture = loadWav("fixtures/ru_calendar.wav")
        val repeated = FloatArray(fixture.size * 8) { fixture[it % fixture.size] }

        val job = async(Dispatchers.Default) {
            recogniser.transcribe(repeated, language = "ru")
        }
        delay(700)
        recogniser.cancel()
        val cancelSet = System.nanoTime()
        val result = withTimeout(30_000) { job.await() }
        val stopAfterFlagMs = (System.nanoTime() - cancelSet) / 1_000_000L
        println("CANCEL_RESULT stopAfterFlagMs=$stopAfterFlagMs resultChars=${result?.length}")

        assertNull("cancelled transcription must not produce text", result)
        assertTrue(
            "decoder kept running for ${stopAfterFlagMs}ms after the cancel flag was set",
            stopAfterFlagMs < 10_000,
        )

        val text = recogniser.transcribe(fixture, language = "ru")
        println("RECOVERY_RESULT text=$text")
        assertTrue(
            "engine did not recover after cancellation: $text",
            !text.isNullOrBlank() && text.lowercase().contains("календар"),
        )
    }

    private fun loadWav(assetPath: String): FloatArray {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bytes = assets.open(assetPath).use { it.readBytes() }
        require(String(bytes, 0, 4) == "RIFF") { "not a WAV file" }
        var offset = 12
        var dataOffset = -1
        var dataSize = 0
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4)
            val size = ByteBuffer.wrap(bytes, offset + 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
            if (id == "data") {
                dataOffset = offset + 8
                dataSize = size
                break
            }
            offset += 8 + size + (size and 1)
        }
        require(dataOffset >= 0) { "WAV data chunk is missing" }
        val buffer = ByteBuffer.wrap(bytes, dataOffset, dataSize).order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(dataSize / 2)
        for (i in samples.indices) {
            samples[i] = buffer.short / 32768f
        }
        return samples
    }
}
