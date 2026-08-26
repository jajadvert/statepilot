package com.example.execution.app.phone

import android.content.Context
import android.provider.CalendarContract
import com.example.execution.calendar.CalendarEventDto
import com.example.execution.calendar.CalendarSource
import kotlinx.datetime.Instant

/** A calendar the user can link (from CalendarContract). */
data class CalendarInfo(
    val id: String,
    val displayName: String,
    val accountName: String?
)

/**
 * Reads events from the Android calendar via CalendarContract.
 * Requires READ_CALENDAR permission (checked before calling).
 */
class AndroidCalendarSource(
    private val context: Context,
    private val calendarIdProvider: () -> String?
) : CalendarSource {

    override suspend fun getEvents(from: Instant, to: Instant): List<CalendarEventDto> {
        val calendarId = calendarIdProvider() ?: return emptyList()
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.DIRTY
        )
        val selection = "${CalendarContract.Events.CALENDAR_ID} = ? AND " +
            "${CalendarContract.Events.DTSTART} < ? AND ${CalendarContract.Events.DTEND} > ?"
        val args = arrayOf(
            calendarId,
            to.toEpochMilliseconds().toString(),
            from.toEpochMilliseconds().toString()
        )

        val events = mutableListOf<CalendarEventDto>()
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI, projection, selection, args, null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(CalendarContract.Events._ID)
            val titleIdx = cursor.getColumnIndex(CalendarContract.Events.TITLE)
            val startIdx = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
            val endIdx = cursor.getColumnIndex(CalendarContract.Events.DTEND)
            val locIdx = cursor.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
            val calIdx = cursor.getColumnIndex(CalendarContract.Events.CALENDAR_ID)
            val allDayIdx = cursor.getColumnIndex(CalendarContract.Events.ALL_DAY)
            val dirtyIdx = cursor.getColumnIndex(CalendarContract.Events.DIRTY)

            while (cursor.moveToNext()) {
                val uid = cursor.getString(idIdx) ?: continue
                val start = cursor.getLong(startIdx)
                val end = cursor.getLong(endIdx)
                if (end <= start) continue
                events += CalendarEventDto(
                    uid = uid,
                    calendarId = cursor.getString(calIdx) ?: calendarId,
                    title = cursor.getString(titleIdx) ?: "(untitled)",
                    start = Instant.fromEpochMilliseconds(start),
                    end = Instant.fromEpochMilliseconds(end),
                    location = cursor.getString(locIdx),
                    allDay = cursor.getInt(allDayIdx) == 1,
                    revision = cursor.getString(dirtyIdx)?.let { "dirty=$it" }
                )
            }
        }
        return events
    }

    companion object {
        /** List calendars visible to the app (needs READ_CALENDAR). */
        fun listCalendars(context: Context): List<CalendarInfo> {
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME
            )
            val result = mutableListOf<CalendarInfo>()
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection, null, null, null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                val nameIdx = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val accountIdx = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
                while (cursor.moveToNext()) {
                    result += CalendarInfo(
                        id = cursor.getString(idIdx) ?: continue,
                        displayName = cursor.getString(nameIdx) ?: "?",
                        accountName = cursor.getString(accountIdx)
                    )
                }
            }
            return result
        }
    }
}
