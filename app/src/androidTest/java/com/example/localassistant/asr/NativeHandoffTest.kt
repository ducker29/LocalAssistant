package com.example.localassistant.asr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.localassistant.llm.GenerationParams
import com.example.localassistant.llm.LlamaEngine
import com.example.localassistant.llm.PromptMessage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Qwen -> Whisper -> Qwen in one process. Both native libraries are loaded into the same
 * linker namespace, so this catches symbol clashes or lifetime bugs between the two ggml
 * copies that a build alone cannot prove.
 */
@RunWith(AndroidJUnit4::class)
class NativeHandoffTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test(timeout = 600_000)
    fun `chat_survives_loading_whisper_in_the_same_process`() = runBlocking {
        val qwen = File(context.filesDir, "models/model.gguf")
        val whisper = File(context.filesDir, "whisper/${WhisperModelSpec.FILE_NAME}")
        assumeTrue("Qwen model is not provisioned", qwen.isFile)
        assumeTrue("Whisper model is not provisioned", whisper.isFile)

        val llama = LlamaEngine()
        val recogniser = WhisperEngine(threads = 4)
        try {
            assertTrue(llama.load(qwen.absolutePath).isSuccess)
            val first = generateOnce(llama)
            assertTrue("first answer is empty", first.isNotBlank())

            assertTrue(recogniser.load(whisper.absolutePath).isSuccess)
            val samples = loadWav("fixtures/ru_calendar.wav")
            val text = recogniser.transcribe(samples, language = "ru")
            println("HANDOFF_ASR text=$text rss=${rssKb()}kB")
            assertTrue("empty transcription", !text.isNullOrBlank())
            assertTrue("unexpected transcription: $text", text!!.lowercase().contains("календар"))

            val second = generateOnce(llama)
            assertTrue("answer after whisper is empty", second.isNotBlank())
            println("HANDOFF_OK rss=${rssKb()}kB first=${first.length} second=${second.length}")
        } finally {
            runBlocking { recogniser.unload() }
            recogniser.close()
            runBlocking { llama.unload() }
            llama.close()
        }
    }

    private suspend fun generateOnce(llama: LlamaEngine): String {
        val chunks = llama.generate(
            listOf(
                PromptMessage("system", "Отвечай одним коротким предложением."),
                PromptMessage("user", "Скажи «тест»."),
            ),
            GenerationParams(maxTokens = 24, temperature = 0f),
        ).take(24).toList()
        return chunks.joinToString("")
    }

    private fun rssKb(): Long {
        return File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toLongOrNull() ?: -1L
    }

    private fun loadWav(assetPath: String): FloatArray {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bytes = assets.open(assetPath).use { it.readBytes() }
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
        require(dataOffset >= 0)
        val buffer = ByteBuffer.wrap(bytes, dataOffset, dataSize).order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(dataSize / 2)
        for (i in samples.indices) {
            samples[i] = buffer.short / 32768f
        }
        return samples
    }
}
