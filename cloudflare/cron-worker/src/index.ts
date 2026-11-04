import {
  HEARTBEAT_BUCKET_MS,
  KV_KEYS,
  KV_TTL,
  incidentKey,
} from "../../src/kvKeys";
import { reduceIncident } from "../../src/incidents";
import { pushEvent } from "../../src/push";
import {
  kvGetJson,
  kvPut,
  readEvents,
  readIncidents,
  readSubscriptions,
  writeIncidents,
  writeSubscriptions,
} from "../../src/state";
import {
  ERROR_CODES,
  type DeviceRecord,
  type Env,
  type HeartbeatRecord,
  type IncidentRecord,
  type MonitorEvent,
} from "../../src/types";

/**
 * The cron Worker.
 *
 * Pages Functions cannot run on a schedule, so this Worker does the periodic work. Every
 * run it scans every known cloud phone and raises `E-HB-01` for the ones that stopped
 * reporting.
 *
 * Detection window
 * ----------------
 * A heartbeat is written to KV at most once per 5 minute bucket, so the backend only knows
 * that a phone was alive inside the current bucket. A phone is therefore reported as
 * offline after `HEARTBEAT_BUCKET_MS * HEARTBEAT_STALE_BUCKETS` (15 minutes) without a
 * new bucket, which is why the cron runs every 5 minutes.
 *
 * The Worker is also the only component allowed to originate `E-HB-01`: a cloud phone that
 * sends a forged `E-HB-01` is rejected by the backend.
 */
export default {
  async scheduled(
    _event: ScheduledEvent,
    env: Env,
    _ctx: ExecutionContext,
  ): Promise<void> {
    await scanForMissingHeartbeats(env);
  },
};

/** Number of buckets without a heartbeat after which a device counts as offline. */
export const HEARTBEAT_STALE_BUCKETS = 3;

/**
 * Scan every device and raise `E-HB-01` for the ones that went silent.
 *
 * Exported so that it can be unit tested and called manually from a Worker invocation.
 */
export async function scanForMissingHeartbeats(env: Env): Promise<{
  scanned: number;
  reported: number;
}> {
  const now = Date.now();
  const devices =
    (await kvGetJson<Record<string, DeviceRecord>>(env.KV, KV_KEYS.devices())) ?? {};
  const heartbeats =
    (await kvGetJson<Record<string, HeartbeatRecord>>(env.KV, KV_KEYS.heartbeats())) ?? {};
  const incidents = await readIncidents(env);

  const deviceIds = Object.keys(devices);
  let reported = 0;

  for (const deviceId of deviceIds) {
    const device = devices[deviceId];
    const heartbeat = heartbeats[deviceId];

    // A device that has never sent a heartbeat is not "offline", it is "unknown".
    if (!heartbeat) continue;

    const age = now - heartbeat.lastSeenAt;
    if (age <= HEARTBEAT_BUCKET_MS * HEARTBEAT_STALE_BUCKETS) continue;

    const key = incidentKey(deviceId, null, ERROR_CODES.E_HB_01);
    const existing = incidents[key];
    if (existing && existing.state === "OPEN") {
      // Already open: one open incident per (device, package, code).
      continue;
    }

    const event: MonitorEvent = {
      eventId: `hb-${deviceId}-${heartbeat.bucket}-${now}`,
      deviceId,
      type: "DOWN",
      code: ERROR_CODES.E_HB_01,
      packageName: null,
      detection: "backend",
      confidence: "high",
      occurredAt: now,
      receivedAt: now,
      seq: 0,
      appVersion: device.appVersion,
      versionCode: device.versionCode,
      metadata: {
        lastHeartbeatAt: String(heartbeat.lastSeenAt),
        silentForMs: String(age),
        bucketMs: String(HEARTBEAT_BUCKET_MS),
      },
    };

    const result = reduceIncident(existing ?? null, event);
    const incident: IncidentRecord = result.incident!;
    incidents[key] = incident;

    // Store the event in the rolling history so Cloud Alert can show it.
    const events = await readEvents(env);
    await kvPut(
      env.KV,
      KV_KEYS.events(),
      JSON.stringify({ events: [...events, event].slice(-1000) }),
      KV_TTL.events,
    );

    await pushEvent(env, event, incident, device.label);
    incident.lastPushAt = Date.now();
    incident.lastPushEventId = event.eventId;

    reported += 1;
  }

  if (reported > 0) {
    await writeIncidents(env, incidents);
  }

  return { scanned: deviceIds.length, reported };
}

/**
 * Remove FCM tokens that Firebase reported as unregistered.
 *
 * The Pages Functions already do this when they push; this is the safety net for the case
 * where no push happens for a long time.
 */
export async function pruneUnregisteredTokens(env: Env): Promise<number> {
  const subscriptions = await readSubscriptions(env);
  let removed = 0;
  for (const subscription of Object.values(subscriptions)) {
    const before = subscription.fcmTokens.length;
    subscription.fcmTokens = subscription.fcmTokens.filter((token) => !token.startsWith("unregistered:"));
    removed += before - subscription.fcmTokens.length;
  }
  if (removed > 0) {
    await writeSubscriptions(env, subscriptions);
  }
  return removed;
}
