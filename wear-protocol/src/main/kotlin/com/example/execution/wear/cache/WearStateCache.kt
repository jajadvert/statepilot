package com.example.execution.wear.cache

import com.example.execution.wear.protocol.WearStateDto
import com.example.execution.wear.protocol.WearStateMerger

/**
 * Fase 12: Wear state-caching.
 * Pure interface so the cache logic is JVM-testable; Android implementation
 * (DataStore) lives in app-wear as an adapter.
 */
interface WearStateCache {
    suspend fun save(state: WearStateDto, savedAtEpochMs: Long)
    suspend fun load(): CachedWearState?
    suspend fun clear()
}

/** Cache entry: the last accepted state plus when it was stored. */
data class CachedWearState(
    val state: WearStateDto,
    val savedAtEpochMs: Long
)

/** In-memory test double. */
class InMemoryWearStateCache : WearStateCache {
    private var stored: CachedWearState? = null

    override suspend fun save(state: WearStateDto, savedAtEpochMs: Long) {
        stored = CachedWearState(state, savedAtEpochMs)
    }

    override suspend fun load(): CachedWearState? = stored
    override suspend fun clear() { stored = null }
}

/** What the watch UI should render right now. */
data class WatchDisplayState(
    val state: WearStateDto?,
    val fromCache: Boolean,
    /** Seconds since the cached copy was last refreshed; 0 when fresh. */
    val staleSeconds: Long
) {
    val isStale: Boolean get() = fromCache && staleSeconds > 30
}

/**
 * Pure logic that decides what the watch shows, combining live transport
 * state with the on-device cache (Fase 12, offline behaviour):
 *  - fresh packet arrives  -> accept (revision merger), cache it, show fresh
 *  - no packet (offline)   -> show cached copy with staleness marker
 *  - nothing cached        -> null state (UI shows placeholder)
 */
class WatchStateProvider(
    private val cache: WearStateCache,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val merger: WearStateMerger = WearStateMerger()
) {
    suspend fun onIncoming(state: WearStateDto?): WatchDisplayState {
        if (state != null && merger.accept(state)) {
            cache.save(state, nowEpochMs())
            return WatchDisplayState(state, fromCache = false, staleSeconds = 0)
        }
        return fromCache()
    }

    suspend fun current(): WatchDisplayState = fromCache()

    private suspend fun fromCache(): WatchDisplayState {
        val cached = cache.load() ?: return WatchDisplayState(null, fromCache = false, staleSeconds = 0)
        return WatchDisplayState(
            state = cached.state,
            fromCache = true,
            staleSeconds = ((nowEpochMs() - cached.savedAtEpochMs) / 1000).coerceAtLeast(0)
        )
    }
}
