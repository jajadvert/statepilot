package com.example.execution.persistence

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import com.example.execution.persistence.entity.ActivityTypeEntity
import com.example.execution.persistence.entity.ActualStateEntity
import com.example.execution.persistence.entity.DeviationEntity
import com.example.execution.persistence.entity.InterruptionEntity
import com.example.execution.persistence.entity.PlaceEntity
import com.example.execution.persistence.entity.PlannedBlockEntity
import com.example.execution.persistence.entity.TransitionEntity

@Dao
interface PlannedBlockDao {
    @Query("SELECT * FROM planned_blocks WHERE id = :id") suspend fun getById(id: String): PlannedBlockEntity?
    @Query("SELECT * FROM planned_blocks WHERE externalEventId = :eventId LIMIT 1")
    suspend fun findByExternalEventId(eventId: String): PlannedBlockEntity?
    @Query("SELECT * FROM planned_blocks WHERE plannedStartEpochMs < :to AND plannedEndEpochMs > :from ORDER BY plannedStartEpochMs")
    suspend fun getBetween(from: Long, to: Long): List<PlannedBlockEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(block: PlannedBlockEntity)
}

@Dao
interface ActualStateDao {
    @Query("SELECT * FROM actual_states WHERE endedAtEpochMs IS NULL LIMIT 1")
    suspend fun getCurrent(): ActualStateEntity?
    @Query("SELECT * FROM actual_states WHERE id = :id") suspend fun getById(id: String): ActualStateEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(state: ActualStateEntity)
    @Query("UPDATE actual_states SET endedAtEpochMs = :endedAt WHERE id = :id AND endedAtEpochMs IS NULL")
    suspend fun finish(id: String, endedAt: Long): Int
    @Query("SELECT * FROM actual_states WHERE startedAtEpochMs < :to AND (endedAtEpochMs IS NULL OR endedAtEpochMs > :from) ORDER BY startedAtEpochMs")
    suspend fun getHistory(from: Long, to: Long): List<ActualStateEntity>
}

@Dao
interface TransitionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(transition: TransitionEntity)
    @Query("SELECT * FROM transitions WHERE requestId = :requestId LIMIT 1")
    suspend fun getByRequestId(requestId: String): TransitionEntity?
    @Query("SELECT * FROM transitions WHERE fromStateId = :stateId OR toStateId = :stateId ORDER BY occurredAtEpochMs")
    suspend fun getByState(stateId: String): List<TransitionEntity>
}

@Dao
interface InterruptionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(interruption: InterruptionEntity)
    @Query("SELECT * FROM interruptions WHERE id = :id") suspend fun getById(id: String): InterruptionEntity?
    @Update suspend fun update(interruption: InterruptionEntity)
    @Query("SELECT * FROM interruptions WHERE endedAtEpochMs IS NULL AND interruptionStateId = :activeStateId ORDER BY startedAtEpochMs DESC LIMIT 1")
    suspend fun getOpenForState(activeStateId: String): InterruptionEntity?
    @Query("SELECT * FROM interruptions WHERE interruptedStateId = :interruptedStateId ORDER BY startedAtEpochMs")
    suspend fun getInterruptionsFor(interruptedStateId: String): List<InterruptionEntity>
    @Query("SELECT * FROM interruptions ORDER BY startedAtEpochMs") suspend fun getAll(): List<InterruptionEntity>
}

@Dao
interface DeviationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(deviation: DeviationEntity)
    @Query("SELECT * FROM deviations WHERE plannedBlockId = :plannedBlockId ORDER BY createdAtEpochMs")
    suspend fun getByPlannedBlock(plannedBlockId: String): List<DeviationEntity>
    @Query("SELECT * FROM deviations ORDER BY createdAtEpochMs") suspend fun getAll(): List<DeviationEntity>
}

@Dao
interface ActivityTypeDao {
    @Query("SELECT * FROM activity_types WHERE id = :id") suspend fun getById(id: String): ActivityTypeEntity?
    @Query("SELECT * FROM activity_types ORDER BY name") suspend fun getAll(): List<ActivityTypeEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(type: ActivityTypeEntity)
}

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places WHERE id = :id") suspend fun getById(id: String): PlaceEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(place: PlaceEntity)
}

@Database(
    entities = [
        PlannedBlockEntity::class,
        ActualStateEntity::class,
        TransitionEntity::class,
        InterruptionEntity::class,
        DeviationEntity::class,
        ActivityTypeEntity::class,
        PlaceEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class StatePilotDatabase : RoomDatabase() {
    abstract fun plannedBlockDao(): PlannedBlockDao
    abstract fun actualStateDao(): ActualStateDao
    abstract fun transitionDao(): TransitionDao
    abstract fun interruptionDao(): InterruptionDao
    abstract fun deviationDao(): DeviationDao
    abstract fun activityTypeDao(): ActivityTypeDao
    abstract fun placeDao(): PlaceDao
}
