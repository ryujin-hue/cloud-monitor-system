import { isAuthorized } from "../../src/auth";
import { errorResponse, jsonResponse, methodNotAllowed } from "../../src/cors";
import { reduceIncident } from "../../src/incidents";
import { incidentKey } from "../../src/kvKeys";
import { pushEvent } from "../../src/push";
import {
  readDevices,
  readEvents,
  readIncidents,
  trimIncidents,
  writeDevices,
  writeEvents,
  writeIncidents,
} from "../../src/state";
import {
  ERROR_CODE_OWNER,
  isErrorCode,
  type Env,
  type EventBatch,
  type IncidentRecord,
  type MonitorEvent,
} from "../../src/types";

/**
 * `POST /api/event` — ingest a batch of events from a Cloud Monitor.
 *
 * Idempotent: the `eventId` is the idempotency key, so re-delivering a batch (for example
 * from the cloud phone's offline queue after a reboot) never creates a second incident and
 * never triggers a second push.
 */
export const onRequestPost = async ({
  request,
  env,
}: {
  request: Request;
  env: Env;
}): Promise<Response> => {
  if (!isAuthorized(request, env, "monitor")) {
    return errorResponse(request, env, 401, "unauthorized", "Invalid or missing x-api-key.");
  }

  let batch: EventBatch;
  try {
    batch = (await request.json()) as EventBatch;
  } catch {
    return errorResponse(request, env, 400, "invalid_json", "The body is not valid JSON.");
  }

  if (!batch || !Array.isArray(batch.events) || batch.events.length === 0) {
    return errorResponse(request, env, 400, "invalid_batch", "No events in the batch.");
  }
  if (batch.events.length > 200) {
    return errorResponse(request, env, 413, "batch_too_large", "At most 200 events per batch.");
  }

  const now = Date.now();
  const accepted: MonitorEvent[] = [];

  for (const raw of batch.events) {
    if (!raw || typeof raw.eventId !== "string" || typeof raw.deviceId !== "string") {
      continue;
    }
    if (!isErrorCode(raw.code)) {
      // Unknown codes are rejected: the code set is closed.
      continue;
    }
    if (ERROR_CODE_OWNER[raw.code] !== "MONITOR") {
      // A cloud phone may never originate a backend-owned code (E-HB-01).
      continue;
    }
    if (raw.type !== "DOWN" && raw.type !== "RECOVERED" && raw.type !== "HEARTBEAT") {
      continue;
    }
    accepted.push({
      ...raw,
      // The server timestamp is authoritative; the device clock may be wrong.
      receivedAt: now,
      occurredAt: clampTimestamp(raw.occurredAt, now),
      metadata: raw.metadata ?? {},
    });
  }

  if (accepted.length === 0) {
    return errorResponse(request, env, 400, "no_valid_events", "No valid events in the batch.");
  }

  const devices = await readDevices(env);
  const incidents = await readIncidents(env);
  const storedEvents = await readEvents(env);
  const knownEventIds = new Set(storedEvents.map((event) => event.eventId));

  let devicesChanged = false;
  const deviceId = batch.deviceId || accepted[0].deviceId;
  const existingDevice = devices[deviceId];
  if (!existingDevice) {
    devices[deviceId] = {
      deviceId,
      label: "",
      appVersion: batch.appVersion ?? "",
      versionCode: batch.versionCode ?? 0,
      watchedPackages: [],
      registeredAt: now,
      updatedAt: now,
      pairingCode: null,
      pairingExpiresAt: null,
      heartbeatIntervalMs: 60_000,
    };
    devicesChanged = true;
  }

  const newEvents: MonitorEvent[] = [];
  const pushed: Array<{ event: MonitorEvent; incident: IncidentRecord | null }> = [];

  for (const event of accepted) {
    if (knownEventIds.has(event.eventId)) {
      // Idempotent replay: already stored, nothing to do.
      continue;
    }
    knownEventIds.add(event.eventId);
    newEvents.push(event);

    const key = incidentKey(event.deviceId, event.packageName, event.code);
    const result = reduceIncident(incidents[key] ?? null, event);
    if (result.incident) {
      incidents[key] = result.incident;
    }
    if (result.shouldPush) {
      pushed.push({ event, incident: result.incident });
    }
  }

  if (newEvents.length > 0) {
    await writeEvents(env, [...storedEvents, ...newEvents]);
  }
  const trimmed = trimIncidents(incidents);
  if (Object.keys(trimmed).length !== Object.keys(incidents).length) {
    await writeIncidents(env, trimmed);
  } else if (newEvents.length > 0) {
    await writeIncidents(env, trimmed);
  }
  if (devicesChanged) {
    await writeDevices(env, devices);
  }

  // Push AFTER the state is persisted, so a push never points at an incident that is not
  // stored yet.
  for (const { event, incident } of pushed) {
    const label = devices[event.deviceId]?.label ?? "";
    await pushEvent(env, event, incident, label);
    if (incident) {
      incident.lastPushAt = Date.now();
      incident.lastPushEventId = event.eventId;
    }
  }
  if (pushed.length > 0) {
    await writeIncidents(env, trimmed);
  }

  return jsonResponse(request, env, {
    ok: true,
    accepted: newEvents.length,
    duplicates: accepted.length - newEvents.length,
    serverTime: now,
  });
};

export const onRequestGet = async ({ request, env }: { request: Request; env: Env }) =>
  methodNotAllowed(request, env);

/**
 * Clamp a device timestamp.
 *
 * A cloud phone with a wrong clock must not create incidents in the future or in 1970. The
 * raw value is kept in the metadata so the cause stays visible.
 */
function clampTimestamp(occurredAt: number, now: number): number {
  if (!Number.isFinite(occurredAt) || occurredAt <= 0) return now;
  // Allow 5 minutes of clock drift into the future.
  if (occurredAt > now + 5 * 60 * 1000) return now;
  // Anything older than 30 days is clamped to "30 days ago" so the history stays usable.
  const oldest = now - 30 * 24 * 60 * 60 * 1000;
  if (occurredAt < oldest) return oldest;
  return occurredAt;
}
