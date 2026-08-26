package com.example.execution.domain.analytics

import com.example.execution.data.repository.InMemoryActualStateRepository
import com.example.execution.data.repository.InMemoryDeviationRepository
import com.example.execution.data.repository.InMemoryInterruptionRepository
import com.example.execution.data.repository.InMemoryPlannedBlockRepository
import com.example.execution.domain.deviation.Deviation
import com.example.execution.domain.deviation.DeviationType
import com.example.execution.domain.interruption.Interruption
import com.example.execution.domain.interruption.InterruptionCategory
import com.example.execution.domain.schedule.PlannedBlock
import com.example.execution.domain.state.ActualState
import com.example.execution.domain.state.StateSource
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.*

/**
 * Fase 15 (§21): planned-vs-actual analytics. All §21 test scenarios.
 */
class DayAnalyzerTest {

    private val D = "2026-08-25T"
    private fun t(hhmm: String) = Instant.parse("$D$hhmm:00Z")

    private lateinit var blocks: InMemoryPlannedBlockRepository
    private lateinit var states: InMemoryActualStateRepository
    private lateinit var interruptions: InMemoryInterruptionRepository
    private lateinit var deviations: InMemoryDeviationRepository
    private lateinit var analyzer: DayAnalyzer

    private var idc = 0

    @BeforeTest
    fun setup() {
        blocks = InMemoryPlannedBlockRepository()
        states = InMemoryActualStateRepository()
        interruptions = InMemoryInterruptionRepository()
        deviations = InMemoryDeviationRepository()
        analyzer = DayAnalyzer(blocks, states, interruptions, deviations)
    }

    private suspend fun block(id: String, start: String, end: String, activity: String? = "deep_work") {
        blocks.upsert(PlannedBlock(
            id = id, activityTypeId = activity, title = "B-$id",
            plannedStart = t(start), plannedEnd = t(end),
            createdAt = t("00:00"), updatedAt = t("00:00")))
    }

    private suspend fun state(activity: String, start: String, end: String?, blockId: String?, resumedFrom: String? = null, note: String? = null): ActualState {
        val s = ActualState(
            id = "st-${++idc}", activityTypeId = activity, plannedBlockId = blockId,
            startedAt = t(start), endedAt = end?.let { t(it) },
            source = StateSource.PHONE, trigger = null,
            resumedFromStateId = resumedFrom, note = note)
        states.insert(s)
        return s
    }

    private suspend fun close(state: ActualState, end: String) =
        states.finish(state.id, t(end))

    // ---- §21 scenario's ----

    @Test
    fun `exact execution`() = runTest {
        block("a", "09:00", "11:00")
        val s = state("deep_work", "09:00", null, "a"); close(s, "11:00")
        val r = analyzer.analyzeDay(t("00:00"), t("23:59"))
        val b = r.blocks.single()
        assertEquals(120L, b.plannedDurationMinutes)
        assertEquals(120L, b.actualDurationMinutes)
        assertEquals(0L, b.startDelaySeconds)
        assertEquals(0L, b.endDeviationSeconds)
        assertFalse(b.skipped)
        assertEquals(1, b.fragmentCount)
    }

    @Test
    fun `late start`() = runTest {
        block("a", "09:00", "11:00")
        val s = state("deep_work", "09:03", null, "a"); close(s, "10:57")
        val b = analyzer.analyzeDay(t("00:00"), t("23:59")).blocks.single()
        assertEquals(180L, b.startDelaySeconds)      // +3 min late
        assertEquals(-180L, b.endDeviationSeconds)   // ended 3 min early
    }

    @Test
    fun `early finish`() = runTest {
        block("a", "09:00", "10:00")
        val s = state("deep_work", "09:00", null, "a"); close(s, "09:45")
        val b = analyzer.analyzeDay(t("00:00"), t("23:59")).blocks.single()
        assertEquals(-900L, b.endDeviationSeconds)
    }

    @Test
    fun `late finish - overrun`() = runTest {
        block("a", "09:00", "10:00")
        val s = state("deep_work", "09:00", null, "a"); close(s, "10:20")
        val b = analyzer.analyzeDay(t("00:00"), t("23:59")).blocks.single()
        assertEquals(1200L, b.endDeviationSeconds) // +20 min
    }

    @Test
    fun `multiple execution fragments`() = runTest {
        block("a", "09:00", "11:00")
        val f1 = state("deep_work", "09:08", null, "a"); close(f1, "09:47")
        val f2 = state("deep_work", "09:59", null, "a"); close(f2, "11:03") // matches §2.1 example
        val b = analyzer.analyzeDay(t("00:00"), t("23:59")).blocks.single()
        assertEquals(2, b.fragmentCount)
        assertEquals(480L, b.startDelaySeconds)          // started 09:08 → +8 min
        assertEquals(180L, b.endDeviationSeconds ?: 0L)  // ended 11:03 → +3 min
        // elapsed execution 09:08→11:03 = 115 min
        assertEquals(115L, b.actualDurationMinutes)
    }

    @Test
    fun `interruptions counted and net focused computed`() = runTest {
        block("a", "09:00", "11:00")
        val main = state("deep_work", "09:00", null, "a")
        close(main, "09:41")
        val call = state("call", "09:41", null, "a", note = "interruption of ${main.id}")
        close(call, "09:52")
        val resumed = state("deep_work", "09:52", null, "a", resumedFrom = main.id); close(resumed, "11:00")

        interruptions.insert(Interruption(
            id = "i1", interruptedStateId = main.id, interruptionStateId = call.id,
            category = InterruptionCategory.CALL, startedAt = t("09:41"),
            endedAt = t("09:52"), resumedStateId = resumed.id))

        val b = analyzer.analyzeDay(t("00:00"), t("23:59")).blocks.single()
        assertEquals(660L, b.interruptionSeconds) // 11 min
        // actual span 09:00→11:00 = 120 min; net = 120 - 11 = 109 min
        assertEquals(120L, b.actualDurationMinutes)
        assertEquals(6540L, b.netFocusedSeconds)  // 109 min net (120 - 11)
    }

    @Test
    fun `skipped block`() = runTest {
        block("a", "09:00", "10:00")
        deviations.insert(Deviation("d1", "a", null, DeviationType.SKIPPED, null, t("09:00")))
        val r = analyzer.analyzeDay(t("00:00"), t("23:59"))
        val b = r.blocks.single()
        assertTrue(b.skipped)
        assertNull(b.actualDurationMinutes)
    }

    @Test
    fun `unplanned state reported`() = runTest {
        state("phone_call", "12:00", "12:15", null)
        val r = analyzer.analyzeDay(t("00:00"), t("23:59"))
        assertEquals(1, r.unplannedStates.size)
        assertEquals(900L, r.unplannedStates.first().totalSeconds)
    }

    @Test
    fun `block never started reported as skipped`() = runTest {
        block("never", "09:00", "10:00")
        val b = analyzer.analyzeDay(t("00:00"), t("23:59")).blocks.single()
        assertTrue(b.skipped)
        assertNull(b.startDelaySeconds)
    }
}
