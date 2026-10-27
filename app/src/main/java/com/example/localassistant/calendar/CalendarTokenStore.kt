package com.example.localassistant.calendar

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Persists the Google Calendar OAuth access token, the account email and the integration flags in
 * `SharedPreferences`.
 *
 * The stored access token is a cache only: it is validated through `AuthorizationClient.authorize`
 * before it is trusted for long-lived state. No expiration timestamp is invented here because the
 * authorization API does not return one; a rejected token is invalidated through
 * `AuthorizationClient.clearToken` and re-acquired.
 *
 * The token is never logged in full; only a short SHA-256 fingerprint is kept for [peek].
 *
 * Versioning: [CURRENT_VERSION] tracks the schema. When the stored version differs, only the
 * calendar authorization keys are cleared once, forcing re-authorization. Conversations, models
 * and all other app data are untouched.
 */
class CalendarTokenStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("local_calendar_auth", Context.MODE_PRIVATE)

    init {
        migrateIfNeeded()
    }

    /** Stores a freshly acquired token and marks the integration enabled. */
    fun saveToken(accessToken: String, email: String?) {
        val resolvedEmail = email ?: prefs.getString(KEY_EMAIL, null)
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_EMAIL, resolvedEmail)
            .putBoolean(KEY_ENABLED, true)
            .putBoolean(KEY_USER_DISCONNECTED, false)
            .putInt(KEY_VERSION, CURRENT_VERSION)
            .apply()
    }

    /** The cached access token, if any. Callers must validate it before long-lived use. */
    fun accessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun email(): String? = prefs.getString(KEY_EMAIL, null)

    /** True when the user has enabled the integration and has not explicitly disconnected. */
    fun isEnabled(): Boolean =
        prefs.getBoolean(KEY_ENABLED, false) && !prefs.getBoolean(KEY_USER_DISCONNECTED, false)

    /** True after the user pressed "Отключить"; prevents silent re-authorization. */
    fun isUserDisconnected(): Boolean = prefs.getBoolean(KEY_USER_DISCONNECTED, false)

    fun setUserDisconnected(disconnected: Boolean) {
        prefs.edit().putBoolean(KEY_USER_DISCONNECTED, disconnected).apply()
    }

    /**
     * Drops only the cached token (for example after a 401) while keeping the integration enabled
     * so the user can be asked to reconnect.
     */
    fun removeToken() {
        prefs.edit().remove(KEY_ACCESS_TOKEN).apply()
    }

    /**
     * Short fingerprint of the stored token for logging; never the token itself.
     */
    fun peek(): String? {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        return "sha256:${digest.take(8).joinToString("") { "%02x".format(it) }}"
    }

    /**
     * Removes all calendar authorization data. Called on explicit disconnect. The integration is
     * marked as user-disconnected so it is not reconnected automatically.
     */
    fun clear() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .putBoolean(KEY_ENABLED, false)
            .putBoolean(KEY_USER_DISCONNECTED, true)
            .putInt(KEY_VERSION, CURRENT_VERSION)
            .apply()
    }

    private fun migrateIfNeeded() {
        val storedVersion = prefs.getInt(KEY_VERSION, 0)
        if (storedVersion != CURRENT_VERSION) {
            Log.i(TAG, "Token store schema changed ($storedVersion → $CURRENT_VERSION); clearing calendar auth only")
            prefs.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_EMAIL)
                .remove(KEY_EXPIRES_AT)
                .putBoolean(KEY_ENABLED, false)
                .putBoolean(KEY_USER_DISCONNECTED, false)
                .putInt(KEY_VERSION, CURRENT_VERSION)
                .apply()
        }
    }

    private companion object {
        const val TAG = "CalendarTokenStore"
        const val CURRENT_VERSION = 3
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_EMAIL = "email"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_ENABLED = "enabled"
        const val KEY_USER_DISCONNECTED = "user_disconnected"
        const val KEY_VERSION = "schema_version"
    }
}
