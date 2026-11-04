import { isAuthorized } from "../../src/auth";
import { errorResponse, jsonResponse, methodNotAllowed } from "../../src/cors";
import { HEARTBEAT_BUCKET_MS } from "../../src/kvKeys";
import { readDevices, readEvents, readHeartbeats, readIncidents } from "../../src/state";
import type { DeviceState, DeviceStatus, Env } from "../../src/types";

/**
 * `GET /api/status` — the Cloud Alert dashboard.
 *
 * Returns every known cloud phone, its liveness state, the version of Cloud Monitor it
 * runs and the incidents that are currently open.
 */
export const onRequestGet = async ({ request, env }: { request: Request; env: Env }) => {
  if (!isAuthorized(request, env, "alert")) {
    return errorResponse(request, env, 401, "unauthorized", "Invalid or missing x-api-key.");
  }

  const now = Date.now();
  const devices = await readDevices(env);
  const heartbeats = await readHeartbeats(env);
  const incidents = await readIncidents(env);
  const events = await readEvents(env);

  // Last event per device, so the dashboard can show when a phone last reported something.
  const lastEventByDevice = new Map<string, number>();
  for (const event of events) {
    const current = lastEventByDevice.get(event.deviceId) ?? 0;
    if (event.occurredAt > current) lastEventByDevice.set(event.deviceId, event.occurredAt);
  }

  const openIncidentCount = new Map<string, number>();
  for (const incident of Object.values(incidents)) {
    if (incident.state === "OPEN") {
      openIncidentCount.set(
        incident.deviceId,
        (openIncidentCount.get(incident.deviceId) ?? 0) + 1,
      );
    }
  }

  const statuses: DeviceStatus[] = Object.values(devices).map((device) => {
    const heartbeat = heartbeats[device.deviceId];
    const lastHeartbeatAt = heartbeat?.lastSeenAt ?? null;
    const state: DeviceState = computeState(lastHeartbeatAt, now);
    return {
      deviceId: device.deviceId,
      label: device.label,
      state,
      lastHeartbeatAt,
      lastEventAt: lastEventByDevice.get(device.deviceId) ?? null,
      appVersion: device.appVersion,
      versionCode: device.versionCode,
      watchedPackages: device.watchedPackages ?? [],
      openIncidentCount: openIncidentCount.get(device.deviceId) ?? 0,
      foregroundServiceActive: heartbeat?.foregroundServiceActive ?? false,
      networkAvailable: heartbeat?.networkAvailable ?? true,
    };
  });

  statuses.sort((a, b) => (b.lastHeartbeatAt ?? 0) - (a.lastHeartbeatAt ?? 0));

  const allIncidents = Object.values(incidents);
  const openIncidents = allIncidents
    .filter((incident) => incident.state === "OPEN")
    .sort((a, b) => b.lastEventAt - a.lastEventAt);
  const recentIncidents = allIncidents
    .filter((incident) => incident.state === "CLOSED")
    .sort((a, b) => b.lastEventAt - a.lastEventAt)
    .slice(0, 20);

  return jsonResponse(request, env, {
    devices: statuses,
    openIncidents,
    recentIncidents,
    serverTime: now,
  });
};

/**
 * Liveness state of a device.
 *
 * ONLINE  - a heartbeat was recorded in the current or the previous bucket.
 * STALE   - the last heartbeat is between 1 and 3 buckets old.
 * OFFLINE - no heartbeat for more than 3 buckets (15 minutes).
 */
export function computeState(lastHeartbeatAt: number | null, now: number): DeviceState {
  if (lastHeartbeatAt === null) return "UNKNOWN";
  const age = now - lastHeartbeatAt;
  if (age <= HEARTBEAT_BUCKET_MS * 2) return "ONLINE";
  if (age <= HEARTBEAT_BUCKET_MS * 3) return "STALE";
  return "OFFLINE";
}

export const onRequestPost = async ({ request, env }: { request: Request; env: Env }) =>
  methodNotAllowed(request, env);
