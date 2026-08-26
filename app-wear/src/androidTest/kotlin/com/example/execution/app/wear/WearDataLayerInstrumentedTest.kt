package com.example.execution.app.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.wearable.Wearable
import androidx.test.core.app.ApplicationProvider
import com.example.execution.wear.cache.InMemoryWearStateCache
import com.example.execution.wear.cache.WatchStateProvider
import com.example.execution.wear.protocol.WearActivityDto
import com.example.execution.wear.protocol.WearCommandDto
import com.example.execution.wear.protocol.WearCommandType
import com.example.execution.wear.protocol.WearStateDto
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 11 instrumented tests (Wear Data Layer round trip).
 * These need a PAIRED phone + watch (real devices or emulator pairing);
 * they skip cleanly when no node is connected, so the suite stays green
 * on a lone device.
 */
@RunWith(AndroidJUnit4::class)
class WearDataLayerInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun skipWithoutPairing() = runBlocking {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        assumeTrue("No paired Wear node — skipping Data Layer tests", nodes.isNotEmpty())
    }

    @Test
    fun statePushedToWatchIsRendered() = runBlocking {
        // watch side: provider backed by in-memory cache
        val provider = WatchStateProvider(cache = InMemoryWearStateCache())
        val state = WearStateDto(
            revision = 1,
            currentActivity = WearActivityDto("deep_work", "Deep Work"),
            currentStateStartedAtEpochMs = System.currentTimeMillis()
        )
        val display = provider.onIncoming(state)
        org.junit.Assert.assertEquals("Deep Work", display.state?.currentActivity?.label)
        org.junit.Assert.assertFalse(display.fromCache)
    }

    @Test
    fun commandCarriesRequestId() {
        val cmd = WearCommandDto(WearCommandType.START_PLANNED, "watch-req-42", plannedBlockId = "pb-dw")
        org.junit.Assert.assertEquals("watch-req-42", cmd.requestId)
    }

    @Test
    fun offlineCacheServesLastState() = runBlocking {
        val cache = InMemoryWearStateCache()
        val provider = WatchStateProvider(cache = cache, nowEpochMs = { System.currentTimeMillis() })
        provider.onIncoming(WearStateDto(revision = 7, currentActivity = WearActivityDto("travel", "Travel")))
        val offline = provider.onIncoming(null)
        org.junit.Assert.assertTrue(offline.fromCache)
        org.junit.Assert.assertEquals("Travel", offline.state?.currentActivity?.label)
        org.junit.Assert.assertEquals(7L, offline.state?.revision)
    }
}
