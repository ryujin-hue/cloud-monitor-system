import { constantTimeEquals } from "../../src/auth";
import { incidentKey } from "../../src/kvKeys";
import { reduceIncident } from "../../src/incidents";
import { pushEvent } from "../../src/push";
import {
  appendEvents,
  bucketMsFor,
  listDeviceRecords,
  mergeIncident,
  readHeartbeat,
  readIncidents,
  readSubscriptions,
  writeDeviceIndex,
  writeSubscriptions,
} from "../../src/state";
import {
  ERROR_CODES,
  type DeviceIndex,
  type Env,
  type IncidentRecord,
  type MonitorEvent,
} from "../../src/types";

/**
 * The cron Worker.
 *
 * Pages Functions cannot run on a schedule, so this Worker does the periodic work. Every
 * run it
 *
 *   1. lists the known cloud phones and REBUILDS the device index (the only writer of
 *      that key, so it cannot race with anything),
 *   2. raises `E-HB-01` for the phones that stopped reporting.
 *
 * Detection window
 * ----------------
 * A heartbeat is written to KV at most once per bucket. The bucket length grows with the
 * fleet size so that the daily write budget is always respected (see
 * `effectiveBucketMs`). A phone is reported as offline after `HEARTBEAT_STALE_BUCKETS`
 * buckets without a new one, which is 20 minutes for a small fleet.
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

  /**
   * Manual trigger:
   *   curl -H "x-cron-secret: <CRON_SECRET>" \
   *     https://cloud-monitor-cron.<account>.workers.dev/
   *
   * The secret belongs in a HEADER, not in the URL: query strings end up in access logs,
   * browser history and Referer headers. The legacy `?key=` / `x-api-key` forms are still
   * accepted for compatibility but should not be used.
   *
   * The scheduled run every five minutes is the normal path; this endpoint exists so the
   * scan can be forced (for example right after a phone was added).
   */
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const secret = env.CRON_SECRET;
    const provided =
      request.headers.get("x-cron-secret") ??
      request.headers.get("x-api-key") ??
      url.searchParams.get("key") ??
      "";
    // Constant-time comparison: `!==` leaks how many leading characters matched.
    if (!secret || !constantTimeEquals(provided, secret)) {
      return new Response(JSON.stringify({ ok: false, error: "unauthorized" }), {
        status: 401,
        headers: { "content-type": "application/json" },
      });
    }
    const pruned = await pruneUnregisteredTokens(env);
    const result = await scanForMissingHeartbeats(env);
    return new Response(JSON.stringify({ ok: true, ...result, prunedTokens: pruned }), {
      status: 200,
      headers: { "content-type": "application/json" },
    });
  },
};

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

  // One KV list request per run: 288 per day, well inside the 1,000 list requests/day of
  // the free plan.
  const devices = await listDeviceRecords(env);

  // The index is rebuilt here and ONLY here, so it has exactly one writer and can never
  // lose an entry to a concurrent write.
  const index: DeviceIndex = {};
  for (const device of devices) {
    index[device.deviceId] = { label: device.label, updatedAt: device.updatedAt };
  }
  await writeDeviceIndex(env, index);

  const bucketMs = bucketMsFor(devices.length || 1);
  const incidents = await readIncidents(env);
  let reported = 0;

  for (const device of devices) {
    const heartbeat = await readHeartbeat(env, device.deviceId);

    // A device that has never sent a heartbeat is not "offline", it is "unknown".
    if (!heartbeat) continue;

    const age = now - heartbeat.lastSeenAt;
    if (age <= bucketMs * HEARTBEAT_STALE_BUCKETS) continue;

    const key = incidentKey(device.deviceId, null, ERROR_CODES.E_HB_01);
    const existing = incidents[key];
    if (existing && existing.state === "OPEN") {
      // Already open: one open incident per (device, package, code).
      continue;
    }

    const event: MonitorEvent = {
      eventId: `hb-${device.deviceId}-${heartbeat.bucket}-${now}`,
      deviceId: device.deviceId,
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
        bucketMs: String(bucketMs),
      },
    };

    const result = reduceIncident(existing ?? null, event);
    const incident: IncidentRecord = result.incident!;
    incidents[key] = incident;

    // Store the event in the rolling history so Cloud Alert can show it.
    await appendEvents(env, [event]);
    await mergeIncident(env, key, incident);

    await pushEvent(env, event, incident, device.label);
    incident.lastPushAt = Date.now();
    incident.lastPushEventId = event.eventId;
    await mergeIncident(env, key, incident);

    reported += 1;
  }

  return { scanned: devices.length, reported };
}

/** How many buckets without a heartbeat after which a device counts as offline. */
export const HEARTBEAT_STALE_BUCKETS = 2;

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
    subscription.fcmTokens = subscription.fcmTokens.filter(
      (token) => !token.startsWith("unregistered:"),
    );
    removed += before - subscription.fcmTokens.length;
  }
  if (removed > 0) {
    await writeSubscriptions(env, subscriptions);
  }
  return removed;
}
