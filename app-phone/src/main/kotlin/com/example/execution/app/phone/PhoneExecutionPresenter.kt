package com.example.execution.app.phone

import com.example.execution.domain.interruption.InterruptionCategory
import com.example.execution.domain.repository.ActualStateRepository
import com.example.execution.domain.repository.PlannedBlockRepository
import com.example.execution.domain.schedule.ScheduleEngine
import com.example.execution.domain.state.*
import com.example.execution.domain.state.StateEngine
import com.example.execution.domain.state.StateResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** What the execution screen renders (§12 layout). Pure data, no Android types. */
data class PhoneUiState(
    val currentLabel: String = "—",
    val currentElapsedSeconds: Long = 0,
    val plannedNowTitle: String = "—",
    val plannedNowWindow: String = "",
    val nextTitle: String = "—",
    val nextStartText: String = "",
    val statusLine: String = "",
    val showResume: Boolean = false,
    val showInterruptionPicker: Boolean = false,
    val watchConnected: Boolean = false,
    val busy: Boolean = false
)

/**
 * Presentation logic for the phone execution screen.
 * No business logic here: it only maps engine output to UI state and forwards
 * user actions as StateCommands. Unit-testable on the JVM.
 */
class PhoneExecutionPresenter(
    private val stateEngine: StateEngine,
    private val scheduleEngine: ScheduleEngine,
    private val actualStates: ActualStateRepository,
    private val plannedBlocks: PlannedBlockRepository,
    private val scope: CoroutineScope,
    private val requestIds: () -> String = { "ui-${requestCounter.incrementAndGet()}" },
    /** Connectivity status of the Wear transport; default no-watch (tests). */
    private val watchConnectedProvider: () -> Boolean = { false }
) {
    private val _ui = MutableStateFlow(PhoneUiState())
    val ui: StateFlow<PhoneUiState> = _ui

    fun start() {
        scope.launch {
            while (scope.isActive) {
                refresh()
                delay(1_000)
            }
        }
    }

    suspend fun refresh() {
        val s = scheduleEngine.status()
        val actual = s.currentActualState
        val now = scheduleEngine.let { it } // clock lives inside engines

        val elapsed = actual?.let {
            // recompute from repository each tick
            (systemNowMs() - it.startedAt.toEpochMilliseconds()) / 1000
        } ?: 0

        _ui.value = PhoneUiState(
            currentLabel = actual?.activityTypeId ?: "—",
            currentElapsedSeconds = elapsed,
            plannedNowTitle = s.currentPlannedBlock?.title ?: "—",
            plannedNowWindow = s.currentPlannedBlock?.let {
                "${it.plannedStart}–${it.plannedEnd}"
            } ?: "",
            nextTitle = s.nextPlannedBlock?.title ?: "—",
            nextStartText = s.nextPlannedBlock?.plannedStart?.toString() ?: "",
            statusLine = statusLine(s.deviationSeconds, s.transitionStatus),
            showResume = isInInterruption(actual),
            // keep transient UI state across the 1s refresh ticks
            showInterruptionPicker = _ui.value.showInterruptionPicker,
            watchConnected = watchConnectedProvider()
        )
    }

    private fun statusLine(deviationSeconds: Long, transitionStatus: com.example.execution.domain.schedule.TransitionStatus): String =
        when {
            deviationSeconds <= 0 -> "On schedule"
            else -> "${deviationSeconds / 60} min behind schedule" +
                if (transitionStatus == com.example.execution.domain.schedule.TransitionStatus.OVERDUE) " · overdue" else ""
        }

    private suspend fun isInInterruption(actual: com.example.execution.domain.state.ActualState?): Boolean =
        actual != null && actual.resumedFromStateId == null &&
            InterruptionCategory.entries.any { it.name.lowercase() == actual.activityTypeId }

    // ---- actions: forward commands only ----

    suspend fun startPlanned(plannedBlockId: String) =
        send(StartPlannedBlock(plannedBlockId, source(), requestIds()))

    suspend fun startActivity(activityTypeId: String) =
        send(StartActivity(activityTypeId, source = source(), requestId = requestIds()))

    suspend fun switchActivity(activityTypeId: String) =
        send(SwitchActivity(activityTypeId, source = source(), requestId = requestIds()))

    suspend fun interrupt(category: InterruptionCategory) =
        send(InterruptCurrentState(category, source(), requestIds()))

    /** Fase 14 UX: open the category picker. */
    fun requestInterruptPicker() {
        _ui.value = _ui.value.copy(showInterruptionPicker = true)
    }

    fun dismissInterruptPicker() {
        _ui.value = _ui.value.copy(showInterruptionPicker = false)
    }

    suspend fun resume() = send(ResumeInterruptedState(source(), requestIds()))

    suspend fun finish() = send(Finish(source(), requestIds()))

    suspend fun skip(plannedBlockId: String) =
        send(SkipPlannedBlock(plannedBlockId, source(), requestIds()))

    suspend fun delay(plannedBlockId: String, seconds: Long) =
        send(DelayPlannedBlock(plannedBlockId, seconds, source(), requestIds()))

    private fun source() = com.example.execution.domain.state.StateSource.PHONE

    private suspend fun send(command: StateCommand): Boolean {
        _ui.value = _ui.value.copy(busy = true)
        val result = try {
            stateEngine.execute(command)
        } finally {
            refresh()
            _ui.value = _ui.value.copy(busy = false)
        }
        return result is StateResult.Success
    }

    companion object {
        private val requestCounter = AtomicLong(0)
        var systemNowMs: () -> Long = { System.currentTimeMillis() }
    }

    @Suppress("unused")
    private val unusedRepoRef = plannedBlocks // kept for future block-picker screen
}
