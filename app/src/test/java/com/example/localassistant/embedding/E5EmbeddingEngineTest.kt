package com.example.localassistant.embedding

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class E5EmbeddingEngineTest {
    @Test fun `prefixes UTF8 normalization and literal markers are search-only`() {
        val original = "  Счёт 📄 <mask>\n"
        assertEquals("query: Счёт 📄 < mask >", EmbeddingText.prepare(original, true).toString(Charsets.UTF_8))
        assertEquals("passage: Строка\nЯчейка", EmbeddingText.prepare("Строка\nЯчейка", false).toString(Charsets.UTF_8))
        assertEquals("  Счёт 📄 <mask>\n", original)
    }

    @Test fun `512 total tokens allowed 513 rejected before native inference and ready state restored`() = runBlocking {
        val bridge = FakeBridge()
        val engine = engine(bridge)
        try {
            engine.load(File("unused"))
            bridge.tokens = 512
            assertEquals(384, engine.embedQuery("Вопрос").size)
            assertEquals("query: Вопрос", bridge.lastText)
            bridge.tokens = 513
            assertEquals(513, engine.countPassageTokens("Строка"))
            expectArgumentError { engine.embedPassage("Строка") }
            assertEquals(1, bridge.embedCalls.get())
            assertEquals(EmbeddingState.Ready, engine.state.value)
        } finally { engine.close() }
    }

    @Test fun `cancelled JNI call exits before context is unloaded or destroyed`() = runBlocking {
        val bridge = FakeBridge(blockInference = true)
        val engine = engine(bridge)
        try {
            engine.load(File("unused"))
            val work = async(start = CoroutineStart.UNDISPATCHED) { engine.embedQuery("Вопрос") }
            assertTrue(bridge.entered.await(5, TimeUnit.SECONDS))
            work.cancel()
            assertTrue(bridge.cancelled.get())
            val closing = async(start = CoroutineStart.UNDISPATCHED) { engine.close() }
            assertFalse(closing.isCompleted)
            assertEquals(0, bridge.destroyCalls.get())
            bridge.release.countDown()
            work.join()
            closing.await()
            assertFalse(bridge.releaseWhileRunning.get())
            assertEquals(1, bridge.destroyCalls.get())
            assertEquals(EmbeddingState.Closed, engine.state.value)
            engine.close()
            engine.unload()
            assertEquals(1, bridge.destroyCalls.get())
        } finally { bridge.release.countDown(); engine.close() }
    }

    @Test fun `manual cancel reaches runtime and releases aborted context before reloading`() = runBlocking {
        val bridge = FakeBridge(blockInference = true)
        val engine = engine(bridge)
        try {
            engine.load(File("unused"))
            val work = async(start = CoroutineStart.UNDISPATCHED) { engine.embedQuery("Вопрос") }
            assertTrue(bridge.entered.await(5, TimeUnit.SECONDS))
            engine.cancel()
            bridge.release.countDown()
            try { work.await(); throw AssertionError("Expected cancellation") } catch (_: CancellationException) { }
            assertEquals(EmbeddingState.Unloaded, engine.state.value)
            engine.load(File("unused"))
            assertEquals(EmbeddingState.Ready, engine.state.value)
            assertEquals(384, engine.embedPassage("Ответ").size)
        } finally { bridge.release.countDown(); engine.close() }
    }

    @Test fun `invalid model never reaches load and invalid vector cannot reach caller`() = runBlocking {
        val bridge = FakeBridge()
        val invalid = E5EmbeddingEngine(bridge, { _, _ -> throw IllegalArgumentException("checksum") }, 1)
        try {
            expectArgumentError { invalid.load(File("unused")) }
            assertEquals(0, bridge.loadCalls.get())
            assertTrue(invalid.state.value is EmbeddingState.Failed)
        } finally { invalid.close() }
        val engine = engine(bridge)
        try {
            engine.load(File("unused"))
            bridge.invalidVector = true
            try { engine.embedQuery("Вопрос"); throw AssertionError("Expected vector failure") }
            catch (_: IllegalStateException) { }
            assertTrue(engine.state.value is EmbeddingState.Failed)
        } finally { engine.close() }
    }

    @Test fun `load inference and release run off caller thread and serialize requests`() = runBlocking {
        val bridge = FakeBridge()
        val engine = engine(bridge)
        try {
            engine.load(File("unused"))
            val first = async { engine.embedQuery("Один") }
            val second = async { engine.embedPassage("Два") }
            first.await(); second.await()
            engine.close()
            assertEquals(1, bridge.workerThreads.size)
            assertNotEquals(Thread.currentThread().id, bridge.workerThreads.single())
            assertEquals(2, bridge.embedCalls.get())
            assertFalse(bridge.releaseWhileRunning.get())
        } finally { engine.close() }
    }

    private fun engine(bridge: FakeBridge) = E5EmbeddingEngine(bridge, { _, checkActive -> checkActive() }, 1)

    private suspend fun expectArgumentError(block: suspend () -> Unit) {
        try { block(); throw AssertionError("Expected invalid argument") }
        catch (_: IllegalArgumentException) { }
    }

    private class FakeBridge(private val blockInference: Boolean = false) : EmbeddingBridge {
        var tokens = 10
        var lastText = ""
        var invalidVector = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val running = AtomicBoolean(false)
        val releaseWhileRunning = AtomicBoolean(false)
        val loadCalls = AtomicInteger(0)
        val embedCalls = AtomicInteger(0)
        val destroyCalls = AtomicInteger(0)
        val workerThreads = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
        private fun record() { workerThreads.add(Thread.currentThread().id) }
        override fun create(): Long { record(); return 1 }
        override fun load(handle: Long, path: ByteArray, threads: Int) { record(); loadCalls.incrementAndGet() }
        override fun countTokens(handle: Long, text: ByteArray): Int { record(); lastText = text.toString(Charsets.UTF_8); return tokens }
        override fun embed(handle: Long, text: ByteArray): FloatArray {
            record(); embedCalls.incrementAndGet(); running.set(true)
            try {
                if (blockInference) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                if (cancelled.get()) throw CancellationException("cancelled")
                return FloatArray(384).also { it[0] = if (invalidVector) Float.NaN else 1f }
            } finally { running.set(false) }
        }
        override fun cancel(handle: Long) { cancelled.set(true) }
        override fun resetCancellation(handle: Long) { record(); cancelled.set(false) }
        override fun unload(handle: Long) { record(); if (running.get()) releaseWhileRunning.set(true) }
        override fun destroy(handle: Long) { record(); if (running.get()) releaseWhileRunning.set(true); destroyCalls.incrementAndGet() }
    }
}
