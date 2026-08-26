package com.example.execution.app.phone

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.execution.domain.interruption.InterruptionCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.interruptDataStore by preferencesDataStore(name = "interrupt_settings")

/**
 * Editable configuration of an interrupt reason: display label + visibility.
 * `id` is always the enum name so the StateEngine contract stays unchanged.
 */
@Serializable
data class InterruptCategoryConfig(
    val id: String,
    val label: String,
    val enabled: Boolean = true
)

/**
 * Persists the user-editable interrupt reasons (DataStore, JSON).
 * Defaults: all categories, english lowercase labels, enabled.
 */
class InterruptCategorySettings(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    val configs: Flow<List<InterruptCategoryConfig>> = context.interruptDataStore.data
        .map { prefs ->
            val raw = prefs[KEY_CONFIGS]
            if (raw == null) defaultConfigs()
            else runCatching { json.decodeFromString(ListSerializer(InterruptCategoryConfig.serializer()), raw) }
                .getOrElse { defaultConfigs() }
        }

    suspend fun getConfigs(): List<InterruptCategoryConfig> =
        context.interruptDataStore.data.first()[KEY_CONFIGS]
            ?.let { raw -> runCatching { json.decodeFromString(ListSerializer(InterruptCategoryConfig.serializer()), raw) }.getOrNull() }
            ?: defaultConfigs()

    suspend fun save(configs: List<InterruptCategoryConfig>) {
        context.interruptDataStore.edit { prefs ->
            prefs[KEY_CONFIGS] = json.encodeToString(ListSerializer(InterruptCategoryConfig.serializer()), configs)
        }
    }

    /** Reset to defaults. */
    suspend fun reset() {
        context.interruptDataStore.edit { it.remove(KEY_CONFIGS) }
    }

    private fun defaultConfigs(): List<InterruptCategoryConfig> =
        InterruptionCategory.entries.map { c ->
            InterruptCategoryConfig(
                id = c.name,
                label = c.name.lowercase().replace('_', ' '),
                enabled = true
            )
        }

    private companion object {
        val KEY_CONFIGS = stringPreferencesKey("interrupt_categories_json")
    }
}
