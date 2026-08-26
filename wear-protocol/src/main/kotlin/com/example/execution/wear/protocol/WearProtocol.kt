package com.example.execution.wear.protocol

import kotlinx.serialization.Serializable

@Serializable
data class WearActivityDto(
    val activityTypeId: String,
    val label: String
)

@Serializable
data class WearPlannedBlockDto(
    val id: String,
    val title: String,
    val startEpochMs: Long,
    val endEpochMs: Long
)

@Serializable
data class WearStateDto(
    val revision: Long,
    val currentActivity: WearActivityDto? = null,
    val currentStateStartedAtEpochMs: Long? = null,
    val currentPlannedBlock: WearPlannedBlockDto? = null,
    val nextPlannedBlock: WearPlannedBlockDto? = null,
    val transitionStatus: String = "NONE",
    val deviationSeconds: Long = 0
)

enum class WearCommandType { START_PLANNED, START_ACTIVITY, INTERRUPT, RESUME, DELAY, SKIP, FINISH }

@Serializable
data class WearCommandDto(
    val type: WearCommandType,
    val requestId: String,
    val plannedBlockId: String? = null,
    val activityTypeId: String? = null,
    val delaySeconds: Long? = null
)

/** Revision semantics (§14.2): only state with revision >= current is accepted. */
class WearStateMerger {
    private var currentRevision: Long = -1

    fun accept(state: WearStateDto): Boolean =
        if (state.revision >= currentRevision) {
            currentRevision = state.revision
            true
        } else false

    fun currentRevision(): Long = currentRevision
}


/** Shared Data Layer paths (single source of truth for phone + watch). */
object WearDataLayerPaths {
    const val STATE_PATH = "/statepilot/state"
    const val COMMAND_PATH = "/statepilot/command"
    const val KEY_STATE = "state_json"
}
