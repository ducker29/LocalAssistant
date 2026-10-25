package com.example.localassistant.chat

import android.net.Uri
import com.example.localassistant.MainDispatcherRule
import com.example.localassistant.llm.EngineState
import com.example.localassistant.llm.GenerationParams
import com.example.localassistant.llm.LlmEngine
import com.example.localassistant.llm.PromptMessage
import com.example.localassistant.model.ModelStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeEngine : LlmEngine {
        override val state = MutableStateFlow<EngineState>(EngineState.Ready("fake", 2048))
        var chunks: List<String> = listOf("Ответ")
        var failure: Throwable? = null
        var gate: CompletableDeferred<Unit>? = null
        var promptTokens = 32
        var lastMessages: List<PromptMessage> = emptyList()

        override suspend fun load(modelPath: String): Result<Unit> = Result.success(Unit)

        override suspend fun unload() = Unit

        override fun generate(messages: List<PromptMessage>, params: GenerationParams): Flow<String> {
            lastMessages = messages
            return flow {
                gate?.await()
                chunks.forEach { emit(it) }
                failure?.let { throw it }
            }
        }

        override fun cancelGeneration() = Unit

        override suspend fun countPromptTokens(messages: List<PromptMessage>): Int = promptTokens

        override suspend fun close() = Unit
    }

    private class FakeGateway : ModelGateway {
        override val status: StateFlow<ModelStatus> =
            MutableStateFlow(ModelStatus.Ready("model.gguf", 1L, "fake", 2048))

        override fun initialize() = Unit
        override fun startDownload() = Unit
        override fun cancelDownload() = Unit
        override fun importModel(uri: Uri) = Unit
        override fun deleteModel() = Unit
        override fun retryLoad() = Unit
    }

    private class MemoryPersistence : ConversationPersistence {
        var stored: List<ChatMessage> = emptyList()
        override suspend fun load(): List<ChatMessage> = stored
        override suspend fun save(messages: List<ChatMessage>) {
            stored = messages
        }

        override suspend fun clear() {
            stored = emptyList()
        }
    }

    private fun viewModel(
        engine: FakeEngine = FakeEngine(),
        gateway: FakeGateway = FakeGateway(),
        persistence: MemoryPersistence = MemoryPersistence(),
    ) = ChatViewModel(engine, gateway, persistence)

    @Test
    fun `send appends the user message and the completed answer`() {
        val engine = FakeEngine().apply { chunks = listOf("При", "вет") }
        val vm = viewModel(engine)

        vm.onInputChange("Привет!")
        vm.send()

        val messages = vm.state.value.messages
        assertEquals(2, messages.size)
        assertEquals(Role.USER, messages[0].role)
        assertEquals("Привет!", messages[0].text)
        assertEquals(Role.ASSISTANT, messages[1].role)
        assertEquals("Привет", messages[1].text)
        assertEquals(MessageStatus.COMPLETE, messages[1].status)
        assertFalse(vm.state.value.isGenerating)
        assertEquals("Привет!", vm.state.value.messages.first().text)
    }

    @Test
    fun `empty submission is rejected`() {
        val vm = viewModel()
        vm.onInputChange("   ")

        vm.send()

        assertTrue(vm.state.value.messages.isEmpty())
    }

    @Test
    fun `send without a ready model reports an error`() {
        val gateway = object : ModelGateway {
            override val status: StateFlow<ModelStatus> = MutableStateFlow(ModelStatus.Absent)
            override fun initialize() = Unit
            override fun startDownload() = Unit
            override fun cancelDownload() = Unit
            override fun importModel(uri: Uri) = Unit
            override fun deleteModel() = Unit
            override fun retryLoad() = Unit
        }
        val vm = ChatViewModel(FakeEngine(), gateway, MemoryPersistence())

        vm.onInputChange("Привет")
        vm.send()

        assertTrue(vm.state.value.messages.isEmpty())
        assertNotNull(vm.state.value.error)
    }

    @Test
    fun `cancelled generation is marked as interrupted and keeps partial text`() {
        val engine = FakeEngine().apply {
            gate = CompletableDeferred()
            chunks = listOf("Начало ответа")
        }
        val vm = viewModel(engine)

        vm.onInputChange("Расскажи длинно")
        vm.send()
        assertTrue(vm.state.value.isGenerating)

        vm.stopGeneration()
        engine.gate?.complete(Unit)

        val assistant = vm.state.value.messages.last()
        assertEquals(MessageStatus.INTERRUPTED, assistant.status)
        assertEquals("Начало ответа", assistant.text)
        assertFalse(vm.state.value.isGenerating)
    }

    @Test
    fun `stop before the first token is interrupted rather than failed`() {
        val engine = FakeEngine().apply {
            gate = CompletableDeferred()
            chunks = emptyList()
        }
        val vm = viewModel(engine)

        vm.onInputChange("Длинный рассказ")
        vm.send()
        vm.stopGeneration()
        engine.gate?.complete(Unit)

        assertEquals(MessageStatus.INTERRUPTED, vm.state.value.messages.last().status)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `failed generation is marked as failed and surfaces the error`() {
        val engine = FakeEngine().apply {
            failure = IllegalStateException("нет памяти")
        }
        val vm = viewModel(engine)

        vm.onInputChange("Привет")
        vm.send()

        val assistant = vm.state.value.messages.last()
        assertEquals(MessageStatus.FAILED, assistant.status)
        assertEquals("нет памяти", vm.state.value.error)
    }

    @Test
    fun `empty model answer is marked as failed`() {
        val engine = FakeEngine().apply { chunks = emptyList() }
        val vm = viewModel(engine)

        vm.onInputChange("Привет")
        vm.send()

        assertEquals(MessageStatus.FAILED, vm.state.value.messages.last().status)
    }

    @Test
    fun `message that does not fit the context is rejected before inference`() {
        val engine = FakeEngine().apply { promptTokens = 100_000 }
        val vm = viewModel(engine)

        vm.onInputChange("Очень длинная история")
        vm.send()

        assertEquals(MessageStatus.FAILED, vm.state.value.messages.last().status)
        assertNotNull(vm.state.value.error)
        assertTrue(engine.lastMessages.isEmpty())
    }

    @Test
    fun `interrupted assistant turns are not sent back to the model`() {
        val engine = FakeEngine().apply {
            gate = CompletableDeferred()
            chunks = listOf("обрывок")
        }
        val vm = viewModel(engine)

        vm.onInputChange("Первый вопрос")
        vm.send()
        vm.stopGeneration()
        engine.gate?.complete(Unit)
        assertEquals(MessageStatus.INTERRUPTED, vm.state.value.messages.last().status)

        engine.gate = null
        engine.chunks = listOf("Второй ответ")
        vm.onInputChange("Второй вопрос")
        vm.send()

        assertEquals("system", engine.lastMessages.first().role)
        assertTrue(engine.lastMessages.none { it.role == "assistant" && it.content == "обрывок" })
    }

    @Test
    fun `new chat clears messages but keeps the model`() {
        val persistence = MemoryPersistence()
        val vm = viewModel(persistence = persistence)
        vm.onInputChange("Привет")
        vm.send()

        vm.newChat()

        assertTrue(vm.state.value.messages.isEmpty())
        assertTrue(persistence.stored.isEmpty())
        assertTrue(vm.state.value.model is ModelStatus.Ready)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `appendDraft adds a new line and keeps existing text`() {
        val vm = viewModel()
        vm.onInputChange("Мой черновик")

        vm.appendDraft("  Добавленная фраза  ")

        assertEquals("Мой черновик\nДобавленная фраза", vm.state.value.input)
        assertTrue(vm.state.value.messages.isEmpty())
    }

    @Test
    fun `appendDraft fills an empty draft`() {
        val vm = viewModel()

        vm.appendDraft("Первая фраза")

        assertEquals("Первая фраза", vm.state.value.input)
    }

    @Test
    fun `appendDraft ignores blank transcripts`() {
        val vm = viewModel()
        vm.onInputChange("Черновик")

        vm.appendDraft("   \n ")

        assertEquals("Черновик", vm.state.value.input)
    }

    @Test
    fun `appendDraft never sends a message`() {
        val engine = FakeEngine()
        val vm = viewModel(engine)
        vm.onInputChange("Черновик")

        vm.appendDraft("Распознанная речь")

        assertFalse(vm.state.value.isGenerating)
        assertTrue(vm.state.value.messages.isEmpty())
        assertTrue(engine.lastMessages.isEmpty())
    }
}
