package com.example.execution.domain.state

import kotlinx.datetime.Instant

data class Transition(
    val id: String,
    val fromStateId: String?,
    val toStateId: String,
    val occurredAt: Instant,
    val source: StateSource,
    val triggerType: TransitionTriggerType,
    val plannedBlockId: String?,
    val requestId: String?
)
