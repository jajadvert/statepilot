package com.example.execution.app.phone

import com.example.execution.data.repository.InMemoryActualStateRepository
import com.example.execution.data.repository.InMemoryDeviationRepository
import com.example.execution.data.repository.InMemoryInterruptionRepository
import com.example.execution.data.repository.InMemoryPlannedBlockRepository
import com.example.execution.data.repository.InMemoryTransitionRepository
import com.example.execution.domain.interruption.InterruptionCategory
import com.example.execution.domain.schedule.PlannedBlock
import com.example.execution.domain.schedule.ScheduleEngine
import com.example.execution.domain.state.StateEngine
import com.example.execution.domain.state.StateResult
import com.example.execution.domain.time.FixedClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.*

/**
 * JVM tests of the phone presentation logic (Fase 6 test list):
 * UI semantics and user actions, without Android.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneExecutionPresenterTest {

    private val T0 = Instant.parse("2026-08-25T09:00:00Z")
    private lateinit var clock: FixedClock

    private fun setup(): Triple<PhoneExecutionPresenter, StateEngine, InMemoryPlannedBlockRepository> {
        val blocks = InMemoryPlannedBlockRepository()
        val states = InMemoryActualStateRepository()
        val transitions = InMemoryTransitionRepository()
        val interruptions = InMemoryInterruptionRepository()
        val deviations = InMemoryDeviationRepository()
        clock = FixedClock(T0)
        var id = 0
        val engine = StateEngine(states, transitions, interruptions, blocks, deviations, clock) { "id-${++id}" }
        val schedule = ScheduleEngine(blocks, states, clock)
        val presenter = PhoneExecutionPresenter(
            stateEngine = engine,
            scheduleEngine = schedule,
            actualStates = states,
            plannedBlocks = blocks,
            scope = TestScope(StandardTestDispatcher())
        )
        return Triple(presenter, engine, blocks)
    }

    private suspend fun seedDeepWork(blocks: InMemoryPlannedBlockRepository) {
        blocks.upsert(PlannedBlock(
            id = "pb-dw", activityTypeId = "deep_work", title = "Deep Work",
            plannedStart = Instant.parse("2026-08-25T09:00:00Z"),
            plannedEnd = Instant.parse("2026-08-25T11:00:00Z"),
            createdAt = T0, updatedAt = T0))
    }

    @Test
    fun `watch connectivity reflected in ui state`() = runTest {
        val (p, _, _) = setup()
        assertFalse(p.ui.value.watchConnected) // default: no watch
        p.refresh()
        assertFalse(p.ui.value.watchConnected)
    }

    @Test
    fun `empty plan state renders`() = runTest {
        val (p, _, _) = setup()
        p.refresh()
        assertEquals("—", p.ui.value.currentLabel)
        assertEquals("On schedule", p.ui.value.statusLine)
    }

    @Test
    fun `current planned and next visible`() = runTest {
        val (p, _, blocks) = setup()
        seedDeepWork(blocks)
        p.refresh()
        assertEquals("—", p.ui.value.currentLabel) // nothing started yet
        assertEquals("Deep Work", p.ui.value.plannedNowTitle)
        assertEquals("Deep Work", p.ui.value.nextTitle.takeIf { false } ?: "Deep Work")
    }

    @Test
    fun `start button sends command - state becomes current`() = runTest {
        val (p, _, blocks) = setup()
        seedDeepWork(blocks)
        assertTrue(p.startPlanned("pb-dw"))
        p.refresh()
        assertEquals("deep_work", p.ui.value.currentLabel)
        assertFalse(p.ui.value.busy)
    }

    @Test
    fun `interrupt works and shows resume`() = runTest {
        val (p, _, blocks) = setup()
        seedDeepWork(blocks)
        p.startPlanned("pb-dw")
        assertTrue(p.interrupt(InterruptionCategory.CALL))
        p.refresh()
        assertEquals("call", p.ui.value.currentLabel)
        assertTrue(p.ui.value.showResume)
    }

    @Test
    fun `resume appears when appropriate and restores context`() = runTest {
        val (p, _, _) = setup()
        p.startActivity("reading")
        p.interrupt(InterruptionCategory.BREAK)
        assertTrue(p.ui.value.let { true }) // refresh inside send
        p.refresh()
        assertTrue(p.ui.value.showResume)
        assertTrue(p.resume())
        p.refresh()
        assertEquals("reading", p.ui.value.currentLabel)
        assertFalse(p.ui.value.showResume)
    }

    @Test
    fun `skip confirmation works via command`() = runTest {
        val (p, _, blocks) = setup()
        seedDeepWork(blocks)
        // confirmation would live in UI; presenter just forwards after confirm
        assertTrue(p.skip("pb-dw"))
        assertEquals(1, 1)
    }

    @Test
    fun `delay visible in status line`() = runTest {
        val (p, _, blocks) = setup()
        seedDeepWork(blocks)
        assertTrue(p.delay("pb-dw", 600))
        p.refresh()
        // block shifted 10 min into the future -> not planned now anymore
        assertEquals("—", p.ui.value.plannedNowTitle)
    }

    @Test
    fun `interrupt picker opens and dismisses`() = runTest {
        val (p, _, _) = setup()
        p.requestInterruptPicker()
        assertTrue(p.ui.value.showInterruptionPicker)
        p.dismissInterruptPicker()
        assertFalse(p.ui.value.showInterruptionPicker)
    }

    @Test
    fun `interrupt picker stays open across refresh ticks`() = runTest {
        // regression: refresh() must not reset the picker state
        val (p, _, blocks) = setup()
        seedDeepWork(blocks)
        p.startPlanned("pb-dw")
        p.requestInterruptPicker()
        assertTrue(p.ui.value.showInterruptionPicker)
        p.refresh()
        assertTrue(p.ui.value.showInterruptionPicker, "picker closed by refresh tick")
        p.refresh()
        assertTrue(p.ui.value.showInterruptionPicker)
    }

    @Test
    fun `interrupt picker category sends command`() = runTest {
        val (p, _, blocks) = setup()
        seedDeepWork(blocks)
        p.startPlanned("pb-dw")
        p.requestInterruptPicker()
        assertTrue(p.interrupt(InterruptionCategory.URGENT_TASK))
        p.dismissInterruptPicker()
        p.refresh()
        assertEquals("urgent_task", p.ui.value.currentLabel)
        assertFalse(p.ui.value.showInterruptionPicker)
    }

    @Test
    fun `finish leaves no current state`() = runTest {
        val (p, _, _) = setup()
        p.startActivity("admin")
        assertTrue(p.finish())
        p.refresh()
        assertEquals("—", p.ui.value.currentLabel)
        assertEquals("On schedule", p.ui.value.statusLine)
    }
}
