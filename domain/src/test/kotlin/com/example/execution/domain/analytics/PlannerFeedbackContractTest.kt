package com.example.execution.domain.analytics

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.*

/**
 * Fase 19 (§23): planner-feedback-contract.
 * Contract is stabiel, versioned en serialiseerbaar.
 */
class PlannerFeedbackContractTest {

    private fun sample(blockId: String, activity: String?, plannedMin: Long, actualMin: Long?, delay: Long?, endDev: Long?, skipped: Boolean, day: Long = 1) =
        ActivitySample(blockId, activity, day, plannedMin, actualMin, delay, endDev, skipped)

    @Test
    fun `medians computed per activity`() {
        val report = DayReport(
            dateEpochDay = 1,
            blocks = listOf(
                BlockAnalysis("b1", "deep_work", "Deep Work", 120, 118, 180, -120, 0, 7080, false, 2),
                BlockAnalysis("b2", "deep_work", "Deep Work", 120, 120, 0, 0, 0, 7200, false, 1),
                BlockAnalysis("b3", "deep_work", "Deep Work", 120, null, 240, 60, 0, null, true, 1)
            ),
            unplannedStates = emptyList()
        )
        val contract = PlannerFeedbackBuilder.build(listOf(report))

        val dw = contract.perActivity.single { it.activityTypeId == "deep_work" }
        assertEquals(120L, dw.medianPlannedDurationMinutes)
        assertEquals(119L, dw.medianActualDurationMinutes)      // median van 118,120
        assertEquals(90L, dw.medianStartDelaySeconds)           // median van 180,0 (240 zit in skipped)
        assertEquals(3, dw.sampleCount)
        assertEquals(2.0 / 3.0, dw.executionRate, 0.001)
    }

    @Test
    fun `overall aggregated`() {
        val report = DayReport(
            dateEpochDay = 1,
            blocks = listOf(
                BlockAnalysis("b1", "a", "A", 60, 60, 0, 0, 0, 3600, false, 1),
                BlockAnalysis("b2", "a", "A", 60, null, 120, null, 0, null, true, 1),
                BlockAnalysis("b3", "b", "B", 30, 30, 60, 0, 0, 1800, false, 1)
            ),
            unplannedStates = emptyList()
        )
        val c = PlannerFeedbackBuilder.build(listOf(report))
        assertEquals(3, c.overall.totalBlocks)
        assertEquals(2, c.overall.totalExecutedBlocks)
        assertEquals(2.0 / 3.0, c.overall.overallExecutionRate, 0.001)
        assertEquals(30L, c.overall.medianStartDelaySeconds) // median van 0,60
    }

    @Test
    fun `serializes to versioned json and back`() {
        val contract = PlannerFeedbackContract(
            generatedAtEpochMs = 1234L,
            perActivity = listOf(ActivityFeedback(
                "deep_work", 120, 119, 90, -30, 0.75, 4, emptyList()
            )),
            overall = OverallFeedback(4, 3, 0.75, 90)
        )
        val json = Json.encodeToString(contract)
        assertTrue(json.contains("contractVersion"), "json was: $json")
        assertTrue(json.contains("medianStartDelaySeconds"), "json was: $json")

        val decoded = Json.decodeFromString<PlannerFeedbackContract>(json)
        assertEquals(1, decoded.contractVersion)
        assertEquals("deep_work", decoded.perActivity.single().activityTypeId)
        assertEquals(0.75, decoded.overall.overallExecutionRate)
    }

    @Test
    fun `samples capped at 30 most recent`() {
        val samples = (1..40).map { i -> sample("b$i", "x", 60, 60, 0, 0, false, day = i.toLong()) }
        val report = DayReport(1, samples.map { s ->
            BlockAnalysis(s.plannedBlockId, "x", "X", 60, 60, 0, 0, 0, 3600, false, 1)
        }, emptyList())
        val c = PlannerFeedbackBuilder.build(listOf(report))
        val fb = c.perActivity.single()
        assertEquals(40, fb.sampleCount)
        assertEquals(30, fb.samples.size)
        assertEquals("b11", fb.samples.first().plannedBlockId) // recentste 30 (b11..b40)
    }
}
