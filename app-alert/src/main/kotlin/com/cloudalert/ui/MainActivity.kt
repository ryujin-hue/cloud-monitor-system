package com.cloudalert.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
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
                )
            }
        }
    }
}
