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
import java.nio.file.Files
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CalendarChatSafetyTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `calendar write without attached executor is clarified and never sent to ordinary Qwen`() {
        val engine = Engine()
        val vm = ChatViewModel(engine, Model(), Memory())
        vm.onInputChange("Проверку оборудования перенеси на 16"); vm.send()
        assertEquals(0, engine.calls)
        assertTrue(vm.state.value.messages.last().text.contains("не выполнено"))
        assertFalse(vm.state.value.messages.last().text.contains("Обновлено"))
        assertTrue(vm.state.value.messages.last().calendarRelated)
    }

    @Test fun `legacy calendar success format is not supplied to ordinary model for imitation`() {
        val report = "Обработано 1 из 1.\n\nОбновлено:\nSYNTHETIC_TOOL_EVENT"
        val engine = Engine()
        val vm = ChatViewModel(engine, Model(), Memory(listOf(ChatMessage(1, Role.ASSISTANT, report))))
        vm.onInputChange("Напиши короткое приветствие"); vm.send()
        assertEquals(1, engine.calls)
        assertFalse(engine.messages.any { it.content.contains("SYNTHETIC_TOOL_EVENT") })
        assertEquals("Обычный ответ", vm.state.value.messages.last().text)
    }

    @Test fun `calendar message metadata survives restart and prevents continuation as generated prose`() = runTest {
        val directory = Files.createTempDirectory("calendar-message-test").toFile()
        try {
            val store = ConversationStore(directory)
            val message = ChatMessage(1, Role.ASSISTANT, "Синтетический результат исполнителя", calendarRelated = true)
            store.save(listOf(message))
            val restored = store.load().single()
            assertEquals(message, restored)
            assertTrue(CalendarChatSafety.excludeFromOrdinaryPrompt(restored))
            assertFalse(restored.copy(status = MessageStatus.TRUNCATED).isContinuable)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `ordinary messages are not classified as calendar reports`() {
        assertFalse(CalendarChatSafety.excludeFromOrdinaryPrompt(ChatMessage(1, Role.ASSISTANT, "Краткое объяснение алгоритма")))
        assertTrue(CalendarChatSafety.excludeFromOrdinaryPrompt(ChatMessage(2, Role.ASSISTANT, "Обработано 1 из 1.\nОбновлено: synthetic")))
    }

    private class Memory(private val initial: List<ChatMessage> = emptyList()) : ConversationPersistence {
        override suspend fun load() = initial
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
    private class Engine : LlmEngine {
        override val state = MutableStateFlow<EngineState>(EngineState.Ready("fake", 2048))
        var calls = 0
        var messages = emptyList<PromptMessage>()
        override suspend fun load(modelPath: String) = Result.success(Unit)
        override suspend fun unload() = Unit
        override suspend fun close() = Unit
        override fun cancelGeneration() = Unit
        override suspend fun countPromptTokens(messages: List<PromptMessage>, assistantPrefix: String) = 20
        override fun generate(messages: List<PromptMessage>, params: GenerationParams, onChunk: (String) -> Unit, onDone: (GenerationResult) -> Unit, assistantPrefix: String): Job {
            calls++; this.messages = messages
            onChunk("Обычный ответ"); onDone(GenerationResult("Обычный ответ", GenerationTermination.EOG))
            return Job().also { it.complete() }
        }
    }
}
