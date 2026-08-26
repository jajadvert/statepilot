package com.example.execution.wear.tile

import com.example.execution.wear.cache.WatchDisplayState
import com.example.execution.wear.protocol.WearActivityDto
import com.example.execution.wear.protocol.WearCommandType
import com.example.execution.wear.protocol.WearPlannedBlockDto
import com.example.execution.wear.protocol.WearStateDto
import kotlin.test.*

/**
 * Watch action buttons: what to show per state and which commands they send.
 */
class WatchActionMapperTest {

    private var idc = 0
    private fun rid() = "req-${++idc}"

    private fun display(activity: WearActivityDto?, transition: String = "NONE", next: WearPlannedBlockDto? = null) =
        WatchDisplayState(
            state = WearStateDto(
                revision = 1,
                currentActivity = activity,
                transitionStatus = transition,
                nextPlannedBlock = next
            ),
            fromCache = false,
            staleSeconds = 0
        )

    @Test
    fun `active deep work shows interrupt and finish`() {
        val buttons = WatchActionMapper.buttons(
            display(WearActivityDto("deep_work", "Deep Work")), ::rid
        )
        assertEquals(listOf("Interrupt", "Finish"), buttons.map { it.label })
        assertEquals(WearCommandType.INTERRUPT, buttons[0].command.type)
        assertEquals(WearCommandType.FINISH, buttons[1].command.type)
    }

    @Test
    fun `interruption state shows resume`() {
        val buttons = WatchActionMapper.buttons(
            display(WearActivityDto("call", "call")), ::rid
        )
        assertEquals(listOf("Resume"), buttons.map { it.label })
        assertEquals(WearCommandType.RESUME, buttons.single().command.type)
    }

    @Test
    fun `due transition shows start delay and skip`() {
        val buttons = WatchActionMapper.buttons(
            display(
                activity = null,
                transition = "OVERDUE",
                next = WearPlannedBlockDto("pb-travel", "Travel", 0, 1)
            ),
            ::rid
        )
        assertEquals(listOf("Start", "+5", "Skip"), buttons.map { it.label })
        assertEquals("pb-travel", buttons[0].command.plannedBlockId)
        assertEquals(WearCommandType.DELAY, buttons[1].command.type)
        assertEquals(300L, buttons[1].command.delaySeconds)
        assertEquals(WearCommandType.SKIP, buttons[2].command.type)
    }

    @Test
    fun `empty state shows no buttons`() {
        val buttons = WatchActionMapper.buttons(
            WatchDisplayState(null, fromCache = false, staleSeconds = 0), ::rid
        )
        assertTrue(buttons.isEmpty())
    }

    @Test
    fun `no buttons when nothing relevant`() {
        val buttons = WatchActionMapper.buttons(
            display(null, transition = "NONE"), ::rid
        )
        assertTrue(buttons.isEmpty())
    }
}
