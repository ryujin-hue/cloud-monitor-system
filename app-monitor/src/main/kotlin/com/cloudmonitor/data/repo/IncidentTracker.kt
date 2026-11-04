package com.cloudmonitor.data.repo

import com.cloudmonitor.data.local.EventLogDao
import com.cloudmonitor.data.local.EventLogEntity
import com.cloudmonitor.shared.IncidentSnapshot
import com.cloudmonitor.shared.IncidentState
import com.cloudmonitor.shared.IncidentStateMachine
import com.cloudmonitor.shared.IncidentTransition
import com.cloudmonitor.shared.dto.EventDto
import com.cloudmonitor.shared.dto.EventType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local mirror of the incident state.
 *
 * The backend is the authority, but the cloud phone needs to know whether an incident is
 * already open so that it does not emit a second DOWN event every scan, and so that it can
 * emit the matching RECOVERED event when the app comes back.
 *
 * The state is rebuilt from the local event log on startup, so it survives an app update.
 */
@Singleton
class IncidentTracker @Inject constructor(
    private val eventLogDao: EventLogDao,
) {

    private val _incidents = MutableStateFlow<Map<String, IncidentSnapshot>>(emptyMap())
    val incidents: StateFlow<Map<String, IncidentSnapshot>> = _incidents.asStateFlow()

    /** Rebuild the state from the persisted event log. */
    suspend fun restore() {
        val rows: List<EventLogEntity> = eventLogDao.observeRecent(MAX_RESTORE_EVENTS).first()
        var state = emptyMap<String, IncidentSnapshot>()
        for (row in rows.sortedBy { it.occurredAt }) {
            val event = row.toEventDto()
            val key = IncidentStateMachine.incidentKey(event.deviceId, event.packageName, event.code)
            val (snapshot, _) = IncidentStateMachine.reduce(state[key], event)
            if (snapshot != null) state = state + (key to snapshot)
        }
        _incidents.value = state
    }

    /** True when an incident for (device, package, code) is currently open. */
    fun isOpen(deviceId: String, packageName: String?, code: String): Boolean {
        val key = IncidentStateMachine.incidentKey(deviceId, packageName, code)
        return _incidents.value[key]?.state == IncidentState.OPEN
    }

    /** All currently open incidents for a package, regardless of code. */
    fun openIncidentsFor(packageName: String): List<IncidentSnapshot> =
        _incidents.value.values.filter {
            it.state == IncidentState.OPEN && it.packageName == packageName
        }

    /** Fold an event into the local state and return the transition. */
    fun apply(event: EventDto): IncidentTransition {
        val key = IncidentStateMachine.incidentKey(event.deviceId, event.packageName, event.code)
        val (snapshot, transition) = IncidentStateMachine.reduce(_incidents.value[key], event)
        _incidents.value = if (snapshot == null) {
            _incidents.value - key
        } else {
            _incidents.value + (key to snapshot)
        }
        return transition
    }

    private fun EventLogEntity.toEventDto(): EventDto = EventDto(
        eventId = eventId,
        deviceId = deviceId,
        type = runCatching { EventType.valueOf(type) }.getOrDefault(EventType.HEARTBEAT),
        code = code,
        packageName = packageName,
        detection = "",
        confidence = "",
        occurredAt = occurredAt,
        seq = seq,
    )

    private companion object {
        const val MAX_RESTORE_EVENTS = 500
    }
}
