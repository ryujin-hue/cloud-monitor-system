package com.cloudalert.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cloudalert.ui.screens.DashboardScreen
import com.cloudalert.ui.screens.HistoryScreen
import com.cloudalert.ui.screens.SettingsScreen
import com.cloudalert.ui.theme.CloudAlertTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 16 (targetSdk 36) no longer allows opting out of edge-to-edge.
        enableEdgeToEdge()
        setContent {
            CloudAlertTheme {
                AlertApp()
            }
        }
    }
}

private object Routes {
    const val DASHBOARD = "dashboard"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
}

@Composable
private fun AlertApp(viewModel: AlertViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // POST_NOTIFICATIONS is a runtime permission on Android 13+ and is mandatory on
    // Android 16 for outage alerts to be visible. Request it only when needed and always
    // with an explanation; a denial never crashes the app.
    var notificationPermissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionGranted = granted
        if (!granted) {
            viewModel.notify(
                "Notification permission is off. Outage alerts will be hidden. Open " +
                    "Settings → Apps → Cloud Alert → Notifications to enable them.",
            )
        }
    }

    fun openAppNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            if (!notificationPermissionGranted) {
                NotificationPermissionBanner(
                    onAllow = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onOpenSettings = { openAppNotificationSettings() },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    state = state,
                    onRefresh = viewModel::refresh,
                    onNavigateSettings = { navController.navigate(Routes.SETTINGS) },
                    onNavigateHistory = { navController.navigate(Routes.HISTORY) },
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    history = state.history,
                    codeFilter = state.codeFilter,
                    codes = viewModel.filterableCodes,
                    onFilter = viewModel::setCodeFilter,
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    apiKey = state.apiKey,
                    label = state.label,
                    subscriptions = state.subscriptions,
                    pushAvailable = state.pushAvailable,
                    onSaveApiKey = viewModel::setApiKey,
                    onSaveLabel = viewModel::setLabel,
                    onRegister = viewModel::register,
                    onPair = viewModel::pair,
                    onOpenNotificationSettings = { openAppNotificationSettings() },
                )
            }
        }
    }
}

/** Shown only while POST_NOTIFICATIONS is missing. Explains the permission before asking. */
@Composable
private fun NotificationPermissionBanner(
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Alerts are hidden", style = MaterialTheme.typography.titleMedium)
            Text(
                "Cloud Alert needs the notification permission to show outage alerts. " +
                    "On Android 13+ (including Android 16) it is off by default. If you chose " +
                    "\"Don't ask again\", open the app's notification settings instead.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onAllow) { Text("Allow notifications") }
                OutlinedButton(onClick = onOpenSettings) { Text("Open settings") }
            }
        }
    }
}
