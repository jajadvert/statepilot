package com.example.execution.domain.schedule

import com.example.execution.domain.repository.ActualStateRepository
import com.example.execution.domain.repository.PlannedBlockRepository
import com.example.execution.domain.time.Clock
import kotlinx.datetime.Instant

/**
 * Pure ScheduleEngine (§11). Reads PlannedBlocks, ActualState and Clock.
 * Answers at any moment:
 *   what am I doing? / what should I be doing? / what is next?
 *   how far ahead/behind am I? / should a warning fire?
 *
 * Transition semantics:
 *  - UPCOMING: next block starts within [upcomingWindowSeconds]
 *  - OVERDUE : either inside a planned block without executing it,
 *              or the next transition time has passed unacted
 */
class ScheduleEngine(
    private val plannedBlocks: PlannedBlockRepository,
    private val actualStates: ActualStateRepository,
    private val clock: Clock
) {

    /** Window before a block start in which the transition counts as UPCOMING. */
    var upcomingWindowSeconds: Long = 600 // 10 minutes

    suspend fun status(now: Instant = clock.now()): ScheduleStatus {
        val dayStart = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - 86_400_000L)
        val dayEnd = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 86_400_000L)

        val blocks = plannedBlocks.getBetween(dayStart, dayEnd)
            .filter { it.status == PlannedBlockStatus.ACTIVE }

        val current = blocks.firstOrNull {
            !it.plannedStart.isAfterInstant(now) && it.plannedEnd.isAfterInstant(now)
        }
        val next = blocks.filter { it.plannedStart.isAfterInstant(now) }.minByOrNull { it.plannedStart }
        val actual = actualStates.getCurrent()

        // --- transition status ---
        val transitionStatus: TransitionStatus = if (next != null) {
            val secondsToStart = (next.plannedStart.toEpochMilliseconds() - now.toEpochMilliseconds()) / 1000
            when {
                secondsToStart <= 0 -> TransitionStatus.OVERDUE
                secondsToStart <= upcomingWindowSeconds -> TransitionStatus.UPCOMING
                else -> TransitionStatus.NONE
            }
        } else if (current != null && !isOnPlan(current, actual)) {
            TransitionStatus.OVERDUE // should be executing this block but isn't
        } else {
            TransitionStatus.NONE
        }

        return ScheduleStatus(
            currentActualState = actual,
            currentPlannedBlock = current,
            nextPlannedBlock = next,
            transitionStatus = transitionStatus,
            deviationSeconds = computeDeviation(current, actual, now)
        )
    }

    /**
     * Positive = behind schedule.
     *  - not executing the planned block: seconds since that block should have started;
     *  - executing it: lateness of the actual start vs planned start;
     *  - nothing planned now: 0.
     */
    private suspend fun computeDeviation(
        current: PlannedBlock?,
        actual: com.example.execution.domain.state.ActualState?,
        now: Instant
    ): Long {
        if (current == null) return 0
        return if (!isOnPlan(current, actual) || actual == null) {
            ((now.toEpochMilliseconds() - current.plannedStart.toEpochMilliseconds()) / 1000).coerceAtLeast(0)
        } else {
            ((actual.startedAt.toEpochMilliseconds() - current.plannedStart.toEpochMilliseconds()) / 1000).coerceAtLeast(0)
        }
    }

    private fun isOnPlan(
        block: PlannedBlock,
        actual: com.example.execution.domain.state.ActualState?
    ): Boolean =
        actual != null && block.activityTypeId != null && actual.activityTypeId == block.activityTypeId

    /** Earliest moment a warning notification should fire for the next transition. */
    suspend fun nextWarningTime(): Instant? {
        val s = status()
        val next = s.nextPlannedBlock ?: return null
        return if (s.transitionStatus == TransitionStatus.NONE) {
            Instant.fromEpochMilliseconds(
                next.plannedStart.toEpochMilliseconds() - upcomingWindowSeconds * 1000
            )
        } else {
            clock.now()
        }
    }

    private fun Instant.isAfterInstant(other: Instant) = this > other
}
