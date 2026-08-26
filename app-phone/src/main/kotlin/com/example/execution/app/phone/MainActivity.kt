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

/**
 * Minimal execution UI (§12, Fase 6 slice). No business logic here:
 * this MVP shell renders state placeholders; wiring to ScheduleEngine
 * happens in the next vertical slice.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ExecutionScreen() }
    }
}

@Composable
fun ExecutionScreen() {
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("CURRENT", fontSize = 12.sp)
            Text("—", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("PLANNED NOW", fontSize = 12.sp)
            Text("—", fontSize = 18.sp)
            Text("NEXT", fontSize = 12.sp)
            Text("—", fontSize = 18.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {}) { Text("Start") }
                Button(onClick = {}) { Text("Interrupt") }
                Button(onClick = {}) { Text("Finish") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {}) { Text("Switch") }
                OutlinedButton(onClick = {}) { Text("Resume") }
                OutlinedButton(onClick = {}) { Text("Skip") }
            }
        }
    }
}
