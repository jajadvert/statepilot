package com.example.execution.domain.state

import kotlinx.datetime.Instant

enum class StateSource { PHONE, WATCH, NOTIFICATION, SYSTEM, LOCATION, IMPORT }

data class ActualState(
    val id: String,
    val activityTypeId: String,
    val plannedBlockId: String?,
    val startedAt: Instant,
    val endedAt: Instant?,
    val source: StateSource,
    val trigger: TransitionTriggerType?,
    val resumedFromStateId: String?,
    val note: String?
)
