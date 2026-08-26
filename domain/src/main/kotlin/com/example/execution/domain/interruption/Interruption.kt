package com.example.execution.domain.interruption

import kotlinx.datetime.Instant

enum class InterruptionCategory { CALL, PERSON, ADMIN, BREAK, MESSAGE, URGENT_TASK, OTHER }

data class Interruption(
    val id: String,
    val interruptedStateId: String,
    val interruptionStateId: String,
    val category: InterruptionCategory,
    val startedAt: Instant,
    val endedAt: Instant?,
    val resumedStateId: String?
)
