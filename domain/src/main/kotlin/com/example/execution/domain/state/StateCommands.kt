package com.example.execution.domain.state

sealed interface StateCommand {
    val source: StateSource
    val requestId: String
}

data class StartPlannedBlock(
    val plannedBlockId: String,
    override val source: StateSource,
    override val requestId: String
) : StateCommand

data class StartActivity(
    val activityTypeId: String,
    val note: String? = null,
    override val source: StateSource,
    override val requestId: String
) : StateCommand

data class Finish(
    override val source: StateSource,
    override val requestId: String
) : StateCommand

data class InterruptCurrentState(
    val category: com.example.execution.domain.interruption.InterruptionCategory,
    override val source: StateSource,
    override val requestId: String
) : StateCommand

data class ResumeInterruptedState(
    override val source: StateSource,
    override val requestId: String
) : StateCommand

data class SwitchActivity(
    val activityTypeId: String,
    val note: String? = null,
    override val source: StateSource,
    override val requestId: String
) : StateCommand

data class SkipPlannedBlock(
    val plannedBlockId: String,
    override val source: StateSource,
    override val requestId: String
) : StateCommand

data class DelayPlannedBlock(
    val plannedBlockId: String,
    val delaySeconds: Long,
    override val source: StateSource,
    override val requestId: String
) : StateCommand
