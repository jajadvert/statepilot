package com.example.execution.app.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.Room
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.execution.calendar.CalendarImporter
import com.example.execution.persistence.RoomActualStateRepository
import com.example.execution.persistence.RoomDeviationRepository
import com.example.execution.persistence.RoomInterruptionRepository
import com.example.execution.persistence.RoomPlannedBlockRepository
import com.example.execution.persistence.RoomTransitionRepository
import com.example.execution.persistence.StatePilotDatabase
import com.example.execution.domain.interruption.InterruptionCategory
import com.example.execution.domain.schedule.PlannedBlock
import com.example.execution.domain.schedule.ScheduleEngine
import com.example.execution.domain.state.StateEngine
import com.example.execution.domain.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

/**
 * Execution UI wired to the real engines (§12, Fase 6).
 * Demo seed: one "Deep Work" block starting now, so the screen shows
 * a live plan without a calendar database. Replace with CalendarSource
 * import once the calendar pipeline is wired.
 */
class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var presenter: PhoneExecutionPresenter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Real persistence: Room-backed repositories (data survives restarts).
        val db = Room.databaseBuilder(this, StatePilotDatabase::class.java, "statepilot.db").build()
        val blocks = RoomPlannedBlockRepository(db)
        val states = RoomActualStateRepository(db)
        val transitions = RoomTransitionRepository(db)
        val interruptions = RoomInterruptionRepository(db)
        val deviations = RoomDeviationRepository(db)
        val clock: Clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        }
        val now = clock.now()
        scope.launch {
            // Demo seed only when the database is empty (first launch).
            if (blocks.getBetween(now, now).isEmpty() && blocks.getById("pb-demo") == null) {
                blocks.upsert(
                    PlannedBlock(
                        id = "pb-demo", activityTypeId = "deep_work", title = "Deep Work",
                        plannedStart = now,
                        plannedEnd = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 2 * 3_600_000L),
                        createdAt = now, updatedAt = now
                    )
                )
            }
        }

        val stateEngine = StateEngine(
            states, transitions, interruptions, blocks, deviations, clock
        ) { "phone-${System.nanoTime()}" }
        val scheduleEngine = ScheduleEngine(blocks, states, clock)
        presenter = PhoneExecutionPresenter(stateEngine, scheduleEngine, states, blocks, scope)

        // Notification loop: pure scheduler + real NotificationManager, every 60s.
        NotificationLoop.create(this, scope, scheduleEngine, blocks).start()

        // Fase 19 export: planner-feedback contract from Room data.
        val exporter = PlannerFeedbackExporter(this, db, scope)

        // Calendar linking: Android calendar source + idempotent importer onto Room.
        val calendarSettings = CalendarSettings(this)
        val syncCalendar: suspend () -> String = {
            val calId = calendarSettings.getLinkedCalendarId()
            if (calId == null) "No calendar linked"
            else {
                // cached provider: reads the setting once per sync
                val source = AndroidCalendarSource(this) { calId }
                val importer = CalendarImporter(source, blocks, clockProvider = { kotlinx.datetime.Instant.fromEpochMilliseconds(System.currentTimeMillis()) })
                val now = System.currentTimeMillis()
                val result = importer.sync(
                    kotlinx.datetime.Instant.fromEpochMilliseconds(now - 7L * 86_400_000L),
                    kotlinx.datetime.Instant.fromEpochMilliseconds(now + 7L * 86_400_000L)
                )
                "created ${result.created}, updated ${result.updated}, cancelled ${result.cancelled}"
            }
        }

        var showSettings by mutableStateOf(false)
        setContent {
            if (showSettings) {
                SettingsScreen(this, calendarSettings, syncCalendar) { showSettings = false }
            }
            val ui by presenter.ui.collectAsState()
            ExecutionScreen(ui = ui, actions = object : PhoneActions {
                override fun openSettings() { showSettings = true }
                override fun export() { exporter.exportLast14Days() }
                override fun start() { scope.launch { presenter.startPlanned("pb-demo") } }
                override fun interrupt() { presenter.requestInterruptPicker() }
                override fun interruptCategory(category: String) {
                    val c = InterruptionCategory.entries.firstOrNull { it.name.equals(category, ignoreCase = true) }
                        ?: InterruptionCategory.OTHER
                    scope.launch { presenter.interrupt(c); presenter.dismissInterruptPicker() }
                }
                override fun dismissInterrupt() { presenter.dismissInterruptPicker() }
                override fun finish() { scope.launch { presenter.finish() } }
                override fun resume() { scope.launch { presenter.resume() } }
                override fun skip() { scope.launch { presenter.skip("pb-demo") } }
            })
        }
        presenter.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

interface PhoneActions {
    fun openSettings()
    fun export()
    fun start()
    fun interrupt()          // opens the category picker
    fun interruptCategory(category: String)
    fun dismissInterrupt()
    fun finish()
    fun resume()
    fun skip()
}

@Composable
fun ExecutionScreen(ui: PhoneUiState, actions: PhoneActions) {
    MaterialTheme {
        if (ui.showInterruptionPicker) {
            InterruptionPickerDialog(
                onPick = actions::interruptCategory,
                onDismiss = actions::dismissInterrupt
            )
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("CURRENT", fontSize = 12.sp)
            Text(
                if (ui.currentLabel == "—") "—" else "${ui.currentLabel} · ${formatElapsed(ui.currentElapsedSeconds)}",
                fontSize = 24.sp, fontWeight = FontWeight.Bold
            )
            Text("PLANNED NOW", fontSize = 12.sp)
            Text(ui.plannedNowTitle, fontSize = 18.sp)
            Text("NEXT", fontSize = 12.sp)
            Text(ui.nextTitle, fontSize = 18.sp)
            Text(ui.statusLine, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions::start) { Text("Start") }
                Button(onClick = actions::interrupt) { Text("Interrupt") }
                Button(onClick = actions::finish) { Text("Finish") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions::resume, enabled = ui.showResume) { Text("Resume") }
                OutlinedButton(onClick = actions::skip) { Text("Skip") }
                OutlinedButton(onClick = actions::export) { Text("Export") }
                OutlinedButton(onClick = actions::openSettings) { Text("Settings") }
            }
        }
    }
}

@Composable
private fun InterruptionPickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val categories = listOf("CALL", "PERSON", "ADMIN", "BREAK", "MESSAGE", "URGENT_TASK", "OTHER")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Interrupt — why?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                categories.forEach { c ->
                    TextButton(onClick = { onPick(c) }) { Text(c.replace('_', ' ').lowercase()) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Live ticking clock: 0:05, 1:23, 1:02:03. */
private fun formatElapsed(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec)
    else "%d:%02d".format(m, sec)
}
