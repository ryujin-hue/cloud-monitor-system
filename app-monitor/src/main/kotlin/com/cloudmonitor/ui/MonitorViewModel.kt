package com.cloudmonitor.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudmonitor.data.local.EventLogDao
import com.cloudmonitor.data.local.EventLogEntity
import com.cloudmonitor.data.local.QueuedEventDao
import com.cloudmonitor.data.prefs.MonitorPreferences
import com.cloudmonitor.data.prefs.MonitorSettings
import com.cloudmonitor.di.PermissionIntents
import com.cloudmonitor.di.PermissionStatus
import com.cloudmonitor.di.PermissionStatusProvider
import com.cloudmonitor.domain.ProcessProbe
import com.cloudmonitor.monitor.NotificationEventBus
import com.cloudmonitor.monitor.NotificationSnapshot
import com.cloudmonitor.service.MonitorForegroundService
import com.cloudmonitor.service.MonitorRuntime
import com.cloudmonitor.service.PairingCodeProvider
import com.cloudmonitor.service.WatchdogWorker
import com.cloudmonitor.shared.CancellationGuard
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A package the user can choose to watch.
 */
data class InstalledPackage(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

/**
 * Everything the Cloud Monitor UI shows.
 */
data class MonitorUiState(
    val settings: MonitorSettings = MonitorSettings(),
    val permissions: PermissionStatus = PermissionStatus(),
    val pairingCode: String? = null,
    val activeNotifications: List<NotificationSnapshot> = emptyList(),
    val queueSize: Int = 0,
    val log: List<EventLogEntity> = emptyList(),
    val installedPackages: List<InstalledPackage> = emptyList(),
    val serviceRunning: Boolean = false,
    val privilegedProbeAvailable: Boolean = false,
    val rootStatusDetail: String = "not checked yet",
    val oemAutostartScreenAvailable: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class MonitorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: MonitorPreferences,
    private val permissionStatus: PermissionStatusProvider,
    val permissionIntents: PermissionIntents,
    private val pairing: PairingCodeProvider,
    private val eventLogDao: EventLogDao,
    private val queueDao: QueuedEventDao,
    private val bus: NotificationEventBus,
    private val runtime: MonitorRuntime,
    private val probe: ProcessProbe,
) : ViewModel() {

    private val _state = MutableStateFlow(MonitorUiState())
    val state: StateFlow<MonitorUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                prefs.settings,
                bus.active,
                queueDao.observeCount(),
                eventLogDao.observeRecent(200),
            ) { settings, notifications, queued, log ->
                Quad(settings, notifications, queued, log)
            }.collect { (settings, notifications, queued, log) ->
                _state.value = _state.value.copy(
                    settings = settings,
                    activeNotifications = notifications,
                    queueSize = queued,
                    log = log,
                )
            }
        }

        viewModelScope.launch {
            pairing.code.collect { code ->
                _state.value = _state.value.copy(pairingCode = code)
            }
        }

        refresh()
    }

    private companion object {
        const val TAG = "MonitorViewModel"
    }

    /** Re-read everything that can change outside of the app (permissions, packages, ...). */
    fun refresh() {
        viewModelScope.launch {
            // Detection runs on Dispatchers.IO inside the probe. Reading `isAvailable`
            // alone is a cheap cached lookup and never blocks the main thread.
            try {
                probe.refreshRootStatus()
            } catch (e: Throwable) {
                CancellationGuard.rethrowCancellation(e)
                Log.e(TAG, "root detection failed", e)
            }
            val permissions = permissionStatus.current()
            _state.value = _state.value.copy(
                permissions = permissions,
                serviceRunning = MonitorForegroundService.isRunning,
                privilegedProbeAvailable = probe.isAvailable,
                rootStatusDetail = probe.rootStatus.detail,
                oemAutostartScreenAvailable = permissionStatus.hasOemAutostartScreen(),
                installedPackages = loadInstalledPackages(),
            )
            val code = pairing.ensureCode()
            _state.value = _state.value.copy(pairingCode = code)
        }
    }

    /** Surface a failed automatic startup (e.g. from boot or an update) to the user. */
    fun reportStartupProblems() {
        MonitorForegroundService.lastStartFailure?.let { message(it) }
    }

    fun setLabel(label: String) = viewModelScope.launch {
        try {
            prefs.setLabel(label)
        } catch (e: Throwable) {
            CancellationGuard.rethrowCancellation(e)
            message("Could not save the name")
            Log.e(TAG, "could not persist label", e)
        }
    }

    fun setApiKey(key: String) = viewModelScope.launch {
        try {
            prefs.setApiKey(key.trim())
            message("API key saved")
        } catch (e: Throwable) {
            CancellationGuard.rethrowCancellation(e)
            // An invalidated Android Keystore key must never crash the settings screen.
            message(
                "Could not save the API key (device encryption key unavailable). " +
                    "Please re-enter it; if it keeps failing, clear app data and try again.",
            )
            Log.e(TAG, "could not persist API key", e)
        }
    }

    fun setHeartbeatInterval(seconds: Int) = viewModelScope.launch {
        prefs.setHeartbeatInterval(seconds)
        message("Heartbeat interval set to ${seconds}s")
    }

    fun toggleWatchedPackage(packageName: String, watched: Boolean) = viewModelScope.launch {
        try {
            val current = prefs.current().watchedPackages
            val updated = if (watched) current + packageName else current - packageName
            prefs.setWatchedPackages(updated)
            runtime.runScan()
        } catch (e: Throwable) {
            CancellationGuard.rethrowCancellation(e)
            message("Could not update the watched apps: ${e.message ?: "unknown error"}")
            Log.e(TAG, "watched package update failed", e)
        }
    }

    /** Use a notification discovered by the inspector as the Discord screen-share target. */
    fun useAsDiscordTarget(snapshot: NotificationSnapshot) = viewModelScope.launch {
        prefs.setDiscordTarget(
            packageName = snapshot.packageName,
            notificationId = snapshot.id,
            notificationTag = snapshot.tag,
            channelId = snapshot.channelId,
        )
        message("Discord target set to ${snapshot.packageName} (id=${snapshot.id})")
    }

    fun clearDiscordTarget() = viewModelScope.launch {
        prefs.setDiscordTarget(null, null, null, null)
        message("Discord target cleared")
    }

    fun rotatePairingCode() = viewModelScope.launch {
        val code = pairing.rotate()
        message("New pairing code: $code")
    }

    fun startService() {
        val outcome = MonitorForegroundService.start(context)
        if (!outcome.requested) {
            // The platform refused the start (Android 12+ background restriction, missing
            // permission, ...). Never report success.
            _state.value = _state.value.copy(serviceRunning = false)
            message(outcome.message)
            return
        }
        WatchdogWorker.schedule(context)
        viewModelScope.launch {
            // startForegroundService() only requests a start. Wait for onCreate() to
            // confirm that startForeground() actually succeeded before saying "started".
            var waited = 0
            while (waited < 2_000 && !MonitorForegroundService.isRunning) {
                delay(150)
                waited += 150
            }
            val failure = MonitorForegroundService.lastStartFailure
            when {
                MonitorForegroundService.isRunning && failure == null ->
                    message(outcome.message.ifBlank { "Monitor started" })
                failure != null -> message(failure)
                else -> message(
                    "The monitor did not start. Android may have blocked the foreground " +
                        "service; check battery optimisation and the notification permission.",
                )
            }
            refresh()
        }
    }

    fun stopService() {
        val outcome = MonitorForegroundService.stop(context)
        viewModelScope.launch {
            var waited = 0
            while (waited < 1_500 && MonitorForegroundService.isRunning) {
                delay(150)
                waited += 150
            }
            message(
                if (MonitorForegroundService.isRunning) {
                    "Could not stop the monitor: ${outcome.message}"
                } else {
                    outcome.message
                },
            )
            refresh()
        }
    }

    fun sendHeartbeatNow() = viewModelScope.launch {
        runtime.sendHeartbeat()
        message("Heartbeat sent")
    }

    fun clearLog() = viewModelScope.launch {
        eventLogDao.clear()
        message("Local history cleared")
    }

    private fun message(text: String) {
        _state.value = _state.value.copy(message = text)
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /** Called when the user denies POST_NOTIFICATIONS (including permanently). */
    fun reportNotificationPermissionDenied() {
        message(
            "Notification permission denied. Outage notifications and the ongoing monitor " +
                "status will be hidden. Enable it in Settings → Apps → Cloud Monitor → " +
                "Notifications (Android 13+ / Android 16).",
        )
    }

    private fun loadInstalledPackages(): List<InstalledPackage> {
        val pm = context.packageManager
        return try {
            pm.getInstalledApplications(0)
                .filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
                .map {
                    InstalledPackage(
                        packageName = it.packageName,
                        label = pm.getApplicationLabel(it).toString(),
                        isSystem = it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0,
                    )
                }
                .sortedBy { it.label.lowercase() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Small helper to combine four flows without pulling in a dependency. */
    private data class Quad<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
    )
}
