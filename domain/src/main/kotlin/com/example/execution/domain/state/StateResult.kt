package com.example.execution.domain.state

/** Explicit error states (§35); UI may not silently ignore these. */
sealed class StateEngineError(message: String? = null) : Exception(message) {
    object NoCurrentState : StateEngineError("no current state")
    object NoPlannedBlock : StateEngineError("planned block not found")
    object NoInterruptedState : StateEngineError("no interrupted state to resume")
    class CommandRejected(reason: String) : StateEngineError(reason)
}

sealed class StateResult {
    data class Success(
        val newState: ActualState?,
        val transition: Transition?
    ) : StateResult()

    /** Duplicate requestId: command already applied, no new effect. */
    data class IdempotentReplay(val originalTransitionId: String) : StateResult()

    data class Failure(val error: StateEngineError) : StateResult()
}
