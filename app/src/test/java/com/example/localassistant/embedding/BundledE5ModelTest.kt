package com.example.localassistant.embedding

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledE5ModelTest {
    private val directory = Files.createTempDirectory("bundled-e5-test").toFile()
    private val target = File(directory, "e5.gguf")
    private val payload = "synthetic model bytes for transactional copy".toByteArray()
    private val verifier: (File, () -> Unit) -> Unit = { file, checkActive ->
        checkActive()
        require(file.readBytes().contentEquals(payload)) { "checksum mismatch" }
    }

    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun `first preparation copies and validates asset then reuses verified file`() = runTest {
        var opened = 0
        val model = BundledE5Model(target, { opened++; payload.inputStream() }, verifier, payload.size.toLong())
        model.prepare()
        assertArrayEquals(payload, target.readBytes())
        model.prepare()
        assertEquals(1, opened)
        assertNoPartialFiles()
    }

    @Test fun `invalid existing model is restored atomically from valid bundled asset`() = runTest {
        target.writeBytes("old invalid model".toByteArray())
        BundledE5Model(target, { payload.inputStream() }, verifier, payload.size.toLong()).prepare()
        assertArrayEquals(payload, target.readBytes())
        assertNoPartialFiles()
    }

    @Test fun `invalid asset preserves existing file and removes partial copy`() = runTest {
        val original = "old invalid model".toByteArray()
        target.writeBytes(original)
        val bad = ByteArray(payload.size) { 0 }
        val model = BundledE5Model(target, { bad.inputStream() }, verifier, payload.size.toLong())
        try { model.prepare(); throw AssertionError("Expected checksum failure") } catch (_: IllegalArgumentException) { }
        assertArrayEquals(original, target.readBytes())
        assertNoPartialFiles()
    }

    @Test fun `cancelled preparation preserves existing file and removes partial copy`() = runTest {
        val original = "old invalid model".toByteArray()
        target.writeBytes(original)
        val model = BundledE5Model(target, {
            object : ByteArrayInputStream(payload) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    throw CancellationException("synthetic cancellation")
                }
            }
        }, verifier, payload.size.toLong())
        try { model.prepare(); throw AssertionError("Expected cancellation") } catch (_: CancellationException) { }
        assertArrayEquals(original, target.readBytes())
        assertNoPartialFiles()
    }

    private fun assertNoPartialFiles() { assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".part") }) }
}
