package com.example.execution.domain.schedule

import kotlinx.datetime.Instant

enum class PlannedBlockStatus { ACTIVE, CANCELLED }

data class PlannedBlock(
    val id: String,
    val externalCalendarId: String? = null,
    val externalEventId: String? = null,
    val activityTypeId: String? = null,
    val title: String,
    val plannedStart: Instant,
    val plannedEnd: Instant,
    val locationText: String? = null,
    val placeId: String? = null,
    val status: PlannedBlockStatus = PlannedBlockStatus.ACTIVE,
    val revision: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant
)
