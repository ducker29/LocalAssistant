package com.example.localassistant.chat

import android.net.Uri
import com.example.localassistant.MainDispatcherRule
import com.example.localassistant.llm.EngineState
import com.example.localassistant.llm.GenerationParams
import com.example.localassistant.llm.GenerationResult
import com.example.localassistant.llm.GenerationTermination
import com.example.localassistant.llm.LlmEngine
import com.example.localassistant.llm.PromptMessage
import com.example.localassistant.model.ModelStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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

    /**
     * A generation handle the test drives explicitly. [complete]/[cancel] fire [onDone]
     * (chunks emitted first), mirroring the real engine: [onDone] is invoked exactly once,
     * off the caller's thread. The [job] returned by [handle] suspends until then.
     */
    private class FakeGeneration(
        initialChunks: List<String>,
        private val failure: Throwable?,
        private val onChunk: (String) -> Unit,
        private val onDone: (GenerationResult) -> Unit,
    ) {
        private val signal = CompletableDeferred<Unit>()
        private val chunks = ArrayList<String>()
        private var fired = false

        init {
            for (chunk in initialChunks) {
                chunks.add(chunk)
                onChunk(chunk)
            }
        }

        fun emit(chunk: String) {
            chunks.add(chunk)
            onChunk(chunk)
        }

        fun cancel() {
            fire(GenerationTermination.CANCELLED)
        }

        fun complete() {
            val termination = if (failure != null) GenerationTermination.ERROR else GenerationTermination.EOG
            fire(termination, errorMessage = failure?.message)
        }

        fun fireTermination(
            termination: GenerationTermination,
            text: String = chunks.joinToString(""),
            errorMessage: String? = null,
        ) {
            fire(termination, text, errorMessage)
        }

        private fun fire(
            termination: GenerationTermination,
            text: String = chunks.joinToString(""),
            errorMessage: String? = null,
        ) {
            if (fired) return
            fired = true
            signal.complete(Unit)
            onDone(GenerationResult(text, termination, errorMessage))
        }

        /** A [Job] that suspends (join) until the generation fires. */
        fun handle(): Job = signal
    }

    private class FakeEngine : LlmEngine {
        override val state = MutableStateFlow<EngineState>(EngineState.Ready("fake", 2048))
        var chunks: List<String> = listOf("Ответ")
        var failure: Throwable? = null
        var promptTokens = 32
        var lastMessages: List<PromptMessage> = emptyList()
        var lastMaxTokens: Int? = null
        var lastAssistantPrefix: String = ""
        var active: FakeGeneration? = null

        override suspend fun load(modelPath: String): Result<Unit> = Result.success(Unit)

        override suspend fun unload() = Unit

        override fun generate(
            messages: List<PromptMessage>,
            params: GenerationParams,
            onChunk: (String) -> Unit,
            onDone: (GenerationResult) -> Unit,
            assistantPrefix: String,
        ): Job {
            lastMessages = messages
            lastMaxTokens = params.maxTokens
            lastAssistantPrefix = assistantPrefix
            val generation = FakeGeneration(emptyList(), failure, onChunk, onDone)
            active = generation
            // Emit chunks up front (streaming), but DO NOT fire onDone yet — the test
            // decides whether the generation completes (finish) or is cancelled (stopGeneration).
            for (chunk in chunks) generation.emit(chunk)
            return generation.handle()
        }

        /** Fire [onDone] with EOG (or ERROR if [failure] is set). */
        fun finish() {
            active?.complete()
        }

        /** Fire [onDone] with an explicit termination, keeping the chunks emitted so far. */
        fun finishWith(termination: GenerationTermination, errorMessage: String? = null) {
            active?.fireTermination(termination, errorMessage = errorMessage)
        }

        override fun cancelGeneration() {
            active?.cancel()
        }

        override suspend fun countPromptTokens(
            messages: List<PromptMessage>,
            assistantPrefix: String,
        ): Int = promptTokens

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
        gateway: ModelGateway = FakeGateway(),
        persistence: MemoryPersistence = MemoryPersistence(),
    ) = ChatViewModel(engine, gateway, persistence)

    @Test
    fun `send appends the user message and the completed answer`() {
        val engine = FakeEngine().apply { chunks = listOf("При", "вет") }
        val vm = viewModel(engine)

        vm.onInputChange("Привет!")
        vm.send()
        engine.finish()

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
            chunks = listOf("Начало ответа")
        }
        val vm = viewModel(engine)

        vm.onInputChange("Расскажи длинно")
        vm.send()
        assertTrue(vm.state.value.isGenerating)

        vm.stopGeneration()
        engine.active?.complete() // native loop notices the cancel flag and returns

        val assistant = vm.state.value.messages.last()
        assertEquals(MessageStatus.INTERRUPTED, assistant.status)
        assertEquals("Начало ответа", assistant.text)
        assertFalse(vm.state.value.isGenerating)
    }

    @Test
    fun `stop before the first token is interrupted rather than failed`() {
        val engine = FakeEngine().apply {
            chunks = emptyList()
        }
        val vm = viewModel(engine)

        vm.onInputChange("Длинный рассказ")
        vm.send()
        vm.stopGeneration()
        engine.active?.complete()

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
        engine.finish() // fires onDone with ERROR (failure is set)

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
        engine.finish() // EOG with empty text -> FAILED

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
            chunks = listOf("обрывок")
        }
        val vm = viewModel(engine)

        vm.onInputChange("Первый вопрос")
        vm.send()
        vm.stopGeneration()
        engine.active?.complete()
        assertEquals(MessageStatus.INTERRUPTED, vm.state.value.messages.last().status)

        engine.chunks = listOf("Второй ответ")
        vm.onInputChange("Второй вопрос")
        vm.send()
        engine.finish()

        assertEquals("system", engine.lastMessages.first().role)
        assertTrue(engine.lastMessages.none { it.role == "assistant" && it.content == "обрывок" })
    }

    @Test
    fun `new chat clears messages but keeps the model`() {
        val engine = FakeEngine()
        val persistence = MemoryPersistence()
        val vm = ChatViewModel(engine, FakeGateway(), persistence)
        vm.onInputChange("Привет")
        vm.send()
        engine.finish()
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

    // --- Phase 2: adaptive budget + termination mapping -------------------------

    @Test
    fun `adaptive budget respects cap and floor`() {
        // available = 2048-32-8 = 2008 >= cap 1024 -> cap wins
        assertEquals(1024, ChatViewModel.computeMaxTokens(2048, 32))
        // available = 2048-1024-8 = 1016 < cap 1024 -> available wins
        assertEquals(1016, ChatViewModel.computeMaxTokens(2048, 1024))
        // 2048 - 1900 - 8 = 140
        assertEquals(140, ChatViewModel.computeMaxTokens(2048, 1900))
        // clamped to the minimum output floor
        assertEquals(8, ChatViewModel.computeMaxTokens(2048, 2040))
        // custom cap
        assertEquals(500, ChatViewModel.computeMaxTokens(2048, 10, cap = 500))
    }

    @Test
    fun `max tokens requested is the adaptive budget`() {
        val engine = FakeEngine() // promptTokens = 32 -> min(1024, 2048-32-8=2008) = 1024
        val vm = viewModel(engine)

        vm.onInputChange("Привет")
        vm.send()
        engine.finish()

        assertEquals(1024, engine.lastMaxTokens)
    }

    // A fake that reports BUDGET termination (output budget exhausted mid-answer) so the
    // ViewModel maps it to TRUNCATED, never COMPLETE.
    private class BudgetFakeEngine : LlmEngine {
        override val state = MutableStateFlow<EngineState>(EngineState.Ready("fake", 2048))
        var termination: GenerationTermination = GenerationTermination.BUDGET
        var errorMessage: String? = null
        var answer: String = "Частичный ответ"
        var promptTokens = 32

        override suspend fun load(modelPath: String): Result<Unit> = Result.success(Unit)
        override suspend fun unload() = Unit

        override fun generate(
            messages: List<PromptMessage>,
            params: GenerationParams,
            onChunk: (String) -> Unit,
            onDone: (GenerationResult) -> Unit,
            assistantPrefix: String,
        ): Job {
            val gen = FakeGeneration(emptyList(), null, onChunk, onDone)
            if (answer.isNotEmpty()) {
                gen.emit(answer)
            }
            gen.fireTermination(termination, answer, errorMessage)
            return gen.handle()
        }

        override fun cancelGeneration() = Unit
        override suspend fun countPromptTokens(
            messages: List<PromptMessage>,
            assistantPrefix: String,
        ): Int = promptTokens
        override suspend fun close() = Unit
    }

    @Test
    fun `budget exhaustion is marked truncated not complete`() {
        val engine = BudgetFakeEngine() // termination = BUDGET by default
        val vm = ChatViewModel(engine, FakeGateway(), MemoryPersistence())

        vm.onInputChange("Расскажи длинно")
        vm.send()

        val assistant = vm.state.value.messages.last()
        assertEquals(MessageStatus.TRUNCATED, assistant.status)
        assertEquals("Частичный ответ", assistant.text)
        assertFalse(vm.state.value.isGenerating)
    }

    @Test
    fun `termination mapping table`() {
        // Mirror of the ViewModel's status derivation, kept in sync on purpose.
        val cases = mapOf(
            GenerationTermination.EOG to MessageStatus.COMPLETE,
            GenerationTermination.BUDGET to MessageStatus.TRUNCATED,
            GenerationTermination.CONTEXT to MessageStatus.TRUNCATED,
        )
        for ((termination, expected) in cases) {
            assertEquals(expected, deriveStatusFor(termination, "непусто"))
        }
        assertEquals(MessageStatus.INTERRUPTED, deriveStatusFor(GenerationTermination.CANCELLED, "обрывок"))
        assertEquals(MessageStatus.FAILED, deriveStatusFor(GenerationTermination.ERROR, "x"))
        assertEquals(MessageStatus.FAILED, deriveStatusFor(GenerationTermination.EOG, ""))
    }

    @Test
    fun `context exhaustion with no answer fails and surfaces the error`() {
        val engine = BudgetFakeEngine().apply {
            termination = GenerationTermination.CONTEXT
            answer = ""
            errorMessage = "История не помещается в контекст модели"
        }
        val vm = ChatViewModel(engine, FakeGateway(), MemoryPersistence())

        vm.onInputChange("Очень длинная история")
        vm.send()

        val assistant = vm.state.value.messages.last()
        assertEquals(MessageStatus.FAILED, assistant.status)
        assertEquals("История не помещается в контекст модели", vm.state.value.error)
        assertFalse(vm.state.value.isGenerating)
    }

    @Test
    fun `repeated send after a completed response keeps working`() {
        val engine = FakeEngine().apply { chunks = listOf("Первый ответ") }
        val vm = viewModel(engine)

        vm.onInputChange("Первый вопрос")
        vm.send()
        engine.finish()
        assertEquals(MessageStatus.COMPLETE, vm.state.value.messages.last().status)

        engine.chunks = listOf("Второй ответ")
        vm.onInputChange("Второй вопрос")
        vm.send()
        engine.finish()

        val messages = vm.state.value.messages
        assertEquals(2, messages.count { it.role == Role.USER })
        assertEquals("Второй ответ", messages.last().text)
        assertEquals(MessageStatus.COMPLETE, messages.last().status)
        assertFalse(vm.state.value.isGenerating)
    }

    @Test
    fun `repeated send after cancellation keeps working`() {
        val engine = FakeEngine().apply { chunks = listOf("обрывок") }
        val vm = viewModel(engine)

        vm.onInputChange("Первый вопрос")
        vm.send()
        vm.stopGeneration()
        engine.active?.complete()
        assertEquals(MessageStatus.INTERRUPTED, vm.state.value.messages.last().status)

        engine.chunks = listOf("Второй ответ")
        vm.onInputChange("Второй вопрос")
        vm.send()
        engine.finish()

        assertEquals("Второй ответ", vm.state.value.messages.last().text)
        assertEquals(MessageStatus.COMPLETE, vm.state.value.messages.last().status)
        assertFalse(vm.state.value.isGenerating)
    }

    @Test
    fun `repeated send after truncation keeps working`() {
        val engine = FakeEngine().apply { chunks = listOf("Частичный ответ") }
        val vm = viewModel(engine)

        vm.onInputChange("Первый вопрос")
        vm.send()
        engine.finishWith(GenerationTermination.BUDGET)
        assertEquals(MessageStatus.TRUNCATED, vm.state.value.messages.last().status)

        engine.chunks = listOf("Полный второй ответ")
        vm.onInputChange("Второй вопрос")
        vm.send()
        engine.finish()

        assertEquals("Полный второй ответ", vm.state.value.messages.last().text)
        assertEquals(MessageStatus.COMPLETE, vm.state.value.messages.last().status)
        assertFalse(vm.state.value.isGenerating)
    }

    @Test
    fun `repeated send after a failed response keeps working`() {
        val engine = FakeEngine().apply { chunks = listOf("до ошибки") }
        val vm = viewModel(engine)

        vm.onInputChange("Первый вопрос")
        vm.send()
        engine.finishWith(GenerationTermination.ERROR, "сбой инференса")
        assertEquals(MessageStatus.FAILED, vm.state.value.messages.last().status)

        engine.chunks = listOf("Второй ответ")
        vm.onInputChange("Второй вопрос")
        vm.send()
        engine.finish()

        assertEquals("Второй ответ", vm.state.value.messages.last().text)
        assertEquals(MessageStatus.COMPLETE, vm.state.value.messages.last().status)
        assertFalse(vm.state.value.isGenerating)
    }

    @Test
    fun `is continuable only for a non-empty truncated assistant message`() {
        val assistant = ChatMessage(1, Role.ASSISTANT, "часть", MessageStatus.TRUNCATED)
        assertTrue(assistant.isContinuable)
        assertFalse(assistant.copy(status = MessageStatus.COMPLETE).isContinuable)
        assertFalse(assistant.copy(status = MessageStatus.INTERRUPTED).isContinuable)
        assertFalse(assistant.copy(status = MessageStatus.FAILED).isContinuable)
        assertFalse(assistant.copy(status = MessageStatus.STREAMING).isContinuable)
        assertFalse(assistant.copy(text = "   ").isContinuable)
        assertFalse(assistant.copy(role = Role.USER).isContinuable)
    }

    private fun truncatedViewModel(engine: FakeEngine): ChatViewModel {
        val vm = viewModel(engine)
        vm.onInputChange("Вопрос")
        vm.send()
        engine.finishWith(GenerationTermination.BUDGET)
        assertEquals(MessageStatus.TRUNCATED, vm.state.value.messages.last().status)
        return vm
    }

    @Test
    fun `continuation appends to the same message and sends the prefix separately`() {
        val engine = FakeEngine().apply { chunks = listOf("Частичный ответ") }
        val vm = truncatedViewModel(engine)
        val assistantId = vm.state.value.messages.last().id

        engine.chunks = listOf(" и продолжение")
        vm.continueGeneration(assistantId)
        engine.finish()

        val messages = vm.state.value.messages
        assertEquals(2, messages.size)
        assertEquals(1, messages.count { it.role == Role.USER })
        assertEquals("Частичный ответ и продолжение", messages.last().text)
        assertEquals(MessageStatus.COMPLETE, messages.last().status)
        assertEquals("Частичный ответ", engine.lastAssistantPrefix)
        assertTrue(engine.lastMessages.none { it.role == "assistant" })
        assertFalse(vm.state.value.isGenerating)
        assertNull(vm.state.value.continuationMessageId)
    }

    @Test
    fun `continuation that exhausts the budget stays truncated`() {
        val engine = FakeEngine().apply { chunks = listOf("Часть") }
        val vm = truncatedViewModel(engine)
        val assistantId = vm.state.value.messages.last().id

        engine.chunks = listOf(" ещё")
        vm.continueGeneration(assistantId)
        engine.finishWith(GenerationTermination.BUDGET)

        val assistant = vm.state.value.messages.last()
        assertEquals(MessageStatus.TRUNCATED, assistant.status)
        assertEquals("Часть ещё", assistant.text)
        assertTrue(assistant.isContinuable)
        assertFalse(vm.state.value.isGenerating)
    }

    @Test
    fun `continuation cancellation is reported as interrupted`() {
        val engine = FakeEngine().apply { chunks = listOf("Часть") }
        val vm = truncatedViewModel(engine)
        val assistantId = vm.state.value.messages.last().id

        engine.chunks = listOf(" ещё немного")
        vm.continueGeneration(assistantId)
        vm.stopGeneration()

        val assistant = vm.state.value.messages.last()
        assertEquals(MessageStatus.INTERRUPTED, assistant.status)
        assertFalse(assistant.isContinuable)
        assertFalse(vm.state.value.isGenerating)
        assertNull(vm.state.value.continuationMessageId)
    }

    @Test
    fun `continuation budget calculation keeps the adaptive formula`() {
        val engine = FakeEngine().apply { chunks = listOf("Часть") }
        val vm = truncatedViewModel(engine)
        val assistantId = vm.state.value.messages.last().id

        engine.chunks = listOf(" ещё")
        engine.promptTokens = 1000
        vm.continueGeneration(assistantId)
        engine.finish()

        assertEquals(1024, engine.lastMaxTokens)
    }

    @Test
    fun `continuation of a long prefix still fits and uses the remaining budget`() {
        val engine = FakeEngine().apply { chunks = listOf("Часть") }
        val vm = truncatedViewModel(engine)
        val assistantId = vm.state.value.messages.last().id

        // 1200-token prompt + the full 1024-token CAP reservation would not fit in 2048,
        // but a continuation only needs a minimal answer, so it must still proceed.
        engine.chunks = listOf(" ещё")
        engine.promptTokens = 1200
        vm.continueGeneration(assistantId)
        engine.finish()

        assertEquals(2048 - 1200 - 8, engine.lastMaxTokens)
        assertEquals(MessageStatus.COMPLETE, vm.state.value.messages.last().status)
        assertEquals("Часть ещё", vm.state.value.messages.last().text)
    }

    @Test
    fun `continuation without room in the context keeps the message truncated`() {
        val engine = FakeEngine().apply { chunks = listOf("Часть") }
        val vm = truncatedViewModel(engine)
        val assistantId = vm.state.value.messages.last().id

        engine.promptTokens = 100_000
        vm.continueGeneration(assistantId)

        val assistant = vm.state.value.messages.last()
        assertEquals(MessageStatus.TRUNCATED, assistant.status)
        assertEquals("Недостаточно контекста для продолжения ответа", vm.state.value.error)
        assertFalse(vm.state.value.isGenerating)
        assertNull(vm.state.value.continuationMessageId)
    }

    @Test
    fun `continuation context exhaustion keeps truncated and surfaces the error`() {
        val engine = FakeEngine().apply { chunks = listOf("Часть") }
        val vm = truncatedViewModel(engine)
        val assistantId = vm.state.value.messages.last().id

        engine.chunks = emptyList()
        vm.continueGeneration(assistantId)
        engine.finishWith(GenerationTermination.CONTEXT, "История не помещается в контекст модели")

        val assistant = vm.state.value.messages.last()
        assertEquals(MessageStatus.TRUNCATED, assistant.status)
        assertEquals("История не помещается в контекст модели", vm.state.value.error)
        assertFalse(vm.state.value.isGenerating)
    }

    private fun deriveStatusFor(termination: GenerationTermination, text: String): MessageStatus =
        when {
            termination == GenerationTermination.CANCELLED -> MessageStatus.INTERRUPTED
            termination == GenerationTermination.BUDGET ||
                termination == GenerationTermination.CONTEXT ->
                if (text.isBlank()) MessageStatus.FAILED else MessageStatus.TRUNCATED
            termination == GenerationTermination.ERROR -> MessageStatus.FAILED
            else -> if (text.isBlank()) MessageStatus.FAILED else MessageStatus.COMPLETE
        }
}
