package com.cloudmonitor.ui

import android.content.Context
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
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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

    /** Re-read everything that can change outside of the app (permissions, packages, ...). */
    fun refresh() {
        viewModelScope.launch {
            val permissions = permissionStatus.current()
            _state.value = _state.value.copy(
                permissions = permissions,
                serviceRunning = MonitorForegroundService.isRunning,
                privilegedProbeAvailable = probe.isAvailable,
                oemAutostartScreenAvailable = permissionStatus.hasOemAutostartScreen(),
                installedPackages = loadInstalledPackages(),
            )
            val code = pairing.ensureCode()
            _state.value = _state.value.copy(pairingCode = code)
        }
    }

    fun setLabel(label: String) = viewModelScope.launch { prefs.setLabel(label) }

    fun setApiKey(key: String) = viewModelScope.launch {
        prefs.setApiKey(key.trim())
        message("API key saved")
    }

    fun setHeartbeatInterval(seconds: Int) = viewModelScope.launch {
        prefs.setHeartbeatInterval(seconds)
        message("Heartbeat interval set to ${seconds}s")
    }

    fun toggleWatchedPackage(packageName: String, watched: Boolean) = viewModelScope.launch {
        val current = prefs.current().watchedPackages
        val updated = if (watched) current + packageName else current - packageName
        prefs.setWatchedPackages(updated)
        runtime.runScan()
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
        MonitorForegroundService.start(context)
        com.cloudmonitor.service.WatchdogWorker.schedule(context)
        message("Monitor started")
        refresh()
    }

    fun stopService() {
        MonitorForegroundService.stop(context)
        message("Monitor stopped")
        refresh()
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
