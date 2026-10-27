package com.example.localassistant.calendar

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI-facing calendar state. Consent is represented as [ConsentRequest] with a local id so the
 * activity launches it exactly once, even if an equal [PendingIntent] was already consumed.
 */
class CalendarViewModel(
    private val authManager: CalendarAuthManager,
    private val executor: CalendarToolExecutor,
) : ViewModel() {

    data class ConsentRequest(
        val id: Long,
        val pendingIntent: PendingIntent,
        val generation: Long,
        val accountEmail: String?,
    )

    val connectionState: StateFlow<CalendarConnectionState> = authManager.state

    val pendingConfirmation: StateFlow<CalendarToolRequest?> = executor.pendingConfirmation
    val confirmationSummary: StateFlow<String?> = executor.confirmationSummary
    val targetSelection: StateFlow<CalendarTargetSelection?> = executor.targetSelection
    val taskListSelection: StateFlow<CalendarTaskListSelection?> = executor.taskListSelection
    fun selectTaskList(id: String?) = executor.selectTaskList(id)

    fun selectTarget(id: String?) = executor.selectTarget(id)
    fun clearToolContext() = executor.clearContext()
    fun plannerContext(): String = executor.plannerContext()
    fun contextVersion(): Long = executor.contextVersion()
    fun prepareTurn(text: String): CalendarTurnPlan = executor.prepareTurn(text)
    fun commandFor(plan: CalendarTurnPlan, proposal: CalendarModelPatch?): CalendarTurnCommand = executor.commandFor(plan, proposal)
    fun stageSubject(plan: CalendarTurnPlan): String = executor.stageSubject(plan)

    private val _statusNote = MutableStateFlow<String?>(null)
    val statusNote: StateFlow<String?> = _statusNote.asStateFlow()

    private val _calendarSettingsPrompt = MutableStateFlow(false)
    val calendarSettingsPrompt: StateFlow<Boolean> = _calendarSettingsPrompt.asStateFlow()

    private val _pendingConsentIntent = MutableStateFlow<ConsentRequest?>(null)
    val pendingConsentIntent: StateFlow<ConsentRequest?> = _pendingConsentIntent.asStateFlow()

    var isAuthorizing: Boolean by mutableStateOf(false)
        private set

    private var activeConsent: ConsentRequest? = null
    private var consentSequence = 0L
    private var checkJob: Job? = null

    /**
     * Explicit user action. Always calls `authorize()` and handles both a direct token and a
     * consent-required result. A throw can never leave [isAuthorizing] stuck.
     */
    fun connect(activity: Activity, accountEmail: String?) {
        if (isAuthorizing) return
        if (accountEmail.isNullOrBlank()) {
            _statusNote.value = "Не выбран Google-аккаунт."
            return
        }
        isAuthorizing = true
        executor.clearContext()
        Log.i(TAG, "connect requested (account=present)")
        viewModelScope.launch {
            try {
                when (val result = authManager.connect(activity, accountEmail)) {
                    is AuthResult.NeedsConsent -> publishConsent(result.pendingIntent, accountEmail)
                    is AuthResult.Failed -> Log.i(TAG, "connect failed: ${result.message}")
                    AuthResult.Ok -> {
                        _calendarSettingsPrompt.value = false
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "connect threw: ${t::class.simpleName}: ${t.message}")
                _statusNote.value = "Не удалось подключить календарь. Попробуйте ещё раз."
            } finally {
                isAuthorizing = false
            }
        }
    }

    /**
     * Called by the activity after the consent screen returns. Results from an attempt that began
     * before an explicit disconnect are ignored.
     */
    fun onConsentResult(activity: Activity, data: Intent?) {
        val expected = activeConsent ?: run {
            Log.i(TAG, "ignoring consent result without an active request")
            return
        }
        activeConsent = null
        viewModelScope.launch {
            if (authManager.authorizationGeneration() != expected.generation) {
                Log.i(TAG, "ignoring stale consent result after disconnect")
                return@launch
            }
            when (val result = authManager.handleConsentResult(activity, data, expected.accountEmail)) {
                is AuthResult.NeedsConsent ->
                    publishConsent(result.pendingIntent, expected.accountEmail)
                is AuthResult.Failed -> Log.i(TAG, "consent failed: ${result.message}")
                AuthResult.Ok -> {
                    _calendarSettingsPrompt.value = false
                }
            }
        }
    }

    fun consumePendingConsentIntent() {
        _pendingConsentIntent.value = null
    }

    /**
     * Runs the bounded, non-interactive connection check. Invoked only by the explicit
     * "Проверить" button; never launches consent and never runs on recomposition.
     */
    fun checkConnection() {
        if (checkJob?.isActive == true) return
        Log.i(TAG, "explicit check requested")
        checkJob = viewModelScope.launch {
            try {
                authManager.checkConnection()
            } catch (t: Throwable) {
                Log.w(TAG, "connection check failed: ${t::class.simpleName}: ${t.message}")
            } finally {
                checkJob = null
            }
        }
    }

    /**
     * Cancels an in-flight connection check. The state is restored from retained local data
     * (token/email); cancellation is not treated as an authorization failure.
     */
    fun cancelCheck() {
        val job = checkJob
        if (job != null) {
            job.cancel()
            checkJob = null
        }
        authManager.cancelCheck()
    }

    fun disconnect() {
        executor.clearContext()
        activeConsent = null
        _pendingConsentIntent.value = null
        Log.i(TAG, "disconnect requested")
        viewModelScope.launch {
            try {
                val result = authManager.disconnect()
                _statusNote.value = result.message
                _calendarSettingsPrompt.value = false
            } catch (t: Throwable) {
                Log.w(TAG, "disconnect threw: ${t::class.simpleName}: ${t.message}")
                _statusNote.value = "Календарь отключён в приложении."
            }
        }
    }

    /**
     * Acquires a token through the shared path, then executes the request. A missing/invalid
     * authorization never turns into an invented calendar answer.
     */
    suspend fun run(command: CalendarTurnCommand): CalendarToolResult {
        val token = authManager.ensureToken()
        CalendarAssistantDebug.log("facade authorization=${token::class.simpleName}, state=${connectionState.value::class.simpleName}")
        return when (token) {
            is TokenResult.Ok -> {
                _calendarSettingsPrompt.value = false
                executor.executeSuspend(command)
            }

            is TokenResult.RequiresReconnect -> {
                _calendarSettingsPrompt.value = true
                CalendarToolResult.NotConnected(token.message)
            }

            is TokenResult.Unavailable -> {
                CalendarToolResult.Error(token.message)
            }

            TokenResult.NotEnabled -> {
                _calendarSettingsPrompt.value = true
                CalendarToolResult.NotConnected(
                    "Календарь не подключён. Откройте настройки, чтобы войти в Google Календарь."
                )
            }
        }
    }

    fun confirmPending() {
        executor.resolvePending(confirmed = true)
    }

    fun cancelPending() {
        executor.resolvePending(confirmed = false)
    }

    fun clearSettingsPrompt() {
        _calendarSettingsPrompt.value = false
    }

    fun clearStatusNote() {
        _statusNote.value = null
    }

    fun knownAccountEmail(): String? = authManager.knownAccountEmail()

    fun onAccountSelectionCancelled() {
        _statusNote.value = "Выбор аккаунта отменён."
    }

    private fun publishConsent(pendingIntent: PendingIntent, accountEmail: String?) {
        val request = ConsentRequest(
            id = ++consentSequence,
            pendingIntent = pendingIntent,
            generation = authManager.authorizationGeneration(),
            accountEmail = accountEmail,
        )
        activeConsent = request
        _pendingConsentIntent.value = request
    }

    private companion object {
        const val TAG = "CalendarViewModel"
    }
}
