package com.cloudmonitor.ui.screens

import android.content.Intent
import android.provider.Settings
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
 * shown instead of the dashboard until both are granted. Each row explains *why* the
 * permission is needed; the POST_NOTIFICATIONS row requests the runtime permission and
 * also offers the system settings screen for the "don't ask again" case.
 */
@Composable
fun PermissionsScreen(
    permissions: PermissionStatus,
    intents: PermissionIntents,
    onRefresh: () -> Unit,
    notificationPermissionGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
) {
    val context = LocalContext.current

    fun open(intent: Intent) {
        runCatching { context.startActivity(intent) }
            .onFailure {
                // Some OEMs do not ship the specific settings screen. Falling back to the
                // root Settings screen must never crash the app.
                runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
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
                "This screen stays visible until the essential ones are in place. " +
                "No permission is requested without an explanation, and denying one never " +
                "crashes the app.",
            style = MaterialTheme.typography.bodyMedium,
        )

        PermissionRow(
            title = "Notification access",
            description = "Lets the monitor see when an app's ongoing notification disappears " +
                "and when Discord's screen-share notification is posted or removed. " +
                "Grant it from the system screen; Android has no runtime dialog for it.",
            granted = permissions.notificationAccess,
            actionLabel = "Open",
            onAction = { open(intents.notificationAccess) },
        )

        PermissionRow(
            title = "Usage access",
            description = "Lets the monitor read foreground/background transitions of the watched apps. " +
                "Grant it from the system screen; Android has no runtime dialog for it.",
            granted = permissions.usageAccess,
            actionLabel = "Open",
            onAction = { open(intents.usageAccess) },
        )

        PermissionRow(
            title = "Ignore battery optimisations",
            description = "Keeps the monitor alive when the screen is off. On Android 12+ it is " +
                "also one of the few conditions that allow a foreground service to start from " +
                "the background (for example after a reboot). This does NOT disable Android " +
                "security features; it only exempts this one app from Doze.",
            granted = permissions.batteryUnrestricted,
            actionLabel = "Allow",
            onAction = { open(intents.batteryExemption) },
        )

        PermissionRow(
            title = "Post notifications",
            description = "Needed on Android 13+ (including Android 16) for the ongoing monitor " +
                "notification and for outage alerts. If you chose \"Don't ask again\", use the " +
                "second button to open the app's notification settings.",
            granted = permissions.notificationsEnabled && notificationPermissionGranted,
            actionLabel = "Allow",
            onAction = onRequestNotificationPermission,
            secondaryActionLabel = "App notification settings",
            onSecondaryAction = { open(intents.notifications) },
        )

        PermissionRow(
            title = "Accessibility (optional)",
            description = "Only used to recognise the system \"isn't responding\" dialog, which " +
                "is the only ANR evidence available for a third-party app on a stock phone. " +
                "Leave it off if you do not need E-204 evidence.",
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
            "Android 16 note: the system may refuse to start the monitor from the background. " +
                "If the dashboard says the service failed to start, open the app and tap Start, " +
                "and keep the battery setting unrestricted.\n\n" +
                "Vendor tip: Xiaomi, Huawei, Oppo, Vivo, Samsung, OnePlus and Asus also kill " +
                "background services from their own \"auto start\" or \"protected apps\" " +
                "screens. Enable Cloud Monitor there as well.",
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
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onAction) { Text(actionLabel) }
                    if (secondaryActionLabel != null && onSecondaryAction != null) {
                        OutlinedButton(onClick = onSecondaryAction) { Text(secondaryActionLabel) }
                    }
                }
            } else if (secondaryActionLabel != null && onSecondaryAction != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onSecondaryAction) { Text(secondaryActionLabel) }
            }
        }
    }
}
