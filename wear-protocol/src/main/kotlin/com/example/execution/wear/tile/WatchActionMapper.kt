package com.example.execution.wear.tile

import com.example.execution.wear.cache.WatchDisplayState
import com.example.execution.wear.protocol.WearCommandDto
import com.example.execution.wear.protocol.WearCommandType

/** One action button shown on the watch. */
data class WatchButton(
    val label: String,
    val command: WearCommandDto
)

/** Action ids used by the watch UI (kept stable for instrumentation). */
object WatchActionIds {
    const val INTERRUPT = "watch.interrupt"
    const val RESUME = "watch.resume"
    const val FINISH = "watch.finish"
    const val SKIP = "watch.skip"
    const val START = "watch.start"
    const val DELAY_5 = "watch.delay5"
}

/**
 * Decides which action buttons the watch should show for a display state,
 * and what command each button sends. Pure — fully JVM-testable.
 *
 *  - active non-interruption state  -> [Interrupt] [Finish]
 *  - active interruption state      -> [Resume]
 *  - transition due/overdue         -> [START] [+5] [SKIP]
 */
object WatchActionMapper {

    private val interruptionCategories = setOf(
        "call", "person", "admin", "break", "message", "urgent_task", "other"
    )

    fun buttons(display: WatchDisplayState, requestId: () -> String): List<WatchButton> {
        val state = display.state ?: return emptyList()
        val buttons = mutableListOf<WatchButton>()
        val activityId = state.currentActivity?.activityTypeId

        when {
            activityId != null && activityId in interruptionCategories -> {
                buttons += WatchButton("Resume", WearCommandDto(WearCommandType.RESUME, requestId()))
            }
            activityId != null -> {
                buttons += WatchButton("Interrupt", WearCommandDto(WearCommandType.INTERRUPT, requestId()))
                buttons += WatchButton("Finish", WearCommandDto(WearCommandType.FINISH, requestId()))
            }
        }

        if (state.transitionStatus == "OVERDUE" || state.transitionStatus == "DUE") {
            val targetId = state.nextPlannedBlock?.id ?: state.currentPlannedBlock?.id
            if (targetId != null) {
                buttons += WatchButton("Start", WearCommandDto(WearCommandType.START_PLANNED, requestId(), plannedBlockId = targetId))
                buttons += WatchButton("+5", WearCommandDto(WearCommandType.DELAY, requestId(), plannedBlockId = targetId, delaySeconds = 300))
                buttons += WatchButton("Skip", WearCommandDto(WearCommandType.SKIP, requestId(), plannedBlockId = targetId))
            }
        }

        return buttons
    }
}
