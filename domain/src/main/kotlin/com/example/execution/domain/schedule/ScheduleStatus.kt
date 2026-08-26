package com.example.execution.domain.schedule

enum class TransitionStatus { NONE, UPCOMING, DUE, OVERDUE }

data class ScheduleStatus(
    val currentActualState: com.example.execution.domain.state.ActualState?,
    val currentPlannedBlock: PlannedBlock?,
    val nextPlannedBlock: PlannedBlock?,
    val transitionStatus: TransitionStatus,
    val deviationSeconds: Long
)
