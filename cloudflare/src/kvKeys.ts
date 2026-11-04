/**
 * KV key design.
 *
 * The whole state lives in FIVE keys of ONE namespace. Keeping it small matters because the
 * Workers KV free plan allows only 1,000 writes per day per namespace, and because KV
 * allows at most ONE write per second to the same key.
 *
 * | key                            | content                                        | written when                       |
 * |--------------------------------|------------------------------------------------|------------------------------------|
 * | `cloud-monitor:devices`        | device registry                                | registration / metadata change      |
 * | `cloud-monitor:heartbeats`     | liveness per device (bucketed)                 | at most once per heartbeat bucket   |
 * | `cloud-monitor:incidents`      | incident state + push bookkeeping              | incident transition                 |
 * | `cloud-monitor:subscriptions`  | alert devices, their FCM tokens and their subs| register / token rotation            |
 * | `cloud-monitor:events`         | rolling event history                          | event batch                         |
 */

export const KV_NAMESPACE_PREFIX = "cloud-monitor";

export const KV_KEYS = {
  /** Device registry: `{ [deviceId]: DeviceRecord }`. */
  devices: (): string => `${KV_NAMESPACE_PREFIX}:devices`,
  /** Liveness: `{ [deviceId]: HeartbeatRecord }`. */
  heartbeats: (): string => `${KV_NAMESPACE_PREFIX}:heartbeats`,
  /** Incidents: `{ [incidentKey]: IncidentRecord }`. */
  incidents: (): string => `${KV_NAMESPACE_PREFIX}:incidents`,
  /** Alert subscriptions: `{ [alertDeviceId]: SubscriptionRecord }`. */
  subscriptions: (): string => `${KV_NAMESPACE_PREFIX}:subscriptions`,
  /** Rolling event history: `{ events: MonitorEvent[] }`. */
  events: (): string => `${KV_NAMESPACE_PREFIX}:events`,
} as const;

/**
 * Time-to-live of every key.
 *
 * The heartbeat key must outlive the "device is offline" threshold, otherwise a device
 * would be forgotten instead of being reported as `E-HB-01`.
 */
export const KV_TTL = {
  devices: 365 * 24 * 60 * 60,
  heartbeats: 365 * 24 * 60 * 60,
  incidents: 365 * 24 * 60 * 60,
  subscriptions: 365 * 24 * 60 * 60,
  events: 30 * 24 * 60 * 60,
} as const;

/**
 * The heartbeat persistence bucket.
 *
 * A heartbeat is written to KV at most once per bucket. With a 5 minute bucket a single
 * device costs 288 writes per day, and because ALL devices share ONE key the total stays
 * at 288 writes per day no matter how many cloud phones are monitored.
 *
 * The price of this is liveness granularity: the backend learns that a device was alive
 * within the current bucket, not within the last few seconds. The `E-HB-01` detection
 * window is therefore `HEARTBEAT_BUCKET_MS * HEARTBEAT_STALE_BUCKETS`.
 */
export const HEARTBEAT_BUCKET_MS = 5 * 60 * 1000;

/** How many buckets may pass before a device is considered offline. */
export const HEARTBEAT_STALE_BUCKETS = 3;

/** Maximum number of events kept in the rolling history. */
export const MAX_STORED_EVENTS = 1000;

/** Maximum number of incidents kept in the incident map. */
export const MAX_STORED_INCIDENTS = 500;

/** Stable identity of an incident. Mirrors `IncidentStateMachine.incidentKey` in Kotlin. */
export function incidentKey(deviceId: string, packageName: string | null | undefined, code: string): string {
  return `${deviceId}|${packageName ?? "-"}|${code}`;
}

/** The bucket a timestamp falls into. */
export function heartbeatBucket(timestamp: number): number {
  return Math.floor(timestamp / HEARTBEAT_BUCKET_MS);
}
