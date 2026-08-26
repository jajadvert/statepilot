package com.example.execution.calendar

import kotlinx.datetime.Instant

data class CalendarEventDto(
    val uid: String,
    val calendarId: String,
    val title: String,
    val start: Instant,
    val end: Instant,
    val location: String? = null,
    val allDay: Boolean = false,
    val revision: String? = null
)

/** Dependency inversion (§10.1): Android implementation lives in the app module. */
interface CalendarSource {
    suspend fun getEvents(from: Instant, to: Instant): List<CalendarEventDto>
}
