package com.example.execution.domain.activity

enum class TransitionPolicy { MANUAL, SUGGEST, AUTO }

data class ActivityType(
    val id: String,
    val name: String,
    val colorKey: String? = null,
    val iconKey: String? = null,
    val defaultTransitionPolicy: TransitionPolicy = TransitionPolicy.MANUAL
)
