package com.example.execution.data.repository

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.*

class InvariantTests {
    private val T0 = Instant.parse("2026-08-25T09:00:00Z")
    private val states = InMemoryActualStateRepository()

    private fun state(id: String, open: Boolean = true) =
        com.example.execution.domain.state.ActualState(
            id = id, activityTypeId = "deep_work", plannedBlockId = null,
            startedAt = T0, endedAt = if (open) null else T0,
            source = com.example.execution.domain.state.StateSource.PHONE,
            trigger = null, resumedFromStateId = null, note = null
        )

    @Test
    fun `only one active state allowed`() = runTest {
        states.insert(state("s1"))
        assertFailsWith<IllegalStateException> { states.insert(state("s2")) }
    }

    @Test
    fun `closed states stay immutable`() = runTest {
        states.insert(state("s1"))
        states.finish("s1", T0)
        assertFailsWith<IllegalStateException> { states.finish("s1", T0) }
    }

    @Test
    fun `finish with endedAt before start rejected`() = runTest {
        states.insert(state("s1"))
        val earlier = Instant.parse("2026-08-25T08:00:00Z")
        assertFailsWith<IllegalStateException> { states.finish("s1", earlier) }
    }

    @Test
    fun `history reconstructable`() = runTest {
        states.insert(state("s1"))
        states.finish("s1", Instant.parse("2026-08-25T10:00:00Z"))
        states.insert(state("s2"))
        assertEquals(2, states.getHistory(T0, Instant.parse("2026-08-25T11:00:00Z")).size)
    }

    @Test
    fun `planned block may exist without actual state`() = runTest {
        val blocks = InMemoryPlannedBlockRepository()
        blocks.upsert(com.example.execution.domain.schedule.PlannedBlock(
            id = "pb-1", title = "Deep Work",
            plannedStart = T0, plannedEnd = T0, createdAt = T0, updatedAt = T0))
        assertNotNull(blocks.getById("pb-1"))
        assertNull(states.getCurrent())
    }

    @Test
    fun `cancelled future block remains auditable - upsert never deletes`() = runTest {
        val blocks = InMemoryPlannedBlockRepository()
        val b = com.example.execution.domain.schedule.PlannedBlock(
            id = "pb-1", title = "Deep Work",
            plannedStart = T0, plannedEnd = T0, createdAt = T0, updatedAt = T0)
        blocks.upsert(b)
        blocks.upsert(b.copy(status = com.example.execution.domain.schedule.PlannedBlockStatus.CANCELLED))
        assertEquals(1, blocks.getBetween(Instant.fromEpochMilliseconds(T0.toEpochMilliseconds() - 3_600_000L), Instant.fromEpochMilliseconds(T0.toEpochMilliseconds() + 3_600_000L)).size)
        assertEquals(com.example.execution.domain.schedule.PlannedBlockStatus.CANCELLED, blocks.getById("pb-1")!!.status)
    }
}
