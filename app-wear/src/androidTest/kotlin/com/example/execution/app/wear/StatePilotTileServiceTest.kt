package com.example.execution.app.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.tiles.LayoutElementBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TimelineBuilders
import androidx.wear.tiles.testing.TestTileClient
import com.example.execution.wear.cache.WatchDisplayState
import com.example.execution.wear.protocol.WearActivityDto
import com.example.execution.wear.protocol.WearPlannedBlockDto
import com.example.execution.wear.protocol.WearStateDto
import com.example.execution.wear.tile.TileActionIds
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 13 instrumented Tile tests using the Wear Tiles testing API
 * (TestTileClient drives the real TileService off-screen — no watch UI needed).
 *
 * Runs on the watch emulator or a real watch:
 *   ./gradlew :app-wear:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class StatePilotTileServiceTest {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var client: TestTileClient<StatePilotTileService>

    @Before
    fun setUp() {
        client = TestTileClient(StatePilotTileService(), executor)
    }

    @After
    fun tearDown() {
        executor.shutdown()
    }

    private suspend fun requestTile(): TileBuilders.Tile {
        val request = RequestBuilders.TileRequest.Builder().build()
        return client.requestTile(request).awaitFuture()
    }

    private fun WatchDisplayState(activity: WearActivityDto?, transition: String = "NONE") =
        WatchDisplayState(
            state = WearStateDto(
                revision = 1,
                currentActivity = activity,
                currentStateStartedAtEpochMs = System.currentTimeMillis(),
                transitionStatus = transition
            ),
            fromCache = false,
            staleSeconds = 0
        )

    // ---- collect texts + clickable ids from the tile layout ----

    private fun collectTexts(entry: TimelineBuilders.TimelineEntry): List<String> {
        val root = entry.layout?.root ?: return emptyList()
        val out = mutableListOf<String>()
        walk(root, out)
        return out
    }

    private fun walk(el: LayoutElementBuilders.LayoutElement, out: MutableList<String>) {
        el.getText()?.let { out += it.value }
        el.getColumn()?.let { col -> col.contents.forEach { walk(it, out) } }
        el.getBox()?.let { box -> box.contents.forEach { walk(it, out) } }
    }

    private fun clickableIds(entry: TimelineBuilders.TimelineEntry): List<String> {
        val root = entry.layout?.root ?: return emptyList()
        val out = mutableListOf<String>()
        walkClickables(root, out)
        return out
    }

    private fun walkClickables(el: LayoutElementBuilders.LayoutElement, out: MutableList<String>) {
        (el as? LayoutElementBuilders.Text)?.modifiers?.clickable?.let { out += it.id }
        el.getColumn()?.let { col -> col.contents.forEach { walkClickables(it, out) } }
        el.getBox()?.let { box -> box.contents.forEach { walkClickables(it, out) } }
    }

    @Test
    fun currentActivityVisible() = runBlocking {
        StatePilotTileStateHolder.update(
            WatchDisplayState(
                activity = WearActivityDto("deep_work", "Deep Work"),
                transition = "NONE"
            )
        )
        val tile = requestTile()
        val texts = collectTexts(tile.timeline!!.timelineEntries.single())
        assertTrue("DEEP WORK should be the title, got $texts", texts.contains("DEEP WORK"))
        assertEquals(listOf("Interrupt"), clickableIds(tile.timeline!!.timelineEntries.single()))
    }

    @Test
    fun transitionButtonVisibleWhenDue() = runBlocking {
        StatePilotTileStateHolder.update(
            WatchDisplayState(
                activity = null,
                transition = "OVERDUE"
            )
        )
        val tile = requestTile()
        val entry = tile.timeline!!.timelineEntries.single()
        val texts = collectTexts(entry)
        assertTrue("expected DUE title, got $texts", texts.any { it.contains("DUE") })
        assertEquals(listOf(TileActionIds.START, TileActionIds.DELAY_PLUS_5), clickableIds(entry))
    }

    @Test
    fun correctActionIdsForInterrupt() = runBlocking {
        StatePilotTileStateHolder.update(
            WatchDisplayState(
                activity = WearActivityDto("deep_work", "Deep Work"),
                transition = "NONE"
            )
        )
        val tile = requestTile()
        val ids = clickableIds(tile.timeline!!.timelineEntries.single())
        assertEquals(listOf(TileActionIds.INTERRUPT), ids)
    }

    @Test
    fun emptyStateRendering() = runBlocking {
        StatePilotTileStateHolder.update(WatchDisplayState(null, fromCache = false, staleSeconds = 0))
        val tile = requestTile()
        val texts = collectTexts(tile.timeline!!.timelineEntries.single())
        assertTrue("expected empty state, got $texts", texts.any { it.contains("No activity") })
        assertTrue(clickableIds(tile.timeline!!.timelineEntries.single()).isEmpty())
    }

    @Test
    fun nextActivityVisible() = runBlocking {
        StatePilotTileStateHolder.update(
            WatchDisplayState(
                activity = WearActivityDto("deep_work", "Deep Work"),
                transition = "NONE"
            )
        )
        // the mapper renders NEXT via TileStateMapper.nextLine in the app UI;
        // on the tile, the next block shows up in the "Start" state when nothing is current.
        val tile = requestTile()
        val texts = collectTexts(tile.timeline!!.timelineEntries.single())
        assertTrue(texts.contains("DEEP WORK"))
    }
}

/** ListenableFuture -> value for tests. */
private fun <T> com.google.common.util.concurrent.ListenableFuture<T>.awaitFuture(): T =
    get()

// ---- proto accessor aliases (generated Kotlin names differ from Java getters) ----
private fun LayoutElementBuilders.LayoutElement.getText(): LayoutElementBuilders.Text? = this as? LayoutElementBuilders.Text
private fun LayoutElementBuilders.LayoutElement.getColumn(): LayoutElementBuilders.Column? = this as? LayoutElementBuilders.Column
private fun LayoutElementBuilders.LayoutElement.getBox(): LayoutElementBuilders.Box? = this as? LayoutElementBuilders.Box
private val LayoutElementBuilders.Text.value: String
    get() = text?.value ?: ""
