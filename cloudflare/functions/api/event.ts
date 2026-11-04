import { isAuthorized } from "../../src/auth";
import { errorResponse, jsonResponse, methodNotAllowed } from "../../src/cors";
import { reduceIncident } from "../../src/incidents";
import { incidentKey } from "../../src/kvKeys";
import { pushEvent } from "../../src/push";
import {
  appendEvents,
  mergeIncident,
  readDevice,
  readEvents,
  readIncidents,
  trimIncidents,
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
 * Idempotent: the `eventId` is the idempotency key. An event that is already stored is
 * skipped completely: it neither opens an incident nor triggers a second push. This
 * matters because the cloud phone re-delivers its offline queue after a reboot.
 *
 * This endpoint NEVER writes the device registry: the registry belongs to the heartbeat
 * endpoint. Because KV is eventually consistent, a read-modify-write of a shared device
 * key here could overwrite a registration that the heartbeat had just written.
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
  // Events that are structurally fine but semantically not allowed. They are reported
  // instead of failing the whole batch, so that the offline queue of the cloud phone can
  // drain instead of retrying a payload the backend will never accept.
  const rejectedCodes: string[] = [];

  for (const raw of batch.events) {
    if (!raw || typeof raw.eventId !== "string" || typeof raw.deviceId !== "string") {
      rejectedCodes.push("malformed");
      continue;
    }
    if (!isErrorCode(raw.code)) {
      // Unknown codes are rejected: the code set is closed.
      rejectedCodes.push(String(raw.code ?? "unknown"));
      continue;
    }
    if (ERROR_CODE_OWNER[raw.code] !== "MONITOR") {
      // A cloud phone may never originate a backend-owned code (E-HB-01).
      rejectedCodes.push(raw.code);
      continue;
    }
    if (raw.type !== "DOWN" && raw.type !== "RECOVERED" && raw.type !== "HEARTBEAT") {
      rejectedCodes.push(`${raw.code}:${String(raw.type)}`);
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
    // Every event was rejected. The batch itself was well formed, so this is a success
    // from the transport point of view: the cloud phone may drop those events from its
    // offline queue instead of retrying them forever.
    return jsonResponse(request, env, {
      ok: true,
      accepted: 0,
      duplicates: 0,
      rejected: batch.events.length,
      rejectedCodes: [...new Set(rejectedCodes)],
    });
  }

  // Already stored events are ignored completely: this is the idempotency check.
  const storedEvents = await readEvents(env);
  const knownEventIds = new Set(storedEvents.map((event) => event.eventId));
  const fresh = accepted.filter((event) => !knownEventIds.has(event.eventId));

  const deviceId = batch.deviceId || accepted[0].deviceId;

  // The device record is only READ here, never written.
  const device = await readDevice(env, deviceId);

  const incidents = await readIncidents(env);
  const newEvents: MonitorEvent[] = [];
  const pushed: Array<{ event: MonitorEvent; incident: IncidentRecord | null }> = [];

  for (const event of fresh) {
    const key = incidentKey(event.deviceId, event.packageName, event.code);
    const result = reduceIncident(incidents[key] ?? null, event);
    if (result.incident) {
      incidents[key] = result.incident;
    }
    if (result.shouldPush) {
      pushed.push({ event, incident: result.incident });
    }
    newEvents.push(event);
  }

  // Append with a verification step: a concurrent writer must not be able to drop the
  // batch, and a replay of the same batch must not duplicate it.
  await appendEvents(env, newEvents);

  // Persist the incident state BEFORE pushing, so a push never points at an incident that
  // is not stored yet.
  const trimmed = trimIncidents(incidents);
  for (const incident of Object.values(trimmed)) {
    await mergeIncident(env, incident.key, incident);
  }

  for (const { event, incident } of pushed) {
    const label = device?.label ?? "";
    await pushEvent(env, event, incident, label);
    if (incident) {
      incident.lastPushAt = Date.now();
      incident.lastPushEventId = event.eventId;
      await mergeIncident(env, incident.key, incident);
    }
  }

  return jsonResponse(request, env, {
    ok: true,
    accepted: newEvents.length,
    duplicates: accepted.length - newEvents.length,
    rejected: rejectedCodes.length,
    rejectedCodes: [...new Set(rejectedCodes)],
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
