package com.example.execution.app.wear

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.execution.wear.cache.CachedWearState
import com.example.execution.wear.cache.WearStateCache
import com.example.execution.wear.protocol.WearStateDto
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "wear_state_cache")

/**
 * Fase 12 Android adapter: persists the last accepted WearStateDto via
 * Preferences DataStore so the watch can render it while offline.
 * Pure logic stays in wear-protocol (WatchStateProvider) — this is only I/O.
 */
class DataStoreWearStateCache(private val context: Context) : WearStateCache {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun save(state: WearStateDto, savedAtEpochMs: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_STATE_JSON] = json.encodeToString(WearStateDto.serializer(), state)
            prefs[KEY_SAVED_AT] = savedAtEpochMs
        }
    }

    override suspend fun load(): CachedWearState? {
        val prefs = context.dataStore.data.first()
        val raw = prefs[KEY_STATE_JSON] ?: return null
        return runCatching {
            CachedWearState(
                state = json.decodeFromString(WearStateDto.serializer(), raw),
                savedAtEpochMs = prefs[KEY_SAVED_AT] ?: 0L
            )
        }.getOrNull()
    }

    override suspend fun clear() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_STATE_JSON)
            prefs.remove(KEY_SAVED_AT)
        }
    }

    private companion object {
        val KEY_STATE_JSON = stringPreferencesKey("state_json")
        val KEY_SAVED_AT = longPreferencesKey("saved_at")
    }
}
