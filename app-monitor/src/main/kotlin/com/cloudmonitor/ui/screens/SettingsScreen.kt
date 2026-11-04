package com.cloudmonitor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cloudmonitor.ui.MonitorUiState

@Composable
fun SettingsScreen(
    state: MonitorUiState,
    onSaveApiKey: (String) -> Unit,
    onSaveLabel: (String) -> Unit,
    onSaveHeartbeat: (Int) -> Unit,
) {
    var apiKey by remember(state.settings.apiKey) { mutableStateOf(state.settings.apiKey) }
    var label by remember(state.settings.label) { mutableStateOf(state.settings.label) }
    var interval by remember(state.settings.heartbeatIntervalSec) {
        mutableStateOf(state.settings.heartbeatIntervalSec.toString())
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Backend API key", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The monitor key from the Cloudflare backend. It is stored encrypted with a " +
                        "key that lives in the Android Keystore.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("x-api-key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { onSaveApiKey(apiKey) }) { Text("Save key") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Device name", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Shown in Cloud Alert") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { onSaveLabel(label) }) { Text("Save name") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Heartbeat interval", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Seconds between heartbeats. Shorter means a faster \"cloud phone is gone\" " +
                        "detection, but also more backend traffic.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = interval,
                    onValueChange = { interval = it.filter(Char::isDigit).take(4) },
                    label = { Text("Seconds") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { onSaveHeartbeat(interval.toIntOrNull() ?: 60) }) { Text("Save interval") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Runtime", style = MaterialTheme.typography.titleMedium)
                Text("Device id: ${state.settings.deviceId}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text("App version: ${state.settings.appVersion} (${state.settings.versionCode})", style = MaterialTheme.typography.bodySmall)
                Text("Privileged probe (tier 2): ${if (state.privilegedProbeAvailable) "available" else "not available"}", style = MaterialTheme.typography.bodySmall)
                Text("Service running: ${state.serviceRunning}", style = MaterialTheme.typography.bodySmall)
                Text("Queued events: ${state.queueSize}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
