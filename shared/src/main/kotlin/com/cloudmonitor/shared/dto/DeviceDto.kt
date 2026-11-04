package com.cloudmonitor.shared.dto

import kotlinx.serialization.Serializable

/**
 * Coarse liveness state of a cloud phone, derived by the backend from the last heartbeat.
 */
@Serializable
enum class DeviceState {
    /** A heartbeat arrived recently. */
    ONLINE,

    /** The last heartbeat is older than the "stale" threshold but not yet "offline". */
    STALE,

    /** No heartbeat within the offline threshold. */
    OFFLINE,

    /** The device is known but has never sent a heartbeat. */
    UNKNOWN,
}

/**
 * One cloud phone as shown on the Cloud Alert dashboard.
 */
@Serializable
data class DeviceStatusDto(
    val deviceId: String,

    /** Human readable name, configurable on the cloud phone. */
    val label: String = "",

    val state: DeviceState = DeviceState.UNKNOWN,

    /** Server-side timestamp of the last accepted heartbeat. */
    val lastHeartbeatAt: Long? = null,

    /** Server-side timestamp of the last accepted event. */
    val lastEventAt: Long? = null,

    /** Version of Cloud Monitor running on the cloud phone. */
    val appVersion: String = "",

    /** versionCode of Cloud Monitor running on the cloud phone. */
    val versionCode: Long = 0,

    /** Packages the cloud phone watches. */
    val watchedPackages: List<String> = emptyList(),

    /** Number of incidents currently open for this device. */
    val openIncidentCount: Int = 0,

    /** Last reported foreground-service state. */
    val foregroundServiceActive: Boolean = false,

    /** Last reported network state. */
    val networkAvailable: Boolean = true,
)

/**
 * An incident: one open (or recently closed) problem for a (device, package, code) triple.
 *
 * There is at most ONE open incident per triple at any time.
 */
@Serializable
data class IncidentDto(
    /** Stable key: `deviceId|packageName|code`. */
    val key: String = "",

    val deviceId: String = "",

    val packageName: String? = null,

    val code: String = "",

    /** `OPEN` or `CLOSED`. */
    val state: String = "CLOSED",

    val openedAt: Long = 0,

    val lastEventAt: Long = 0,

    val closedAt: Long? = null,

    val eventCount: Int = 0,

    val confidence: String = "",

    val detection: String = "",

    /** Human readable title resolved from the canonical error code. */
    val title: String = "",
)

/**
 * Response of `GET /api/status`.
 */
@Serializable
data class StatusResponseDto(
    val devices: List<DeviceStatusDto> = emptyList(),

    /** Incidents that are currently open, newest first. */
    val openIncidents: List<IncidentDto> = emptyList(),

    /** Recently closed incidents, newest first. */
    val recentIncidents: List<IncidentDto> = emptyList(),

    /** Server clock at the time of the response. */
    val serverTime: Long = 0,
)

/**
 * Response of `GET /api/events`.
 */
@Serializable
data class EventsResponseDto(
    val events: List<EventDto> = emptyList(),

    /** Opaque cursor for the next page, or null when the end was reached. */
    val nextCursor: String? = null,
)

/**
 * A single error returned by the backend.
 */
@Serializable
data class ApiErrorDto(
    val error: String = "",
    val message: String = "",
)
