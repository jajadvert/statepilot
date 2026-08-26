package com.example.execution.wear

import com.example.execution.domain.repository.ActualStateRepository
import com.example.execution.domain.schedule.PlannedBlock
import com.example.execution.domain.repository.PlannedBlockRepository
import com.example.execution.domain.schedule.ScheduleEngine
import com.example.execution.domain.state.StateCommand
import com.example.execution.domain.state.StateEngine
import com.example.execution.domain.state.StateResult
import com.example.execution.domain.state.Finish
import com.example.execution.domain.state.StartPlannedBlock
import com.example.execution.domain.state.StartActivity
import com.example.execution.domain.state.SwitchActivity
import com.example.execution.domain.state.InterruptCurrentState
import com.example.execution.domain.state.ResumeInterruptedState
import com.example.execution.domain.state.DelayPlannedBlock
import com.example.execution.domain.state.SkipPlannedBlock
import com.example.execution.wear.protocol.WearActivityDto
import com.example.execution.wear.protocol.WearCommandDto
import com.example.execution.wear.protocol.WearCommandType
import com.example.execution.wear.protocol.WearPlannedBlockDto
import com.example.execution.wear.protocol.WearStateDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant

/**
 * WearTransport abstraction (Fase 9, §15). Two directions:
 *  - phone -> watch: state Flow (published by PhoneWearBridge)
 *  - watch -> phone: commands
 */
interface WearTransport {
    val states: Flow<WearStateDto>
    /** Publish the current state to the paired device (no-op safe offline). */
    suspend fun publish(state: WearStateDto): Boolean
    suspend fun sendCommand(command: WearCommandDto): Result<Unit>
}

/** Test double: full Wear UI runs without a real phone or watch (§15 DoD). */
class FakeWearTransport : WearTransport {
    private val _states = MutableStateFlow(WearStateDto(revision = 0))
    override val states: Flow<WearStateDto> = _states

    val sentCommands = mutableListOf<WearCommandDto>()
    var failCommands = false

    override suspend fun publish(state: WearStateDto): Boolean {
        _states.value = state
        return true
    }

    override suspend fun sendCommand(command: WearCommandDto): Result<Unit> =
        if (failCommands) Result.failure(IllegalStateException("Phone unavailable"))
        else { sentCommands.add(command); Result.success(Unit) }
}

/**
 * Phone side: projects ScheduleEngine + StateEngine output to WearStateDto and
 * publishes it with a monotonic revision. Executes incoming watch commands.
 */
class PhoneWearBridge(
    private val scheduleEngine: ScheduleEngine,
    private val stateEngine: StateEngine,
    @Suppress("UNUSED_PARAMETER") actualStates: ActualStateRepository,
    @Suppress("UNUSED_PARAMETER") plannedBlocks: PlannedBlockRepository,
    private val transport: WearTransport
) {
    private var revision = 0L

    suspend fun publishState(now: Instant) {
        val s = scheduleEngine.status(now)
        revision += 1
        transport.publish(
            WearStateDto(
                revision = revision,
                currentActivity = s.currentActualState?.let {
                    WearActivityDto(it.activityTypeId, labelFor(it.activityTypeId))
                },
                currentStateStartedAtEpochMs = s.currentActualState?.startedAt?.toEpochMilliseconds(),
                currentPlannedBlock = s.currentPlannedBlock?.toDto(),
                nextPlannedBlock = s.nextPlannedBlock?.toDto(),
                transitionStatus = s.transitionStatus.name,
                deviationSeconds = s.deviationSeconds
            )
        )
    }

    /** Watch command -> StateEngine command. Idempotent via requestId. */
    suspend fun onWatchCommand(command: WearCommandDto): Boolean {
        val source = com.example.execution.domain.state.StateSource.WATCH
        val cmd: StateCommand? = when (command.type) {
            WearCommandType.START_PLANNED -> command.plannedBlockId?.let {
                StartPlannedBlock(it, source, command.requestId)
            }
            WearCommandType.START_ACTIVITY -> command.activityTypeId?.let {
                StartActivity(it, source = source, requestId = command.requestId)
            }
            WearCommandType.INTERRUPT ->
                InterruptCurrentState(
                    command.category
                        ?.let { runCatching { com.example.execution.domain.interruption.InterruptionCategory.valueOf(it) }.getOrNull() }
                        ?: com.example.execution.domain.interruption.InterruptionCategory.OTHER,
                    source, command.requestId
                )
            WearCommandType.RESUME -> ResumeInterruptedState(source, command.requestId)
            WearCommandType.DELAY -> command.plannedBlockId?.let {
                DelayPlannedBlock(it, command.delaySeconds ?: 600, source, command.requestId)
            }
            WearCommandType.SKIP -> command.plannedBlockId?.let { SkipPlannedBlock(it, source, command.requestId) }
            WearCommandType.FINISH -> Finish(source, command.requestId)
        }
        if (cmd == null) return false
        return stateEngine.execute(cmd).let { it is StateResult.Success }
    }

    private fun PlannedBlock.toDto() = WearPlannedBlockDto(
        id = id, title = title,
        startEpochMs = plannedStart.toEpochMilliseconds(),
        endEpochMs = plannedEnd.toEpochMilliseconds()
    )

    private fun labelFor(activityTypeId: String): String =
        activityTypeId.split("_").joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
}
