package com.example.localassistant.chat

import android.net.Uri
import com.example.localassistant.MainDispatcherRule
import com.example.localassistant.documents.DocumentFormat
import com.example.localassistant.documents.DocumentChunk
import com.example.localassistant.llm.EngineState
import com.example.localassistant.llm.GenerationParams
import com.example.localassistant.llm.GenerationResult
import com.example.localassistant.llm.GenerationTermination
import com.example.localassistant.llm.LlmEngine
import com.example.localassistant.llm.PromptMessage
import com.example.localassistant.model.ModelStatus
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DocumentChatModeTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `document reply requires explicitly enabled mode and retains followup context`() {
        val engine = FakeEngine()
        val gateway = FakeDocuments()
        val vm = ChatViewModel(engine, Model(), Memory(), documents = gateway)
        assertFalse(vm.state.value.documentSearchEnabled)
        vm.setDocumentSearchEnabled(true)
        vm.onInputChange("Какая гарантия у оборудования?"); vm.send()
        assertEquals(0, engine.calls)
        assertTrue(vm.state.value.messages.last().documentRelated)
        assertEquals(1, vm.state.value.messages.last().documentSources.size)
        assertFalse(vm.state.value.isGenerating)
        vm.onInputChange("А подробнее?"); vm.send()
        assertEquals("А подробнее?", gateway.questions.last())
        assertTrue(gateway.history.last().documentRelated)
        assertEquals(4, vm.state.value.messages.size)
    }

    @Test fun `OFF never invokes document gateway even for calendar or document questions`() {
        val engine = FakeEngine()
        val gateway = FakeDocuments()
        val vm = ChatViewModel(engine, Model(), Memory(), documents = gateway)
        vm.onInputChange("Гарантия?"); vm.send()
        assertEquals(0, gateway.questions.size)
        assertEquals(1, engine.calls)
        vm.setDocumentSearchEnabled(true)
        vm.onInputChange("Гарантия?"); vm.send()
        assertEquals(1, gateway.questions.size)
        vm.setDocumentSearchEnabled(false)
        vm.onInputChange("Какие события в календаре завтра?"); vm.send()
        assertEquals(1, gateway.questions.size)
        assertEquals(2, engine.calls)
        assertEquals("Обычный ответ", vm.state.value.messages.last().text)
        assertFalse(engine.lastMessages.any { it.content.contains("RAW_DOCUMENT_DATA") })
    }

    @Test fun `mode cannot change during generation and stop cancels document lookup`() {
        val gateway = FakeDocuments().apply { wait = CompletableDeferred() }
        val vm = ChatViewModel(FakeEngine(), Model(), Memory(), documents = gateway)
        vm.setDocumentSearchEnabled(true)
        vm.onInputChange("Гарантия?"); vm.send()
        assertTrue(vm.state.value.isGenerating)
        vm.setDocumentSearchEnabled(false)
        assertTrue(vm.state.value.documentSearchEnabled)
        vm.stopGeneration()
        assertTrue(gateway.cancelled)
        assertFalse(vm.state.value.isGenerating)
        assertEquals(MessageStatus.INTERRUPTED, vm.state.value.messages.last().status)
    }

    @Test fun `document source metadata and full raw special spellings survive restart`() = runTest {
        val directory = Files.createTempDirectory("chat-document-test").toFile()
        try {
            val store = ConversationStore(directory)
            val message = ChatMessage(1, Role.ASSISTANT, "Краткий ответ 📄", documentRelated = true, documentSources = listOf(source()), documentSnapshot = snapshot())
            store.save(listOf(message))
            assertEquals(message, store.load().single())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `full answer stays bound to original snapshot after later turns and cannot recursively expand`() {
        val gateway = FakeDocuments()
        val vm = ChatViewModel(FakeEngine(), Model(), Memory(), documents = gateway)
        vm.setDocumentSearchEnabled(true)
        vm.onInputChange("Гарантия?"); vm.send()
        val short = vm.state.value.messages.last()
        assertTrue(short.isPrimaryDocumentAnswer)
        gateway.reply = DocumentChatReply("Ответ на другую тему", listOf(source()), snapshot("Цена?"))
        vm.onInputChange("Цена?"); vm.send()
        vm.requestFullDocumentAnswer(short.id)
        assertEquals(short.documentSnapshot, gateway.fullSnapshot)
        assertEquals(2, gateway.questions.size)
        val full = vm.state.value.messages.last()
        assertEquals(short.id, full.fullAnswerFor)
        assertFalse(full.isPrimaryDocumentAnswer)
        val count = vm.state.value.messages.size
        vm.requestFullDocumentAnswer(short.id)
        assertEquals(count, vm.state.value.messages.size)
        vm.setDocumentSearchEnabled(false)
        vm.requestFullDocumentAnswer(vm.state.value.messages[3].id)
        assertEquals(count, vm.state.value.messages.size)
    }

    private class FakeDocuments : DocumentChatGateway {
        var reply = DocumentChatReply("Краткий человеческий ответ", listOf(source()), snapshot())
        var history = emptyList<ChatMessage>()
        val questions = mutableListOf<String>()
        var wait: CompletableDeferred<Unit>? = null
        var cancelled = false
        var fullSnapshot: DocumentAnswerSnapshot? = null
        override suspend fun answer(question: String, history: List<ChatMessage>, progress: (String) -> Unit): DocumentChatReply {
            this.history = history; questions.add(question); progress("Поиск…")
            wait?.await()
            return reply
        }
        override suspend fun fullAnswer(snapshot: DocumentAnswerSnapshot, progress: (String) -> Unit): DocumentChatReply {
            fullSnapshot = snapshot
            return DocumentChatReply("Полный связный ответ Qwen", snapshot.sources)
        }
        override fun cancel() { cancelled = true }
    }
    private class Memory : ConversationPersistence {
        override suspend fun load() = emptyList<ChatMessage>()
        override suspend fun save(messages: List<ChatMessage>) = Unit
        override suspend fun clear() = Unit
    }
    private class Model : ModelGateway {
        override val status = MutableStateFlow<ModelStatus>(ModelStatus.Ready("fake.gguf", 1, "fake", 2048))
        override fun initialize() = Unit
        override fun startDownload() = Unit
        override fun cancelDownload() = Unit
        override fun importModel(uri: Uri) = Unit
        override fun deleteModel() = Unit
        override fun retryLoad() = Unit
    }
    private class FakeEngine : LlmEngine {
        override val state = MutableStateFlow<EngineState>(EngineState.Ready("fake", 2048))
        var calls = 0
        var lastMessages = emptyList<PromptMessage>()
        override suspend fun load(modelPath: String) = Result.success(Unit)
        override suspend fun unload() = Unit
        override suspend fun close() = Unit
        override fun cancelGeneration() = Unit
        override suspend fun countPromptTokens(messages: List<PromptMessage>, assistantPrefix: String) = 20
        override fun generate(messages: List<PromptMessage>, params: GenerationParams, onChunk: (String) -> Unit, onDone: (GenerationResult) -> Unit, assistantPrefix: String): Job {
            calls++; lastMessages = messages
            onChunk("Обычный ответ"); onDone(GenerationResult("Обычный ответ", GenerationTermination.EOG))
            return Job().also { it.complete() }
        }
    }
    companion object {
        private fun source() = ChatDocumentSource("a".repeat(64), "Договор.docx", DocumentFormat.DOCX, "Договор.docx · Абзац 1")
        private fun snapshot(question: String = "Гарантия?") = DocumentAnswerSnapshot(question,
            listOf(DocumentChunk("a".repeat(64), "Договор.docx", 0, 0, "Абзац 1", null, "RAW_DOCUMENT_DATA", "RAW_DOCUMENT_DATA")), listOf(source()))
    }
}
