package com.example.execution.wear.cache

import com.example.execution.wear.protocol.WearActivityDto
import com.example.execution.wear.protocol.WearStateDto
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Fase 12 test list: caching on the watch.
 *  - fresh state cached and shown
 *  - offline -> cached copy shown with staleness marker
 *  - empty cache offline -> placeholder (null)
 *  - older revision does not overwrite cache
 *  - cache survives disconnect/reconnect cycle
 */
class WearStateCacheTest {

    private val T0 = 1_700_000_000_000L
    private lateinit var cache: InMemoryWearStateCache
    private var now: Long = T0
    private lateinit var provider: WatchStateProvider

    private fun state(revision: Long, label: String? = "Deep Work") = WearStateDto(
        revision = revision,
        currentActivity = label?.let { WearActivityDto("deep_work", it) },
        currentStateStartedAtEpochMs = T0
    )

    @BeforeTest
    fun setup() {
        cache = InMemoryWearStateCache()
        now = T0
        provider = WatchStateProvider(cache, nowEpochMs = { now })
    }

    @Test
    fun `fresh state cached and shown`() = runTest {
        val d = provider.onIncoming(state(1))
        assertFalse(d.fromCache)
        assertEquals("Deep Work", d.state?.currentActivity?.label)
        assertEquals(0L, d.staleSeconds)

        val reloaded = provider.current()
        assertEquals("Deep Work", reloaded.state?.currentActivity?.label)
        assertTrue(reloaded.fromCache)
    }

    @Test
    fun `offline shows cached copy with staleness`() = runTest {
        provider.onIncoming(state(1))
        now = T0 + 90_000 // 90 seconds later, no packet
        val d = provider.onIncoming(null)
        assertTrue(d.fromCache)
        assertTrue(d.isStale)
        assertEquals(90L, d.staleSeconds)
        assertEquals("Deep Work", d.state?.currentActivity?.label)
    }

    @Test
    fun `fresh packet clears staleness`() = runTest {
        provider.onIncoming(state(1))
        now = T0 + 120_000
        val stale = provider.onIncoming(null)
        assertTrue(stale.isStale)

        now = T0 + 150_000
        val fresh = provider.onIncoming(state(2))
        assertFalse(fresh.fromCache)
        assertFalse(fresh.isStale)
        assertEquals(0L, fresh.staleSeconds)
    }

    @Test
    fun `empty cache offline is placeholder`() = runTest {
        val d = provider.onIncoming(null)
        assertNull(d.state)
        assertFalse(d.fromCache)
    }

    @Test
    fun `older revision does not overwrite cache`() = runTest {
        provider.onIncoming(state(5, "Travel"))
        val rejected = provider.onIncoming(state(4, "Stale"))
        // rejected packet falls back to cache -> still shows Travel
        assertTrue(rejected.fromCache)
        assertEquals("Travel", rejected.state?.currentActivity?.label)
        // and the cache still holds revision 5
        assertEquals(5L, cache.load()?.state?.revision)
    }

    @Test
    fun `cache survives disconnect reconnect cycle`() = runTest {
        provider.onIncoming(state(1))
        // disconnect: several ticks offline
        now = T0 + 30_000
        provider.onIncoming(null)
        now = T0 + 60_000
        val offline = provider.onIncoming(null)
        assertTrue(offline.fromCache)
        // reconnect with new revision
        now = T0 + 90_000
        val fresh = provider.onIncoming(state(3))
        assertFalse(fresh.fromCache)
        assertEquals(3L, fresh.state?.revision)
    }

    @Test
    fun `clear empties cache`() = runTest {
        provider.onIncoming(state(1))
        cache.clear()
        assertNull(provider.current().state)
    }
}
