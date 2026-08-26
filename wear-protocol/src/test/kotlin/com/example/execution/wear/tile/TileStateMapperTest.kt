package com.example.execution.wear.tile

import com.example.execution.wear.cache.WatchDisplayState
import com.example.execution.wear.protocol.WearActivityDto
import com.example.execution.wear.protocol.WearPlannedBlockDto
import com.example.execution.wear.protocol.WearStateDto
import kotlin.test.*

/**
 * Fase 13 test list (pure logic, no emulator):
 *  - current activity visible
 *  - next activity visible
 *  - transition button visible when due
 *  - correct action ids
 *  - empty-state rendering
 */
class TileStateMapperTest {

    // 2026-08-25T10:00:00Z
    private val T0 = 1787652000000L

    private fun display(state: WearStateDto?, fromCache: Boolean = false) =
        WatchDisplayState(state, fromCache, staleSeconds = 0)

    @Test
    fun `current activity visible`() {
        TileStateMapper.nowEpochMs = { T0 } // frozen: elapsed = 0... use offset below
        val state = WearStateDto(
            revision = 1,
            currentActivity = WearActivityDto("deep_work", "Deep Work"),
            currentStateStartedAtEpochMs = T0 - 78 * 60_000L // 1h18 elapsed
        )
        val m = TileStateMapper.map(display(state))
        assertEquals("DEEP WORK", m.title)
        assertTrue(m.subtitle!!.startsWith("1h"), "subtitle was ${m.subtitle}")
        assertEquals(listOf("Interrupt"), m.buttons.map { it.label })
        assertEquals(TileActionIds.INTERRUPT, m.buttons.single().actionId)
    }

    @Test
    fun `next activity visible`() {
        val state = WearStateDto(
            revision = 1,
            currentActivity = WearActivityDto("deep_work", "Deep Work"),
            nextPlannedBlock = WearPlannedBlockDto("pb-travel", "Travel", T0 + 3600_000L, T0 + 7200_000L)
        )
        val line = TileStateMapper.nextLine(state)
        assertNotNull(line)
        assertTrue(line!!.startsWith("Travel · "))
        assertEquals("11:00", line.substringAfter("· ")) // T0 = 10:00 UTC
    }

    @Test
    fun `transition button visible when due`() {
        val state = WearStateDto(
            revision = 1,
            transitionStatus = "OVERDUE",
            nextPlannedBlock = WearPlannedBlockDto("pb-travel", "Travel", T0, T0 + 3600_000L)
        )
        val m = TileStateMapper.map(display(state))
        assertEquals("TRAVEL DUE", m.title)
        assertEquals(listOf("START", "+5"), m.buttons.map { it.label })
    }

    @Test
    fun `correct action ids`() {
        val state = WearStateDto(revision = 1, transitionStatus = "DUE",
            currentPlannedBlock = WearPlannedBlockDto("pb-x", "X", T0, T0 + 600_000L))
        val m = TileStateMapper.map(display(state))
        assertEquals(TileActionIds.START, m.buttons[0].actionId)
        assertEquals(TileActionIds.DELAY_PLUS_5, m.buttons[1].actionId)
    }

    @Test
    fun `empty-state rendering`() {
        val m = TileStateMapper.map(display(null))
        assertTrue(m.empty)
        assertEquals("No activity", m.subtitle)
        assertTrue(m.buttons.isEmpty())
    }

    @Test
    fun `cached current activity shows resume instead of interrupt`() {
        val state = WearStateDto(
            revision = 1,
            currentActivity = WearActivityDto("deep_work", "Deep Work")
        )
        val m = TileStateMapper.map(display(state, fromCache = true))
        assertEquals("Resume", m.buttons.single().label)
        assertEquals(TileActionIds.RESUME, m.buttons.single().actionId)
    }

    @Test
    fun `next block without current shows start`() {
        val state = WearStateDto(
            revision = 1,
            nextPlannedBlock = WearPlannedBlockDto("pb-dw", "Deep Work", T0, T0 + 3600_000L)
        )
        val m = TileStateMapper.map(display(state))
        assertEquals("Start", m.buttons.single().label)
        assertEquals(TileActionIds.START, m.buttons.single().actionId)
    }
}
