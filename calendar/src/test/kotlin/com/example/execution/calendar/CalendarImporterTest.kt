package com.example.execution.calendar

import com.example.execution.data.repository.InMemoryPlannedBlockRepository
import com.example.execution.domain.schedule.PlannedBlockStatus
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.*

class CalendarImporterTest {
    private val T0 = Instant.parse("2026-08-25T00:00:00Z")
    private val blocks = InMemoryPlannedBlockRepository()
    private var now = T0

    private fun source(vararg events: CalendarEventDto) = object : CalendarSource {
        override suspend fun getEvents(from: Instant, to: Instant) = events.toList()
    }

    private fun importer(vararg events: CalendarEventDto) =
        CalendarImporter(source(*events), blocks, { now })

    private fun event(uid: String, title: String, startH: Int, endH: Int, rev: String? = null) = CalendarEventDto(
        uid = uid, calendarId = "cal-1", title = title,
        start = Instant.parse("2026-08-25T%02d:00:00Z".format(startH)),
        end = Instant.parse("2026-08-25T%02d:00:00Z".format(endH)),
        revision = rev
    )

    @Test
    fun `new event creates block`() = runTest {
        val r = importer(event("e1", "Deep Work", 9, 11)).sync(T0, Instant.fromEpochMilliseconds(T0.toEpochMilliseconds() + 86_400_000))
        assertEquals(1, r.created)
        assertEquals("deep_work", blocks.getById("pb-e1")!!.activityTypeId)
    }

    @Test
    fun `same event twice creates one block`() = runTest {
        val imp = importer(event("e1", "Deep Work", 9, 11))
        imp.sync(T0, Instant.fromEpochMilliseconds(T0.toEpochMilliseconds() + 86_400_000)); imp.sync(T0, Instant.fromEpochMilliseconds(T0.toEpochMilliseconds() + 86_400_000))
        assertEquals(1, blocks.getBetween(T0, Instant.fromEpochMilliseconds(T0.toEpochMilliseconds() + 86_400_000)).size)
    }

    @Test
    fun `changed time updates block`() = runTest {
        val imp = importer(event("e1", "Deep Work", 9, 11), )
        imp.sync(T0, Instant.fromEpochMilliseconds(T0.toEpochMilliseconds() + 86_400_000))
        val imp2 = importer(event("e1", "Deep Work", 10, 12, rev = "r2"))
        val r = imp2.sync(T0, Instant.fromEpochMilliseconds(T0.toEpochMilliseconds() + 86_400_000))
        assertEquals(1, r.updated)
        assertEquals(10, blocks.getById("pb-e1")!!.plannedStart.toString().substring(11,13).toInt())
    }

    @Test
    fun `changed title updates block`() = runTest {
        importer(event("e1", "Deep Work", 9, 11)).sync(T0, Instant.fromEpochMilliseconds(T0.toEpochMilliseconds() + 86_400_000))
        importer(event("e1", "Travel", 9, 11, rev = "r3")).sync(T0, Instant.fromEpochMilliseconds(T0.toEpochMilliseconds() + 86_400_000))
        assertEquals("travel", blocks.getById("pb-e1")!!.activityTypeId)
    }

    @Test
    fun `deleted future event marks cancelled - remains auditable`() = runTest {
        importer(event("e1", "Deep Work", 9, 11)).sync(T0, Instant.fromEpochMilliseconds(T0.toEpochMilliseconds() + 86_400_000))
        importer().sync(T0, Instant.fromEpochMilliseconds(T0.toEpochMilliseconds() + 86_400_000))
        val b = blocks.getById("pb-e1")!!
        assertEquals(PlannedBlockStatus.CANCELLED, b.status)
        assertNotNull(blocks.getById("pb-e1")) // still present, never deleted
    }

    @Test
    fun `all-day event handled explicitly`() = runTest {
        val e = CalendarEventDto("ad1", "c", "Admin Day",
            start = Instant.parse("2026-08-25T00:00:00Z"),
            end = Instant.parse("2026-08-26T00:00:00Z"), allDay = true)
        importer(e).sync(T0, Instant.fromEpochMilliseconds(T0.toEpochMilliseconds() + 172_800_000))
        assertEquals(1, blocks.getBetween(T0, Instant.fromEpochMilliseconds(T0.toEpochMilliseconds() + 172_800_000)).size)
    }

    @Test
    fun `overlapping events supported`() = runTest {
        importer(
            event("o1", "Meeting", 9, 11),
            event("o2", "Admin", 10, 12)
        ).sync(T0, Instant.fromEpochMilliseconds(T0.toEpochMilliseconds() + 86_400_000))
        assertEquals(2, blocks.getBetween(
            Instant.parse("2026-08-25T10:30:00Z"), Instant.parse("2026-08-25T10:30:00Z")
        ).size)
    }
}
