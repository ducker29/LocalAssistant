package com.example.localassistant.chat

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.localassistant.llm.GenerationParams
import com.example.localassistant.llm.GenerationTermination
import com.example.localassistant.llm.LlamaEngine
import com.example.localassistant.llm.LlmEngine
import com.example.localassistant.llm.PromptMessage
import com.example.localassistant.llm.ReasoningFilter
import com.example.localassistant.model.ModelStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val model: ModelStatus = ModelStatus.Absent,
    val isGenerating: Boolean = false,
    val droppedTurns: Int = 0,
    val error: String? = null,
) {
    val canSend: Boolean
        get() = input.isNotBlank() && model is ModelStatus.Ready && !isGenerating
}

class ChatViewModel(
    private val engine: LlmEngine,
    private val repository: ModelGateway,
    private val conversationStore: ConversationPersistence,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var generationJob: Job? = null
    private var cancelRequested = false
    private var nextId = 1L

    init {
        viewModelScope.launch {
            val loaded = conversationStore.load()
            if (loaded.isNotEmpty()) {
                nextId = (loaded.maxOfOrNull { it.id } ?: 0L) + 1L
                _state.update { it.copy(messages = loaded) }
            }
        }
        viewModelScope.launch {
            repository.status.collect { status ->
                _state.update { it.copy(model = status) }
            }
        }
        repository.initialize()
    }

    fun onInputChange(value: String) {
        _state.update { it.copy(input = value) }
    }

    /**
     * Adds a recognised utterance to the draft without sending anything. Existing text is
     * preserved; the new utterance starts on its own line.
     */
    fun appendDraft(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        _state.update { current ->
            val existing = current.input.trimEnd()
            val merged = if (existing.isEmpty()) clean else existing + "\n" + clean
            current.copy(input = merged)
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun send() {
        val snapshot = _state.value
        val text = snapshot.input.trim()
        if (text.isEmpty()) return
        if (snapshot.isGenerating) return
        if (snapshot.model !is ModelStatus.Ready) {
            _state.update { it.copy(error = "Сначала подготовьте модель") }
            return
        }

        val userMessage = ChatMessage(id = nextId++, role = Role.USER, text = text)
        val assistantId = nextId++
        val placeholder = ChatMessage(
            id = assistantId,
            role = Role.ASSISTANT,
            text = "",
            status = MessageStatus.STREAMING,
        )
        cancelRequested = false
        _state.update {
            it.copy(
                messages = it.messages + userMessage + placeholder,
                input = "",
                isGenerating = true,
                droppedTurns = 0,
                error = null,
            )
        }
        persist()

        generationJob = viewModelScope.launch {
            val startedAt = System.nanoTime()
            fun elapsedMs() = (System.nanoTime() - startedAt) / 1_000_000
            val nativeContext = (snapshot.model as ModelStatus.Ready).contextTokens

            // Fit history into the window, reserving room for at least a minimal answer.
            // The output is capped at CAP_TOKENS, so reserving that here guarantees the
            // planned prompt leaves room for the full budget (the planner's 8-token margin
            // keeps the safety margin intact).
            val planner = ContextPlanner(
                maxContextTokens = nativeContext,
                reservedOutputTokens = CAP_TOKENS,
            )
            val history = _state.value.messages.dropLast(1)
            val turns = buildTurns(history)
            val planned = try {
                planner.plan(SYSTEM_PROMPT, turns, engine::countPromptTokens)
            } catch (t: Throwable) {
                Log.w(TAG, "planning failed: ${t.message}")
                null
            }
            if (planned == null) {
                finishMessage(
                    assistantId,
                    MessageStatus.FAILED,
                    "Сообщение не помещается в контекст модели. Очистите историю.",
                )
                _state.update { it.copy(error = "Недостаточно контекста для этого запроса") }
                _state.update { it.copy(isGenerating = false) }
                persist()
                return@launch
            }
            _state.update { it.copy(droppedTurns = planned.droppedTurns) }

            // Adaptive output budget: never ask for more than the window can actually return.
            val promptTokens = engine.countPromptTokens(planned.messages)
            val maxTokens = computeMaxTokens(nativeContext, promptTokens, CAP_TOKENS, SAFETY_MARGIN)
            Log.i(
                TAG,
                "adaptive budget: n_ctx=$nativeContext, prompt=${promptTokens} " +
                    "tokens, max_tokens=$maxTokens (cap=$CAP_TOKENS, margin=$SAFETY_MARGIN)",
            )

            val filter = ReasoningFilter()
            val answer = StringBuilder()
            var termination: GenerationTermination = GenerationTermination.CANCELLED
            var errorMessage: String? = null
            var firstVisibleLogged = false
            fun noteVisible(text: String) {
                if (text.isEmpty()) return
                answer.append(text)
                if (!firstVisibleLogged) {
                    firstVisibleLogged = true
                    Log.i(TAG, "first visible token after ${elapsedMs()} ms")
                }
                updateMessageText(assistantId, answer.toString())
            }

            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
            val genJob = engine.generate(
                planned.messages,
                GenerationParams(maxTokens = maxTokens),
                onChunk = { chunk -> noteVisible(filter.append(chunk)) },
                onDone = { result ->
                    noteVisible(filter.finish())
                    termination = result.termination
                    errorMessage = result.errorMessage
                    done.complete(Unit)
                },
            )
            generationJob = genJob

            try {
                done.join()
            } catch (t: Throwable) {
                termination = GenerationTermination.ERROR
                errorMessage = t.message ?: "Ошибка генерации"
            }

            val textOut = answer.toString()
            val finalStatus = when {
                termination == GenerationTermination.CANCELLED -> MessageStatus.INTERRUPTED

                termination == GenerationTermination.BUDGET ||
                    termination == GenerationTermination.CONTEXT ->
                    if (textOut.isBlank()) {
                        _state.update {
                            it.copy(error = errorMessage ?: "Недостаточно контекста для ответа")
                        }
                        MessageStatus.FAILED
                    } else {
                        MessageStatus.TRUNCATED
                    }

                termination == GenerationTermination.ERROR -> {
                    _state.update { it.copy(error = errorMessage ?: "Ошибка генерации") }
                    MessageStatus.FAILED
                }

                else -> { // EOG
                    if (textOut.isBlank()) {
                        _state.update { it.copy(error = "Модель вернула пустой ответ") }
                        MessageStatus.FAILED
                    } else {
                        MessageStatus.COMPLETE
                    }
                }
            }
            finishMessage(assistantId, finalStatus, textOut)
            _state.update { it.copy(isGenerating = false) }
            Log.i(
                TAG,
                "turn finished in ${elapsedMs()} ms, chars=${textOut.length}, " +
                    "termination=${termination.name}, status=$finalStatus",
            )
            persist()
        }
    }

    fun stopGeneration() {
        if (!_state.value.isGenerating) return
        cancelRequested = true
        engine.cancelGeneration()
    }

    fun newChat() {
        engine.cancelGeneration()
        generationJob = null
        viewModelScope.launch {
            conversationStore.clear()
            _state.update {
                it.copy(
                    messages = emptyList(),
                    input = "",
                    isGenerating = false,
                    droppedTurns = 0,
                    error = null,
                )
            }
        }
    }

    fun downloadModel() = repository.startDownload()

    fun cancelDownload() = repository.cancelDownload()

    fun importModel(uri: Uri) = repository.importModel(uri)

    fun deleteModel() = repository.deleteModel()

    fun retryModelLoad() = repository.retryLoad()

    private fun updateMessageText(id: Long, text: String) {
        _state.update { current ->
            current.copy(
                messages = current.messages.map { message ->
                    if (message.id == id) message.copy(text = text) else message
                }
            )
        }
    }

    private fun finishMessage(id: Long, status: MessageStatus, text: String) {
        _state.update { current ->
            current.copy(
                messages = current.messages.map { message ->
                    when {
                        message.id != id -> message
                        text.isBlank() -> message.copy(status = status)
                        else -> message.copy(status = status, text = text)
                    }
                }
            )
        }
    }

    private fun persist() {
        val snapshot = _state.value.messages
        viewModelScope.launch { conversationStore.save(snapshot) }
    }

    private fun buildTurns(messages: List<ChatMessage>): List<List<PromptMessage>> {
        val turns = ArrayList<MutableList<PromptMessage>>()
        for (message in messages) {
            if (message.text.isBlank()) continue
            if (message.status != MessageStatus.COMPLETE) continue
            val prompt = PromptMessage(message.role.wireName, message.text)
            val current = turns.lastOrNull()
            if (current == null || message.role == Role.USER) {
                turns.add(mutableListOf(prompt))
            } else {
                current.add(prompt)
            }
        }
        return turns
    }

    class Factory(
        private val engine: LlmEngine,
        private val repository: ModelGateway,
        private val conversationStore: ConversationPersistence,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(engine, repository, conversationStore) as T
        }
    }

    companion object {
        private const val TAG = "ChatViewModel"
        private const val SYSTEM_PROMPT =
            "Ты — локальный ассистент на смартфоне. Отвечай ясно, по существу и на языке " +
                "пользователя. Если не знаешь ответа — скажи об этом прямо."

        /** Hard cap on the output budget in tokens (spec: min(1024, available)). */
        const val CAP_TOKENS = 1024
        /** Tokens reserved inside the window for the safety margin. */
        const val SAFETY_MARGIN = 8

        /**
         * Adaptive output budget: [effectiveMaxTokens] = min(cap, nativeContext - promptTokens - margin),
         * clamped to [MIN_OUTPUT_TOKENS]..[cap].
         */
        fun computeMaxTokens(
            nativeContext: Int,
            promptTokens: Int,
            cap: Int = CAP_TOKENS,
            margin: Int = SAFETY_MARGIN,
        ): Int {
            val available = nativeContext - promptTokens - margin
            return minOf(cap, available).coerceIn(MIN_OUTPUT_TOKENS, cap)
        }

        private const val MIN_OUTPUT_TOKENS = 8
    }
}
