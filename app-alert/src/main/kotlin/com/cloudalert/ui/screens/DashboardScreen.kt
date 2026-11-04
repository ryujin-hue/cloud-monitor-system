package com.cloudalert.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cloudalert.ui.AlertUiState
import com.cloudmonitor.shared.dto.DeviceState
import com.cloudmonitor.shared.dto.DeviceStatusDto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    state: AlertUiState,
    onRefresh: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigateHistory: () -> Unit,
) {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Cloud phones", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${state.onlineCount} online · ${state.offlineCount} offline/stale · " +
                            "${state.openIncidentCount} open incident(s)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.lastSyncAt > 0) {
                        Text(
                            "Last sync: ${formatter.format(Date(state.lastSyncAt))}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (!state.pushAvailable) {
                        Text(
                            "Push is not configured (no google-services.json). The dashboard still " +
                                "works by polling.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRefresh) { Text("Refresh") }
                        OutlinedButton(onClick = onNavigateHistory) { Text("History") }
                        OutlinedButton(onClick = onNavigateSettings) { Text("Settings") }
                    }
                }
            }
        }

        if (state.devices.isEmpty()) {
            item {
                Text(
                    "No cloud phone yet. Open Settings, paste the backend API key and enter the " +
                        "6-digit pairing code shown on the cloud phone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        items(state.devices) { device ->
            DeviceCard(device, formatter)
        }
    }
}

@Composable
private fun DeviceCard(device: DeviceStatusDto, formatter: SimpleDateFormat) {
    val color = when (device.state) {
        DeviceState.ONLINE -> Color(0xFF4CAF50)
        DeviceState.STALE -> Color(0xFFFFB300)
        DeviceState.OFFLINE -> Color(0xFFFF5252)
        DeviceState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    device.label.ifBlank { device.deviceId },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(device.state.name, style = MaterialTheme.typography.labelMedium, color = color)
            }
            Text(
                "Monitor version ${device.appVersion} (${device.versionCode})",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Last heartbeat: ${device.lastHeartbeatAt?.let { formatter.format(Date(it)) } ?: "never"}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (device.openIncidentCount > 0) {
                Text(
                    "${device.openIncidentCount} open incident(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (device.watchedPackages.isNotEmpty()) {
                Text(
                    "Watching: ${device.watchedPackages.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
