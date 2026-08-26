package com.example.execution.domain.state

enum class TransitionTriggerType {
    MANUAL_START,
    MANUAL_SWITCH,
    MANUAL_FINISH,
    INTERRUPT,
    RESUME,
    SCHEDULE_DUE,
    DELAY,
    SKIP,
    SYSTEM,
    LOCATION_SUGGESTION
}
