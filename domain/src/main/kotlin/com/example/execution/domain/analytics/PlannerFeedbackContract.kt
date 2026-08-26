package com.example.execution.domain.analytics

import kotlinx.datetime.Instant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * Fase 19 (§23): planner-feedback-contract.
 * Stabiel, versioned JSON-contract dat de planner (of een extern systeem)
 * kan importeren om de kwaliteit van planningen te verbeteren.
 *
 * Contractversie wordt mee-geserialiseerd; het formaat is additief,
 * zodat oudere planners nooit breken.
 */
@Serializable
data class PlannerFeedbackContract(
    @EncodeDefault
    val contractVersion: Int = 1,
    val generatedAtEpochMs: Long,
    val perActivity: List<ActivityFeedback>,
    val overall: OverallFeedback
)

@Serializable
data class ActivityFeedback(
    val activityTypeId: String,
    val medianPlannedDurationMinutes: Long,
    val medianActualDurationMinutes: Long?,
    val medianStartDelaySeconds: Long,
    val medianEndDeviationSeconds: Long?,
    val executionRate: Double,        // 0.0..1.0 — aandeel niet-overgeslagen blokken
    val sampleCount: Int,
    val samples: List<ActivitySample> // max 30 recentste, voor herleidbaarheid
)

@Serializable
data class ActivitySample(
    val plannedBlockId: String,
    val activityTypeId: String?,
    val dateEpochDay: Long,
    val plannedDurationMinutes: Long,
    val actualDurationMinutes: Long?,
    val startDelaySeconds: Long?,
    val endDeviationSeconds: Long?,
    val skipped: Boolean
)

@Serializable
data class OverallFeedback(
    val totalBlocks: Int,
    val totalExecutedBlocks: Int,
    val overallExecutionRate: Double,
    val medianStartDelaySeconds: Long
)

/**
 * Builds the contract from DayReports. Pure function of the input data —
 * deterministic and fully JVM-testable.
 */
object PlannerFeedbackBuilder {

    fun build(reports: List<DayReport>, generatedAt: Instant = Instant.fromEpochMilliseconds(0)): PlannerFeedbackContract {
        val samples = reports.flatMap { day ->
            day.blocks.map { b ->
                ActivitySample(
                    plannedBlockId = b.plannedBlockId,
                    activityTypeId = b.activityTypeId,
                    dateEpochDay = day.dateEpochDay,
                    plannedDurationMinutes = b.plannedDurationMinutes,
                    actualDurationMinutes = b.actualDurationMinutes,
                    startDelaySeconds = b.startDelaySeconds,
                    endDeviationSeconds = b.endDeviationSeconds,
                    skipped = b.skipped
                )
            }
        }
        val byActivity = samples.groupBy { activityOf(it) }

        val perActivity = byActivity.map { (activity, s) ->
            val executed = s.filter { !it.skipped }
            ActivityFeedback(
                activityTypeId = activity,
                medianPlannedDurationMinutes = median(s.map { it.plannedDurationMinutes.toLong() }) ?: 0,
                medianActualDurationMinutes = median(executed.mapNotNull { it.actualDurationMinutes }),
                medianStartDelaySeconds = median(executed.mapNotNull { it.startDelaySeconds }) ?: 0,
                medianEndDeviationSeconds = median(executed.mapNotNull { it.endDeviationSeconds }),
                executionRate = if (s.isEmpty()) 0.0 else executed.size.toDouble() / s.size,
                sampleCount = s.size,
                samples = s.sortedBy { it.dateEpochDay }.takeLast(30)
            )
        }.sortedBy { it.activityTypeId }

        val allExecuted = samples.filter { !it.skipped }
        return PlannerFeedbackContract(
            generatedAtEpochMs = generatedAt.toEpochMilliseconds(),
            perActivity = perActivity,
            overall = OverallFeedback(
                totalBlocks = samples.size,
                totalExecutedBlocks = allExecuted.size,
                overallExecutionRate = if (samples.isEmpty()) 0.0 else allExecuted.size.toDouble() / samples.size,
                medianStartDelaySeconds = median(allExecuted.mapNotNull { it.startDelaySeconds }) ?: 0
            )
        )
    }

    private fun activityOf(sample: ActivitySample): String =
        sample.activityTypeId ?: sample.plannedBlockId.substringBefore("-")

    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
