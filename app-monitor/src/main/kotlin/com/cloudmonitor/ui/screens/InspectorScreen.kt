package com.cloudmonitor.ui.screens

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cloudmonitor.monitor.NotificationSnapshot

/**
 * Notification Inspector.
 *
 * Discord's screen-share notification differs between Discord versions, forks and OEM
 * builds, so the real package name, notification id, tag, channel and ongoing flag are
 * DISCOVERED here instead of being hard-coded.
 *
 * How to use it:
 *  1. Start a screen share in Discord on the cloud phone.
 *  2. Open this screen.
 *  3. Find the notification that mentions the stream and tap "Use as Discord target".
 */
@Composable
fun InspectorScreen(
    notifications: List<NotificationSnapshot>,
    onUse: (NotificationSnapshot) -> Unit,
    onClear: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Notification inspector", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Start the Discord screen share first, then pick the matching notification below. " +
                "The monitor will watch exactly that notification.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onClear) { Text("Clear current target") }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (notifications.isEmpty()) {
                item {
                    Text(
                        "No notification is currently posted, or Notification Access has not " +
                            "been granted yet.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(notifications) { snapshot ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                snapshot.packageName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                if (snapshot.ongoing) "ONGOING" else "not ongoing",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (snapshot.ongoing) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("id=${snapshot.id}  tag=${snapshot.tag ?: "-"}  channel=${snapshot.channelId ?: "-"}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace)
                        if (snapshot.title.isNotBlank()) {
                            Text("title: ${snapshot.title}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (snapshot.text.isNotBlank()) {
                            Text("text: ${snapshot.text}", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = { onUse(snapshot) }) {
                            Text("Use as Discord target")
                        }
                    }
                }
            }
        }
    }
}
