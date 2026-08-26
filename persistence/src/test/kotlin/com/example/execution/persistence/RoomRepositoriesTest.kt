package com.example.execution.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.execution.domain.interruption.InterruptionCategory
import com.example.execution.domain.schedule.PlannedBlock
import com.example.execution.domain.state.ActualState
import com.example.execution.domain.state.StateEngine
import com.example.execution.domain.state.StateResult
import com.example.execution.domain.state.StateSource
import com.example.execution.domain.state.StartActivity
import com.example.execution.domain.state.InterruptCurrentState
import com.example.execution.domain.state.ResumeInterruptedState
import com.example.execution.domain.state.SwitchActivity
import com.example.execution.domain.time.FixedClock
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Room-persistence tests (Robolectric — run on the JVM, no emulator needed).
 * Proves the real StateEngine works end-to-end against the SQLite database,
 * including the MAX-1-active invariant surviving across queries.
 */
@RunWith(RobolectricTestRunner::class)
class RoomRepositoriesTest {

    private val T0 = Instant.parse("2026-08-25T09:00:00Z")
    private lateinit var db: StatePilotDatabase
    private lateinit var blocksRepo: RoomPlannedBlockRepository
    private lateinit var statesRepo: RoomActualStateRepository
    private lateinit var transitionsRepo: RoomTransitionRepository
    private lateinit var interruptionsRepo: RoomInterruptionRepository
    private lateinit var deviationsRepo: RoomDeviationRepository
    private lateinit var clock: FixedClock
    private var idc = 0

    private fun engine() = StateEngine(
        statesRepo, transitionsRepo, interruptionsRepo, blocksRepo, deviationsRepo, clock
    ) { "s${++idc}" }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StatePilotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        blocksRepo = RoomPlannedBlockRepository(db)
        statesRepo = RoomActualStateRepository(db)
        transitionsRepo = RoomTransitionRepository(db)
        interruptionsRepo = RoomInterruptionRepository(db)
        deviationsRepo = RoomDeviationRepository(db)
        clock = FixedClock(T0)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `planned block round trip`() = runTest {
        val block = PlannedBlock(
            id = "pb-1", activityTypeId = "deep_work", title = "Deep Work",
            plannedStart = T0, plannedEnd = Instant.fromEpochMilliseconds(T0.toEpochMilliseconds() + 7_200_000L),
            createdAt = T0, updatedAt = T0
        )
        blocksRepo.upsert(block)
        val loaded = blocksRepo.getById("pb-1")
        assertNotNull(loaded)
        assertEquals("Deep Work", loaded!!.title)
        assertEquals(T0, loaded.plannedStart)
        assertTrue(blocksRepo.getBetween(T0, T0.plusSecondsSafe(7200)).isNotEmpty())
    }

    @Test
    fun `calendar sync upsert does not destroy history`() = runTest {
        val block = PlannedBlock(
            id = "pb-1", activityTypeId = "deep_work", title = "Deep Work",
            plannedStart = T0, plannedEnd = Instant.fromEpochMilliseconds(T0.toEpochMilliseconds() + 7_200_000L),
            createdAt = T0, updatedAt = T0
        )
        blocksRepo.upsert(block)
        // calendar re-sync: same event, shifted time
        val shifted = block.copy(
            plannedStart = T0.plusSecondsSafe(3_600),
            plannedEnd = T0.plusSecondsSafe(10_800),
            updatedAt = T0.plusSecondsSafe(60)
        )
        blocksRepo.upsert(shifted)
        assertEquals(1, blocksRepo.getById("pb-1")?.let { listOf(it) }?.size)
        assertEquals(T0.plusSecondsSafe(3_600), blocksRepo.getById("pb-1")!!.plannedStart)
    }

    @Test
    fun `state engine full flow on room`() = runTest {
        val e = engine()
        // start
        val start = e.execute(StartActivity("deep_work", source = StateSource.PHONE, requestId = "r1"))
        assertIsSuccess(start)
        assertEquals("deep_work", statesRepo.getCurrent()!!.activityTypeId)

        // interrupt
        val int = e.execute(InterruptCurrentState(InterruptionCategory.CALL, StateSource.PHONE, "r2"))
        assertIsSuccess(int)
        assertEquals("call", statesRepo.getCurrent()!!.activityTypeId)

        // resume
        val res = e.execute(ResumeInterruptedState(StateSource.PHONE, "r3"))
        assertIsSuccess(res)
        assertEquals("deep_work", statesRepo.getCurrent()!!.activityTypeId)

        // exactly ONE open state (MAX-1 invariant via DAO)
        val open = db.actualStateDao().getCurrent()
        assertNotNull(open)
        assertEquals("deep_work", open!!.activityTypeId)
    }

    @Test
    fun `history survives across database close`() = runTest {
        val s = ActualState(
            id = "st-1", activityTypeId = "reading", plannedBlockId = null,
            startedAt = T0, endedAt = null, source = StateSource.PHONE,
            trigger = null, resumedFromStateId = null, note = null
        )
        statesRepo.insert(s)
        statesRepo.finish("st-1", T0.plusSecondsSafe(600))

        val hist = statesRepo.getHistory(T0.minusSecondsSafe(60), T0.plusSecondsSafe(3600))
        assertEquals(1, hist.size)
        assertNotNull(hist[0].endedAt)
    }

    @Test
    fun `switch after interrupt does not auto resume - on room`() = runTest {
        val e = engine()
        e.execute(StartActivity("deep_work", source = StateSource.PHONE, requestId = "r1"))
        e.execute(InterruptCurrentState(InterruptionCategory.BREAK, StateSource.PHONE, "r2"))
        val sw = e.execute(SwitchActivity("admin", source = StateSource.PHONE, requestId = "r3"))
        assertIsSuccess(sw)
        assertEquals("admin", statesRepo.getCurrent()!!.activityTypeId)
    }

    @Test
    fun `duplicate requestId is idempotent on room`() = runTest {
        val e = engine()
        val first = e.execute(StartActivity("deep_work", source = StateSource.PHONE, requestId = "dup-1"))
        assertIsSuccess(first)
        val second = e.execute(StartActivity("deep_work", source = StateSource.PHONE, requestId = "dup-1"))
        assertTrue(second is StateResult.IdempotentReplay)
    }

    private fun assertIsSuccess(r: StateResult) {
        assertTrue("expected Success, got $r", r is StateResult.Success)
    }
}

private fun Instant.plusSecondsSafe(seconds: Long) =
    Instant.fromEpochMilliseconds(toEpochMilliseconds() + seconds * 1000)

private fun Instant.minusSecondsSafe(seconds: Long) =
    Instant.fromEpochMilliseconds(toEpochMilliseconds() - seconds * 1000)
