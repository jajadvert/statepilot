package com.example.execution.domain.schedule

import com.example.execution.data.repository.InMemoryActualStateRepository
import com.example.execution.data.repository.InMemoryPlannedBlockRepository
import com.example.execution.domain.state.ActualState
import com.example.execution.domain.state.StateSource
import com.example.execution.domain.time.FixedClock
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.*

class ScheduleEngineTest {

    private val T0 = Instant.parse("2026-08-25T09:00:00Z")
    private val blocks = InMemoryPlannedBlockRepository()
    private val states = InMemoryActualStateRepository()
    private val clock = FixedClock(T0)
    private val engine = ScheduleEngine(blocks, states, clock)

    private suspend fun block(id: String, startH: Int, endH: Int, activity: String? = "deep_work") {
        val plannedBlock = PlannedBlock(
            id = id, activityTypeId = activity, title = "B-$id",
            plannedStart = Instant.parse("2026-08-25T%02d:00:00Z".format(startH)),
            plannedEnd = Instant.parse("2026-08-25T%02d:00:00Z".format(endH)),
            createdAt = T0, updatedAt = T0
        )
        blocks.upsert(plannedBlock)
    }

    private suspend fun startState(activity: String, startedAt: Instant) {
        states.insert(ActualState(
            id = "st-$activity", activityTypeId = activity, plannedBlockId = null,
            startedAt = startedAt, endedAt = null,
            source = StateSource.PHONE, trigger = null, resumedFromStateId = null, note = null))
    }

    @Test
    fun `before first block`() = runTest {
        block("a", 10, 12)
        clock.set(Instant.parse("2026-08-25T08:00:00Z"))
        val s = engine.status()
        assertNull(s.currentPlannedBlock)
        assertEquals("a", s.nextPlannedBlock?.id)
        assertEquals(TransitionStatus.NONE, s.transitionStatus) // > 10 min away
    }

    @Test
    fun `1 minute before transition`() = runTest {
        block("a", 9, 11)
        block("b", 11, 13)
        clock.set(Instant.parse("2026-08-25T10:59:00Z"))
        val s = engine.status()
        assertEquals("b", s.nextPlannedBlock?.id)
        assertEquals(TransitionStatus.UPCOMING, s.transitionStatus)
    }

    @Test
    fun `exact block start`() = runTest {
        block("a", 9, 11)
        clock.set(T0)
        val s = engine.status()
        assertEquals("a", s.currentPlannedBlock?.id)
        // Nothing executed yet -> transition due/overdue for this block
        assertEquals(TransitionStatus.OVERDUE, s.transitionStatus)
        assertEquals(0L, s.deviationSeconds)
    }

    @Test
    fun `5 minutes overdue - user not executing plan`() = runTest {
        block("a", 9, 11)
        clock.set(Instant.parse("2026-08-25T09:05:00Z"))
        val s = engine.status()
        assertEquals("a", s.currentPlannedBlock?.id)
        assertEquals(TransitionStatus.OVERDUE, s.transitionStatus)
        assertEquals(300L, s.deviationSeconds) // 5 min behind
    }

    @Test
    fun `between blocks`() = runTest {
        block("a", 8, 9)
        block("b", 10, 12)
        clock.set(Instant.parse("2026-08-25T09:30:00Z"))
        val s = engine.status()
        assertNull(s.currentPlannedBlock)
        assertEquals("b", s.nextPlannedBlock?.id)
    }

    @Test
    fun `after last block`() = runTest {
        block("a", 8, 10)
        clock.set(Instant.parse("2026-08-25T15:00:00Z"))
        val s = engine.status()
        assertNull(s.nextPlannedBlock)
        assertEquals(TransitionStatus.NONE, s.transitionStatus)
    }

    @Test
    fun `overlapping blocks - first match wins as current`() = runTest {
        block("o1", 9, 11)
        block("o2", 10, 12)
        clock.set(Instant.parse("2026-08-25T10:30:00Z"))
        val s = engine.status()
        assertEquals(1, listOfNotNull(s.currentPlannedBlock).size)
    }

    @Test
    fun `empty calendar`() = runTest {
        clock.set(T0)
        val s = engine.status()
        assertNull(s.currentPlannedBlock)
        assertNull(s.nextPlannedBlock)
        assertEquals(TransitionStatus.NONE, s.transitionStatus)
        assertEquals(0L, s.deviationSeconds)
    }

    @Test
    fun `cancelled block ignored`() = runTest {
        block("c", 8, 18)
        blocks.getById("c")?.let {
            blocks.upsert(it.copy(status = PlannedBlockStatus.CANCELLED))
        }
        clock.set(Instant.parse("2026-08-25T12:00:00Z"))
        val s = engine.status()
        assertNull(s.currentPlannedBlock)
        assertNull(s.nextPlannedBlock)
    }

    @Test
    fun `current state matches plan`() = runTest {
        block("a", 9, 11, activity = "deep_work")
        clock.set(Instant.parse("2026-08-25T09:03:00Z")) // started 3 min late
        startState("deep_work", Instant.parse("2026-08-25T09:03:00Z"))
        val s = engine.status()
        assertEquals(180L, s.deviationSeconds) // +3 min late start, not accumulating
    }

    @Test
    fun `current state differs from plan`() = runTest {
        block("a", 9, 11, activity = "deep_work")
        clock.set(Instant.parse("2026-08-25T09:17:00Z"))
        startState("breakfast", T0)
        val s = engine.status()
        assertEquals(1020L, s.deviationSeconds) // 17 min behind schedule
    }
}
