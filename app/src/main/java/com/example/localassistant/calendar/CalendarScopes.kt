package com.example.localassistant.calendar

import com.google.android.gms.common.api.Scope

/**
 * OAuth scopes required by the Calendar integration.
 *
 * Event and native Task access. Existing users must explicitly grant the added Tasks scope.
 */
object CalendarScopes {
    val CALENDAR_EVENTS: Scope = Scope("https://www.googleapis.com/auth/calendar.events")
    val TASKS: Scope = Scope("https://www.googleapis.com/auth/tasks")
    val REQUIRED: List<Scope> = listOf(CALENDAR_EVENTS, TASKS)
}
