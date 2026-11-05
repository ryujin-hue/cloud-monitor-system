package com.cloudmonitor.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cloudmonitor.service.MonitorForegroundService
import com.cloudmonitor.service.WatchdogWorker
import com.cloudmonitor.ui.screens.DashboardScreen
import com.cloudmonitor.ui.screens.EventLogScreen
import com.cloudmonitor.ui.screens.InspectorScreen
import com.cloudmonitor.ui.screens.PackagesScreen
import com.cloudmonitor.ui.screens.PermissionsScreen
import com.cloudmonitor.ui.screens.SettingsScreen
import com.cloudmonitor.ui.theme.CloudMonitorTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.hilt.navigation.compose.hiltViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15/16 enforce edge-to-edge for targetSdk 35+; the opt-out attribute is
        // disabled for targetSdk 36. enableEdgeToEdge() plus a Scaffold handles the insets.
        enableEdgeToEdge()

        // The monitor must survive the app being closed and the phone being rebooted.
        // The activity is user-visible, so starting a foreground service here is an
        // explicit exemption from the Android 12+ background-start restriction.
        val outcome = MonitorForegroundService.start(this)
        if (!outcome.requested) {
            android.util.Log.w("MonitorActivity", "auto-start refused: ${outcome.message}")
        }
        WatchdogWorker.schedule(this)

        setContent {
            CloudMonitorTheme {
                MonitorApp()
            }
        }
    }
}

private object Routes {
    const val PERMISSIONS = "permissions"
    const val DASHBOARD = "dashboard"
    const val PACKAGES = "packages"
    const val INSPECTOR = "inspector"
    const val SETTINGS = "settings"
    const val LOG = "log"
}

@Composable
private fun MonitorApp(viewModel: MonitorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // POST_NOTIFICATIONS is a runtime permission on Android 13+ (and is required on
    // Android 16 for the ongoing foreground-service notification / task-manager entry).
    // It is requested only when needed, with an explanation on the onboarding screen.
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
            viewModel.reportNotificationPermissionDenied()
        }
        viewModel.refresh()
    }

    // Permissions can be granted while the app is in the background, so re-check on resume.
    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.reportStartupProblems()
    }

    // Surface every status/error message. Without this, "service failed to start" would
    // never be visible and the dashboard could silently claim success.
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val startDestination = if (state.permissions.essentialsGranted) Routes.DASHBOARD else Routes.PERMISSIONS

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.PERMISSIONS) {
                PermissionsScreen(
                    permissions = state.permissions,
                    intents = viewModel.permissionIntents,
                    onRefresh = viewModel::refresh,
                    notificationPermissionGranted = notificationPermissionGranted,
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
            }
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    state = state,
                    onStart = viewModel::startService,
                    onStop = viewModel::stopService,
                    onHeartbeat = viewModel::sendHeartbeatNow,
                    onRotateCode = viewModel::rotatePairingCode,
                    onNavigatePackages = { navController.navigate(Routes.PACKAGES) },
                    onNavigateInspector = { navController.navigate(Routes.INSPECTOR) },
                    onNavigateSettings = { navController.navigate(Routes.SETTINGS) },
                    onNavigateLog = { navController.navigate(Routes.LOG) },
                )
            }
            composable(Routes.PACKAGES) {
                PackagesScreen(
                    packages = state.installedPackages,
                    watched = state.settings.watchedPackages,
                    onToggle = { pkg, checked -> viewModel.toggleWatchedPackage(pkg, checked) },
                )
            }
            composable(Routes.INSPECTOR) {
                InspectorScreen(
                    notifications = state.activeNotifications,
                    onUse = viewModel::useAsDiscordTarget,
                    onClear = viewModel::clearDiscordTarget,
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    state = state,
                    onSaveApiKey = viewModel::setApiKey,
                    onSaveLabel = viewModel::setLabel,
                    onSaveHeartbeat = viewModel::setHeartbeatInterval,
                )
            }
            composable(Routes.LOG) {
                EventLogScreen(log = state.log, onClear = viewModel::clearLog)
            }
        }
    }
}
