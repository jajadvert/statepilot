package com.example.execution.notifications

import com.example.execution.domain.schedule.PlannedBlock
import com.example.execution.domain.schedule.PlannedBlockStatus
import com.example.execution.domain.schedule.ScheduleEngine
import com.example.execution.domain.schedule.TransitionStatus
import com.example.execution.domain.repository.PlannedBlockRepository
import kotlinx.datetime.Instant

enum class NotificationType { UPCOMING, DUE, OVERDUE, TRAVEL_WARNING }

/** Immutable description of a notification the scheduler wants to show. */
data class PlannedNotification(
    val id: String,
    val type: NotificationType,
    val plannedBlockId: String,
    val fireAt: Instant,
    val title: String,
    val body: String
)

/**
 * Dependency inversion (§2.6): the Android side implements this with
 * NotificationManager/AlarmManager/WorkManager; fakes are used in tests.
 */
interface NotificationGateway {
    fun show(notification: PlannedNotification)
    fun cancel(notificationId: String)
    fun shownNotificationIds(): Set<String>
    fun notificationFor(id: String): PlannedNotification? = null
}

/**
 * Pure notification scheduling (§13). Reconciles desired notifications against
 * what is already shown:
 *  - UPCOMING warning fires upcomingWindow before next block start
 *  - DUE fires when a block should start (next due or current-but-not-started)
 *  - OVERDUE escalation after 5 minutes unacted
 *  - TRAVEL_WARNING fires travelWarningLeadSeconds before a travel block
 *  - delayed/cancelled block -> stale notifications cancelled
 *  - duplicate notification never created
 */
class NotificationScheduler(
    private val scheduleEngine: ScheduleEngine,
    private val plannedBlocks: PlannedBlockRepository,
    private val gateway: NotificationGateway,
    private val idGenerator: () -> String = { "gen" },
    /** Travel blocks get an extra pre-warning this many seconds before start. */
    private val travelWarningLeadSeconds: Long = 900L // 15 minutes
) {

    /** Reconcile desired notifications with gateway state. Call periodically. */
    suspend fun reconcile(now: Instant) {
        val status = scheduleEngine.status(now)

        // The block the user should transition TO now:
        val target: PlannedBlock? = status.nextPlannedBlock ?: status.currentPlannedBlock
        if (target == null || target.status != PlannedBlockStatus.ACTIVE) {
            cancelAll()
            return
        }

        val desired = LinkedHashMap<String, PlannedNotification>()

        // DUE: target is next-and-due OR current-but-not-started.
        val dueNow =
            status.transitionStatus == TransitionStatus.OVERDUE ||
                (target.plannedStart <= now && target.plannedEnd > now && status.currentActualState == null)
        if (dueNow) {
            desired[notifId(target, NotificationType.DUE)] = notification(
                NotificationType.DUE, target, now,
                title = "${target.title} should start now",
                body = "[START] [+10 MIN] [SKIP]"
            )
        } else {
            // UPCOMING warning scheduled for upcomingWindow before start.
            val warnAt = at(target.plannedStart.toEpochMilliseconds() - scheduleEngine.upcomingWindowSeconds * 1000)
            desired[notifId(target, NotificationType.UPCOMING)] = notification(
                NotificationType.UPCOMING, target, warnAt,
                title = "${target.title} starts in ${minutes(warnAt, target.plannedStart)} minutes",
                body = "Planned ${target.title}"
            )
        }

        // TRAVEL_WARNING for travel blocks.
        if (target.activityTypeId == "travel") {
            val leaveAt = at(target.plannedStart.toEpochMilliseconds() - travelWarningLeadSeconds * 1000)
            if (now <= leaveAt) {
                desired[notifId(target, NotificationType.TRAVEL_WARNING)] = notification(
                    NotificationType.TRAVEL_WARNING, target, leaveAt,
                    title = "Leave in ${minutes(leaveAt, target.plannedStart)} minutes",
                    body = "Travel to ${target.locationText ?: target.title}"
                )
            }
        }

        // OVERDUE escalation when still unacted well past start.
        val overdueSeconds = (now.toEpochMilliseconds() - target.plannedStart.toEpochMilliseconds()) / 1000
        if (dueNow && overdueSeconds > overdueEscalationSeconds()) {
            desired[notifId(target, NotificationType.OVERDUE)] = notification(
                NotificationType.OVERDUE, target, now,
                title = "${target.title} is overdue",
                body = "Still not started"
            )
        }

        // Cancel shown notifications that are no longer desired (delayed/cancelled/rescheduled).
        val desiredIds = desired.keys
        for (shownId in gateway.shownNotificationIds().toList()) {
            if (!desiredIds.contains(shownId)) {
                gateway.cancel(shownId)
            }
        }

        // Show those whose fire time has come; never duplicate.
        // If fireAt changed (block delayed), cancel the stale copy so it is re-shown with new time.
        for ((id, n) in desired) {
            if (gateway.notificationFor(id)?.let { it.fireAt != n.fireAt } == true) {
                gateway.cancel(id)
            }
            if (!gateway.shownNotificationIds().contains(id) && !now.isBeforeInstant(n.fireAt)) {
                gateway.show(n)
            }
        }
    }

    private fun notification(
        type: NotificationType,
        block: PlannedBlock,
        fireAt: Instant,
        title: String,
        body: String
    ) = PlannedNotification(
        id = notifId(block, type),
        type = type,
        plannedBlockId = block.id,
        fireAt = fireAt,
        title = title,
        body = body
    )

    private fun cancelAll() {
        for (id in gateway.shownNotificationIds().toList()) {
            gateway.cancel(id)
        }
    }

    private fun overdueEscalationSeconds(): Long = 300L

    private fun notifId(block: PlannedBlock, type: NotificationType) =
        "ntf-${type.name.lowercase()}-${block.id}"

    private fun minutes(from: Instant, to: Instant): Long =
        (to.toEpochMilliseconds() - from.toEpochMilliseconds()) / 60_000

    private fun at(epochMs: Long) = Instant.fromEpochMilliseconds(epochMs)

    private fun Instant.isBeforeInstant(other: Instant) = this < other
}
