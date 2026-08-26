package com.example.execution.domain.analytics

import com.example.execution.domain.repository.ActualStateRepository
import com.example.execution.domain.repository.DeviationRepository
import com.example.execution.domain.repository.InterruptionRepository
import com.example.execution.domain.schedule.PlannedBlock
import com.example.execution.domain.schedule.PlannedBlockStatus
import com.example.execution.domain.repository.PlannedBlockRepository
import kotlinx.datetime.Instant

/** Per-block execution analysis (Fase 15, §21). */
data class BlockAnalysis(
    val plannedBlockId: String,
    val activityTypeId: String?,
    val title: String,
    val plannedDurationMinutes: Long,
    val actualDurationMinutes: Long?,
    val startDelaySeconds: Long?,        // positive = late
    val endDeviationSeconds: Long?,      // positive = ended later than planned
    val interruptionSeconds: Long,
    val netFocusedSeconds: Long?,        // actual duration minus interruptions
    val skipped: Boolean,
    val fragmentCount: Int               // how many ActualStates executed this block
)

data class DayReport(
    val dateEpochDay: Long,
    val blocks: List<BlockAnalysis>,
    val unplannedStates: List<UnplannedSummary>
)

data class UnplannedSummary(
    val activityTypeId: String,
    val totalSeconds: Long
)

/**
 * Pure analytics engine. Reads repositories only; fully deterministic via the
 * data inside them (no clock dependency — analyses a closed day).
 */
class DayAnalyzer(
    private val plannedBlocks: PlannedBlockRepository,
    private val actualStates: ActualStateRepository,
    private val interruptions: InterruptionRepository,
    private val deviations: DeviationRepository
) {

    suspend fun analyzeDay(dayStart: Instant, dayEnd: Instant): DayReport {
        val blocks = plannedBlocks.getBetween(dayStart, dayEnd)
            .filter { it.status == PlannedBlockStatus.ACTIVE }
        val states = actualStates.getHistory(dayStart, dayEnd)
        val skippedBlockIds = deviations.getAll().filter { it.type == com.example.execution.domain.deviation.DeviationType.SKIPPED }
            .mapNotNull { it.plannedBlockId }.toSet()

        val analyses = blocks.map { block -> analyzeBlock(block, states.filter { it.plannedBlockId == block.id }, skippedBlockIds.contains(block.id)) }

        // Unplanned activity: states without plannedBlockId (excluding interruptions).
        val unplanned = states.filter { it.plannedBlockId == null && !isInterruptionState(it) }
            .groupBy { it.activityTypeId }
            .map { (type, list) ->
                UnplannedSummary(type, list.sumOf { st ->
                    ((st.endedAt?.toEpochMilliseconds() ?: dayEnd.toEpochMilliseconds()) - st.startedAt.toEpochMilliseconds()) / 1000
                })
            }

        return DayReport(
            dateEpochDay = dayStart.toEpochMilliseconds() / 86_400_000L,
            blocks = analyses,
            unplannedStates = unplanned
        )
    }

    private suspend fun analyzeBlock(block: PlannedBlock, fragments: List<com.example.execution.domain.state.ActualState>, skipped: Boolean): BlockAnalysis {
        val plannedDurationSec = (block.plannedEnd.toEpochMilliseconds() - block.plannedStart.toEpochMilliseconds()) / 1000

        if (fragments.isEmpty() || skipped) {
            return BlockAnalysis(
                plannedBlockId = block.id, activityTypeId = block.activityTypeId, title = block.title,
                plannedDurationMinutes = plannedDurationSec / 60,
                actualDurationMinutes = null, startDelaySeconds = null,
                endDeviationSeconds = null, interruptionSeconds = 0,
                netFocusedSeconds = null, skipped = skipped || fragments.isEmpty(),
                fragmentCount = fragments.size
            )
        }

        val firstStart = fragments.minOf { it.startedAt }
        val lastEnd = fragments.mapNotNull { it.endedAt }.maxOrNull()
        val actualSec = lastEnd?.let { (it.toEpochMilliseconds() - firstStart.toEpochMilliseconds()) / 1000 }

        // Interruptions attached to any fragment of this block.
        var interruptionSec = 0L
        for (f in fragments) {
            interruptions.getInterruptionsFor(f.id)?.forEach { i ->
                val end = i.endedAt?.toEpochMilliseconds() ?: f.endedAt?.toEpochMilliseconds()
                if (end != null) interruptionSec += (end - i.startedAt.toEpochMilliseconds()) / 1000
            }
        }

        val startDelay = (firstStart.toEpochMilliseconds() - block.plannedStart.toEpochMilliseconds()) / 1000
        val endDeviation = lastEnd?.let { (it.toEpochMilliseconds() - block.plannedEnd.toEpochMilliseconds()) / 1000 }

        return BlockAnalysis(
            plannedBlockId = block.id, activityTypeId = block.activityTypeId, title = block.title,
            plannedDurationMinutes = plannedDurationSec / 60,
            actualDurationMinutes = actualSec?.div(60),
            startDelaySeconds = startDelay,
            endDeviationSeconds = endDeviation,
            interruptionSeconds = interruptionSec,
            netFocusedSeconds = actualSec?.minus(interruptionSec),
            skipped = false,
            fragmentCount = fragments.size
        )
    }

    /** Interruption states carry category names as their activityTypeId. */
    private fun isInterruptionState(state: com.example.execution.domain.state.ActualState): Boolean =
        com.example.execution.domain.interruption.InterruptionCategory.entries
            .any { it.name.lowercase() == state.activityTypeId } ||
            state.note?.startsWith("interruption of") == true
}
