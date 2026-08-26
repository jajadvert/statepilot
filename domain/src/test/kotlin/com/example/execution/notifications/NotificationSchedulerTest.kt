package com.example.execution.notifications

import com.example.execution.data.repository.InMemoryPlannedBlockRepository
import com.example.execution.domain.schedule.PlannedBlock
import com.example.execution.domain.schedule.PlannedBlockStatus
import com.example.execution.domain.schedule.ScheduleEngine
import com.example.execution.data.repository.InMemoryActualStateRepository
import com.example.execution.domain.time.FixedClock
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.*

class NotificationSchedulerTest {

    private val T0 = Instant.parse("2026-08-25T09:00:00Z")
    private lateinit var clock: FixedClock
    private lateinit var blocks: InMemoryPlannedBlockRepository
    private lateinit var gateway: FakeNotificationGateway
    private lateinit var scheduler: NotificationScheduler

    @BeforeTest
    fun setup() {
        blocks = InMemoryPlannedBlockRepository()
        clock = FixedClock(T0)
        gateway = FakeNotificationGateway()
        scheduler = NotificationScheduler(
            scheduleEngine = ScheduleEngine(blocks, InMemoryActualStateRepository(), clock),
            plannedBlocks = blocks,
            gateway = gateway,
            idGenerator = { "gen" }
        )
    }

    private suspend fun travel(startH: Int, endH: Int, id: String = "tr") {
        blocks.upsert(PlannedBlock(
            id = id, activityTypeId = "travel", title = "Travel",
            locationText = "Dentist",
            plannedStart = h(startH), plannedEnd = h(endH), createdAt = T0, updatedAt = T0))
    }

    private suspend fun block(id: String, startH: Int, endH: Int, activity: String? = "deep_work") {
        blocks.upsert(PlannedBlock(
            id = id, activityTypeId = activity, title = "B-$id",
            plannedStart = h(startH), plannedEnd = h(endH), createdAt = T0, updatedAt = T0))
    }

    private fun h(hour: Int) = Instant.parse("2026-08-25T%02d:00:00Z".format(hour))

    // ---- unit test list (§13) ----

    @Test
    fun `correct warning timestamp`() = runTest {
        block("dw", 11, 13) // next at 11:00
        clock.set(Instant.parse("2026-08-25T10:49:59Z"))
        scheduler.reconcile(clock.now())
        assertTrue(gateway.shown.isEmpty()) // warning not due yet (fires at 10:50)
        clock.set(Instant.parse("2026-08-25T10:50:00Z"))
        scheduler.reconcile(clock.now())
        val n = gateway.shown.values.firstOrNull { it.type == NotificationType.UPCOMING }
        assertNotNull(n)
        assertEquals(h(10).toEpochMilliseconds() + 50 * 60_000L, n.fireAt.toEpochMilliseconds()) // 10:50
    }

    @Test
    fun `correct due timestamp`() = runTest {
        block("dw", 9, 11) // current & overdue (nothing executed)
        scheduler.reconcile(T0)
        val due = gateway.shown.values.firstOrNull { it.type == NotificationType.DUE }
        assertNotNull(due)
        assertEquals("B-dw should start now", due.title)
    }

    @Test
    fun `delay reschedules - old notification cancelled`() = runTest {
        block("dw", 11, 13)
        clock.set(Instant.parse("2026-08-25T10:55:00Z"))
        scheduler.reconcile(clock.now())
        assertTrue(gateway.shown.containsKey("ntf-upcoming-dw"))

        // delay the block by 30 min -> old notification becomes stale
        val b = blocks.getById("dw")!!
        blocks.upsert(b.copy(
            plannedStart = Instant.fromEpochMilliseconds(b.plannedStart.toEpochMilliseconds() + 1_800_000L),
            plannedEnd = Instant.fromEpochMilliseconds(b.plannedEnd.toEpochMilliseconds() + 1_800_000L)))
        scheduler.reconcile(clock.now())
        assertFalse(gateway.shown.containsKey("ntf-upcoming-dw"))
        assertTrue(gateway.cancelled.contains("ntf-upcoming-dw"))
    }

    @Test
    fun `changed calendar event cancels old notification`() = runTest {
        block("dw", 11, 13)
        clock.set(Instant.parse("2026-08-25T10:55:00Z"))
        scheduler.reconcile(clock.now())
        // event cancelled
        val b = blocks.getById("dw")!!
        blocks.upsert(b.copy(status = PlannedBlockStatus.CANCELLED))
        scheduler.reconcile(clock.now())
        assertFalse(gateway.shown.containsKey("ntf-upcoming-dw"))
    }

    @Test
    fun `duplicate notification not created`() = runTest {
        block("dw", 11, 13)
        clock.set(Instant.parse("2026-08-25T10:50:00Z"))
        scheduler.reconcile(clock.now())
        val count1 = gateway.showCount
        scheduler.reconcile(clock.now()) // again, same state
        assertEquals(count1, gateway.showCount) // no second show for same id
    }

    @Test
    fun `travel warning appears 15 minutes before departure`() = runTest {
        travel(11, 12)
        clock.set(Instant.parse("2026-08-25T10:45:00Z"))
        scheduler.reconcile(clock.now())
        val tw = gateway.shown.values.firstOrNull { it.type == NotificationType.TRAVEL_WARNING }
        assertNotNull(tw)
        assertEquals(15L, (h(11).toEpochMilliseconds() - tw.fireAt.toEpochMilliseconds()) / 60_000)
        assertEquals("Leave in 15 minutes", tw.title)
        assertEquals("Travel to Dentist", tw.body)
    }
}

class FakeNotificationGateway : NotificationGateway {
    val shown = linkedMapOf<String, PlannedNotification>()
    val cancelled = mutableListOf<String>()
    var showCount = 0

    override fun show(notification: PlannedNotification) {
        shown[notification.id] = notification
        showCount++
    }

    override fun cancel(notificationId: String) {
        shown.remove(notificationId)
        cancelled.add(notificationId)
    }

    override fun shownNotificationIds(): Set<String> = shown.keys

    override fun notificationFor(id: String): PlannedNotification? = shown[id]
}
