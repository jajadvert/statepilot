package com.example.execution.app.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Settings state: calendars + selection + last sync result. */
data class SettingsUiState(
    val calendars: List<CalendarInfo> = emptyList(),
    val linkedCalendarId: String? = null,
    val hasPermission: Boolean = false,
    val syncing: Boolean = false,
    val lastSync: String? = null // "created X, updated Y, cancelled Z"
)

@Composable
fun SettingsScreen(
    context: Context,
    settings: CalendarSettings,
    onSync: suspend () -> String,
    interruptSettings: InterruptCategorySettings,
    onClose: () -> Unit
) {
    val linkedId by settings.linkedCalendarId.collectAsStateWithLifecycle(initialValue = null)
    var calendars by remember { mutableStateOf<List<CalendarInfo>>(emptyList()) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var syncing by remember { mutableStateOf(false) }
    var lastSync by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) calendars = AndroidCalendarSource.listCalendars(context)
    }

    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            Text("Link a calendar", style = MaterialTheme.typography.titleMedium)

            if (!hasPermission) {
                Button(onClick = { permissionLauncher.launch(Manifest.permission.READ_CALENDAR) }) {
                    Text("Grant calendar access")
                }
            } else {
                if (calendars.isEmpty()) {
                    LaunchedEffect(Unit) { calendars = AndroidCalendarSource.listCalendars(context) }
                }
                Text("Choose calendar", style = MaterialTheme.typography.bodyMedium)
                // simple radio list
                calendars.forEach { cal ->
                    val selected = cal.id == linkedId
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = {
                                scope.launch { settings.setLinkedCalendarId(cal.id) }
                            }
                        )
                        Column {
                            Text(cal.displayName, style = MaterialTheme.typography.bodyLarge)
                            cal.accountName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            syncing = true
                            lastSync = try { onSync() } finally { syncing = false }
                        }
                    },
                    enabled = linkedId != null && !syncing
                ) {
                    Text(if (syncing) "Syncing…" else "Sync now")
                }
                lastSync?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }

            // ---- interrupt reasons ----
            HorizontalDivider()
            Text("Interrupt reasons", style = MaterialTheme.typography.titleMedium)
            val configs by interruptSettings.configs.collectAsStateWithLifecycle(initialValue = emptyList())
            if (configs.isEmpty()) {
                LaunchedEffect(Unit) { /* store initializes on first read */ }
            }
            configs.forEach { cfg ->
                var label by remember(cfg.id) { mutableStateOf(cfg.label) }
                var enabled by remember(cfg.id) { mutableStateOf(cfg.enabled) }
                val scope2 = rememberCoroutineScope()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            enabled = on
                            scope2.launch {
                                interruptSettings.save(
                                    configs.map {
                                        if (it.id == cfg.id) it.copy(enabled = on, label = label) else it
                                    }
                                )
                            }
                        }
                    )
                    OutlinedTextField(
                        value = label,
                        onValueChange = { newLabel ->
                            label = newLabel
                            scope2.launch {
                                interruptSettings.save(
                                    configs.map {
                                        if (it.id == cfg.id) it.copy(label = newLabel, enabled = enabled) else it
                                    }
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            TextButton(onClick = onClose) { Text("Back") }
        }
    }
}
