package com.example.execution.wear.tile

import com.example.execution.wear.cache.WatchDisplayState
import com.example.execution.wear.protocol.WearStateDto

/** Pure tile layout model — no Android types, fully JVM-testable. */
data class TileLayoutModel(
    val title: String,
    val subtitle: String?,
    val buttons: List<TileButton>,
    val empty: Boolean = false
)

/** One tappable tile button; actionId is the contract with the TileService. */
data class TileButton(
    val label: String,
    val actionId: String
)

object TileActionIds {
    const val START = "action.start"
    const val INTERRUPT = "action.interrupt"
    const val RESUME = "action.resume"
    const val FINISH = "action.finish"
    const val DELAY_PLUS_5 = "action.delay_5"
    const val NONE = ""
}

/**
 * Fase 13: maps watch state to the tile layout (pure logic).
 *
 * Normal view:
 *   DEEP WORK
 *   1h18
 *   NEXT
 *   Travel · 11:00
 *   [Interrupt]
 *
 * Transition view (due/overdue):
 *   TRAVEL DUE
 *   [START]
 *   [+5]
 */
object TileStateMapper {

    /** Injectable for deterministic tests. */
    var nowEpochMs: () -> Long = { System.currentTimeMillis() }

    fun map(display: WatchDisplayState): TileLayoutModel {
        val state = display.state ?: return TileLayoutModel(
            title = "StatePilot",
            subtitle = "No activity",
            buttons = emptyList(),
            empty = true
        )

        // Transition due: next planned block is due/overdue -> action buttons
        if (state.transitionStatus == "OVERDUE" || state.transitionStatus == "DUE") {
            val next = state.nextPlannedBlock ?: state.currentPlannedBlock
            return TileLayoutModel(
                title = (next?.title ?: "Transition").uppercase() + " DUE",
                subtitle = null,
                buttons = listOf(
                    TileButton("START", TileActionIds.START),
                    TileButton("+5", TileActionIds.DELAY_PLUS_5)
                )
            )
        }

        val current = state.currentActivity
        val nextBlock = state.nextPlannedBlock
        return TileLayoutModel(
            title = current?.label?.uppercase() ?: "No current activity",
            subtitle = when {
                current != null -> formatElapsed(state.currentStateStartedAtEpochMs)
                else -> null
            },
            buttons = when {
                current != null && display.fromCache -> listOf(TileButton("Resume", TileActionIds.RESUME))
                current != null -> listOf(TileButton("Interrupt", TileActionIds.INTERRUPT))
                nextBlock != null -> listOf(TileButton("Start", TileActionIds.START))
                else -> emptyList()
            }
        )
    }

    /** NEXT line is rendered separately by the UI; expose helper for tests. */
    fun nextLine(state: WearStateDto): String? {
        val next = state.nextPlannedBlock ?: return null
        val time = java.time.Instant.ofEpochMilli(next.startEpochMs)
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalTime()
        val hhmm = "%02d:%02d".format(time.hour, time.minute)
        return "${next.title} · $hhmm"
    }

    private fun formatElapsed(startedAtEpochMs: Long?): String {
        if (startedAtEpochMs == null) return ""
        val minutes = ((nowEpochMs() - startedAtEpochMs) / 60_000).coerceAtLeast(0)
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h$m" else "${m}m"
    }
}
