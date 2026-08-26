package com.example.execution.domain.state

import com.example.execution.domain.deviation.Deviation
import com.example.execution.domain.deviation.DeviationType
import com.example.execution.domain.interruption.Interruption
import com.example.execution.domain.interruption.InterruptionCategory
import com.example.execution.domain.repository.ActualStateRepository
import com.example.execution.domain.repository.DeviationRepository
import com.example.execution.domain.repository.InterruptionRepository
import com.example.execution.domain.repository.PlannedBlockRepository
import com.example.execution.domain.repository.TransitionRepository
import com.example.execution.domain.schedule.PlannedBlockStatus
import com.example.execution.domain.time.Clock
import kotlinx.datetime.Instant

/**
 * Pure state engine (§8): no UI, calendar, wear or GPS dependencies.
 * All external commands are idempotent via requestId.
 */
class StateEngine(
    private val actualStates: ActualStateRepository,
    private val transitions: TransitionRepository,
    private val interruptions: InterruptionRepository,
    private val plannedBlocks: PlannedBlockRepository,
    private val deviations: DeviationRepository,
    private val clock: Clock,
    private val idGenerator: () -> String
) {

    suspend fun execute(command: StateCommand): StateResult {
        // Idempotency: a requestId may never execute twice.
        if (command.requestId.isNotEmpty()) {
            transitions.getByRequestId(command.requestId)?.let {
                return StateResult.IdempotentReplay(it.id)
            }
        }
        return try {
            when (command) {
                is StartPlannedBlock -> startPlannedBlock(command)
                is StartActivity -> startActivity(command.activityTypeId, command.source, command.requestId, null, command.note)
                is SwitchActivity -> startActivity(command.activityTypeId, command.source, command.requestId, TransitionTriggerType.MANUAL_SWITCH, command.note)
                is Finish -> finish(command.source, command.requestId)
                is InterruptCurrentState -> interrupt(command.category, command.source, command.requestId)
                is ResumeInterruptedState -> resume(command.source, command.requestId)
                is SkipPlannedBlock -> skip(command)
                is DelayPlannedBlock -> delay(command)
            }
        } catch (e: StateEngineError) {
            StateResult.Failure(e)
        }
    }

    private suspend fun startPlannedBlock(command: StartPlannedBlock): StateResult {
        val block = plannedBlocks.getById(command.plannedBlockId)
            ?: throw StateEngineError.NoPlannedBlock
        require(block.status == PlannedBlockStatus.ACTIVE) {
            throw StateEngineError.CommandRejected("planned block ${block.id} is CANCELLED")
        }
        return startActivity(
            activityTypeId = block.activityTypeId ?: "other",
            source = command.source,
            requestId = command.requestId,
            trigger = TransitionTriggerType.SCHEDULE_DUE,
            note = null,
            plannedBlockId = block.id
        )
    }

    private suspend fun startActivity(
        activityTypeId: String,
        source: StateSource,
        requestId: String,
        trigger: TransitionTriggerType?,
        note: String?,
        plannedBlockId: String? = null
    ): StateResult {
        val now = clock.now()
        val previous = actualStates.getCurrent()

        recordStartDeviation(previous, plannedBlockId, now)

        val newState = ActualState(
            id = idGenerator(),
            activityTypeId = activityTypeId,
            plannedBlockId = plannedBlockId,
            startedAt = now,
            endedAt = null,
            source = source,
            trigger = trigger,
            resumedFromStateId = null,
            note = note
        )
        closePreviousAndInsert(previous, newState, source, trigger ?: TransitionTriggerType.MANUAL_START, requestId)
        return StateResult.Success(newState, transitions.getByRequestId(requestId))
    }

    private suspend fun finish(source: StateSource, requestId: String): StateResult {
        val current = actualStates.getCurrent() ?: throw StateEngineError.NoCurrentState
        val now = clock.now()
        actualStates.finish(current.id, now)

        // Close any open interruption against this state's parent chain.
        val openAtFinish = interruptions.getOpenForState(current.id)
        if (openAtFinish != null) {
            interruptions.update(openAtFinish.copy(endedAt = now))
        }

        val transition = newTransition(fromStateId = current.id, toStateId = "", occurredAt = now, source = source,
            triggerType = TransitionTriggerType.MANUAL_FINISH, plannedBlockId = current.plannedBlockId, requestId = requestId)
        return StateResult.Success(null, transition)
    }

    private suspend fun interrupt(category: InterruptionCategory, source: StateSource, requestId: String): StateResult {
        val interrupted = actualStates.getCurrent() ?: throw StateEngineError.NoCurrentState
        val now = clock.now()

        val interruptionState = ActualState(
            id = idGenerator(),
            activityTypeId = category.name.lowercase(),
            plannedBlockId = interrupted.plannedBlockId,
            startedAt = now,
            endedAt = null,
            source = source,
            trigger = TransitionTriggerType.INTERRUPT,
            resumedFromStateId = null,
            note = "interruption of ${interrupted.id}"
        )
        closePreviousAndInsert(interrupted, interruptionState, source, TransitionTriggerType.INTERRUPT, requestId)

        val record = Interruption(
            id = idGenerator(),
            interruptedStateId = interrupted.id,
            interruptionStateId = interruptionState.id,
            category = category,
            startedAt = now,
            endedAt = null,
            resumedStateId = null
        )
        interruptions.insert(record)
        deviations.insert(
            Deviation(idGenerator(), interrupted.plannedBlockId, interrupted.id, DeviationType.INTERRUPTED, null, now)
        )
        return StateResult.Success(interruptionState, transitions.getByRequestId(requestId))
    }

    private suspend fun resume(source: StateSource, requestId: String): StateResult {
        val current = actualStates.getCurrent() ?: throw StateEngineError.NoCurrentState
        val openInterruption = interruptions.getOpenForState(current.id)
            ?: throw StateEngineError.NoInterruptedState
        val original = actualStates.getById(openInterruption.interruptedStateId)
            ?: throw StateEngineError.NoInterruptedState
        val now = clock.now()

        // Resume creates a NEW state; the original historical state is never mutated.
        val resumed = ActualState(
            id = idGenerator(),
            activityTypeId = original.activityTypeId,
            plannedBlockId = original.plannedBlockId,
            startedAt = now,
            endedAt = null,
            source = source,
            trigger = TransitionTriggerType.RESUME,
            resumedFromStateId = original.id,
            note = null
        )
        closePreviousAndInsert(current, resumed, source, TransitionTriggerType.RESUME, requestId)

        interruptions.update(
            openInterruption.copy(endedAt = now, resumedStateId = resumed.id)
        )
        return StateResult.Success(resumed, transitions.getByRequestId(requestId))
    }

    private suspend fun skip(command: SkipPlannedBlock): StateResult {
        val block = plannedBlocks.getById(command.plannedBlockId) ?: throw StateEngineError.NoPlannedBlock
        val now = clock.now()
        deviations.insert(
            Deviation(idGenerator(), block.id, null, DeviationType.SKIPPED, null, now)
        )
        val transition = newTransition(null, "", now, command.source, TransitionTriggerType.SKIP, block.id, command.requestId)
        return StateResult.Success(actualStates.getCurrent(), transition)
    }

    private suspend fun delay(command: DelayPlannedBlock): StateResult {
        val block = plannedBlocks.getById(command.plannedBlockId) ?: throw StateEngineError.NoPlannedBlock
        val now = clock.now()
        // Future planning may change; history stays intact. Only shift future times forward.
        val startShifted = Instant.fromEpochMilliseconds(
            block.plannedStart.toEpochMilliseconds() + command.delaySeconds * 1_000L
        )
        val endShifted = Instant.fromEpochMilliseconds(
            maxOf(block.plannedEnd.toEpochMilliseconds(), startShifted.toEpochMilliseconds())
        )
        val shifted = block.copy(
            plannedStart = startShifted,
            plannedEnd = endShifted,
            updatedAt = now
        )
        plannedBlocks.upsert(shifted)
        deviations.insert(
            Deviation(idGenerator(), block.id, null, DeviationType.RESCHEDULED, command.delaySeconds, now)
        )
        val transition = newTransition(null, "", now, command.source, TransitionTriggerType.DELAY, block.id, command.requestId)
        return StateResult.Success(actualStates.getCurrent(), transition)
    }

    /** MAX 1 active ActualState invariant: starting a new state closes the previous one. */
    private suspend fun closePreviousAndInsert(
        previous: ActualState?,
        next: ActualState,
        source: StateSource,
        trigger: TransitionTriggerType,
        requestId: String
    ) {
        val now = clock.now()
        previous?.let { actualStates.finish(it.id, now) }
        actualStates.insert(next)
        val transition = newTransition(
            fromStateId = previous?.id,
            toStateId = next.id,
            occurredAt = now,
            source = source,
            triggerType = trigger,
            plannedBlockId = next.plannedBlockId,
            requestId = requestId
        )
    }

    private suspend fun newTransition(
        fromStateId: String?,
        toStateId: String,
        occurredAt: Instant,
        source: StateSource,
        triggerType: TransitionTriggerType,
        plannedBlockId: String?,
        requestId: String
    ): Transition {
        val t = Transition(
            id = idGenerator(),
            fromStateId = fromStateId,
            toStateId = toStateId,
            occurredAt = occurredAt,
            source = source,
            triggerType = triggerType,
            plannedBlockId = plannedBlockId,
            requestId = requestId.ifEmpty { null }
        )
        transitions.insert(t)
        return t
    }

    private suspend fun recordStartDeviation(previous: ActualState?, plannedBlockId: String?, now: Instant) {
        if (plannedBlockId == null || previous != null) return
        val block = plannedBlocks.getById(plannedBlockId) ?: return
        val delta = (now - block.plannedStart).inWholeSeconds
        val type = when {
            delta > 0 -> DeviationType.STARTED_LATE
            delta < 0 -> DeviationType.STARTED_EARLY
            else -> return
        }
        deviations.insert(Deviation(idGenerator(), plannedBlockId, null, type, kotlin.math.abs(delta), now))
    }
}
