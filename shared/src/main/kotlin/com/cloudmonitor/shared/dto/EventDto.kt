package com.cloudmonitor.shared.dto

import kotlinx.serialization.Serializable

/**
 * The lifecycle of a monitored app, as seen by the cloud phone.
 */
@Serializable
enum class EventType {
    /** Something bad happened: the app died, stopped sharing, or the device went offline. */
    DOWN,

    /** The previously reported [DOWN] condition no longer holds. */
    RECOVERED,

    /** Liveness signal. Carries no incident semantics. */
    HEARTBEAT,
}

/**
 * A single observation reported by a Cloud Monitor.
 *
 * [eventId] is a UUID generated on the device and is used by the backend as the idempotency
 * key: re-delivering the same event (for example from the offline queue after a reboot) must
 * not create a second incident or a second push.
 */
@Serializable
data class EventDto(
    /** UUID v4, generated on the device. Idempotency key. */
    val eventId: String,

    /** Stable id of the cloud phone. */
    val deviceId: String,

    /** DOWN / RECOVERED / HEARTBEAT. */
    val type: EventType,

    /** Canonical error code, e.g. `E-101`. Never a free-form string. */
    val code: String,

    /** The monitored package the event refers to, when applicable. */
    val packageName: String? = null,

    /** Detection tier that produced the event: `tier1`, `tier2` or `backend`. */
    val detection: String,

    /** Confidence: `high`, `medium` or `low`. */
    val confidence: String,

    /** Device wall-clock time when the observation happened (epoch millis). */
    val occurredAt: Long,

    /** Server wall-clock time when the event was accepted. Filled in by the backend. */
    val receivedAt: Long? = null,

    /** Monotonic per-device sequence number, used to detect gaps and clock skew. */
    val seq: Long,

    /** Version of Cloud Monitor that produced the event, e.g. `1.0.42`. */
    val appVersion: String = "",

    /** versionCode of Cloud Monitor that produced the event. */
    val versionCode: Long = 0,

    /** Free-form, code-specific evidence (exit reason, notification id, ...). */
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * A batch of events sent to `POST /api/event`.
 *
 * Events are batched so that a burst of incidents costs a single request and a single
 * KV write on the backend.
 */
@Serializable
data class EventBatchDto(
    val deviceId: String,
    val appVersion: String = "",
    val versionCode: Long = 0,
    val events: List<EventDto>,
)
