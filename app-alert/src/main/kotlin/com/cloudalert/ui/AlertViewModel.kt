package com.cloudalert.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudalert.data.local.AlertEventDao
import com.cloudalert.data.local.AlertEventEntity
import com.cloudalert.data.prefs.AlertPreferences
import com.cloudalert.data.repo.AlertRepository
import com.cloudalert.fcm.FcmGateway
import com.cloudmonitor.shared.dto.DeviceState
import com.cloudmonitor.shared.ErrorCode
import com.cloudmonitor.shared.dto.DeviceStatusDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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

class AlertViewModel @Inject constructor(
    private val repository: AlertRepository,
    private val prefs: AlertPreferences,
    private val eventDao: AlertEventDao,
    private val fcm: FcmGateway,
) : ViewModel() {

    private val _state = MutableStateFlow(AlertUiState())
    val state: StateFlow<AlertUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            prefs.settings.collect { settings ->
                _state.value = _state.value.copy(
                    apiKey = settings.apiKey,
                    label = settings.label,
                    subscriptions = settings.subscribedDeviceIds,
                    lastSyncAt = settings.lastSyncAt,
                )
            }
        }
        viewModelScope.launch {
            eventDao.observeRecent(300).collect { rows ->
                _state.value = _state.value.copy(history = rows)
            }
        }
        _state.value = _state.value.copy(pushAvailable = fcm.isAvailable)
        refresh()
    }

    /** Refresh the dashboard. */
    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val status = repository.refreshStatus()
            val events = repository.refreshEvents(limit = 200)
            val message = status.exceptionOrNull()?.message
            _state.value = _state.value.copy(
                devices = status.getOrDefault(emptyList()),
                loading = false,
                message = message,
            )
            events.getOrNull()?.forEach { repository.storeEvent(it) }
        }
    }

    /** Register this device with the backend (also refreshes the FCM token). */
    fun register() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val result = repository.register()
            _state.value = _state.value.copy(loading = false, message = result)
            refresh()
        }
    }

    /** Pair with a cloud phone using its 6-digit code. */
    fun pair(pairingCode: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val result = repository.register(pairingCode)
            _state.value = _state.value.copy(loading = false, message = result)
            refresh()
        }
    }

    fun setApiKey(key: String) {
        viewModelScope.launch {
            prefs.setApiKey(key.trim())
            _state.value = _state.value.copy(message = "API key saved")
        }
    }

    fun setLabel(label: String) {
        viewModelScope.launch {
            prefs.setLabel(label)
            _state.value = _state.value.copy(message = "Name saved")
        }
    }

    fun setCodeFilter(code: String?) {
        _state.value = _state.value.copy(codeFilter = code)
    }

    /** All codes that can be filtered by. */
    val filterableCodes: List<String> = ErrorCode.all.map { it.wire }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
