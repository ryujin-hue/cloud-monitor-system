package com.cloudmonitor.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
        enableEdgeToEdge()

        // The monitor must survive the app being closed and the phone being rebooted.
        MonitorForegroundService.start(this)
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

    // Permissions can be granted while the app is in the background, so re-check on resume.
    LaunchedEffect(Unit) { viewModel.refresh() }

    val startDestination = if (state.permissions.essentialsGranted) Routes.DASHBOARD else Routes.PERMISSIONS

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.PERMISSIONS) {
            PermissionsScreen(
                permissions = state.permissions,
                intents = viewModel.permissionIntents,
                onRefresh = viewModel::refresh,
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
