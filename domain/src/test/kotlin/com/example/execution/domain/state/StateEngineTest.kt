package com.example.execution.domain.state

import com.example.execution.data.repository.*
import com.example.execution.domain.interruption.InterruptionCategory
import com.example.execution.domain.repository.ActualStateRepository
import com.example.execution.domain.repository.DeviationRepository
import com.example.execution.domain.repository.InterruptionRepository
import com.example.execution.domain.repository.PlannedBlockRepository
import com.example.execution.domain.repository.TransitionRepository
import com.example.execution.domain.schedule.PlannedBlock
import com.example.execution.domain.schedule.PlannedBlockStatus
import com.example.execution.domain.time.FixedClock
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.*

class StateEngineTest {

    private lateinit var actualStates: ActualStateRepository
    private lateinit var transitions: TransitionRepository
    private lateinit var interruptions: InterruptionRepository
    private lateinit var plannedBlocks: PlannedBlockRepository
    private lateinit var deviations: DeviationRepository
    private lateinit var clock: FixedClock
    private lateinit var engine: StateEngine

    private var counter = 0
    private fun nextId() = "id-${++counter}"

    companion object {
        val T0 = Instant.parse("2026-08-25T09:00:00Z")
        val T1 = Instant.parse("2026-08-25T11:00:00Z")
    }

    @BeforeTest
    fun setup() {
        actualStates = InMemoryActualStateRepository()
        transitions = InMemoryTransitionRepository()
        interruptions = InMemoryInterruptionRepository()
        plannedBlocks = InMemoryPlannedBlockRepository()
        deviations = InMemoryDeviationRepository()
        clock = FixedClock(T0)
        engine = StateEngine(actualStates, transitions, interruptions, plannedBlocks, deviations, clock) { nextId() }
    }

    private suspend fun block(
        id: String = "pb-1",
        activityTypeId: String? = "deep_work",
        start: Instant = T0,
        end: Instant = T1,
        status: PlannedBlockStatus = PlannedBlockStatus.ACTIVE
    ) = PlannedBlock(
        id = id, externalEventId = id, activityTypeId = activityTypeId, title = "Block $id",
        plannedStart = start, plannedEnd = end, status = status,
        createdAt = T0, updatedAt = T0
    ).also { plannedBlocks.upsert(it) }

    // ---- core flows ----

    @Test
    fun `start first state`() = runTest {
        val result = engine.execute(StartActivity("deep_work", source = StateSource.PHONE, requestId = "r1"))
        assertTrue(result is StateResult.Success)
        assertEquals("deep_work", actualStates.getCurrent()?.activityTypeId)
    }

    @Test
    fun `start second state closes first`() = runTest {
        engine.execute(StartActivity("deep_work", source = StateSource.PHONE, requestId = "r1"))
        clock.set(Instant.parse("2026-08-25T10:00:00Z"))
        engine.execute(StartActivity("meeting", source = StateSource.PHONE, requestId = "r2"))

        val current = actualStates.getCurrent()
        assertEquals("meeting", current?.activityTypeId)
        val first = actualStates.getById("id-1")!!
        assertEquals(T0, first.startedAt)
        assertEquals(clock.now(), first.endedAt) // closed at transition moment
    }

    @Test
    fun `switch creates transition`() = runTest {
        engine.execute(StartActivity("deep_work", source = StateSource.PHONE, requestId = "r1"))
        engine.execute(SwitchActivity("reading", source = StateSource.WATCH, requestId = "r2"))
        val t = transitions.getByRequestId("r2")!!
        assertEquals("id-1", t.fromStateId)
        assertEquals(StateSource.WATCH, t.source)
        assertEquals(TransitionTriggerType.MANUAL_SWITCH, t.triggerType)
    }

    @Test
    fun `starting same state twice follows defined behavior - closes and reopens`() = runTest {
        block()
        engine.execute(StartPlannedBlock("pb-1", StateSource.PHONE, "r1"))
        clock.advanceBy(kotlin.time.Duration.parse("5m"))
        engine.execute(StartPlannedBlock("pb-1", StateSource.PHONE, "r2"))
        // Defined behavior: second start closes the first fragment and opens a new one.
        assertNull(actualStates.getCurrent()?.endedAt)
        assertNotNull(actualStates.getById("id-1")!!.endedAt) // first fragment closed
    }

    @Test
    fun `duplicate requestId executes once`() = runTest {
        block()
        val r1 = engine.execute(StartPlannedBlock("pb-1", StateSource.PHONE, "req-dup"))
        val r2 = engine.execute(StartPlannedBlock("pb-1", StateSource.PHONE, "req-dup"))
        assertTrue(r1 is StateResult.Success)
        assertTrue(r2 is StateResult.IdempotentReplay)
        assertEquals(1, transitions.getByState((r1 as StateResult.Success).newState!!.id).size)
    }

    @Test
    fun `interrupt closes current state`() = runTest {
        engine.execute(StartActivity("deep_work", source = StateSource.PHONE, requestId = "r1"))
        clock.advanceBy(kotlin.time.Duration.parse("41m"))
        val res = engine.execute(InterruptCurrentState(InterruptionCategory.CALL, StateSource.WATCH, "r2"))
        assertTrue(res is StateResult.Success)
        assertNotNull(actualStates.getById("id-1")!!.endedAt)
        assertEquals("call", actualStates.getCurrent()?.activityTypeId)
    }

    @Test
    fun `interrupt remembers interrupted context`() = runTest {
        block()
        engine.execute(StartPlannedBlock("pb-1", StateSource.PHONE, "r1"))
        engine.execute(InterruptCurrentState(InterruptionCategory.CALL, StateSource.WATCH, "r2"))
        val currentId = actualStates.getCurrent()?.id!!
        val open = interruptions.getOpenForState(currentId)
        assertNotNull(open)
        assertEquals("id-1", open.interruptedStateId)
        assertEquals(InterruptionCategory.CALL, open.category)
        assertEquals(actualStates.getCurrent()?.id, open.interruptionStateId)
    }

    @Test
    fun `resume restores activity context`() = runTest {
        block()
        engine.execute(StartPlannedBlock("pb-1", StateSource.PHONE, "r1"))
        engine.execute(InterruptCurrentState(InterruptionCategory.CALL, StateSource.WATCH, "r2"))
        clock.advanceBy(kotlin.time.Duration.parse("11m"))
        val res = engine.execute(ResumeInterruptedState(StateSource.WATCH, "r3"))
        assertTrue(res is StateResult.Success)
        val resumed = (res as StateResult.Success).newState!!
        assertEquals("deep_work", resumed.activityTypeId)
        assertEquals("id-1", resumed.resumedFromStateId)
        assertEquals("pb-1", resumed.plannedBlockId)
        // original historical state never mutated
        assertNotNull(actualStates.getById("id-1")!!.endedAt) // original closed, not mutated
    }

    @Test
    fun `interrupt then choose different state does not auto-resume`() = runTest {
        block()
        engine.execute(StartPlannedBlock("pb-1", StateSource.PHONE, "r1"))
        engine.execute(InterruptCurrentState(InterruptionCategory.BREAK, StateSource.PHONE, "r2"))
        engine.execute(StartActivity("admin", source = StateSource.PHONE, requestId = "r3"))
        val current = actualStates.getCurrent()
        assertEquals("admin", current?.activityTypeId)
        assertNull(current?.resumedFromStateId)
    }

    @Test
    fun `finish leaves no current state`() = runTest {
        engine.execute(StartActivity("deep_work", source = StateSource.PHONE, requestId = "r1"))
        val res = engine.execute(Finish(StateSource.PHONE, "r2"))
        assertTrue(res is StateResult.Success)
        assertNull(actualStates.getCurrent())
    }

    @Test
    fun `unplanned state allowed`() = runTest {
        val res = engine.execute(StartActivity("other", source = StateSource.PHONE, requestId = "r1"))
        assertTrue(res is StateResult.Success)
        assertNull((res as StateResult.Success).newState!!.plannedBlockId)
    }

    // ---- guards ----

    @Test
    fun `finish without current state fails`() = runTest {
        val res = engine.execute(Finish(StateSource.PHONE, "r1"))
        assertTrue(res is StateResult.Failure)
    }

    @Test
    fun `resume without interruption fails`() = runTest {
        engine.execute(StartActivity("deep_work", source = StateSource.PHONE, requestId = "r1"))
        val res = engine.execute(ResumeInterruptedState(StateSource.PHONE, "r2"))
        assertTrue(res is StateResult.Failure)
    }

    @Test
    fun `start of cancelled block rejected`() = runTest {
        block(id = "pb-x", status = PlannedBlockStatus.CANCELLED)
        val res = engine.execute(StartPlannedBlock("pb-x", StateSource.PHONE, "r1"))
        assertTrue(res is StateResult.Failure)
    }

    // ---- skip / delay ----

    @Test
    fun `skip records deviation without touching history`() = runTest {
        block(id = "pb-s")
        val res = engine.execute(SkipPlannedBlock("pb-s", StateSource.PHONE, "r1"))
        assertTrue(res is StateResult.Success)
        assertEquals(1, deviations.getByPlannedBlock("pb-s").size)
    }

    @Test
    fun `delay shifts future planning and records deviation`() = runTest {
        block(id = "pb-d")
        val res = engine.execute(DelayPlannedBlock("pb-d", 600, StateSource.NOTIFICATION, "r1"))
        assertTrue(res is StateResult.Success)
        // plannedEnd = max(old end, shifted start) = old end (block not shortened/extended past itself)
        assertEquals(T1.toEpochMilliseconds(), plannedBlocks.getById("pb-d")!!.plannedEnd.toEpochMilliseconds())
        assertEquals(1, deviations.getByPlannedBlock("pb-d").size)
    }
}
