package com.example.execution.persistence.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.execution.domain.activity.ActivityType
import com.example.execution.domain.activity.TransitionPolicy
import com.example.execution.domain.deviation.Deviation
import com.example.execution.domain.deviation.DeviationType
import com.example.execution.domain.interruption.Interruption
import com.example.execution.domain.interruption.InterruptionCategory
import com.example.execution.domain.place.Place
import com.example.execution.domain.schedule.PlannedBlock
import com.example.execution.domain.schedule.PlannedBlockStatus
import com.example.execution.domain.state.ActualState
import com.example.execution.domain.state.StateSource
import com.example.execution.domain.state.Transition
import com.example.execution.domain.state.TransitionTriggerType
import kotlinx.datetime.Instant

@Entity(tableName = "planned_blocks")
data class PlannedBlockEntity(
    @PrimaryKey val id: String,
    val externalCalendarId: String?,
    val externalEventId: String?,
    val activityTypeId: String?,
    val title: String,
    val plannedStartEpochMs: Long,
    val plannedEndEpochMs: Long,
    val locationText: String?,
    val placeId: String?,
    val status: String,
    val revision: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
) {
    fun toDomain() = PlannedBlock(
        id = id,
        externalCalendarId = externalCalendarId,
        externalEventId = externalEventId,
        activityTypeId = activityTypeId,
        title = title,
        plannedStart = Instant.fromEpochMilliseconds(plannedStartEpochMs),
        plannedEnd = Instant.fromEpochMilliseconds(plannedEndEpochMs),
        locationText = locationText,
        placeId = placeId,
        status = PlannedBlockStatus.valueOf(status),
        revision = revision,
        createdAt = Instant.fromEpochMilliseconds(createdAtEpochMs),
        updatedAt = Instant.fromEpochMilliseconds(updatedAtEpochMs)
    )

    companion object {
        fun fromDomain(b: PlannedBlock) = PlannedBlockEntity(
            id = b.id,
            externalCalendarId = b.externalCalendarId,
            externalEventId = b.externalEventId,
            activityTypeId = b.activityTypeId,
            title = b.title,
            plannedStartEpochMs = b.plannedStart.toEpochMilliseconds(),
            plannedEndEpochMs = b.plannedEnd.toEpochMilliseconds(),
            locationText = b.locationText,
            placeId = b.placeId,
            status = b.status.name,
            revision = b.revision,
            createdAtEpochMs = b.createdAt.toEpochMilliseconds(),
            updatedAtEpochMs = b.updatedAt.toEpochMilliseconds()
        )
    }
}

@Entity(tableName = "actual_states")
data class ActualStateEntity(
    @PrimaryKey val id: String,
    val activityTypeId: String,
    val plannedBlockId: String?,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val source: String,
    val trigger: String?,
    val resumedFromStateId: String?,
    val note: String?
) {
    fun toDomain() = ActualState(
        id = id,
        activityTypeId = activityTypeId,
        plannedBlockId = plannedBlockId,
        startedAt = Instant.fromEpochMilliseconds(startedAtEpochMs),
        endedAt = endedAtEpochMs?.let { Instant.fromEpochMilliseconds(it) },
        source = StateSource.valueOf(source),
        trigger = trigger?.let { TransitionTriggerType.valueOf(it) },
        resumedFromStateId = resumedFromStateId,
        note = note
    )

    companion object {
        fun fromDomain(s: ActualState) = ActualStateEntity(
            id = s.id,
            activityTypeId = s.activityTypeId,
            plannedBlockId = s.plannedBlockId,
            startedAtEpochMs = s.startedAt.toEpochMilliseconds(),
            endedAtEpochMs = s.endedAt?.toEpochMilliseconds(),
            source = s.source.name,
            trigger = s.trigger?.name,
            resumedFromStateId = s.resumedFromStateId,
            note = s.note
        )
    }
}

@Entity(tableName = "transitions")
data class TransitionEntity(
    @PrimaryKey val id: String,
    val fromStateId: String?,
    val toStateId: String,
    val occurredAtEpochMs: Long,
    val source: String,
    val triggerType: String,
    val plannedBlockId: String?,
    val requestId: String?
) {
    fun toDomain() = Transition(
        id = id,
        fromStateId = fromStateId,
        toStateId = toStateId,
        occurredAt = Instant.fromEpochMilliseconds(occurredAtEpochMs),
        source = StateSource.valueOf(source),
        triggerType = TransitionTriggerType.valueOf(triggerType),
        plannedBlockId = plannedBlockId,
        requestId = requestId
    )

    companion object {
        fun fromDomain(t: Transition) = TransitionEntity(
            id = t.id,
            fromStateId = t.fromStateId,
            toStateId = t.toStateId,
            occurredAtEpochMs = t.occurredAt.toEpochMilliseconds(),
            source = t.source.name,
            triggerType = t.triggerType.name,
            plannedBlockId = t.plannedBlockId,
            requestId = t.requestId
        )
    }
}

@Entity(tableName = "interruptions")
data class InterruptionEntity(
    @PrimaryKey val id: String,
    val interruptedStateId: String,
    val interruptionStateId: String,
    val category: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val resumedStateId: String?
) {
    fun toDomain() = Interruption(
        id = id,
        interruptedStateId = interruptedStateId,
        interruptionStateId = interruptionStateId,
        category = InterruptionCategory.valueOf(category),
        startedAt = Instant.fromEpochMilliseconds(startedAtEpochMs),
        endedAt = endedAtEpochMs?.let { Instant.fromEpochMilliseconds(it) },
        resumedStateId = resumedStateId
    )

    companion object {
        fun fromDomain(i: Interruption) = InterruptionEntity(
            id = i.id,
            interruptedStateId = i.interruptedStateId,
            interruptionStateId = i.interruptionStateId,
            category = i.category.name,
            startedAtEpochMs = i.startedAt.toEpochMilliseconds(),
            endedAtEpochMs = i.endedAt?.toEpochMilliseconds(),
            resumedStateId = i.resumedStateId
        )
    }
}

@Entity(tableName = "deviations")
data class DeviationEntity(
    @PrimaryKey val id: String,
    val plannedBlockId: String?,
    val actualStateId: String?,
    val type: String,
    val amountSeconds: Long?,
    val createdAtEpochMs: Long
) {
    fun toDomain() = Deviation(
        id = id,
        plannedBlockId = plannedBlockId,
        actualStateId = actualStateId,
        type = DeviationType.valueOf(type),
        amountSeconds = amountSeconds,
        createdAt = Instant.fromEpochMilliseconds(createdAtEpochMs)
    )

    companion object {
        fun fromDomain(d: Deviation) = DeviationEntity(
            id = d.id,
            plannedBlockId = d.plannedBlockId,
            actualStateId = d.actualStateId,
            type = d.type.name,
            amountSeconds = d.amountSeconds,
            createdAtEpochMs = d.createdAt.toEpochMilliseconds()
        )
    }
}

@Entity(tableName = "activity_types")
data class ActivityTypeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorKey: String?,
    val iconKey: String?,
    val defaultTransitionPolicy: String
) {
    fun toDomain() = ActivityType(
        id = id,
        name = name,
        colorKey = colorKey,
        iconKey = iconKey,
        defaultTransitionPolicy = TransitionPolicy.valueOf(defaultTransitionPolicy)
    )

    companion object {
        fun fromDomain(a: ActivityType) = ActivityTypeEntity(
            id = a.id,
            name = a.name,
            colorKey = a.colorKey,
            iconKey = a.iconKey,
            defaultTransitionPolicy = a.defaultTransitionPolicy.name
        )
    }
}

@Entity(tableName = "places")
data class PlaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double
) {
    fun toDomain() = Place(id, name, latitude, longitude, radiusMeters)
    companion object {
        fun fromDomain(p: Place) = PlaceEntity(p.id, p.name, p.latitude, p.longitude, p.radiusMeters)
    }
}
