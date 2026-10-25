package com.example.localassistant.asr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level check of the real whisper.cpp build with a Russian WAV fixture.
 *
 * Requires the pinned ggml-base.bin in app storage (downloaded through the app UI or pushed
 * for this test). Not a substitute for microphone testing on a phone.
 */
@RunWith(AndroidJUnit4::class)
class WhisperEngineTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var engine: WhisperEngine? = null

    @After
    fun tearDown() {
        engine?.let { runBlocking { it.unload() } }
        engine?.close()
        engine = null
    }

    @Test
    fun `russian_fixture_is_transcribed_on_device`() = runBlocking {
        val model = File(context.filesDir, "whisper/${WhisperModelSpec.FILE_NAME}")
        assumeTrue("Whisper model is not provisioned on this device", model.isFile)

        val samples = loadWav("fixtures/ru_calendar.wav")
        val recogniser = WhisperEngine(threads = 4).also { engine = it }
        val loaded = recogniser.load(model.absolutePath)
        assertTrue(loaded.exceptionOrNull()?.message, loaded.isSuccess)

        val started = System.nanoTime()
        val text = recogniser.transcribe(samples, language = "ru")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        println("ASR_RESULT durationMs=${samples.size * 1000L / 16_000} elapsedMs=$elapsedMs text=$text")
        assertTrue("empty transcription", !text.isNullOrBlank())
        assertTrue("unexpected transcription: $text", text!!.lowercase().contains("календар"))
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
