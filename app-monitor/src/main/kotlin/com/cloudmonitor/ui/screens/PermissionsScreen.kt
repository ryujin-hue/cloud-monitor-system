package com.cloudmonitor.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cloudmonitor.di.PermissionIntents
import com.cloudmonitor.di.PermissionStatus

/**
 * The onboarding gate.
 *
 * The monitor cannot work without Notification Access and Usage Access, so this screen is
 * shown instead of the dashboard until both are granted.
 */
@Composable
fun PermissionsScreen(
    permissions: PermissionStatus,
    intents: PermissionIntents,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current

    fun open(intent: Intent) {
        runCatching { context.startActivity(intent) }
            .onFailure { runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Permissions required", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Cloud Monitor cannot detect anything until the permissions below are granted. " +
                "This screen stays visible until the essential ones are in place.",
            style = MaterialTheme.typography.bodyMedium,
        )

        PermissionRow(
            title = "Notification access",
            description = "Lets the monitor see when an app's ongoing notification disappears " +
                "and when Discord's screen-share notification is posted or removed.",
            granted = permissions.notificationAccess,
            actionLabel = "Open",
            onAction = { open(intents.notificationAccess) },
        )

        PermissionRow(
            title = "Usage access",
            description = "Lets the monitor read foreground/background transitions of the watched apps.",
            granted = permissions.usageAccess,
            actionLabel = "Open",
            onAction = { open(intents.usageAccess) },
        )

        PermissionRow(
            title = "Ignore battery optimisations",
            description = "Keeps the monitor alive when the cloud phone screen is off. " +
                "Without it Android may kill the service during Doze.",
            granted = permissions.batteryUnrestricted,
            actionLabel = "Allow",
            onAction = { open(intents.batteryExemption) },
        )

        PermissionRow(
            title = "Post notifications",
            description = "Needed on Android 13+ so the ongoing monitor notification can be shown.",
            granted = permissions.notificationsEnabled,
            actionLabel = "Open",
            onAction = { open(intents.notifications) },
        )

        PermissionRow(
            title = "Accessibility (optional)",
            description = "Only used to recognise the system \"isn't responding\" dialog, which " +
                "is the only ANR evidence available for a third-party app on a stock phone.",
            granted = false,
            actionLabel = "Open",
            onAction = { open(intents.accessibility) },
        )

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRefresh) { Text("Check again") }
            OutlinedButton(onClick = { open(intents.notificationAccess) }) {
                Text("Notification access")
            }
        }

        Text(
            "Vendor tip: many OEMs (Xiaomi, Huawei, Oppo, Vivo, Samsung, OnePlus) also kill " +
                "services from their own \"auto start\" or \"protected apps\" screens. Enable " +
                "Cloud Monitor there as well.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (granted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                )
                Text(
                    if (granted) "GRANTED" else "MISSING",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (granted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                )
            }
            Text(description, style = MaterialTheme.typography.bodySmall)
            if (!granted) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
