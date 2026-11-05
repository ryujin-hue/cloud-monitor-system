package com.cloudalert.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudalert.data.local.AlertEventDao
import com.cloudalert.data.local.AlertEventEntity
import com.cloudalert.data.prefs.AlertPreferences
import com.cloudalert.data.repo.AlertRepository
import com.cloudalert.fcm.FcmGateway
import com.cloudmonitor.shared.CancellationGuard
import com.cloudmonitor.shared.ErrorCode
import com.cloudmonitor.shared.dto.DeviceState
import com.cloudmonitor.shared.dto.DeviceStatusDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State of the Cloud Alert dashboard.
 */
data class AlertUiState(
    val devices: List<DeviceStatusDto> = emptyList(),
    val history: List<AlertEventEntity> = emptyList(),
    val apiKey: String = "",
    val label: String = "",
    val subscriptions: Set<String> = emptySet(),
    val codeFilter: String? = null,
    val pushAvailable: Boolean = false,
    val lastSyncAt: Long = 0L,
    val loading: Boolean = false,
    val message: String? = null,
) {
    val onlineCount: Int get() = devices.count { it.state == DeviceState.ONLINE }
    val offlineCount: Int get() = devices.count { it.state == DeviceState.OFFLINE || it.state == DeviceState.STALE }
    val openIncidentCount: Int get() = devices.sumOf { it.openIncidentCount }
}

/**
 * Dashboard ViewModel.
 *
 * `@HiltViewModel` is REQUIRED because [com.cloudalert.ui.MainActivity] obtains this
 * instance with `hiltViewModel()`. Without the annotation Hilt does not generate a
 * ViewModel factory binding, the default factory tries to instantiate the class through a
 * no-argument constructor that does not exist, and Cloud Alert crashes as soon as the
 * activity opens.
 */
@HiltViewModel
class AlertViewModel @Inject constructor(
    private val repository: AlertRepository,
    private val prefs: AlertPreferences,
    private val eventDao: AlertEventDao,
    private val fcm: FcmGateway,
) : ViewModel() {

    private companion object {
        const val TAG = "AlertViewModel"
    }

    private val _state = MutableStateFlow(AlertUiState())
    val state: StateFlow<AlertUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                prefs.settings.collect { settings ->
                    _state.value = _state.value.copy(
                        apiKey = settings.apiKey,
                        label = settings.label,
                        subscriptions = settings.subscribedDeviceIds,
                        lastSyncAt = settings.lastSyncAt,
                    )
                }
            } catch (e: Throwable) {
                CancellationGuard.rethrowCancellation(e)
                Log.e(TAG, "settings stream failed", e)
            }
        }
        viewModelScope.launch {
            try {
                eventDao.observeRecent(300).collect { rows ->
                    _state.value = _state.value.copy(history = rows)
                }
            } catch (e: Throwable) {
                CancellationGuard.rethrowCancellation(e)
                Log.e(TAG, "history stream failed", e)
            }
        }
        _state.value = _state.value.copy(pushAvailable = fcm.isAvailable)
        refresh()
    }

    /** Refresh the dashboard. A backend or database failure never crashes the screen. */
    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            try {
                val status = repository.refreshStatus()
                val events = repository.refreshEvents(limit = 200)
                _state.value = _state.value.copy(
                    devices = status.getOrDefault(emptyList()),
                    loading = false,
                    message = status.exceptionOrNull()?.message,
                )
                events.getOrNull()?.forEach { event ->
                    try {
                        repository.storeEvent(event)
                    } catch (e: Throwable) {
                        CancellationGuard.rethrowCancellation(e)
                        Log.e(TAG, "could not store event ${event.eventId}", e)
                    }
                }
            } catch (e: Throwable) {
                CancellationGuard.rethrowCancellation(e)
                _state.value = _state.value.copy(
                    loading = false,
                    message = "Could not refresh: ${e.message ?: "unknown error"}",
                )
            }
        }
    }

    /** Register this device with the backend (also refreshes the FCM token). */
    fun register() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            try {
                val result = repository.register()
                _state.value = _state.value.copy(loading = false, message = result)
            } catch (e: Throwable) {
                CancellationGuard.rethrowCancellation(e)
                _state.value = _state.value.copy(
                    loading = false,
                    message = "Registration failed: ${e.message ?: "unknown error"}",
                )
            }
            refresh()
        }
    }

    /** Pair with a cloud phone using its 6-digit code. */
    fun pair(pairingCode: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            try {
                val result = repository.register(pairingCode)
                _state.value = _state.value.copy(loading = false, message = result)
            } catch (e: Throwable) {
                CancellationGuard.rethrowCancellation(e)
                _state.value = _state.value.copy(
                    loading = false,
                    message = "Pairing failed: ${e.message ?: "unknown error"}",
                )
            }
            refresh()
        }
    }

    fun setApiKey(key: String) {
        viewModelScope.launch {
            try {
                prefs.setApiKey(key.trim())
                _state.value = _state.value.copy(message = "API key saved")
            } catch (e: Throwable) {
                CancellationGuard.rethrowCancellation(e)
                // A Keystore key can be invalidated (e.g. after a lock-screen change or a
                // restore). Never crash: tell the user to re-enter the key instead.
                _state.value = _state.value.copy(
                    message = "Could not save the API key (device encryption key unavailable). " +
                        "Please re-enter it; if it keeps failing, clear app data and try again.",
                )
                Log.e(TAG, "could not persist API key", e)
            }
        }
    }

    fun setLabel(label: String) {
        viewModelScope.launch {
            try {
                prefs.setLabel(label)
                _state.value = _state.value.copy(message = "Name saved")
            } catch (e: Throwable) {
                CancellationGuard.rethrowCancellation(e)
                _state.value = _state.value.copy(message = "Could not save the name")
                Log.e(TAG, "could not persist label", e)
            }
        }
    }

    fun setCodeFilter(code: String?) {
        _state.value = _state.value.copy(codeFilter = code)
    }

    /** All codes that can be filtered by. */
    val filterableCodes: List<String> = ErrorCode.all.map { it.wire }

    /** Publish a user-readable message from outside the ViewModel (e.g. permission UI). */
    fun notify(message: String) {
        _state.value = _state.value.copy(message = message)
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
