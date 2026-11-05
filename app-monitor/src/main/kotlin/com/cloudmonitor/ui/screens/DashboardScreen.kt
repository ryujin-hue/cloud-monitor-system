package com.cloudmonitor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cloudmonitor.ui.MonitorUiState

@Composable
fun DashboardScreen(
    state: MonitorUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onHeartbeat: () -> Unit,
    onRotateCode: () -> Unit,
    onNavigatePackages: () -> Unit,
    onNavigateInspector: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigateLog: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (state.serviceRunning) "Monitor is running" else "Monitor is stopped",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (state.serviceRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "Device id: ${state.settings.deviceId.ifBlank { "not initialised yet" }}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "Version: ${state.settings.appVersion} (versionCode ${state.settings.versionCode})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Detection tier: ${if (state.privilegedProbeAvailable) "tier 2 (privileged)" else "tier 1 (no privileges)"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Root check: ${state.rootStatusDetail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Queued events: ${state.queueSize}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onStart) { Text(if (state.serviceRunning) "Restart" else "Start") }
                        OutlinedButton(onClick = onStop) { Text("Stop") }
                        OutlinedButton(onClick = onHeartbeat) { Text("Heartbeat now") }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Pairing code", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        state.pairingCode ?: "disabled",
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Type this 6-digit code in Cloud Alert to subscribe to this cloud phone. " +
                            "The code expires after 30 minutes.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = onRotateCode) { Text("New code") }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Watched apps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (state.settings.watchedPackages.isEmpty()) {
                        Text("No app selected yet.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        state.settings.watchedPackages.forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    OutlinedButton(onClick = onNavigatePackages) { Text("Choose apps") }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Discord screen share", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val target = state.settings.discordPackage
                    if (target == null) {
                        Text(
                            "Not configured. Open the Notification Inspector while a screen share is " +
                                "running and pick the notification to monitor.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(target, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Text(
                            "notification id = ${state.settings.discordNotificationId ?: "any"}, " +
                                "tag = ${state.settings.discordNotificationTag ?: "any"}, " +
                                "channel = ${state.settings.discordChannelId ?: "any"}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    OutlinedButton(onClick = onNavigateInspector) { Text("Notification inspector") }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onNavigateSettings) { Text("Settings") }
                OutlinedButton(onClick = onNavigateLog) { Text("History") }
            }
        }

        items(state.log.take(10)) { row ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("${row.code} ${row.type}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(row.packageName ?: "-", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    Text(
                        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(row.occurredAt)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
