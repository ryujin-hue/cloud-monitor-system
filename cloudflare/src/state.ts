import { KV_KEYS, KV_TTL, MAX_STORED_EVENTS, MAX_STORED_INCIDENTS } from "./kvKeys";
import type {
  DeviceRecord,
  Env,
  HeartbeatRecord,
  IncidentRecord,
  MonitorEvent,
  SubscriptionRecord,
} from "./types";

/**
 * Thin, typed wrappers around the Workers KV binding.
 *
 * Two things happen here that matter a lot in production:
 *
 *  1. **Write retries.** KV allows at most one write per second to the same key and
 *     answers with HTTP 429 when that limit is hit. Two cloud phones reporting at the same
 *     moment would otherwise lose an event, so every write retries with a small backoff.
 *
 *  2. **Bounded growth.** The incident and event maps are trimmed so that the values stay
 *     far below the 25 MB KV value limit.
 */

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function isRateLimited(error: unknown): boolean {
  const status = (error as { status?: number } | null)?.status;
  if (status === 429) return true;
  return /429|rate limit|too many/i.test(String(error));
}

export async function kvPut(
  kv: KVNamespace,
  key: string,
  value: string,
  expirationTtl?: number,
): Promise<void> {
  let lastError: unknown;
  for (let attempt = 0; attempt < 4; attempt++) {
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

export async function readDevices(env: Env): Promise<Record<string, DeviceRecord>> {
  return (await kvGetJson<Record<string, DeviceRecord>>(env.KV, KV_KEYS.devices())) ?? {};
}

export async function writeDevices(
  env: Env,
  devices: Record<string, DeviceRecord>,
): Promise<void> {
  await kvPut(env.KV, KV_KEYS.devices(), JSON.stringify(devices), KV_TTL.devices);
}

export async function readHeartbeats(env: Env): Promise<Record<string, HeartbeatRecord>> {
  return (await kvGetJson<Record<string, HeartbeatRecord>>(env.KV, KV_KEYS.heartbeats())) ?? {};
}

export async function writeHeartbeats(
  env: Env,
  heartbeats: Record<string, HeartbeatRecord>,
): Promise<void> {
  await kvPut(env.KV, KV_KEYS.heartbeats(), JSON.stringify(heartbeats), KV_TTL.heartbeats);
}

export async function readIncidents(env: Env): Promise<Record<string, IncidentRecord>> {
  return (await kvGetJson<Record<string, IncidentRecord>>(env.KV, KV_KEYS.incidents())) ?? {};
}

export async function writeIncidents(
  env: Env,
  incidents: Record<string, IncidentRecord>,
): Promise<void> {
  await kvPut(env.KV, KV_KEYS.incidents(), JSON.stringify(incidents), KV_TTL.incidents);
}

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
