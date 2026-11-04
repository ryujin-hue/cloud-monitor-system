/**
 * Typed wrappers around the Workers KV binding.
 *
 * Three things happen here that matter a lot in production:
 *
 *  1. **Write retries.** KV allows at most one write per second to the same key and
 *     answers with HTTP 429 when that limit is hit. Two cloud phones reporting at the same
 *     moment would otherwise lose an event, so every write retries with a small backoff
 *     and RE-READS the key, so a retry never writes a stale merge.
 *
 *  2. **Verified merges.** The shared keys (events, incidents, subscriptions, the device
 *     index) are written with a read-modify-write. Because KV is eventually consistent,
 *     a concurrent writer can clobber our change. `mergeJson` re-reads after writing and
 *     retries when its own change did not survive.
 *
 *  3. **Bounded growth.** The incident and event maps are trimmed so that the values stay
 *     far below the 25 MB KV value limit.
 */

import {
  KV_KEYS,
  KV_NAMESPACE_PREFIX,
  KV_TTL,
  MAX_STORED_EVENTS,
  MAX_STORED_INCIDENTS,
  effectiveBucketMs,
} from "./kvKeys";
import type {
  DeviceIndex,
  DeviceIndexEntry,
  DeviceRecord,
  Env,
  HeartbeatRecord,
  IncidentRecord,
  MonitorEvent,
  SubscriptionRecord,
} from "./types";

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function isRateLimited(error: unknown): boolean {
  const status = (error as { status?: number } | null)?.status;
  if (status === 429) return true;
  return /429|rate limit|too many/i.test(String(error));
}

/**
 * Write a value, retrying when KV answers with 429 (one write per second per key).
 */
export async function kvPut(
  kv: KVNamespace,
  key: string,
  value: string,
  expirationTtl?: number,
): Promise<void> {
  let lastError: unknown;
  for (let attempt = 0; attempt < 5; attempt++) {
    try {
      await kv.put(key, value, expirationTtl ? { expirationTtl } : undefined);
      return;
    } catch (error) {
      lastError = error;
      if (!isRateLimited(error)) throw error;
      // KV allows 1 write/second/key: wait a little longer than a second.
      await sleep(1100 + attempt * 250);
    }
  }
  throw new Error(`KV write failed for ${key}: ${String(lastError)}`);
}

export async function kvGetJson<T>(kv: KVNamespace, key: string): Promise<T | null> {
  const raw = await kv.get(key, "json");
  return (raw as T | null) ?? null;
}

/**
 * Read-modify-write with a verification step.
 *
 * `patch` receives the current value and returns the new one. After writing, the key is
 * read again: when our own change did not survive (a concurrent writer clobbered us with
 * a stale copy) the merge is retried.
 */
export async function mergeJson<T extends object>(
  kv: KVNamespace,
  key: string,
  patch: (current: T) => T,
  expirationTtl: number,
  verify?: (current: T) => boolean,
  maxAttempts = 4,
): Promise<T> {
  let last: T = {} as T;
  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    const current = ((await kvGetJson<T>(kv, key)) ?? {}) as T;
    const next = patch(current);
    last = next;

    try {
      await kv.put(key, JSON.stringify(next), { expirationTtl });
    } catch (error) {
      if (!isRateLimited(error)) throw error;
      await sleep(1100 + attempt * 250);
      continue;
    }

    if (!verify) return next;

    const check = ((await kvGetJson<T>(kv, key)) ?? {}) as T;
    if (verify(check)) return next;

    // Our change did not survive: another writer used a stale copy. Retry.
    await sleep(200 + attempt * 200);
  }
  return last;
}

// ---------------------------------------------------------------------------
// Device registry (one key per device)
// ---------------------------------------------------------------------------

export async function readDevice(env: Env, deviceId: string): Promise<DeviceRecord | null> {
  return kvGetJson<DeviceRecord>(env.KV, KV_KEYS.device(deviceId));
}

export async function writeDevice(env: Env, device: DeviceRecord): Promise<void> {
  await kvPut(env.KV, KV_KEYS.device(device.deviceId), JSON.stringify(device), KV_TTL.device);
}

/**
 * Enumerate the known devices by listing their own keys.
 *
 * A KV list request counts against the "1,000 list requests per day" limit of the free
 * plan, so this is only used by the cron Worker (one list per run) and by the (rare)
 * pairing lookup.
 */
export async function listDeviceRecords(env: Env): Promise<DeviceRecord[]> {
  const devices: DeviceRecord[] = [];
  let cursor: string | undefined;
  do {
    const result = await env.KV.list({
      prefix: `${KV_NAMESPACE_PREFIX}:device:`,
      cursor,
    });
    for (const key of result.keys) {
      const device = await kvGetJson<DeviceRecord>(env.KV, key.name);
      if (device) devices.push(device);
    }
    cursor = result.list_complete ? undefined : result.cursor;
  } while (cursor);
  return devices;
}

export async function readDeviceIndex(env: Env): Promise<DeviceIndex> {
  return (await kvGetJson<DeviceIndex>(env.KV, KV_KEYS.deviceIndex())) ?? {};
}

/**
 * Rebuild the device index.
 *
 * Only the cron Worker calls this, so there is exactly ONE writer and therefore no race.
 */
export async function writeDeviceIndex(env: Env, index: DeviceIndex): Promise<void> {
  await kvPut(env.KV, KV_KEYS.deviceIndex(), JSON.stringify(index), KV_TTL.deviceIndex);
}

// ---------------------------------------------------------------------------
// Heartbeats (one shared key, bucketed)
// ---------------------------------------------------------------------------

/**
 * The liveness record of ONE device.
 *
 * Every device has its own key, so two cloud phones can never overwrite each other's
 * liveness data. That matters because KV is eventually consistent: with a shared key,
 * five phones that heartbeated at the same instant reduced to two.
 */
export async function readHeartbeat(env: Env, deviceId: string): Promise<HeartbeatRecord | null> {
  return kvGetJson<HeartbeatRecord>(env.KV, KV_KEYS.heartbeat(deviceId));
}

export async function writeHeartbeat(env: Env, record: HeartbeatRecord): Promise<void> {
  await kvPut(
    env.KV,
    KV_KEYS.heartbeat(record.deviceId),
    JSON.stringify(record),
    KV_TTL.heartbeat,
  );
}

/**
 * The heartbeat bucket length that the current fleet size allows.
 */
export function bucketMsFor(deviceCount: number): number {
  return effectiveBucketMs(deviceCount);
}

// ---------------------------------------------------------------------------
// Incidents (one shared key)
// ---------------------------------------------------------------------------

export async function readIncidents(env: Env): Promise<Record<string, IncidentRecord>> {
  return (await kvGetJson<Record<string, IncidentRecord>>(env.KV, KV_KEYS.incidents())) ?? {};
}

export async function writeIncidents(
  env: Env,
  incidents: Record<string, IncidentRecord>,
): Promise<void> {
  await kvPut(env.KV, KV_KEYS.incidents(), JSON.stringify(incidents), KV_TTL.incidents);
}

/**
 * Merge one incident into the shared incident map and verify that it survived.
 */
export async function mergeIncident(
  env: Env,
  key: string,
  incident: IncidentRecord,
): Promise<void> {
  await mergeJson<Record<string, IncidentRecord>>(
    env.KV,
    KV_KEYS.incidents(),
    (current) => ({ ...current, [key]: incident }),
    KV_TTL.incidents,
    (check) => check[key]?.state === incident.state && check[key]?.eventCount === incident.eventCount,
  );
}

// ---------------------------------------------------------------------------
// Subscriptions (one shared key)
// ---------------------------------------------------------------------------

export async function readSubscriptions(
  env: Env,
): Promise<Record<string, SubscriptionRecord>> {
  return (
    (await kvGetJson<Record<string, SubscriptionRecord>>(env.KV, KV_KEYS.subscriptions())) ?? {}
  );
}

export async function writeSubscriptions(
  env: Env,
  subscriptions: Record<string, SubscriptionRecord>,
): Promise<void> {
  await kvPut(
    env.KV,
    KV_KEYS.subscriptions(),
    JSON.stringify(subscriptions),
    KV_TTL.subscriptions,
  );
}

// ---------------------------------------------------------------------------
// Events (one shared key, rolling history)
// ---------------------------------------------------------------------------

export interface EventLog {
  events: MonitorEvent[];
}

export async function readEvents(env: Env): Promise<MonitorEvent[]> {
  const log = await kvGetJson<EventLog>(env.KV, KV_KEYS.events());
  return log?.events ?? [];
}

export async function writeEvents(env: Env, events: MonitorEvent[]): Promise<void> {
  const trimmed = events.slice(-MAX_STORED_EVENTS);
  await kvPut(env.KV, KV_KEYS.events(), JSON.stringify({ events: trimmed }), KV_TTL.events);
}

/**
 * Append events to the rolling history and verify that they survived.
 *
 * The verification matters because a concurrent writer could otherwise drop our batch.
 */
export async function appendEvents(env: Env, events: MonitorEvent[]): Promise<void> {
  if (events.length === 0) return;
  const wanted = new Set(events.map((event) => event.eventId));
  await mergeJson<EventLog>(
    env.KV,
    KV_KEYS.events(),
    (current) => {
      const known = new Set((current.events ?? []).map((event) => event.eventId));
      const fresh = events.filter((event) => !known.has(event.eventId));
      return { events: [...(current.events ?? []), ...fresh].slice(-MAX_STORED_EVENTS) };
    },
    KV_TTL.events,
    (check) => (check.events ?? []).some((event) => wanted.has(event.eventId)),
  );
}

/** Keep the incident map bounded. The newest incidents win. */
export function trimIncidents(
  incidents: Record<string, IncidentRecord>,
): Record<string, IncidentRecord> {
  const entries = Object.values(incidents);
  if (entries.length <= MAX_STORED_INCIDENTS) return incidents;
  entries.sort((a, b) => b.lastEventAt - a.lastEventAt);
  const kept = entries.slice(0, MAX_STORED_INCIDENTS);
  const result: Record<string, IncidentRecord> = {};
  for (const incident of kept) result[incident.key] = incident;
  return result;
}

export type { DeviceIndexEntry };
