package com.example.localassistant.documents

import com.example.localassistant.llm.EngineState
import com.example.localassistant.llm.GenerationParams
import com.example.localassistant.llm.GenerationResult
import com.example.localassistant.llm.GenerationTermination
import com.example.localassistant.llm.LlmEngine
import com.example.localassistant.llm.PromptMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class DetailedDocumentAnswerTest {
    @Test fun `full answer processes all hits in bounded batches but never appends raw evidence`() = runTest {
        val engine = FakeEngine()
        val hits = (1..8).map { index -> hit(index, "Факт $index: " + "подробные условия и исключения. ".repeat(30)) }
        val answer = DocumentLlm(engine).fullAnswer("Какие условия?", hits)
        assertTrue(engine.calls > 1)
        assertEquals(8, answer.sources.size)
        hits.forEach { hit -> assertTrue(engine.seenSourceTexts.any { it.contains(hit.chunk.text) }) }
        assertFalse(answer.text.contains(hits.first().chunk.text))
        assertFalse(answer.text.contains("Полные найденные фрагменты"))
        assertTrue(engine.budgets.all { it == 1024 })
        assertTrue(engine.checkedBudgets.all { it <= 2048 })
    }

    @Test fun `a large source is split for Qwen without dropping any original text`() = runTest {
        val engine = FakeEngine()
        val source = hit(1, "Полная строка 📄 с числом 18500 и условием. ".repeat(100))
        val answer = DocumentLlm(engine).fullAnswer("Приведи полностью", listOf(source))
        assertTrue(engine.calls > 1)
        assertEquals(source.chunk.text, engine.seenSourceTexts.joinToString(""))
        assertFalse(answer.text.contains(source.chunk.text))
        assertEquals(1, answer.sources.size)
        assertTrue(engine.checkedBudgets.all { it <= 2048 })
    }

    @Test fun `invalid citations fail explicitly instead of replacing the answer with technical data`() = runTest {
        val engine = FakeEngine().apply { invalidCitation = true }
        val source = hit(1, "Гарантия ровно 24 месяца")
        try { DocumentLlm(engine).fullAnswer("Гарантия?", listOf(source)); throw AssertionError("Expected citation rejection") }
        catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("неизвестный источник")) }
    }

    @Test fun `cancellation stops shared Qwen job instead of beginning later batches`() = runTest {
        val engine = FakeEngine().apply { hold = true }
        val work = async(start = CoroutineStart.UNDISPATCHED) { DocumentLlm(engine).fullAnswer("Вопрос", listOf(hit(1, "Факт"))) }
        assertEquals(1, engine.calls)
        work.cancel(); work.join()
        assertTrue(engine.cancelled)
        assertEquals(1, engine.calls)
    }

    @Test fun `initial answer is one short generation without raw fragments`() = runTest {
        val engine = FakeEngine()
        val source = hit(1, "RAW_SOURCE_FIELDS: Гарантия 24 месяца")
        val answer = DocumentLlm(engine).answer("Гарантия?", listOf(source))
        assertEquals(1, engine.calls)
        assertEquals(listOf(384), engine.budgets)
        assertFalse(answer.text.contains("RAW_SOURCE_FIELDS"))
        assertEquals(1, answer.sources.size)
    }

    private fun hit(index: Int, text: String): SearchHit {
        val chunk = DocumentChunk("a".repeat(64), "Договор.docx", index, 0, "Абзац $index", null, text, text)
        return SearchHit(chunk, 1.0, true)
    }

    private class FakeEngine : LlmEngine {
        override val state = MutableStateFlow<EngineState>(EngineState.Ready("fake", 2048))
        var calls = 0
        var hold = false
        var cancelled = false
        var invalidCitation = false
        val budgets = mutableListOf<Int>()
        val checkedBudgets = mutableListOf<Int>()
        val seenSourceTexts = mutableListOf<String>()
        private var active: kotlinx.coroutines.CompletableJob? = null
        override suspend fun load(modelPath: String) = Result.success(Unit)
        override suspend fun unload() = Unit
        override suspend fun close() = Unit
        override suspend fun countPromptTokens(messages: List<PromptMessage>, assistantPrefix: String) = messages.sumOf { it.content.length } / 3 + 8
        override fun generate(messages: List<PromptMessage>, params: GenerationParams, onChunk: (String) -> Unit, onDone: (GenerationResult) -> Unit, assistantPrefix: String): Job {
            calls++; budgets.add(params.maxTokens)
            checkedBudgets.add(messages.sumOf { it.content.length } / 3 + 8 + params.maxTokens + 32)
            val job = Job(); active = job
            if (hold) return job
            val data = JSONObject(messages.last().content).getJSONArray("sources")
            for (index in 0 until data.length()) seenSourceTexts.add(data.getJSONObject(index).getString("text"))
            val sources = JSONArray()
            for (index in 0 until data.length()) sources.put(if (invalidCitation) "S999" else data.getJSONObject(index).getString("id"))
            val text = JSONObject().put("notFound", false).put("answer", "Подробный разбор всех условий из этой порции.").put("sources", sources).toString()
            onDone(GenerationResult(text, GenerationTermination.EOG)); job.complete()
            return job
        }
        override fun cancelGeneration() { cancelled = true; active?.cancel() }
    }
}
