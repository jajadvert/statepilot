package com.example.execution.calendar

import com.example.execution.domain.repository.PlannedBlockRepository
import com.example.execution.domain.schedule.PlannedBlock
import kotlinx.datetime.Instant

/**
 * Idempotent incremental sync (§10.4):
 *  - same external UID -> same PlannedBlock (upsert by externalEventId)
 *  - changed time/title/revision updates the future block
 *  - events missing from source are marked CANCELLED (never deleted)
 *  - execution history is never touched: only PlannedBlocks change.
 */
class CalendarImporter(
    private val calendarSource: CalendarSource,
    private val plannedBlocks: PlannedBlockRepository,
    private val clockProvider: () -> Instant,
    private val activityMapping: (String) -> String? = ::defaultActivityMapping
) {

    suspend fun sync(from: Instant, to: Instant): SyncResult {
        val events = calendarSource.getEvents(from, to)
        var created = 0; var updated = 0; var cancelled = 0

        for (event in events) {
            val existing = plannedBlocks.findByExternalEventId(event.uid)
            if (existing == null) {
                plannedBlocks.upsert(toBlock(event))
                created++
            } else {
                if (!isSame(existing, event)) {
                    plannedBlocks.upsert(merge(existing, event))
                    updated++
                }
            }
        }

        // Cancel future blocks whose event disappeared from the source.
        val uids = events.map { it.uid }.toSet()
        for (block in plannedBlocks.getBetween(from, to)) {
            if (block.externalEventId != null && block.externalEventId !in uids && block.status.name == "ACTIVE") {
                plannedBlocks.upsert(block.copy(status = com.example.execution.domain.schedule.PlannedBlockStatus.CANCELLED, updatedAt = clockProvider()))
                cancelled++
            }
        }
        return SyncResult(created, updated, cancelled)
    }

    private fun toBlock(event: CalendarEventDto): PlannedBlock = PlannedBlock(
        id = "pb-${event.uid}",
        externalCalendarId = event.calendarId,
        externalEventId = event.uid,
        activityTypeId = activityMapping(event.title),
        title = event.title,
        plannedStart = event.start,
        plannedEnd = event.end,
        locationText = event.location,
        status = com.example.execution.domain.schedule.PlannedBlockStatus.ACTIVE,
        revision = event.revision,
        createdAt = clockProvider(),
        updatedAt = clockProvider()
    )

    private fun isSame(block: PlannedBlock, event: CalendarEventDto): Boolean =
        block.title == event.title &&
            block.plannedStart.toEpochMilliseconds() == event.start.toEpochMilliseconds() &&
            block.plannedEnd.toEpochMilliseconds() == event.end.toEpochMilliseconds() &&
            block.locationText == event.location &&
            block.revision == event.revision &&
            block.status != com.example.execution.domain.schedule.PlannedBlockStatus.CANCELLED

    private fun merge(existing: PlannedBlock, event: CalendarEventDto): PlannedBlock = existing.copy(
        title = event.title,
        activityTypeId = activityMapping(event.title),
        plannedStart = event.start,
        plannedEnd = event.end,
        locationText = event.location,
        revision = event.revision,
        status = com.example.execution.domain.schedule.PlannedBlockStatus.ACTIVE,
        updatedAt = clockProvider()
    )

    data class SyncResult(val created: Int, val updated: Int, val cancelled: Int)
}

fun defaultActivityMapping(title: String): String? {
    val t = title.trim().lowercase().replace(" ", "_")
    return when {
        t.contains("deep") || t.contains("focus") -> "deep_work"
        t.contains("meeting") || t.contains("afspraak") || t.contains("appointment") -> "meeting"
        t.contains("travel") || t.contains("commute") || t.contains("reizen") -> "travel"
        t.contains("lunch") -> "lunch"
        t.contains("breakfast") || t.contains("ontbijt") -> "breakfast"
        t.contains("exercise") || t.contains("sport") -> "exercise"
        t.contains("admin") -> "admin"
        t.contains("read") -> "reading"
        t.contains("sleep") -> "sleep"
        else -> null
    }
}
