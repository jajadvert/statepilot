package com.example.execution.domain.repository

import com.example.execution.domain.activity.ActivityType
import com.example.execution.domain.deviation.Deviation
import com.example.execution.domain.interruption.Interruption
import com.example.execution.domain.place.Place
import com.example.execution.domain.schedule.PlannedBlock
import com.example.execution.domain.state.ActualState
import com.example.execution.domain.state.Transition
import kotlinx.datetime.Instant

interface ActivityTypeRepository {
    suspend fun getById(id: String): ActivityType?
    suspend fun getAll(): List<ActivityType>
    suspend fun upsert(type: ActivityType)
}

interface PlannedBlockRepository {
    suspend fun getById(id: String): PlannedBlock?
    suspend fun findByExternalEventId(externalEventId: String): PlannedBlock?
    suspend fun getBetween(from: Instant, to: Instant): List<PlannedBlock>
    suspend fun upsert(block: PlannedBlock)
}

interface ActualStateRepository {
    /** Returns the single active (not ended) state, or null. Enforces MAX 1 invariant. */
    suspend fun getCurrent(): ActualState?
    suspend fun getById(id: String): ActualState?
    suspend fun insert(state: ActualState)
    /** Closes a state; closed states are immutable afterwards. */
    suspend fun finish(id: String, endedAt: Instant)
    suspend fun getHistory(from: Instant, to: Instant): List<ActualState>
}

interface TransitionRepository {
    suspend fun insert(transition: Transition)
    suspend fun getByRequestId(requestId: String): Transition?
    suspend fun getByState(stateId: String): List<Transition>
}

interface InterruptionRepository {
    suspend fun insert(interruption: Interruption)
    suspend fun getById(id: String): Interruption?
    suspend fun update(interruption: Interruption)
    suspend fun getOpenForState(activeInterruptionStateId: String): Interruption?
    /** All interruptions recorded against an interrupted (parent) state. */
    suspend fun getInterruptionsFor(interruptedStateId: String): List<Interruption>
}

interface DeviationRepository {
    suspend fun insert(deviation: Deviation)
    suspend fun getByPlannedBlock(plannedBlockId: String): List<Deviation>
    suspend fun getAll(): List<Deviation>
}

interface PlaceRepository {
    suspend fun getById(id: String): Place?
    suspend fun upsert(place: Place)
}
