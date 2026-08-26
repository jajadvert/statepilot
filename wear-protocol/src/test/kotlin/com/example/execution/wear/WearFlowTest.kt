package com.example.execution.wear

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
import com.example.execution.wear.protocol.WearCommandDto
import com.example.execution.wear.protocol.WearCommandType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.*

/**
 * Fase 9/10 slice: full Wear flow against FakeWearTransport —
 * state projection, watch commands, offline behaviour.
 */
class WearFlowTest {

    private val T0 = Instant.parse("2026-08-25T09:00:00Z")
    private lateinit var clock: FixedClock
    private lateinit var blocks: InMemoryPlannedBlockRepository
    private lateinit var transport: FakeWearTransport
    private lateinit var bridge: PhoneWearBridge

    @BeforeTest
    fun setup() {
        blocks = InMemoryPlannedBlockRepository()
        clock = FixedClock(T0)
        val states = InMemoryActualStateRepository()
        val engine = StateEngine(
            states, InMemoryTransitionRepository(), InMemoryInterruptionRepository(),
            blocks, InMemoryDeviationRepository(), clock
        ) { "id-${(0..9999).random()}" }
        transport = FakeWearTransport()
        bridge = PhoneWearBridge(
            ScheduleEngine(blocks, states, clock), engine,
            states, blocks, transport
        )
    }

    private suspend fun seed() {
        blocks.upsert(PlannedBlock(
            id = "pb-dw", activityTypeId = "deep_work", title = "Deep Work",
            plannedStart = Instant.parse("2026-08-25T09:00:00Z"),
            plannedEnd = Instant.parse("2026-08-25T11:00:00Z"),
            createdAt = T0, updatedAt = T0))
    }

    /** runTest wrapper that seeds first. */
    private fun wt(testBody: suspend TestScope.() -> Unit) =
        runTest { seed(); testBody() }

    // ---- Fase 10 UI semantics ----

    @Test
    fun `current state shown on watch`() = wt {
        bridge.onWatchCommand(WearCommandDto(WearCommandType.START_PLANNED, "w1", plannedBlockId = "pb-dw"))
        bridge.publishState(clock.now())
        val s = transport.states.first()
        assertEquals("Deep Work", s.currentActivity?.label)
        assertNotNull(s.currentStateStartedAtEpochMs)
    }

    @Test
    fun `next block shown with transition status`() = wt {
        clock.set(Instant.parse("2026-08-25T08:55:00Z"))
        bridge.publishState(clock.now())
        val s = transport.states.first()
        assertEquals("Deep Work", s.nextPlannedBlock?.title)
        assertEquals("UPCOMING", s.transitionStatus)
    }

    @Test
    fun `overdue shown`() = wt {
        clock.set(Instant.parse("2026-08-25T09:05:00Z"))
        bridge.publishState(clock.now())
        val s = transport.states.first()
        assertEquals("OVERDUE", s.transitionStatus)
        assertEquals(300L, s.deviationSeconds)
    }

    // ---- command roundtrip ----

    @Test
    fun `watch start command changes phone state`() = wt {
        assertTrue(bridge.onWatchCommand(WearCommandDto(WearCommandType.START_PLANNED, "w1", plannedBlockId = "pb-dw")))
        bridge.publishState(clock.now())
        assertEquals("Deep Work", transport.states.first().currentActivity?.label)
    }

    @Test
    fun `duplicate command processed once`() = wt {
        assertTrue(bridge.onWatchCommand(WearCommandDto(WearCommandType.START_PLANNED, "dup", plannedBlockId = "pb-dw")))
        val second = bridge.onWatchCommand(WearCommandDto(WearCommandType.START_PLANNED, "dup", plannedBlockId = "pb-dw"))
        // Idempotent replay is not an error, but also has no new effect:
        assertFalse(second) // replay returns non-success (IdempotentReplay)
    }

    @Test
    fun `interrupt and resume via watch`() = wt {
        bridge.onWatchCommand(WearCommandDto(WearCommandType.START_PLANNED, "i1", plannedBlockId = "pb-dw"))
        assertTrue(bridge.onWatchCommand(WearCommandDto(WearCommandType.INTERRUPT, "i2")))
        assertTrue(bridge.onWatchCommand(WearCommandDto(WearCommandType.RESUME, "i3")))
        bridge.publishState(clock.now())
        assertEquals("Deep Work", transport.states.first().currentActivity?.label)
    }

    @Test
    fun `offline error state`() = wt {
        transport.failCommands = true
        val r = transport.sendCommand(WearCommandDto(WearCommandType.FINISH, "off1"))
        assertTrue(r.isFailure)
        assertEquals("Phone unavailable", r.exceptionOrNull()?.message)
    }

    @Test
    fun `old revision ignored by merger`() {
        val merger = com.example.execution.wear.protocol.WearStateMerger()
        assertTrue(merger.accept(com.example.execution.wear.protocol.WearStateDto(revision = 5)))
        assertFalse(merger.accept(com.example.execution.wear.protocol.WearStateDto(revision = 4)))
        assertTrue(merger.accept(com.example.execution.wear.protocol.WearStateDto(revision = 5))) // duplicate idempotent
    }
}
