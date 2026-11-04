package com.cloudmonitor.shared

import com.cloudmonitor.shared.dto.EventDto
import com.cloudmonitor.shared.dto.EventType

/**
 * Lifecycle of an incident.
 */
enum class IncidentState {
    OPEN,
    CLOSED,
}

/**
 * Immutable snapshot of one incident.
 */
data class IncidentSnapshot(
    /** Stable key: `deviceId|packageName|code`. */
    val key: String,
    val deviceId: String,
    val packageName: String?,
    val code: String,
    val state: IncidentState,
    val openedAt: Long,
    val lastEventAt: Long,
    val closedAt: Long?,
    val eventCount: Int,
    val confidence: String,
    val detection: String,
)

/**
 * Result of folding one event into the incident state.
 */
sealed interface IncidentTransition {

    /** The snapshot that results from applying the event. */
    val snapshot: IncidentSnapshot?

    /** Whether this transition should produce a user-visible push. */
    val shouldPush: Boolean

    /** A new incident was opened by a DOWN event. */
    data class Opened(override val snapshot: IncidentSnapshot) : IncidentTransition {
        override val shouldPush: Boolean = true
    }

    /** A DOWN event arrived for an incident that is already open. The incident stays open. */
    data class DuplicateDown(override val snapshot: IncidentSnapshot) : IncidentTransition {
        override val shouldPush: Boolean = false
    }

    /** An open incident was closed by a RECOVERED event. */
    data class Recovered(override val snapshot: IncidentSnapshot) : IncidentTransition {
        override val shouldPush: Boolean = true
    }

    /** The event did not change any incident state (duplicate RECOVERED, HEARTBEAT, ...). */
    data class Unchanged(override val snapshot: IncidentSnapshot?) : IncidentTransition {
        override val shouldPush: Boolean = false
    }
}

/**
 * The incident state machine.
 *
 * Invariants:
 *  * There is at most ONE open incident per (deviceId, packageName, code).
 *  * A DOWN event opens an incident, or keeps an existing one open.
 *  * A RECOVERED event closes an open incident; it never opens one.
 *  * A RECOVERED event for an incident that was never opened is ignored, so a stale recovery
 *    can never produce a spurious "recovered" notification.
 *  * HEARTBEAT events never touch incident state.
 *
 * The identical rules are implemented in TypeScript in `cloudflare/src/incidents.ts`, where the
 * backend is the authority. This Kotlin implementation is the client-side mirror used by
 * Cloud Monitor (to decide DOWN vs RECOVERED) and by Cloud Alert (to render incidents).
 */
object IncidentStateMachine {

    /**
     * Stable identity of an incident. `packageName` may be null for device-level codes such
     * as `E-HB-01`.
     */
    fun incidentKey(deviceId: String, packageName: String?, code: String): String =
        "$deviceId|${packageName ?: "-"}|$code"

    /**
     * Fold [event] into the incident identified by [previous].
     *
     * @return the new snapshot (or `null` when nothing is known about this incident yet)
     *         together with the transition that describes what happened.
     */
    fun reduce(
        previous: IncidentSnapshot?,
        event: EventDto,
    ): Pair<IncidentSnapshot?, IncidentTransition> {
        val key = incidentKey(event.deviceId, event.packageName, event.code)

        return when (event.type) {
            // A heartbeat never mutates incident state, but the current snapshot must be
            // carried over untouched.
            EventType.HEARTBEAT -> previous to IncidentTransition.Unchanged(previous)

            EventType.DOWN -> {
                if (previous == null || previous.state == IncidentState.CLOSED) {
                    val snapshot = IncidentSnapshot(
                        key = key,
                        deviceId = event.deviceId,
                        packageName = event.packageName,
                        code = event.code,
                        state = IncidentState.OPEN,
                        openedAt = event.occurredAt,
                        lastEventAt = event.occurredAt,
                        closedAt = null,
                        eventCount = 1,
                        confidence = event.confidence,
                        detection = event.detection,
                    )
                    snapshot to IncidentTransition.Opened(snapshot)
                } else {
                    val snapshot = previous.copy(
                        lastEventAt = maxOf(previous.lastEventAt, event.occurredAt),
                        eventCount = previous.eventCount + 1,
                        confidence = event.confidence,
                        detection = event.detection,
                    )
                    snapshot to IncidentTransition.DuplicateDown(snapshot)
                }
            }

            EventType.RECOVERED -> {
                if (previous != null && previous.state == IncidentState.OPEN) {
                    val snapshot = previous.copy(
                        state = IncidentState.CLOSED,
                        lastEventAt = maxOf(previous.lastEventAt, event.occurredAt),
                        closedAt = event.occurredAt,
                        eventCount = previous.eventCount + 1,
                    )
                    snapshot to IncidentTransition.Recovered(snapshot)
                } else {
                    // Never opened (or already closed): ignore, do not create a "recovered"
                    // notification for something that was never reported as down.
                    previous to IncidentTransition.Unchanged(previous)
                }
            }
        }
    }

    /**
     * Convenience helper that folds a whole list of events in chronological order.
     */
    fun reduceAll(
        previous: IncidentSnapshot?,
        events: List<EventDto>,
    ): Pair<IncidentSnapshot?, List<IncidentTransition>> {
        var current = previous
        val transitions = ArrayList<IncidentTransition>(events.size)
        for (event in events.sortedBy { it.occurredAt }) {
            val (snapshot, transition) = reduce(current, event)
            current = snapshot
            transitions += transition
        }
        return current to transitions
    }
}
