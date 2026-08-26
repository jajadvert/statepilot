package com.example.execution.app.phone

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.test.core.app.ApplicationProvider
import android.content.Context

/**
 * Interrupt-reason settings: defaults, round-trip, enable/disable semantics.
 */
@RunWith(RobolectricTestRunner::class)
class InterruptCategorySettingsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `defaults cover all categories`() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val settings = InterruptCategorySettings(ctx)
        val configs = settings.getConfigs()
        assertEquals(7, configs.size)
        assertTrue(configs.all { it.enabled })
        assertEquals("urgent task", configs.first { it.id == "URGENT_TASK" }.label)
    }

    @Test
    fun `save and reload round trip`() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val settings = InterruptCategorySettings(ctx)
        val custom = settings.getConfigs().map {
            if (it.id == "CALL") it.copy(label = "Phone call", enabled = false) else it
        }
        settings.save(custom)

        val reloaded = settings.getConfigs()
        assertEquals("Phone call", reloaded.first { it.id == "CALL" }.label)
        assertFalse(reloaded.first { it.id == "CALL" }.enabled)
        assertTrue(reloaded.first { it.id == "BREAK" }.enabled)

        settings.reset()
        assertTrue(settings.getConfigs().all { it.enabled })
    }

    @Test
    fun `config serializes to json and back`() {
        val list = listOf(
            InterruptCategoryConfig("CALL", "Phone call", false),
            InterruptCategoryConfig("BREAK", "break", true)
        )
        val raw = json.encodeToString(ListSerializer(InterruptCategoryConfig.serializer()), list)
        val decoded = json.decodeFromString(ListSerializer(InterruptCategoryConfig.serializer()), raw)
        assertEquals(list, decoded)
    }
}
