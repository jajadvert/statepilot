package com.example.execution.domain.state

import com.example.execution.data.repository.InMemoryActualStateRepository
import com.example.execution.data.repository.InMemoryDeviationRepository
import com.example.execution.data.repository.InMemoryInterruptionRepository
import com.example.execution.data.repository.InMemoryPlannedBlockRepository
import com.example.execution.data.repository.InMemoryTransitionRepository
import com.example.execution.domain.interruption.InterruptionCategory
import com.example.execution.domain.time.FixedClock
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.*

/**
 * Fase 14 — interruption UX test list.
 *  - interrupt records parent state
 *  - resume creates new state
 *  - resume references original context
 *  - interrupt then switch does not auto-resume
 *  - multiple sequential interruptions handled
 */
class InterruptionFlowTest {

    private val T0 = Instant.parse("2026-08-25T09:00:00Z")
    private val clock = FixedClock(T0)
    private val states = InMemoryActualStateRepository()
    private val transitions = InMemoryTransitionRepository()
    private val interruptions = InMemoryInterruptionRepository()
    private val blocks = InMemoryPlannedBlockRepository()
    private val deviations = InMemoryDeviationRepository()
    private var id = 0

    private val engine = StateEngine(
        states, transitions, interruptions, blocks, deviations, clock
    ) { "s${++id}" }

    private suspend fun startDeepWork(): ActualState {
        val r = engine.execute(StartActivity("deep_work", source = StateSource.PHONE, requestId = "req-start-${++id}"))
        return (r as StateResult.Success).newState ?: error("no state")
    }

    @Test
    fun `interrupt records parent state`() = runTest {
        val deepWork = startDeepWork()
        val r = engine.execute(InterruptCurrentState(InterruptionCategory.CALL, StateSource.PHONE, "req-int-1"))
        assertIs<StateResult.Success>(r)
        assertEquals("call", r.newState!!.activityTypeId)

        val record = interruptions.getInterruptionsFor(deepWork.id).single()
        assertEquals(deepWork.id, record.interruptedStateId)
        assertEquals(r.newState!!.id, record.interruptionStateId)
        assertEquals(InterruptionCategory.CALL, record.category)
        assertNull(record.endedAt)
        assertNull(record.resumedStateId)
    }

    @Test
    fun `resume creates new state`() = runTest {
        val deepWork = startDeepWork()
        engine.execute(InterruptCurrentState(InterruptionCategory.BREAK, StateSource.PHONE, "req-int-2"))
        val r = engine.execute(ResumeInterruptedState(StateSource.PHONE, "req-res-1"))
        assertIs<StateResult.Success>(r)

        // New state, not a mutation of the original
        assertNotEquals(deepWork.id, r.newState!!.id)
        assertEquals("deep_work", r.newState!!.activityTypeId)
        assertNull(r.newState!!.endedAt)
        assertEquals(StateSource.PHONE, r.newState!!.source)
    }

    @Test
    fun `resume references original context`() = runTest {
        val deepWork = startDeepWork()
        engine.execute(InterruptCurrentState(InterruptionCategory.CALL, StateSource.PHONE, "req-int-3"))
        val r = engine.execute(ResumeInterruptedState(StateSource.PHONE, "req-res-2"))
        assertIs<StateResult.Success>(r)

        assertEquals(deepWork.id, r.newState!!.resumedFromStateId)
        // Interruption record now closed with reference to the resumed state
        val record = interruptions.getInterruptionsFor(deepWork.id).single()
        assertNotNull(record.endedAt)
        assertEquals(r.newState!!.id, record.resumedStateId)
    }

    @Test
    fun `interrupt then switch does not auto-resume`() = runTest {
        startDeepWork()
        engine.execute(InterruptCurrentState(InterruptionCategory.MESSAGE, StateSource.PHONE, "req-int-4"))

        // User switches to another activity instead of resuming
        val r = engine.execute(SwitchActivity("admin", source = StateSource.PHONE, requestId = "req-sw-1"))
        assertIs<StateResult.Success>(r)
        assertEquals("admin", r.newState!!.activityTypeId)

        // No auto-resume: current state is still admin, not deep_work
        val current = states.getCurrent()
        assertEquals("admin", current?.activityTypeId)
    }

    @Test
    fun `multiple sequential interruptions handled`() = runTest {
        startDeepWork()
        // Interruption 1
        val i1 = engine.execute(InterruptCurrentState(InterruptionCategory.CALL, StateSource.PHONE, "req-int-5"))
        assertIs<StateResult.Success>(i1)
        // Resume
        val r1 = engine.execute(ResumeInterruptedState(StateSource.PHONE, "req-res-3"))
        assertIs<StateResult.Success>(r1)
        // Interruption 2 (different category)
        val i2 = engine.execute(InterruptCurrentState(InterruptionCategory.ADMIN, StateSource.PHONE, "req-int-6"))
        assertIs<StateResult.Success>(i2)
        assertEquals("admin", i2.newState!!.activityTypeId)
        // Resume again
        val r2 = engine.execute(ResumeInterruptedState(StateSource.PHONE, "req-res-4"))
        assertIs<StateResult.Success>(r2)
        assertEquals("deep_work", r2.newState!!.activityTypeId)

        // Two interruption records, both closed, both pointing to their resumed states
        val records = interruptions.getAll()
        assertEquals(2, records.size)
        assertTrue(records.all { it.endedAt != null && it.resumedStateId != null })
        // Chain invariant: the second interruption interrupts the state that
        // the first resume created (context is preserved through the chain).
        val (first, second) = records
        assertEquals(first.resumedStateId, second.interruptedStateId)
    }
}
