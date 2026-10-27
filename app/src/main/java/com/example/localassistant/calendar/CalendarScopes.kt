package com.example.localassistant.calendar

import com.google.android.gms.common.api.Scope

/**
 * OAuth scopes required by the Calendar integration.
 *
 * Only [CALENDAR_EVENTS] is requested. The app writes exclusively to the `primary` calendar
 * and does not enumerate calendar lists, so `calendarlist.readonly` is not needed.
 */
object CalendarScopes {
    val CALENDAR_EVENTS: Scope = Scope("https://www.googleapis.com/auth/calendar.events")
}
