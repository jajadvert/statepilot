package com.example.execution.domain.transition

import com.example.execution.domain.activity.TransitionPolicy
import com.example.execution.domain.schedule.PlannedBlock
import com.example.execution.domain.schedule.PlannedBlockStatus
import com.example.execution.domain.schedule.ScheduleStatus
import com.example.execution.domain.schedule.TransitionStatus
import kotlinx.datetime.Instant
import kotlin.test.*

/**
 * Fase 17 test list:
 *  - planned commute due + geofence exit + motion -> high confidence (plan example: 0.95)
 *  - no evidence -> manual/low confidence
 *  - explain() lists all evidence
 *  - policy respected (AUTO only when configured + high confidence)
 */
class TransitionEngineTest {

    private val T0 = Instant.parse("2026-08-25T08:30:00Z")

    private fun status(transitionStatus: TransitionStatus = TransitionStatus.NONE): ScheduleStatus =
        ScheduleStatus(
            currentActualState = null,
            currentPlannedBlock = null,
            nextPlannedBlock = PlannedBlock(
                id = "pb-travel", activityTypeId = "travel", title = "Travel",
                plannedStart = T0, plannedEnd = Instant.fromEpochMilliseconds(T0.toEpochMilliseconds() + 1800_000L),
                createdAt = T0, updatedAt = T0, status = PlannedBlockStatus.ACTIVE
            ),
            transitionStatus = transitionStatus,
            deviationSeconds = 0
        )

    private fun ctx(
        transitionStatus: TransitionStatus = TransitionStatus.NONE,
        geofenceExit: Boolean = false,
        geofenceEnter: Boolean = false,
        motion: Boolean = false,
        policy: TransitionPolicy = TransitionPolicy.SUGGEST
    ) = TransitionContext(
        now = T0,
        schedule = status(transitionStatus),
        geofenceExit = geofenceExit,
        geofenceEnter = geofenceEnter,
        motionDetected = motion,
        currentActivityTypeId = "breakfast",
        policyFor = { policy }
    )

    @Test
    fun `plan example - confidence 0_95`() {
        val engine = TransitionEngine()
        val s = engine.suggest(
            "travel",
            ctx(transitionStatus = TransitionStatus.OVERDUE, geofenceExit = true, motion = true)
        )
        // 0.40 (calendar due) + 0.35 (geofence exit) + 0.20 (motion) = 0.95
        assertEquals(0.95, s.confidence, 0.0001)
        assertEquals(3, s.evidence.size)
        assertEquals(TransitionPolicy.SUGGEST, s.recommendedAction)
    }

    @Test
    fun `no evidence - manual`() {
        val engine = TransitionEngine()
        val s = engine.suggest("travel", ctx())
        assertEquals(0.0, s.confidence)
        assertTrue(s.evidence.isEmpty())
        assertEquals(TransitionPolicy.MANUAL, s.recommendedAction)
    }

    @Test
    fun `calendar due alone - suggest`() {
        val engine = TransitionEngine()
        val s = engine.suggest("travel", ctx(transitionStatus = TransitionStatus.OVERDUE))
        assertEquals(0.40, s.confidence, 0.0001)
        assertEquals(EvidenceType.CALENDAR_DUE, s.evidence.single().type)
        assertEquals(TransitionPolicy.SUGGEST, s.recommendedAction)
    }

    @Test
    fun `explain lists all evidence`() {
        val engine = TransitionEngine()
        val s = engine.suggest(
            "travel",
            ctx(transitionStatus = TransitionStatus.OVERDUE, geofenceExit = true, motion = true)
        )
        val explanation = engine.explain(s)
        assertTrue(explanation.contains("Travel suggested because"))
        assertTrue(explanation.contains("planned start"))
        assertTrue(explanation.contains("geofence exit detected"))
        assertTrue(explanation.contains("movement detected"))
    }

    @Test
    fun `auto policy only with high confidence`() {
        val engine = TransitionEngine()
        // AUTO configured but low confidence -> not AUTO
        val low = engine.suggest("travel", ctx(policy = TransitionPolicy.AUTO))
        assertEquals(TransitionPolicy.MANUAL, low.recommendedAction)

        // AUTO + all evidence -> AUTO
        val high = engine.suggest(
            "travel",
            ctx(transitionStatus = TransitionStatus.OVERDUE, geofenceExit = true, motion = true, policy = TransitionPolicy.AUTO)
        )
        assertEquals(TransitionPolicy.AUTO, high.recommendedAction)
    }

    @Test
    fun `confidence clamped at 1_0`() {
        val engine = TransitionEngine(calendarDueWeight = 0.8, geofenceExitWeight = 0.8, motionWeight = 0.8)
        val s = engine.suggest(
            "travel",
            ctx(transitionStatus = TransitionStatus.OVERDUE, geofenceExit = true, motion = true)
        )
        assertEquals(1.0, s.confidence)
    }
}
