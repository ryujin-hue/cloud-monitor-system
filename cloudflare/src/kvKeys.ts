/**
 * KV key design.
 *
 * The whole state lives in ONE namespace.
 *
 * WHY EVERYTHING DEVICE RELATED IS STORED PER DEVICE
 * --------------------------------------------------
 * Workers KV is eventually consistent: a read may return a value that is up to ~60 s old.
 * A shared key that is written with a read-modify-write therefore loses records whenever
 * two writers overlap: the second writer read a stale copy and wrote it back, deleting the
 * first writer's entry.
 *
 * This is not theoretical. During development the event endpoint overwrote the device
 * registration that the heartbeat endpoint had just written (the pairing code vanished),
 * and five cloud phones that heartbeated at the same instant reduced to two in the
 * device index.
 *
 * The fix:
 *   * every device has its own key, so two devices can never overwrite each other,
 *   * the device index is written by exactly ONE writer (the cron Worker), so it cannot
 *     race with anything,
 *   * the remaining shared keys (incidents, subscriptions, events) use a merge with
 *     verification.
 *
 * | key                                  | content                                    | written when                     |
 * |--------------------------------------|--------------------------------------------|----------------------------------|
 * | `cloud-monitor:device:<deviceId>`    | one device, including its pairing code    | metadata change / code rotation   |
 * | `cloud-monitor:hb:<deviceId>`        | liveness of one device (bucketed)         | at most once per heartbeat bucket |
 * | `cloud-monitor:deviceindex`          | `{ [deviceId]: { label, updatedAt } }`    | by the cron Worker only           |
 * | `cloud-monitor:incidents`            | incident state + push bookkeeping         | incident transition               |
 * | `cloud-monitor:subscriptions`        | alert devices, FCM tokens and their subs  | register / token rotation         |
 * | `cloud-monitor:events`               | rolling event history                      | event batch                       |
 */

export const KV_NAMESPACE_PREFIX = "cloud-monitor";

export const KV_KEYS = {
  /** One device: `{ [deviceId]: DeviceRecord }` — see the module comment. */
  device: (deviceId: string): string => `${KV_NAMESPACE_PREFIX}:device:${deviceId}`,

  /** Liveness of ONE device: `HeartbeatRecord`. */
  heartbeat: (deviceId: string): string => `${KV_NAMESPACE_PREFIX}:hb:${deviceId}`,

  /** Index of the known devices: `{ [deviceId]: { label, updatedAt } }`. */
  deviceIndex: (): string => `${KV_NAMESPACE_PREFIX}:deviceindex`,

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
  device: 365 * 24 * 60 * 60,
  heartbeat: 365 * 24 * 60 * 60,
  deviceIndex: 365 * 24 * 60 * 60,
  incidents: 365 * 24 * 60 * 60,
  subscriptions: 365 * 24 * 60 * 60,
  events: 30 * 24 * 60 * 60,
} as const;

/**
 * The base heartbeat persistence bucket.
 *
 * A heartbeat is written to KV at most once per bucket. The bucket is stored in the
 * record, so a fleet that grows simply switches to a longer bucket (see
 * `effectiveBucketMs`) instead of exhausting the write budget.
 *
 * The price of bucketing is liveness granularity: the backend learns that a device was
 * alive inside the current bucket, not within the last few seconds. The `E-HB-01`
 * detection window is therefore `bucketMs * HEARTBEAT_STALE_BUCKETS`.
 */
export const HEARTBEAT_BUCKET_MS = 10 * 60 * 1000;

/** How many buckets may pass before a device is considered offline. */
export const HEARTBEAT_STALE_BUCKETS = 2;

/**
 * How many devices one "budget step" may cost.
 *
 * With one bucket write per device per bucket, the daily write cost of the heartbeat is
 * `deviceCount * 1440 / bucketMinutes`. To stay inside the 1,000 writes/day of the free
 * plan the bucket grows with the fleet: every `HEARTBEAT_DEVICES_PER_BUCKET` devices add
 * one bucket step.
 */
export const HEARTBEAT_DEVICES_PER_BUCKET = 4;

/** Upper bound of the adaptive bucket: one hour. */
export const MAX_HEARTBEAT_BUCKET_MS = 60 * 60 * 1000;

/**
 * The bucket length that the current fleet size allows.
 *
 * One cloud phone  -> 10 minutes -> 144 writes/day
 * Four cloud phones -> 10 minutes -> 576 writes/day
 * Eight            -> 20 minutes -> 576 writes/day
 * Sixteen          -> 40 minutes -> 576 writes/day
 *
 * The detection window (`E-HB-01`) grows with it: 20 minutes for a small fleet, 80 minutes
 * for a large one.
 */
export function effectiveBucketMs(deviceCount: number): number {
  const steps = Math.max(1, Math.ceil(Math.max(deviceCount, 1) / HEARTBEAT_DEVICES_PER_BUCKET));
  const bucket = HEARTBEAT_BUCKET_MS * steps;
  return Math.min(bucket, MAX_HEARTBEAT_BUCKET_MS);
}

/** Maximum number of events kept in the rolling history. */
export const MAX_STORED_EVENTS = 1000;

/** Maximum number of incidents kept in the incident map. */
export const MAX_STORED_INCIDENTS = 500;

/** Stable identity of an incident. Mirrors `IncidentStateMachine.incidentKey` in Kotlin. */
export function incidentKey(deviceId: string, packageName: string | null | undefined, code: string): string {
  return `${deviceId}|${packageName ?? "-"}|${code}`;
}

/**
 * The bucket a timestamp falls into.
 *
 * The bucket length is explicit because it adapts to the fleet size.
 */
export function heartbeatBucket(timestamp: number, bucketMs = HEARTBEAT_BUCKET_MS): number {
  return Math.floor(timestamp / bucketMs);
}
