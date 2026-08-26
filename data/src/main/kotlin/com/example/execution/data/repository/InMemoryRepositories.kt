package com.example.execution.data.repository

import com.example.execution.domain.activity.ActivityType
import com.example.execution.domain.deviation.Deviation
import com.example.execution.domain.interruption.Interruption
import com.example.execution.domain.place.Place
import com.example.execution.domain.repository.*
import com.example.execution.domain.schedule.PlannedBlock
import com.example.execution.domain.state.Transition
import kotlinx.datetime.Instant

class InMemoryPlannedBlockRepository : PlannedBlockRepository {
    private val blocks = linkedMapOf<String, PlannedBlock>()

    override suspend fun getById(id: String): PlannedBlock? = blocks[id]

    override suspend fun findByExternalEventId(externalEventId: String): PlannedBlock? =
        blocks.values.firstOrNull { it.externalEventId == externalEventId }

    override suspend fun getBetween(from: Instant, to: Instant): List<PlannedBlock> =
        blocks.values.filter { it.plannedStart < to && it.plannedEnd > from }.sortedBy { it.plannedStart }

    override suspend fun upsert(block: PlannedBlock) {
        // Calendar sync may update future planning but never destroys existing records:
        // upsert updates fields, never deletes.
        blocks[block.id] = block
    }
}

class InMemoryTransitionRepository : TransitionRepository {
    private val items = linkedMapOf<String, Transition>()

    override suspend fun insert(transition: Transition) {
        require(items[transition.id] == null) { "transition ${transition.id} already exists" }
        items[transition.id] = transition
    }

    override suspend fun getByRequestId(requestId: String): Transition? =
        items.values.firstOrNull { it.requestId == requestId }

    override suspend fun getByState(stateId: String): List<Transition> =
        items.values.filter { it.fromStateId == stateId || it.toStateId == stateId }
}

class InMemoryInterruptionRepository : InterruptionRepository {
    private val items = linkedMapOf<String, Interruption>()

    override suspend fun insert(interruption: Interruption) {
        require(items[interruption.id] == null) { "interruption ${interruption.id} already exists" }
        items[interruption.id] = interruption
    }

    override suspend fun getById(id: String): Interruption? = items[id]

    override suspend fun update(interruption: Interruption) {
        check(items.containsKey(interruption.id)) { "unknown interruption ${interruption.id}" }
        items[interruption.id] = interruption
    }

    /** The open interruption whose interruption-state is currently active (id param = active state id). */
    override suspend fun getOpenForState(activeStateId: String): Interruption? =
        items.values.lastOrNull { it.endedAt == null && it.interruptionStateId == activeStateId }

    override suspend fun getInterruptionsFor(interruptedStateId: String): List<Interruption> =
        items.values.filter { it.interruptedStateId == interruptedStateId }

    override suspend fun getAll(): List<Interruption> = items.values.toList()
}

class InMemoryDeviationRepository : DeviationRepository {
    private val items = mutableListOf<Deviation>()
    override suspend fun insert(deviation: Deviation) { items.add(deviation) }
    override suspend fun getByPlannedBlock(plannedBlockId: String): List<Deviation> =
        items.filter { it.plannedBlockId == plannedBlockId }
    override suspend fun getAll(): List<Deviation> = items.toList()
}

class InMemoryActivityTypeRepository : ActivityTypeRepository {
    private val items = linkedMapOf<String, ActivityType>()
    override suspend fun getById(id: String): ActivityType? = items[id]
    override suspend fun getAll(): List<ActivityType> = items.values.toList()
    override suspend fun upsert(type: ActivityType) { items[type.id] = type }
}

class InMemoryPlaceRepository : PlaceRepository {
    private val items = linkedMapOf<String, Place>()
    override suspend fun getById(id: String): Place? = items[id]
    override suspend fun upsert(place: Place) { items[place.id] = place }
}
