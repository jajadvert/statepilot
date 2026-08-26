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
import com.example.execution.data.repository.InMemoryActualStateRepository
import com.example.execution.data.repository.InMemoryDeviationRepository
import com.example.execution.data.repository.InMemoryInterruptionRepository
import com.example.execution.data.repository.InMemoryPlannedBlockRepository
import com.example.execution.data.repository.InMemoryTransitionRepository
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

        val blocks = InMemoryPlannedBlockRepository()
        val states = InMemoryActualStateRepository()
        val clock: Clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        }
        val now = clock.now()
        scope.launch {
            blocks.upsert(
                PlannedBlock(
                    id = "pb-demo", activityTypeId = "deep_work", title = "Deep Work",
                    plannedStart = now,
                    plannedEnd = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 2 * 3_600_000L),
                    createdAt = now, updatedAt = now
                )
            )
        }

        val stateEngine = StateEngine(
            states, InMemoryTransitionRepository(), InMemoryInterruptionRepository(),
            blocks, InMemoryDeviationRepository(), clock
        ) { "phone-${System.nanoTime()}" }
        val scheduleEngine = ScheduleEngine(blocks, states, clock)
        presenter = PhoneExecutionPresenter(stateEngine, scheduleEngine, states, blocks, scope)

        setContent {
            val ui by presenter.ui.collectAsState()
            ExecutionScreen(ui = ui, actions = object : PhoneActions {
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
                if (ui.currentLabel == "—") "—" else "${ui.currentLabel} · ${ui.currentElapsedSeconds / 60} min",
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
