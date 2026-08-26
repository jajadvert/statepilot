package com.example.execution.persistence

import com.example.execution.domain.activity.ActivityType
import com.example.execution.domain.deviation.Deviation
import com.example.execution.domain.interruption.Interruption
import com.example.execution.domain.place.Place
import com.example.execution.domain.repository.ActivityTypeRepository
import com.example.execution.domain.repository.ActualStateRepository
import com.example.execution.domain.repository.DeviationRepository
import com.example.execution.domain.repository.InterruptionRepository
import com.example.execution.domain.repository.PlaceRepository
import com.example.execution.domain.repository.PlannedBlockRepository
import com.example.execution.domain.repository.TransitionRepository
import com.example.execution.domain.schedule.PlannedBlock
import com.example.execution.domain.state.ActualState
import com.example.execution.domain.state.Transition
import com.example.execution.persistence.entity.ActivityTypeEntity
import com.example.execution.persistence.entity.ActualStateEntity
import com.example.execution.persistence.entity.DeviationEntity
import com.example.execution.persistence.entity.InterruptionEntity
import com.example.execution.persistence.entity.PlaceEntity
import com.example.execution.persistence.entity.PlannedBlockEntity
import com.example.execution.persistence.entity.TransitionEntity
import kotlinx.datetime.Instant

/** Room-backed PlannedBlockRepository. Drop-in for the in-memory one. */
class RoomPlannedBlockRepository(private val db: StatePilotDatabase) : PlannedBlockRepository {
    override suspend fun getById(id: String): PlannedBlock? = db.plannedBlockDao().getById(id)?.toDomain()
    override suspend fun findByExternalEventId(externalEventId: String): PlannedBlock? =
        db.plannedBlockDao().findByExternalEventId(externalEventId)?.toDomain()
    override suspend fun getBetween(from: Instant, to: Instant): List<PlannedBlock> =
        db.plannedBlockDao().getBetween(from.toEpochMilliseconds(), to.toEpochMilliseconds()).map { it.toDomain() }
    override suspend fun upsert(block: PlannedBlock) = db.plannedBlockDao().upsert(PlannedBlockEntity.fromDomain(block))
}

/** Room-backed ActualStateRepository. */
class RoomActualStateRepository(private val db: StatePilotDatabase) : ActualStateRepository {
    override suspend fun getCurrent(): ActualState? = db.actualStateDao().getCurrent()?.toDomain()
    override suspend fun getById(id: String): ActualState? = db.actualStateDao().getById(id)?.toDomain()
    override suspend fun insert(state: ActualState) = db.actualStateDao().insert(ActualStateEntity.fromDomain(state))
    override suspend fun finish(id: String, endedAt: Instant) {
        db.actualStateDao().finish(id, endedAt.toEpochMilliseconds())
    }
    override suspend fun getHistory(from: Instant, to: Instant): List<ActualState> =
        db.actualStateDao().getHistory(from.toEpochMilliseconds(), to.toEpochMilliseconds()).map { it.toDomain() }
}

/** Room-backed TransitionRepository. */
class RoomTransitionRepository(private val db: StatePilotDatabase) : TransitionRepository {
    override suspend fun insert(transition: Transition) = db.transitionDao().insert(TransitionEntity.fromDomain(transition))
    override suspend fun getByRequestId(requestId: String): Transition? = db.transitionDao().getByRequestId(requestId)?.toDomain()
    override suspend fun getByState(stateId: String): List<Transition> = db.transitionDao().getByState(stateId).map { it.toDomain() }
}

/** Room-backed InterruptionRepository. */
class RoomInterruptionRepository(private val db: StatePilotDatabase) : InterruptionRepository {
    override suspend fun insert(interruption: Interruption) = db.interruptionDao().insert(InterruptionEntity.fromDomain(interruption))
    override suspend fun getById(id: String): Interruption? = db.interruptionDao().getById(id)?.toDomain()
    override suspend fun update(interruption: Interruption) = db.interruptionDao().update(InterruptionEntity.fromDomain(interruption))
    override suspend fun getOpenForState(activeInterruptionStateId: String): Interruption? =
        db.interruptionDao().getOpenForState(activeInterruptionStateId)?.toDomain()
    override suspend fun getInterruptionsFor(interruptedStateId: String): List<Interruption> =
        db.interruptionDao().getInterruptionsFor(interruptedStateId).map { it.toDomain() }
    override suspend fun getAll(): List<Interruption> = db.interruptionDao().getAll().map { it.toDomain() }
}

/** Room-backed DeviationRepository. */
class RoomDeviationRepository(private val db: StatePilotDatabase) : DeviationRepository {
    override suspend fun insert(deviation: Deviation) = db.deviationDao().insert(DeviationEntity.fromDomain(deviation))
    override suspend fun getByPlannedBlock(plannedBlockId: String): List<Deviation> =
        db.deviationDao().getByPlannedBlock(plannedBlockId).map { it.toDomain() }
    override suspend fun getAll(): List<Deviation> = db.deviationDao().getAll().map { it.toDomain() }
}

/** Room-backed ActivityTypeRepository. */
class RoomActivityTypeRepository(private val db: StatePilotDatabase) : ActivityTypeRepository {
    override suspend fun getById(id: String): ActivityType? = db.activityTypeDao().getById(id)?.toDomain()
    override suspend fun getAll(): List<ActivityType> = db.activityTypeDao().getAll().map { it.toDomain() }
    override suspend fun upsert(type: ActivityType) = db.activityTypeDao().upsert(ActivityTypeEntity.fromDomain(type))
}

/** Room-backed PlaceRepository. */
class RoomPlaceRepository(private val db: StatePilotDatabase) : PlaceRepository {
    override suspend fun getById(id: String): Place? = db.placeDao().getById(id)?.toDomain()
    override suspend fun upsert(place: Place) = db.placeDao().upsert(PlaceEntity.fromDomain(place))
}
