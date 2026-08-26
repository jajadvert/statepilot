package com.example.execution.app.phone

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.calendarDataStore by preferencesDataStore(name = "calendar_settings")

/**
 * Persists the linked calendar id (DataStore). Pure settings I/O.
 */
class CalendarSettings(private val context: Context) {

    val linkedCalendarId: Flow<String?> = context.calendarDataStore.data
        .map { it[KEY_CALENDAR_ID] }

    suspend fun getLinkedCalendarId(): String? =
        context.calendarDataStore.data.first()[KEY_CALENDAR_ID]

    suspend fun setLinkedCalendarId(id: String?) {
        context.calendarDataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_CALENDAR_ID) else prefs[KEY_CALENDAR_ID] = id
        }
    }

    private companion object {
        val KEY_CALENDAR_ID = stringPreferencesKey("linked_calendar_id")
    }
}
