package com.example.execution.app.phone

import android.content.Context
import com.example.execution.domain.schedule.ScheduleEngine
import com.example.execution.notifications.NotificationScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Integration: runs the pure NotificationScheduler against the real
 * NotificationManager every [tickMs]. Start it once from the app.
 */
class NotificationLoop(
    private val scope: CoroutineScope,
    private val scheduler: NotificationScheduler,
    private val tickMs: Long = 60_000L
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (scope.isActive) {
                runCatching { scheduler.reconcile(kotlinx.datetime.Instant.fromEpochMilliseconds(System.currentTimeMillis())) }
                delay(tickMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        fun create(context: Context, scope: CoroutineScope, scheduleEngine: ScheduleEngine, plannedBlocks: com.example.execution.domain.repository.PlannedBlockRepository): NotificationLoop {
            val gateway = AndroidNotificationGateway(context.applicationContext)
            val scheduler = NotificationScheduler(scheduleEngine, plannedBlocks, gateway, idGenerator = { "ntf-${System.nanoTime()}" })
            return NotificationLoop(scope, scheduler)
        }
    }
}
