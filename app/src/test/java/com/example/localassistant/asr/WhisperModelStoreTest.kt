package com.example.localassistant.asr

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperModelStoreTest {

    private fun tempFile(bytes: ByteArray): File {
        val file = File.createTempFile("whisper-model", ".bin")
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file
    }

    @Test
    fun `ggml magic is recognised in little endian`() {
        val file = tempFile(byteArrayOf(0x6c, 0x6d, 0x67, 0x67))

        assertTrue(WhisperModelStore.hasGgmlMagic(file))
    }

    @Test
    fun `other magic is rejected`() {
        val file = tempFile("GGUF".toByteArray())

        assertFalse(WhisperModelStore.hasGgmlMagic(file))
    }

    @Test
    fun `a truncated file is not a plausible artifact`() {
        val file = tempFile(byteArrayOf(0x6c, 0x6d, 0x67, 0x67))

        assertFalse(WhisperModelStore.isPlausibleArtifact(file))
    }

    @Test
    fun `missing file is not a plausible artifact`() {
        val file = File(System.getProperty("java.io.tmpdir"), "missing-whisper-model.bin")

        assertFalse(WhisperModelStore.isPlausibleArtifact(file))
    }
}
