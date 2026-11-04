import { incidentKey } from "./kvKeys";
import type { IncidentRecord, MonitorEvent } from "./types";

/**
 * The authoritative incident state machine.
 *
 * This is the TypeScript twin of `IncidentStateMachine` in
 * `shared/src/main/kotlin/com/cloudmonitor/shared/IncidentStateMachine.kt` and follows the
 * exact same rules:
 *
 *  * there is at most ONE open incident per (deviceId, packageName, code),
 *  * a DOWN event opens an incident, or keeps an existing one open,
 *  * a RECOVERED event closes an open incident and never opens one,
 *  * a RECOVERED event for an incident that was never opened is ignored,
 *  * HEARTBEAT events never touch incident state.
 */

export type IncidentTransition = "opened" | "duplicate_down" | "recovered" | "unchanged";

export interface ReduceResult {
  incident: IncidentRecord | null;
  transition: IncidentTransition;
  /** Whether this transition should produce a user-visible push. */
  shouldPush: boolean;
}

export function reduceIncident(
  previous: IncidentRecord | null,
  event: MonitorEvent,
): ReduceResult {
  const key = incidentKey(event.deviceId, event.packageName, event.code);

  if (event.type === "HEARTBEAT") {
    return { incident: previous, transition: "unchanged", shouldPush: false };
  }

  if (event.type === "DOWN") {
    if (previous === null || previous.state === "CLOSED") {
      const incident: IncidentRecord = {
        key,
        deviceId: event.deviceId,
        packageName: event.packageName ?? null,
        code: event.code,
        state: "OPEN",
        openedAt: event.occurredAt,
        lastEventAt: event.occurredAt,
        closedAt: null,
        eventCount: 1,
        confidence: event.confidence,
        detection: event.detection,
      };
      return { incident, transition: "opened", shouldPush: true };
    }
    const incident: IncidentRecord = {
      ...previous,
      lastEventAt: Math.max(previous.lastEventAt, event.occurredAt),
      eventCount: previous.eventCount + 1,
      confidence: event.confidence,
      detection: event.detection,
    };
    return { incident, transition: "duplicate_down", shouldPush: false };
  }

  // RECOVERED
  if (previous !== null && previous.state === "OPEN") {
    const incident: IncidentRecord = {
      ...previous,
      state: "CLOSED",
      lastEventAt: Math.max(previous.lastEventAt, event.occurredAt),
      closedAt: event.occurredAt,
      eventCount: previous.eventCount + 1,
    };
    return { incident, transition: "recovered", shouldPush: true };
  }
  return { incident: previous, transition: "unchanged", shouldPush: false };
}
