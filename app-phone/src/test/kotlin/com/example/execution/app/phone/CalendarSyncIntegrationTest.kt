package com.example.execution.app.phone

import com.example.execution.calendar.CalendarEventDto
import com.example.execution.calendar.CalendarImporter
import com.example.execution.calendar.CalendarSource
import com.example.execution.domain.schedule.PlannedBlockStatus
import com.example.execution.persistence.RoomPlannedBlockRepository
import com.example.execution.persistence.StatePilotDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.room.Room
import android.content.Context
import androidx.test.core.app.ApplicationProvider

/**
 * Integration test: CalendarImporter (pure) onto Room (real SQLite via Robolectric).
 * Proves the calendar-linking flow end to end without a device.
 */
@RunWith(RobolectricTestRunner::class)
class CalendarSyncIntegrationTest {

    private val T0 = Instant.parse("2026-08-25T00:00:00Z")
    private lateinit var db: StatePilotDatabase
    private lateinit var blocks: RoomPlannedBlockRepository

    private class FakeCalendarSource(val events: List<CalendarEventDto>) : CalendarSource {
        override suspend fun getEvents(from: Instant, to: Instant): List<CalendarEventDto> = events
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StatePilotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        blocks = RoomPlannedBlockRepository(db)
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `sync imports calendar events as planned blocks`() = runTest {
        val events = listOf(
            CalendarEventDto("e1", "cal-1", "Deep Work", T0.plusSec(9 * 3600), T0.plusSec(11 * 3600), "Office"),
            CalendarEventDto("e2", "cal-1", "Travel", T0.plusSec(11 * 3600), T0.plusSec((11.5 * 3600).toLong()))
        )
        val importer = CalendarImporter(FakeCalendarSource(events), blocks, clockProvider = { T0 })
        val result = importer.sync(T0, T0.plusSec(24 * 3600))

        assertEquals(2, result.created)
        val deepWork = blocks.findByExternalEventId("e1")
        assertNotNull(deepWork)
        assertEquals("Deep Work", deepWork!!.title)
        assertEquals("deep_work", deepWork.activityTypeId) // via defaultActivityMapping
        assertEquals("travel", blocks.findByExternalEventId("e2")!!.activityTypeId)
        assertEquals("Office", deepWork.locationText)
    }

    @Test
    fun `resync is idempotent - no duplicates`() = runTest {
        val events = listOf(
            CalendarEventDto("e1", "cal-1", "Deep Work", T0.plusSec(9 * 3600), T0.plusSec(11 * 3600))
        )
        val importer = CalendarImporter(FakeCalendarSource(events), blocks, clockProvider = { T0 })
        importer.sync(T0, T0.plusSec(24 * 3600))
        val second = importer.sync(T0, T0.plusSec(24 * 3600))

        assertEquals(0, second.created)
        assertEquals(0, second.updated)
        assertEquals(1, blocks.getBetween(T0, T0.plusSec(24 * 3600)).size)
    }

    @Test
    fun `moved event updates the block instead of duplicating`() = runTest {
        val importer = CalendarImporter(
            FakeCalendarSource(listOf(CalendarEventDto("e1", "cal-1", "Deep Work", T0.plusSec(9 * 3600), T0.plusSec(11 * 3600)))),
            blocks,
            clockProvider = { T0 }
        )
        importer.sync(T0, T0.plusSec(24 * 3600))

        // event shifted to 10:00
        val moved = CalendarImporter(
            FakeCalendarSource(listOf(CalendarEventDto("e1", "cal-1", "Deep Work", T0.plusSec(10 * 3600), T0.plusSec(12 * 3600)))),
            blocks,
            clockProvider = { T0 }
        )
        val result = moved.sync(T0, T0.plusSec(24 * 3600))

        assertEquals(1, result.updated)
        assertEquals(0, result.created)
        assertEquals(1, blocks.getBetween(T0, T0.plusSec(24 * 3600)).size)
        assertEquals(T0.plusSec(10 * 3600), blocks.findByExternalEventId("e1")!!.plannedStart)
    }

    @Test
    fun `removed event cancels future block without deleting`() = runTest {
        val importer = CalendarImporter(
            FakeCalendarSource(listOf(CalendarEventDto("e1", "cal-1", "Deep Work", T0.plusSec(9 * 3600), T0.plusSec(11 * 3600)))),
            blocks,
            clockProvider = { T0 }
        )
        importer.sync(T0, T0.plusSec(24 * 3600))

        // calendar no longer contains the event
        val after = CalendarImporter(FakeCalendarSource(emptyList()), blocks, clockProvider = { T0 })
        val result = after.sync(T0, T0.plusSec(24 * 3600))

        assertEquals(1, result.cancelled)
        val block = blocks.findByExternalEventId("e1")!!
        assertEquals(PlannedBlockStatus.CANCELLED, block.status)
        // history not destroyed: block still exists
        assertEquals(1, blocks.getBetween(T0, T0.plusSec(24 * 3600)).size)
    }

    @Test
    fun `unknown title maps to null activity but still imports`() = runTest {
        val importer = CalendarImporter(
            FakeCalendarSource(listOf(CalendarEventDto("e9", "cal-1", "Errands", T0.plusSec(15 * 3600), T0.plusSec(16 * 3600)))),
            blocks,
            clockProvider = { T0 }
        )
        val result = importer.sync(T0, T0.plusSec(24 * 3600))
        assertEquals(1, result.created)
        assertEquals(null, blocks.findByExternalEventId("e9")!!.activityTypeId)
    }

    private fun Instant.plusSec(sec: Long): Instant =
        Instant.fromEpochMilliseconds(toEpochMilliseconds() + sec * 1000)
}
