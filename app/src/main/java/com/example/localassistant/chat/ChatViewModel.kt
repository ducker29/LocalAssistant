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
import com.example.localassistant.calendar.CalendarTurnCommand
import com.example.localassistant.calendar.CalendarTurnPlan
import com.example.localassistant.calendar.CalendarTurnTransition
import com.example.localassistant.calendar.CalendarAssistantDebug
import com.example.localassistant.calendar.CalendarToolResult
import com.example.localassistant.calendar.CalendarViewModel
import com.example.localassistant.llm.ReasoningFilter
import com.example.localassistant.model.ModelStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    val documentSearchEnabled: Boolean = false,
) {
    val canSend: Boolean
        get() = input.isNotBlank() && model is ModelStatus.Ready && !isGenerating
}

class ChatViewModel(
    private val engine: LlmEngine,
    private val repository: ModelGateway,
    private val conversationStore: ConversationPersistence,
    private val calendar: CalendarViewModel? = null,
    private val documents: DocumentChatGateway? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var generationJob: Job? = null
    private var cancelRequested = false
    private var documentRequestId: Long? = null
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

    fun setDocumentSearchEnabled(enabled: Boolean) {
        if (_state.value.isGenerating) return
        _state.update { it.copy(documentSearchEnabled = enabled) }
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
        if (snapshot.documentSearchEnabled && documents == null) {
            _state.update { it.copy(error = "Поиск по документам недоступен") }
            return
        }

        val userMessage = ChatMessage(id = nextId++, role = Role.USER, text = text, documentRelated = snapshot.documentSearchEnabled)
        val assistantId = nextId++
        val placeholder = ChatMessage(
            id = assistantId,
            role = Role.ASSISTANT,
            text = "",
            status = MessageStatus.STREAMING,
            documentRelated = snapshot.documentSearchEnabled,
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
            )
        }
        persist()

        if (snapshot.documentSearchEnabled) documentRequestId = assistantId
        generationJob = viewModelScope.launch {
            // OFF must never call a router, inspect an index or access document services.
            if (snapshot.documentSearchEnabled) {
                runDocumentReply(text, snapshot.messages, userMessage.id, assistantId)
                return@launch
            }
            currentCoroutineContext().ensureActive()
            val startedAt = System.nanoTime()
            fun elapsedMs() = (System.nanoTime() - startedAt) / 1_000_000
            val nativeContext = (snapshot.model as ModelStatus.Ready).contextTokens

            // ---- Planner pass (optional, only when a calendar facade is attached) ----
            val calId = java.util.UUID.randomUUID().toString().take(8)
            CalendarAssistantDebug.log(calId, "planner entered: calendarAttached=${calendar != null}")
            val plannerOutcome = runPlanner(text, calId)
            if (plannerOutcome is PlannerOutcome.Cancelled) {
                markCalendarTurn(userMessage.id, assistantId)
                finishMessage(assistantId, MessageStatus.INTERRUPTED, "Остановлено")
                _state.update { it.copy(isGenerating = false) }
                persist()
                return@launch
            }
            if (plannerOutcome is PlannerOutcome.Failed) {
                markCalendarTurn(userMessage.id, assistantId)
                finishMessage(assistantId, MessageStatus.COMPLETE, plannerOutcome.message)
                _state.update { it.copy(isGenerating = false) }
                persist()
                return@launch
            }
            if (plannerOutcome is PlannerOutcome.ToolCall) {
                markCalendarTurn(userMessage.id, assistantId)
                val command = plannerOutcome.command
                CalendarAssistantDebug.log(calId, "dispatch: object_kind=${command.plan.objectKind}, app_operation=${command.plan.operation}, transition=${command.plan.transition}, fields=${command.patch.values.keys.map { it.wire }}")
                val result = runCalendarTool(command, calId)
                CalendarAssistantDebug.log(calId, "final tool_result=${result::class.simpleName}, returned_to_model=false, destination=UI")
                val response = when (result) {
                    is CalendarToolResult.Ok -> result.statusText
                    is CalendarToolResult.Clarification -> result.message
                    is CalendarToolResult.Error -> result.message
                    is CalendarToolResult.NotConnected -> result.message
                }
                finishMessage(assistantId, if (cancelRequested) MessageStatus.INTERRUPTED else MessageStatus.COMPLETE, response)
                _state.update { it.copy(isGenerating = false) }
                persist()
                return@launch
            }
            // No tool call (or NONE): fall through to the normal streaming answer below.
            CalendarAssistantDebug.log(calId, "tool_result=None, returned_to_model=false, destination=normal_chat")

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
        if (documentRequestId != null) {
            documents?.cancel()
            engine.cancelGeneration()
            generationJob?.cancel()
            return
        }
        calendar?.clearToolContext()
        engine.cancelGeneration()
    }

    fun newChat() {
        if (documentRequestId != null) documents?.cancel()
        documentRequestId = null
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
                    documentSearchEnabled = false,
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

    fun requestFullDocumentAnswer(shortAnswerId: Long) {
        val current = _state.value
        if (!current.documentSearchEnabled || current.isGenerating) return
        if (current.model !is ModelStatus.Ready) { _state.update { it.copy(error = "Сначала подготовьте модель") }; return }
        val original = current.messages.firstOrNull { it.id == shortAnswerId && it.isPrimaryDocumentAnswer } ?: return
        val snapshot = original.documentSnapshot ?: return
        if (current.messages.any { it.fullAnswerFor == shortAnswerId && it.status == MessageStatus.COMPLETE }) return
        val retry = current.messages.lastOrNull { it.fullAnswerFor == shortAnswerId && it.status in setOf(MessageStatus.FAILED, MessageStatus.INTERRUPTED) }
        val id = retry?.id ?: nextId++
        val placeholder = ChatMessage(id, Role.ASSISTANT, "Собираю полный ответ…", MessageStatus.STREAMING,
            documentRelated = true, fullAnswerFor = shortAnswerId)
        _state.update { it.copy(messages = if (retry == null) it.messages + placeholder else it.messages.map { m -> if (m.id == id) placeholder else m },
            isGenerating = true, error = null, continuationMessageId = null) }
        cancelRequested = false
        documentRequestId = id
        persist()
        generationJob = viewModelScope.launch { runDocumentReply(snapshot.question, emptyList(), null, id, snapshot) }
    }

    /** Only explicit document mode reaches here; calendar session is never prepared or mutated. */
    private suspend fun runDocumentReply(text: String, history: List<ChatMessage>, userId: Long?, assistantId: Long, snapshot: DocumentAnswerSnapshot? = null) {
        try {
            val gateway = documents ?: error("Поиск по документам недоступен")
            val progress: (String) -> Unit = { progress ->
                if (documentRequestId == assistantId) updateMessageText(assistantId, progress)
            }
            val reply = if (snapshot == null) gateway.answer(text, history, progress) else gateway.fullAnswer(snapshot, progress)
            currentCoroutineContext().ensureActive()
            if (documentRequestId == assistantId) {
                _state.update { current -> current.copy(messages = current.messages.map { message ->
                    when (message.id) {
                        userId -> message.copy(documentRelated = true)
                        assistantId -> message.copy(text = reply.text, status = MessageStatus.COMPLETE,
                            documentRelated = true, documentSources = reply.sources, documentSnapshot = reply.snapshot)
                        else -> message
                    }
                }) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (documentRequestId == assistantId) finishMessage(assistantId, MessageStatus.INTERRUPTED, "Обращение к документам остановлено.")
            throw e
        } catch (e: Exception) {
            if (documentRequestId == assistantId) finishMessage(assistantId, MessageStatus.FAILED,
                e.message?.take(1000) ?: "Не удалось обратиться к документам. Уточните запрос.")
        } catch (_: LinkageError) {
            if (documentRequestId == assistantId) finishMessage(assistantId, MessageStatus.FAILED, "Native runtime поиска недоступен в этой сборке")
        } finally {
            if (documentRequestId == assistantId) {
                documentRequestId = null
                _state.update { it.copy(isGenerating = false) }
                persist()
            }
        }
    }

    /** Result of the planner pass. */
    private fun markCalendarTurn(userId: Long, assistantId: Long) {
        _state.update { state -> state.copy(messages = state.messages.map { message ->
            if (message.id == userId || message.id == assistantId) message.copy(calendarRelated = true) else message
        }) }
    }

    sealed interface PlannerOutcome {
        data object Cancelled : PlannerOutcome
        data class Failed(val message: String) : PlannerOutcome
        data object None : PlannerOutcome
        data class ToolCall(val command: CalendarTurnCommand) : PlannerOutcome
    }

    /**
     * Runs the short non-streaming planner call. Returns [PlannerOutcome.ToolCall] when the model
     * produced a valid calendar tool call, otherwise [PlannerOutcome.None].
     */
    private suspend fun runPlanner(userText: String, calId: String): PlannerOutcome {
        val cal = calendar
        if (cal == null) {
            if (com.example.localassistant.calendar.CalendarTurnPolicy.hasWriteIntent(userText))
                return PlannerOutcome.Failed("Календарь недоступен. Изменение не выполнено; подключите календарь и уточните событие и действие.")
            CalendarAssistantDebug.log(calId, "planner skipped: calendar_not_attached")
            return PlannerOutcome.None
        }
        val connectionState = cal.connectionState.value
        val contextVersion = cal.contextVersion()
        val turn = cal.prepareTurn(userText)
        CalendarAssistantDebug.log(calId, "turn: object_kind=${turn.objectKind}, app_operation=${turn.operation}, transition=${turn.transition}, task_id=${turn.taskId}, version=${turn.version}")
        when (turn.transition) {
            CalendarTurnTransition.GENERAL -> {
                if (com.example.localassistant.calendar.CalendarTurnPolicy.hasWriteIntent(userText))
                    return PlannerOutcome.Failed("Уточните событие и действие. Изменение календаря ещё не выполнено.")
                // A general chat reply is not cancellation of an unfinished Calendar action.
                // Only an explicit new Calendar action/cancel/new chat/account reset replaces it.
                return PlannerOutcome.None
            }
            CalendarTurnTransition.CANCEL -> {
                cal.clearToolContext()
                return PlannerOutcome.Failed("Текущая Calendar-задача отменена.")
            }
            CalendarTurnTransition.CLARIFY -> {
                if (turn.resetOnClarify) cal.clearToolContext()
                return PlannerOutcome.Failed(turn.message ?: "Уточните действие с календарём.")
            }
            CalendarTurnTransition.NEW_OBJECT -> return PlannerOutcome.Failed(cal.stageSubject(turn))
            else -> Unit
        }
        CalendarAssistantDebug.log(calId, "planner state=${connectionState::class.simpleName}, connection_state_in_prompt=true")
        val raw = try {
            runPlannerGeneration(userText, calId, connectionState, turn)
        } catch (t: kotlinx.coroutines.CancellationException) {
            currentCoroutineContext().ensureActive()
            return PlannerOutcome.Cancelled
        } catch (t: Exception) {
            CalendarAssistantDebug.log(calId, "planner failure category=${t::class.simpleName}, fallback=grounded_app_fields")
            null
        }
        if (cancelRequested) return PlannerOutcome.Cancelled
        if (raw != null) CalendarAssistantDebug.log(calId, "planner raw length=${raw.length}, redacted=${CalendarAssistantDebug.redactedRaw(raw)}")
        if (contextVersion != cal.contextVersion()) {
            return PlannerOutcome.Failed("Calendar-контекст или подключение изменились. Повторите запрос.")
        }
        val proposal = when (val parsed = raw?.let { com.example.localassistant.calendar.CalendarToolParser.parse(it) }) {
            is com.example.localassistant.calendar.CalendarToolParser.ParseOutcome.Ok -> {
                CalendarAssistantDebug.log(calId, "parser OK: field_patch=true, version_matches=${parsed.proposal.taskId == turn.taskId && parsed.proposal.version == turn.version}")
                parsed.proposal
            }
            is com.example.localassistant.calendar.CalendarToolParser.ParseOutcome.ToolParseError -> {
                CalendarAssistantDebug.log(calId, "parser REJECTED: reason=${CalendarAssistantDebug.rejection(parsed.reason)}")
                null
            }
            null -> null
        }
        return PlannerOutcome.ToolCall(cal.commandFor(turn, proposal))
    }

    /** Single non-streaming generation; collects the full text. */
    private suspend fun runPlannerGeneration(
        userText: String,
        calId: String,
        connectionState: com.example.localassistant.calendar.CalendarConnectionState,
        turn: CalendarTurnPlan,
    ): String {
        val system = com.example.localassistant.calendar.CalendarToolSpec.plannerSystemPrompt(
            connectionState,
            java.time.ZonedDateTime.now(),
        )
        val plannerInput = "SESSION (данные приложения):\n${turn.snapshot}\n" +
            "APP_OBJECT_KIND=${turn.objectKind}; APP_OPERATION=${turn.operation}; APP_TRANSITION=${turn.transition}; " +
            "KNOWN_FIELDS=${turn.seed.values.keys.map { it.wire }}\n" +
            "ТЕКУЩАЯ РЕПЛИКА:\n$userText"
        val messages = listOf(
            PromptMessage("system", system),
            PromptMessage("user", plannerInput),
        )
        CalendarAssistantDebug.log(calId, "planner input=state_and_date_system+typed_session+current_request, connection_state_in_prompt=true")
        val nativeContext = (_state.value.model as? ModelStatus.Ready)?.contextTokens ?: 2048
        val promptTokens = engine.countPromptTokens(messages)
        require(promptTokens >= 0 && promptTokens + 256 + 8 <= nativeContext) { "Planner context limit" }
        val sb = StringBuilder()
        val done = kotlinx.coroutines.CompletableDeferred<GenerationTermination>()
        val params = GenerationParams(maxTokens = 256, temperature = 0.0f)
        if (cancelRequested) throw kotlinx.coroutines.CancellationException("Planner cancelled")
        engine.generate(
            messages = messages,
            params = params,
            onChunk = { chunk -> sb.append(chunk) },
            onDone = { result ->
                CalendarAssistantDebug.log(calId, "planner generation termination=${result.termination}")
                done.complete(result.termination)
            },
        )
        val termination = done.await()
        if (termination == GenerationTermination.CANCELLED) throw kotlinx.coroutines.CancellationException("Planner cancelled")
        check(termination == GenerationTermination.EOG) { "Planner did not finish" }
        return sb.toString()
    }

    private suspend fun runCalendarTool(command: CalendarTurnCommand, calId: String): CalendarToolResult {
        val cal = calendar ?: return CalendarToolResult.Error("Календарь не настроен")
        return kotlinx.coroutines.withContext(CalendarAssistantDebug.Trace(calId)) {
            cal.run(command)
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
            if (message.documentRelated || CalendarChatSafety.excludeFromOrdinaryPrompt(message)) continue
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
        private val documents: DocumentChatGateway? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(engine, repository, conversationStore, calendar, documents) as T
        }
    }

    companion object {
        private const val TAG = "ChatViewModel"
        private const val SYSTEM_PROMPT =
            "Ты — локальный ассистент на смартфоне. Отвечай ясно, по существу и на языке " +
                "пользователя. Если не знаешь ответа — скажи об этом прямо. " +
                "В этом режиме ты не изменяешь календарь и не выполняешь внешние действия. " +
                "Не имитируй отчёты о создании, переносе или удалении событий. Если просят такое действие, попроси уточнить запрос."

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
