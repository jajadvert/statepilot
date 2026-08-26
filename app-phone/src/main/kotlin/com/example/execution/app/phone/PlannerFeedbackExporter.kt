package com.example.execution.app.phone

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.execution.domain.analytics.DayAnalyzer
import com.example.execution.domain.analytics.PlannerFeedbackBuilder
import com.example.execution.persistence.RoomActualStateRepository
import com.example.execution.persistence.RoomDeviationRepository
import com.example.execution.persistence.RoomInterruptionRepository
import com.example.execution.persistence.RoomPlannedBlockRepository
import com.example.execution.persistence.StatePilotDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Integration: Fase 19 export — builds the planner-feedback contract from the
 * Room data of the last 14 days and shares it (share-sheet).
 */
class PlannerFeedbackExporter(
    private val context: Context,
    private val db: StatePilotDatabase,
    private val scope: CoroutineScope
) {
    private val json = Json { prettyPrint = true }

    fun exportLast14Days() {
        scope.launch {
            val now = System.currentTimeMillis()
            val start = Instant.fromEpochMilliseconds(now - 14L * 86_400_000L)
            val end = Instant.fromEpochMilliseconds(now)

            val analyzer = DayAnalyzer(
                RoomPlannedBlockRepository(db),
                RoomActualStateRepository(db),
                RoomInterruptionRepository(db),
                RoomDeviationRepository(db)
            )
            // one report per day
            val reports = (0..13).map { dayOffset ->
                val dayStartMs = start.toEpochMilliseconds() + dayOffset * 86_400_000L
                val dayEndMs = dayStartMs + 86_400_000L
                analyzer.analyzeDay(
                    Instant.fromEpochMilliseconds(dayStartMs),
                    Instant.fromEpochMilliseconds(dayEndMs)
                )
            }

            val contract = PlannerFeedbackBuilder.build(reports, Instant.fromEpochMilliseconds(now))
            val payload = json.encodeToString(contract)
            share("statepilot-feedback.json", payload)
        }
    }

    private fun share(fileName: String, content: String) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "StatePilot planner feedback"))
    }
}
