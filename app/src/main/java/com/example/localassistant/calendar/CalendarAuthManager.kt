package com.example.localassistant.calendar

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

sealed interface CalendarConnectionState {
    /** No integration, or the user explicitly disconnected. Never reconnected automatically. */
    data object Disconnected : CalendarConnectionState

    /** A bounded, non-interactive authorization check is running. */
    data object Checking : CalendarConnectionState

    data class Connected(val email: String?) : CalendarConnectionState

    /** Consent or a fresh authorization is required; the user must press Connect. */
    data class NeedsReconnect(val message: String) : CalendarConnectionState

    /** The connection could not be checked (for example offline). Access is not declared revoked. */
    data class Unavailable(val message: String) : CalendarConnectionState

    data class Error(val message: String) : CalendarConnectionState
}

sealed interface AuthResult {
    data object Ok : AuthResult
    data class NeedsConsent(val pendingIntent: PendingIntent) : AuthResult
    data class Failed(val message: String) : AuthResult
}

/** Result of acquiring a token for a calendar operation. */
sealed interface TokenResult {
    data class Ok(val token: String) : TokenResult
    data class RequiresReconnect(val message: String) : TokenResult
    data class Unavailable(val message: String) : TokenResult
    data object NotEnabled : TokenResult
}

data class DisconnectResult(
    val googleRevoked: Boolean,
    val message: String,
)

/**
 * The narrow authorization surface used by [AuthorizedCalendarGateway]. Keeping it an interface
 * allows the gateway's retry policy to be tested without GMS.
 */
interface CalendarAuthorization {
    suspend fun ensureToken(): TokenResult
    suspend fun invalidateRejectedToken(rejected: String?)
}

/**
 * Single authorization/token acquisition path for Google Calendar.
 *
 * - Interactive sign-in ([connect]) and [handleConsentResult] are the only places that may launch
 *   consent, and only through the Activity passed by the caller.
 * - [ensureToken] is a bounded, non-interactive authorization check used immediately before a
 *   calendar operation. It never launches UI.
 * - [checkConnection] is the same check used when the Calendar settings section is opened.
 * - [invalidateRejectedToken] clears a token rejected by the Calendar API through
 *   `AuthorizationClient.clearToken`, then requires the user to reconnect.
 * - [disconnect] clears app state and best-effort revokes Google-side access.
 *
 * The access token is never logged and no expiration timestamp is invented.
 */
class CalendarAuthManager(
    context: Context,
    private val tokenStore: CalendarTokenStore,
) : CalendarAuthorization {
    private val appContext: Context = context.applicationContext
    private val client: AuthorizationClient = Identity.getAuthorizationClient(appContext)

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<CalendarConnectionState> = _state

    /** Last validated/acquired token. Read by the HTTP gateway for each request. */
    @Volatile
    private var cachedToken: String? =
        if (tokenStore.isEnabled() && !tokenStore.email().isNullOrBlank()) {
            tokenStore.accessToken()
        } else {
            null
        }

    /** Incremented by an explicit disconnect so late authorization callbacks are ignored. */
    private val generation = AtomicLong(0)

    private val authorizationMutex = Mutex()
    private val tokenMutex = Mutex()

    @Volatile
    private var checking = false

    fun currentToken(): String? = cachedToken

    fun isEnabled(): Boolean = tokenStore.isEnabled()

    fun authorizationGeneration(): Long = generation.get()

    /** Email of the account selected by the user; needed to revoke access on disconnect. */
    fun knownAccountEmail(): String? = tokenStore.email()

    /**
     * Builds an [AuthorizationRequest] for the Calendar scopes the implementation needs. Only
     * `calendar.events` is requested: the app writes exclusively to the `primary` calendar and does
     * not enumerate calendar lists.
     *
     * The request is bound to [accountEmail] when known. The Authorization API does not reveal
     * which account it selected, so the account must be chosen explicitly to make
     * [disconnect] able to revoke it.
     */
    fun buildAuthorizationRequest(accountEmail: String? = tokenStore.email()): AuthorizationRequest {
        val builder = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(CalendarScopes.CALENDAR_EVENTS))
        accountEmail?.takeIf { it.isNotBlank() }?.let {
            builder.setAccount(Account(it, GOOGLE_ACCOUNT_TYPE))
        }
        return builder.build()
    }

    /**
     * Explicit user-initiated connection. Always calls `authorize()`; handles both a direct token
     * and a result that needs consent.
     *
     * [accountEmail] comes from the system account picker. The account is bound to the request so
     * consent uses it and [disconnect] can revoke access for exactly that account.
     */
    suspend fun connect(activity: Activity, accountEmail: String?): AuthResult {
        // The user explicitly asked to connect, so clear the user-disconnect marker first.
        tokenStore.setUserDisconnected(false)
        Log.i(TAG, "connect: start account=${if (accountEmail.isNullOrBlank()) "absent" else "present"}")
        val gen = generation.get()
        // Interactive flows use the Activity-scoped client; silent checks use the application one.
        val response = authorizationMutex.withLock {
            authorize(Identity.getAuthorizationClient(activity), accountEmail)
        }
        if (generation.get() != gen) {
            Log.i(TAG, "connect: authorization result discarded after disconnect")
            return AuthResult.Failed("Авторизация отменена.")
        }
        return when (response) {
            is ApiResponse.Success -> handleAuthorizationResult(response.result, accountEmail)
            is ApiResponse.Failure -> failureResult(response)
        }
    }

    /**
     * Called after the consent activity returns. Any result from an attempt that started before an
     * explicit disconnect is rejected by the caller using [authorizationGeneration].
     */
    suspend fun handleConsentResult(
        activity: Activity,
        data: Intent?,
        accountEmail: String?,
    ): AuthResult {
        if (data == null) {
            Log.i(TAG, "consent result has no data (cancelled)")
            return AuthResult.Failed("Авторизация отменена.")
        }
        val result: AuthorizationResult = try {
            Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
        } catch (e: ApiException) {
            Log.w(TAG, "getAuthorizationResultFromIntent ApiException: code=${e.statusCode}")
            return AuthResult.Failed(mapApiException(e))
        } catch (t: Throwable) {
            Log.w(TAG, "getAuthorizationResultFromIntent failed: ${t::class.simpleName}: ${t.message}")
            return AuthResult.Failed("Не удалось обработать результат авторизации.")
        }
        return handleAuthorizationResult(result, accountEmail)
    }

    /**
     * Bounded, non-interactive check used when the Calendar settings section opens. Never launches
     * consent: if consent is required the state becomes [CalendarConnectionState.NeedsReconnect]
     * and the UI offers an explicit action.
     */
    suspend fun checkConnection() {
        if (checking) return
        if (!tokenStore.isEnabled() || tokenStore.isUserDisconnected()) {
            cachedToken = null
            _state.value = CalendarConnectionState.Disconnected
            Log.i(TAG, "checkConnection: skipped (disabled or user-disconnected)")
            return
        }
        if (tokenStore.email().isNullOrBlank()) {
            dropCachedToken()
            _state.value = CalendarConnectionState.NeedsReconnect(
                "Выберите Google-аккаунт заново, чтобы приложение могло отозвать доступ."
            )
            Log.i(TAG, "checkConnection: blocked (account email is unknown)")
            return
        }
        checking = true
        val gen = generation.get()
        _state.value = CalendarConnectionState.Checking
        Log.i(TAG, "checkConnection: start")
        try {
            val response = kotlinx.coroutines.withTimeout(15_000) {
                authorizationMutex.withLock {
                    authorize(accountEmail = tokenStore.email())
                }
            }
            if (generation.get() != gen) return
            restoreStateFromRetainedData()
            when (response) {
                is ApiResponse.Success -> {
                    val result = response.result
                    if (result.hasResolution()) {
                        dropCachedToken()
                        _state.value = CalendarConnectionState.NeedsReconnect(
                            "Требуется повторное подключение к Google Календарю."
                        )
                        return
                    }
                    val token = result.accessToken
                    if (token.isNullOrBlank() || !scopeGranted(result)) {
                        dropCachedToken()
                        _state.value = CalendarConnectionState.NeedsReconnect(
                            "Доступ к календарю не подтверждён. Подключите его заново."
                        )
                        return
                    }
                    persistToken(token, extractEmail(result))
                    _state.value = CalendarConnectionState.Connected(tokenStore.email())
                }

                is ApiResponse.Failure -> {
                    _state.value = if (response.offline) {
                        CalendarConnectionState.Unavailable(
                            "Не удалось проверить подключение: нет сети. Доступ не отозван."
                        )
                    } else {
                        CalendarConnectionState.NeedsReconnect(
                            "Не удалось проверить доступ к календарю. Подключите его заново."
                        )
                    }
                }
            }
        } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "checkConnection: timeout (15s)")
            _state.value = CalendarConnectionState.Unavailable(
                "Не удалось проверить подключение: время ожидания истекло."
            )
        } catch (t: kotlinx.coroutines.CancellationException) {
            Log.i(TAG, "checkConnection: cancelled by user")
            restoreStateFromRetainedData()
        } finally {
            checking = false
        }
    }

    /**
     * User-initiated cancellation of an in-flight [checkConnection]. Does NOT treat the
     * connection as failed; restores the actionable state derived from retained local data.
     */
    fun cancelCheck() {
        generation.incrementAndGet()
        checking = false
        restoreStateFromRetainedData()
    }

    /**
     * State derived purely from retained local data (token, email, disconnect marker).
     * Used both at app startup ([initialState]) and after cancellation/timeout, so the UI is
     * never left stuck in [CalendarConnectionState.Checking].
     */
    private fun restoredStateFromRetainedData(): CalendarConnectionState = when {
        !tokenStore.isEnabled() || tokenStore.isUserDisconnected() ->
            CalendarConnectionState.Disconnected
        tokenStore.accessToken() != null && tokenStore.email().isNullOrBlank() ->
            CalendarConnectionState.NeedsReconnect(
                "Выберите Google-аккаунт заново, чтобы приложение могло отозвать доступ."
            )
        tokenStore.accessToken() != null ->
            CalendarConnectionState.Connected(tokenStore.email())
        else ->
            CalendarConnectionState.NeedsReconnect(
                "Требуется повторное подключение к Google Календарю."
            )
    }

    private fun restoreStateFromRetainedData() {
        _state.value = restoredStateFromRetainedData()
    }

    /**
     * Acquires a token immediately before a calendar operation. Uses the cached token when present;
     * otherwise runs a single non-interactive `authorize()` call. Never launches consent and never
     * authorizes after an explicit disconnect.
     */
    override suspend fun ensureToken(): TokenResult {
        if (!tokenStore.isEnabled() || tokenStore.isUserDisconnected()) {
            return TokenResult.NotEnabled
        }
        if (tokenStore.email().isNullOrBlank()) {
            dropCachedToken()
            _state.value = CalendarConnectionState.NeedsReconnect(
                "Выберите Google-аккаунт заново, чтобы приложение могло отозвать доступ."
            )
            return TokenResult.RequiresReconnect(
                "Выберите Google-аккаунт заново, чтобы приложение могло отозвать доступ."
            )
        }
        cachedToken?.let { return TokenResult.Ok(it) }

        val gen = generation.get()
        val response = authorizationMutex.withLock { authorize(accountEmail = tokenStore.email()) }
        if (generation.get() != gen) {
            return TokenResult.RequiresReconnect("Авторизация отменена.")
        }
        return when (response) {
            is ApiResponse.Success -> {
                val result = response.result
                if (result.hasResolution()) {
                    _state.value = CalendarConnectionState.NeedsReconnect(
                        "Требуется повторное подключение к Google Календарю."
                    )
                    return TokenResult.RequiresReconnect(
                        "Требуется повторное подключение к Google Календарю."
                    )
                }
                val token = result.accessToken
                if (token.isNullOrBlank() || !scopeGranted(result)) {
                    _state.value = CalendarConnectionState.NeedsReconnect(
                        "Доступ к календарю не подтверждён. Подключите его заново."
                    )
                    return TokenResult.RequiresReconnect(
                        "Доступ к календарю не подтверждён. Подключите его заново."
                    )
                }
                persistToken(token, extractEmail(result))
                _state.value = CalendarConnectionState.Connected(tokenStore.email())
                TokenResult.Ok(token)
            }

            is ApiResponse.Failure -> {
                if (response.offline) {
                    _state.value = CalendarConnectionState.Unavailable(
                        "Нет сети. Не удалось проверить доступ к календарю."
                    )
                    TokenResult.Unavailable("Нет сети. Не удалось проверить доступ к календарю.")
                } else {
                    _state.value = CalendarConnectionState.NeedsReconnect(
                        "Требуется повторное подключение к Google Календарю."
                    )
                    TokenResult.RequiresReconnect(
                        "Требуется повторное подключение к Google Календарю."
                    )
                }
            }
        }
    }

    /**
     * Invalidates a token rejected with HTTP 401: clears Google's local cache for this token and the
     * app-side copy, then requires the user to reconnect.
     */
    override suspend fun invalidateRejectedToken(rejected: String?) {
        tokenMutex.withLock {
            val token = rejected ?: cachedToken
            cachedToken = null
            tokenStore.removeToken()
            if (!token.isNullOrBlank()) {
                try {
                    client.clearToken(ClearTokenRequest.builder().setToken(token).build())
                        .awaitResult()
                        .onFailure { Log.w(TAG, "clearToken failed: ${it::class.simpleName}") }
                } catch (t: Throwable) {
                    Log.w(TAG, "clearToken threw: ${t::class.simpleName}")
                }
            }
        }
        _state.value = if (tokenStore.isUserDisconnected()) {
            CalendarConnectionState.Disconnected
        } else {
            CalendarConnectionState.NeedsReconnect(
                "Сессия календаря истекла. Подключите календарь заново."
            )
        }
    }

    private fun dropCachedToken() {
        cachedToken = null
        tokenStore.removeToken()
    }

    /**
     * Explicit disconnect from this application. Does not delete events or remove the Google
     * account from the device. Clears app state immediately, then best-effort clears Google's local
     * token cache and revokes the application's access.
     */
    suspend fun disconnect(): DisconnectResult {
        val previousToken = cachedToken ?: tokenStore.accessToken()
        val email = tokenStore.email()
        generation.incrementAndGet()
        cachedToken = null
        tokenStore.clear()
        _state.value = CalendarConnectionState.Disconnected

        var cleared = false
        if (!previousToken.isNullOrBlank()) {
            cleared = try {
                client.clearToken(ClearTokenRequest.builder().setToken(previousToken).build())
                    .awaitResult()
                    .isSuccess
            } catch (t: Throwable) {
                Log.w(TAG, "clearToken on disconnect failed: ${t::class.simpleName}")
                false
            }
        }

        var revoked = false
        if (!email.isNullOrBlank()) {
            revoked = try {
                client.revokeAccess(
                    RevokeAccessRequest.builder()
                        .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
                        .setScopes(listOf(CalendarScopes.CALENDAR_EVENTS))
                        .build()
                ).awaitResult().isSuccess
            } catch (t: Throwable) {
                Log.w(TAG, "revokeAccess failed: ${t::class.simpleName}: ${t.message}")
                false
            }
        }

        val message = when {
            revoked -> "Календарь отключён. Доступ приложения к Google-аккаунту отозван."
            cleared -> "Календарь отключён на этом устройстве."
            else -> "Календарь отключён в приложении. Не удалось подтвердить отзыв доступа на стороне Google."
        }
        Log.i(TAG, "disconnect: tokenCleared=$cleared, googleRevoked=$revoked")
        return DisconnectResult(googleRevoked = revoked, message = message)
    }

    private suspend fun authorize(
        authClient: AuthorizationClient = client,
        accountEmail: String? = tokenStore.email(),
    ): ApiResponse {
        val task: Task<AuthorizationResult> = try {
            authClient.authorize(buildAuthorizationRequest(accountEmail))
        } catch (t: Throwable) {
            return ApiResponse.Failure(t, offline = isOffline(t))
        }
        val outcome = task.awaitResult()
        return outcome.fold(
            onSuccess = { ApiResponse.Success(it) },
            onFailure = { ApiResponse.Failure(it, offline = isOffline(it)) },
        )
    }

    private fun handleAuthorizationResult(
        result: AuthorizationResult,
        accountEmail: String?,
    ): AuthResult {
        Log.i(
            TAG,
            "authorize result: resolution=${result.hasResolution()}, " +
                "token=${if (result.accessToken.isNullOrBlank()) "absent" else "present"}, " +
                "account=${if (accountEmail.isNullOrBlank()) "absent" else "present"}"
        )
        if (result.hasResolution()) {
            val pendingIntent = result.pendingIntent
                ?: return AuthResult.Failed("Не удалось получить разрешение на доступ к календарю.")
            return AuthResult.NeedsConsent(pendingIntent)
        }
        val accessToken = result.accessToken
        if (accessToken.isNullOrBlank()) {
            _state.value = CalendarConnectionState.NeedsReconnect(
                "Авторизация отклонена. Доступ к календарю не выдан."
            )
            return AuthResult.Failed("Доступ к календарю не выдан. Повторите попытку.")
        }
        if (!scopeGranted(result)) {
            _state.value = CalendarConnectionState.NeedsReconnect(
                "Необходимый доступ к календарю не выдан."
            )
            return AuthResult.Failed("Необходимый доступ к календарю не выдан. Повторите попытку.")
        }
        persistToken(accessToken, extractEmail(result) ?: accountEmail)
        _state.value = CalendarConnectionState.Connected(tokenStore.email())
        Log.i(TAG, "calendar authorized (email=${if (tokenStore.email() != null) "present" else "unavailable"})")
        return AuthResult.Ok
    }

    /**
     * `grantedScopes` may be absent on some direct results. Treat `null` as "not reported" rather
     * than as a denial, but reject an explicitly reported list that lacks the required scope.
     */
    private fun scopeGranted(result: AuthorizationResult): Boolean {
        val granted = result.grantedScopes ?: return true
        return CalendarScopes.CALENDAR_EVENTS.scopeUri in granted
    }

    private fun persistToken(accessToken: String, email: String?) {
        cachedToken = accessToken
        tokenStore.saveToken(accessToken, email)
    }

    private fun extractEmail(result: AuthorizationResult): String? = try {
        result.toGoogleSignInAccount()?.email
    } catch (t: Throwable) {
        Log.w(TAG, "could not extract account email; continuing without it")
        null
    }

    private fun failureResult(response: ApiResponse.Failure): AuthResult {
        val message = if (response.offline) {
            "Нет сети. Не удалось проверить доступ к календарю."
        } else {
            "Не удалось авторизоваться в Google Календаре: " +
                (response.cause.message ?: "неизвестная ошибка")
        }
        _state.value = if (response.offline) {
            CalendarConnectionState.Unavailable(message)
        } else {
            CalendarConnectionState.Error(message)
        }
        return AuthResult.Failed(message)
    }

    private fun initialState(): CalendarConnectionState = restoredStateFromRetainedData()

    private fun isOffline(t: Throwable): Boolean {
        if (t is ApiException) {
            return t.statusCode == CommonStatusCodes.NETWORK_ERROR ||
                t.statusCode == CommonStatusCodes.TIMEOUT
        }
        return t is java.io.IOException && t !is ApiException
    }

    private fun mapApiException(e: ApiException): String = when (e.statusCode) {
        CommonStatusCodes.CANCELED -> "Авторизация отменена."
        CommonStatusCodes.DEVELOPER_ERROR ->
            "Неверный запрос авторизации. Обратитесь к разработчику."
        CommonStatusCodes.NETWORK_ERROR, CommonStatusCodes.TIMEOUT ->
            "Нет сети. Не удалось завершить авторизацию."
        else -> "Ошибка авторизации (код ${e.statusCode}). Попробуйте ещё раз."
    }

    private sealed interface ApiResponse {
        data class Success(val result: AuthorizationResult) : ApiResponse
        data class Failure(val cause: Throwable, val offline: Boolean) : ApiResponse
    }

    private companion object {
        const val TAG = "CalendarAuthManager"
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }
}

private suspend fun <T> Task<T>.awaitResult(): Result<T> =
    suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            if (!cont.isActive) return@addOnCompleteListener
            try {
                cont.resume(Result.success(task.result))
            } catch (t: Throwable) {
                cont.resume(Result.failure(t))
            }
        }
    }
