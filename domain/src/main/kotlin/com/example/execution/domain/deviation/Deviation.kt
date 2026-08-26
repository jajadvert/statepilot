package com.example.execution.domain.deviation

import kotlinx.datetime.Instant

enum class DeviationType {
    STARTED_LATE, STARTED_EARLY, ENDED_LATE, ENDED_EARLY,
    SKIPPED, INTERRUPTED, UNPLANNED, RESCHEDULED, OVERRUN
}

data class Deviation(
    val id: String,
    val plannedBlockId: String?,
    val actualStateId: String?,
    val type: DeviationType,
    val amountSeconds: Long?,
    val createdAt: Instant
)
