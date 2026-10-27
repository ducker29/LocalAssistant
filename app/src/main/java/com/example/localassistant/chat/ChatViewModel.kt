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
import com.example.localassistant.calendar.CalendarToolRequest
import com.example.localassistant.calendar.CalendarAssistantDebug
import com.example.localassistant.calendar.CalendarToolResult
import com.example.localassistant.calendar.CalendarViewModel
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
    /** Assistant message currently being continued, if any. */
    val continuationMessageId: Long? = null,
    /** A calendar write awaiting user confirmation, if any. */
    val pendingCalendarRequest: CalendarToolRequest? = null,
) {
    val canSend: Boolean
        get() = input.isNotBlank() && model is ModelStatus.Ready && !isGenerating
}

class ChatViewModel(
    private val engine: LlmEngine,
    private val repository: ModelGateway,
    private val conversationStore: ConversationPersistence,
    private val calendar: CalendarViewModel? = null,
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
                continuationMessageId = null,
                pendingCalendarRequest = null,
            )
        }
        persist()

        generationJob = viewModelScope.launch {
            val startedAt = System.nanoTime()
            fun elapsedMs() = (System.nanoTime() - startedAt) / 1_000_000
            val nativeContext = (snapshot.model as ModelStatus.Ready).contextTokens

            // ---- Planner pass (optional, only when a calendar facade is attached) ----
            val calId = java.util.UUID.randomUUID().toString().take(8)
            CalendarAssistantDebug.log(calId, "planner entered: calendarAttached=${calendar != null}")
            val plannerOutcome = runPlanner(text, calId)
            if (plannerOutcome is PlannerOutcome.ToolCall) {
                val req = plannerOutcome.request
                CalendarAssistantDebug.log(calId, "dispatch: ${CalendarAssistantDebug.parameters(req)}")
                val result = runCalendarTool(req, calId)
                CalendarAssistantDebug.log(calId, "final tool_result=${result::class.simpleName}, returned_to_model=false, destination=UI")
                when (result) {
                    is CalendarToolResult.NeedConfirmation -> {
                        // Wait for the user to confirm/cancel; the gate lives in the executor.
                        finishMessage(assistantId, MessageStatus.COMPLETE,
                            "Подтвердите действие в диалоге ниже.")
                        _state.update { st -> st.copy(isGenerating = false) }
                        persist()
                        return@launch
                    }
                    is CalendarToolResult.NotConnected -> {
                        finishMessage(assistantId, MessageStatus.COMPLETE,
                            result.message)
                        _state.update { st -> st.copy(isGenerating = false) }
                        persist()
                        return@launch
                    }
                    is CalendarToolResult.Ambiguous -> {
                        val list = result.candidates.joinToString("\n") { "• " + it.summary(
                            java.time.ZonedDateTime.now()) }
                        finishMessage(assistantId, MessageStatus.COMPLETE,
                            "Несколько подходящих событий:\n$list\n\nУточни, какое именно.")
                        _state.update { st -> st.copy(isGenerating = false) }
                        persist()
                        return@launch
                    }
                    is CalendarToolResult.Ok -> {
                        finishMessage(assistantId, MessageStatus.COMPLETE, result.statusText)
                        _state.update { st -> st.copy(isGenerating = false) }
                        persist()
                        return@launch
                    }
                    is CalendarToolResult.Error -> {
                        finishMessage(assistantId, MessageStatus.COMPLETE, result.message)
                        _state.update { st -> st.copy(isGenerating = false) }
                        persist()
                        return@launch
                    }
                }
            }
            // No tool call (or NONE): fall through to the normal streaming answer below.
            CalendarAssistantDebug.log(calId, "tool_result=None, returned_to_model=false, destination=normal_chat")

            // Fit history into the window, reserving room for at least a minimal answer.

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
                CalendarAssistantDebug.log(calId, "final outcome=normal_chat_context_rejected, tool_result=None, returned_to_model=false")
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
            CalendarAssistantDebug.log(calId, "final outcome=normal_chat, status=$finalStatus, tool_result=None, returned_to_model=false")
            _state.update { it.copy(isGenerating = false) }
            Log.i(
                TAG,
                "turn finished in ${elapsedMs()} ms, chars=${textOut.length}, " +
                    "termination=${termination.name}, status=$finalStatus",
            )
            persist()
        }
    }

    /**
     * Continues a truncated assistant message in place: no new user/assistant pair is added.
     * The already produced text is sent to the model as the assistant prefix, so the answer
     * resumes exactly where the output budget stopped it.
     */
    fun continueGeneration(messageId: Long) {
        val snapshot = _state.value
        if (snapshot.isGenerating) return
        val target = snapshot.messages.firstOrNull { it.id == messageId } ?: return
        if (!target.isContinuable) return
        if (snapshot.model !is ModelStatus.Ready) {
            _state.update { it.copy(error = "Сначала подготовьте модель") }
            return
        }

        val prefix = target.text
        val turns = buildTurns(snapshot.messages.filter { it.id < messageId })
        if (turns.isEmpty()) {
            _state.update { it.copy(error = "Недостаточно контекста для продолжения ответа") }
            return
        }
        cancelRequested = false
        _state.update {
            it.copy(
                isGenerating = true,
                continuationMessageId = messageId,
                error = null,
            )
        }
        persist()

        generationJob = viewModelScope.launch {
            val nativeContext = (snapshot.model as ModelStatus.Ready).contextTokens

            // The planner counts the prefix as part of the prompt, so the window is fitted
            // against the real continuation prompt. Oldest complete turns are dropped first.
            // Unlike a fresh send, continuation only has to leave room for a minimal answer:
            // the actual output budget is derived afterwards by computeMaxTokens, which caps
            // it at whatever the window still allows. Reserving the full CAP_TOKENS here
            // would reject continuations whose already-generated prefix is long.
            val planner = ContextPlanner(
                maxContextTokens = nativeContext,
                reservedOutputTokens = MIN_OUTPUT_TOKENS,
            )
            val planned = try {
                planner.plan(SYSTEM_PROMPT, turns) { messages ->
                    engine.countPromptTokens(messages, prefix)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "continuation planning failed: ${t.message}")
                null
            }
            if (planned == null || planned.messages.isEmpty()) {
                _state.update {
                    it.copy(
                        isGenerating = false,
                        continuationMessageId = null,
                        error = "Недостаточно контекста для продолжения ответа",
                    )
                }
                return@launch
            }

            val promptTokens = engine.countPromptTokens(planned.messages, prefix)
            val maxTokens = computeMaxTokens(nativeContext, promptTokens, CAP_TOKENS, SAFETY_MARGIN)
            Log.i(
                TAG,
                "continuation budget: n_ctx=$nativeContext, prompt=$promptTokens " +
                    "tokens (prefix=${prefix.length} chars), max_tokens=$maxTokens",
            )

            val filter = ReasoningFilter()
            val answer = StringBuilder(prefix)
            var termination: GenerationTermination = GenerationTermination.CANCELLED
            var errorMessage: String? = null
            fun noteVisible(text: String) {
                if (text.isEmpty()) return
                answer.append(text)
                updateMessageText(messageId, answer.toString())
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
                assistantPrefix = prefix,
            )
            generationJob = genJob

            try {
                done.join()
            } catch (t: Throwable) {
                termination = GenerationTermination.ERROR
                errorMessage = t.message ?: "Ошибка генерации"
            }

            val textOut = answer.toString()
            val finalStatus = when (termination) {
                GenerationTermination.CANCELLED -> MessageStatus.INTERRUPTED

                GenerationTermination.BUDGET -> MessageStatus.TRUNCATED

                GenerationTermination.CONTEXT -> {
                    _state.update {
                        it.copy(error = errorMessage ?: "Недостаточно контекста для продолжения ответа")
                    }
                    MessageStatus.TRUNCATED
                }

                GenerationTermination.ERROR -> {
                    _state.update { it.copy(error = errorMessage ?: "Ошибка генерации") }
                    MessageStatus.FAILED
                }

                GenerationTermination.EOG -> MessageStatus.COMPLETE
            }
            finishMessage(messageId, finalStatus, textOut)
            _state.update {
                it.copy(isGenerating = false, continuationMessageId = null)
            }
            Log.i(TAG, "continuation finished, termination=${termination.name}, status=$finalStatus")
            persist()
        }
    }

    fun stopGeneration() {
        if (!_state.value.isGenerating) return
        cancelRequested = true
        calendar?.cancelPending()
        calendar?.selectTarget(null)
        engine.cancelGeneration()
    }

    fun newChat() {
        engine.cancelGeneration()
        generationJob?.cancel()
        calendar?.clearToolContext()
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
                    continuationMessageId = null,
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

    /** Result of the planner pass. */
    sealed interface PlannerOutcome {
        data object None : PlannerOutcome
        data class ToolCall(val request: CalendarToolRequest) : PlannerOutcome
    }

    /**
     * Runs the short non-streaming planner call. Returns [PlannerOutcome.ToolCall] when the model
     * produced a valid calendar tool call, otherwise [PlannerOutcome.None].
     */
    private suspend fun runPlanner(userText: String, calId: String): PlannerOutcome {
        val cal = calendar
        if (cal == null) {
            CalendarAssistantDebug.log(calId, "planner skipped: calendar_not_attached")
            return PlannerOutcome.None
        }
        val connectionState = cal.connectionState.value
        CalendarAssistantDebug.log(calId, "planner state=${connectionState::class.simpleName}, connection_state_in_prompt=true")
        val raw = try {
            runPlannerGeneration(userText, calId, connectionState)
        } catch (t: Throwable) {
            CalendarAssistantDebug.log(calId, "planner failure category=${t::class.simpleName}")
            return PlannerOutcome.None
        }
        CalendarAssistantDebug.log(calId, "planner raw length=${raw.length}, redacted=${CalendarAssistantDebug.redactedRaw(raw)}")
        val parsed = com.example.localassistant.calendar.CalendarToolParser.parse(raw)
        return when (parsed) {
            is com.example.localassistant.calendar.CalendarToolParser.ParseOutcome.Ok -> {
                CalendarAssistantDebug.log(calId, "parser OK: ${CalendarAssistantDebug.parameters(parsed.request)}")
                PlannerOutcome.ToolCall(parsed.request)
            }
            is com.example.localassistant.calendar.CalendarToolParser.ParseOutcome.ToolParseError -> {
                CalendarAssistantDebug.log(calId, "parser REJECTED: reason=${CalendarAssistantDebug.rejection(parsed.reason)}")
                PlannerOutcome.None
            }
        }
    }

    /** Single non-streaming generation; collects the full text. */
    private suspend fun runPlannerGeneration(
        userText: String,
        calId: String,
        connectionState: com.example.localassistant.calendar.CalendarConnectionState,
    ): String {
        val system = com.example.localassistant.calendar.CalendarToolSpec.plannerSystemPrompt(
            connectionState,
            java.time.ZonedDateTime.now(),
        )
        val history = _state.value.messages.dropLast(2)
        // Free-form assistant refusals are not capability facts for the tool router.
        // Preserve recent user references/explicit IDs; ordinary chat history is unchanged.
        val recent = history.filter { it.role == Role.USER }.takeLast(3)
        // Consecutive user turns made the small model repeat an older request. Keep the
        // current task unambiguous in one user turn; history is explicitly reference data.
        val plannerInput = buildString {
            if (recent.isNotEmpty()) {
                append("ИСТОРИЯ (не выполнять, только для ссылок):\n")
                recent.forEachIndexed { index, message ->
                    append(index + 1).append(") ").append(message.text).append('\n')
                }
            }
            append("ТЕКУЩИЙ ЗАПРОС (выбери инструмент только для него):\n").append(userText)
        }
        val messages = listOf(
            PromptMessage("system", system),
            PromptMessage("user", plannerInput),
        )
        CalendarAssistantDebug.log(calId, "planner input=state_and_date_system+labeled_history_and_current_request, connection_state_in_prompt=true")
        val sb = StringBuilder()
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        val params = GenerationParams(maxTokens = 256, temperature = 0.0f)
        engine.generate(
            messages = messages,
            params = params,
            onChunk = { chunk -> sb.append(chunk) },
            onDone = { result ->
                CalendarAssistantDebug.log(calId, "planner generation termination=${result.termination}")
                done.complete(Unit)
            },
        )
        try { done.join() } catch (t: Throwable) { throw t }
        return sb.toString()
    }

    private suspend fun runCalendarTool(request: CalendarToolRequest, calId: String): CalendarToolResult {
        val cal = calendar ?: return CalendarToolResult.Error("Календарь не настроен")
        return kotlinx.coroutines.withContext(CalendarAssistantDebug.Trace(calId)) {
            cal.run(request)
        }
    }

    /** Confirm the pending calendar write (from the UI dialog). */
    fun confirmCalendarPending() {
        calendar?.confirmPending()
    }

    fun cancelCalendarPending() {
        calendar?.cancelPending()
    }

    fun dismissCalendarSettingsPrompt() {
        calendar?.clearSettingsPrompt()
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
        private val calendar: CalendarViewModel? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(engine, repository, conversationStore, calendar) as T
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
