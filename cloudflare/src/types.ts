/**
 * TypeScript mirror of the DTOs and error codes defined in the Kotlin `shared/` module.
 *
 * `shared/src/main/kotlin/com/cloudmonitor/shared/ErrorCode.kt` is the single source of
 * truth. The parity test in `test/parity.test.ts` parses that file and fails if the two
 * lists ever drift apart.
 */

export const ERROR_CODES = {
  E_101: "E-101",
  E_102: "E-102",
  E_204: "E-204",
  E_SHARE_01: "E-SHARE-01",
  E_SHARE_02: "E-SHARE-02",
  E_HB_01: "E-HB-01",
  E_NET_01: "E-NET-01",
} as const;

export type ErrorCode = (typeof ERROR_CODES)[keyof typeof ERROR_CODES];

export const ALL_ERROR_CODES: ErrorCode[] = Object.values(ERROR_CODES);

/** Which component is allowed to originate a code. */
export type CodeOwner = "MONITOR" | "BACKEND";

export const ERROR_CODE_OWNER: Record<ErrorCode, CodeOwner> = {
  "E-101": "MONITOR",
  "E-102": "MONITOR",
  "E-204": "MONITOR",
  "E-SHARE-01": "MONITOR",
  "E-SHARE-02": "MONITOR",
  "E-HB-01": "BACKEND",
  "E-NET-01": "MONITOR",
};

export const ERROR_CODE_TITLE: Record<ErrorCode, string> = {
  "E-101": "App process died",
  "E-102": "App terminated by the system",
  "E-204": "App not responding (ANR)",
  "E-SHARE-01": "Discord screen share stopped",
  "E-SHARE-02": "Discord died during screen share",
  "E-HB-01": "Cloud phone stopped reporting",
  "E-NET-01": "Network unavailable",
};

export type Severity = "INFO" | "WARNING" | "CRITICAL";

export const ERROR_CODE_SEVERITY: Record<ErrorCode, Severity> = {
  "E-101": "WARNING",
  "E-102": "WARNING",
  "E-204": "CRITICAL",
  "E-SHARE-01": "CRITICAL",
  "E-SHARE-02": "CRITICAL",
  "E-HB-01": "CRITICAL",
  "E-NET-01": "INFO",
};

export function isErrorCode(value: string): value is ErrorCode {
  return (ALL_ERROR_CODES as string[]).includes(value);
}

export function errorTitle(code: string): string {
  return isErrorCode(code) ? ERROR_CODE_TITLE[code] : code;
}

export function errorSeverity(code: string): Severity {
  return isErrorCode(code) ? ERROR_CODE_SEVERITY[code] : "INFO";
}

// ---------------------------------------------------------------------------
// Events
// ---------------------------------------------------------------------------

export type EventType = "DOWN" | "RECOVERED" | "HEARTBEAT";

export interface MonitorEvent {
  /** UUID v4, generated on the device. Idempotency key. */
  eventId: string;
  deviceId: string;
  type: EventType;
  code: string;
  packageName?: string | null;
  /** `tier1`, `tier2` or `backend`. */
  detection: string;
  /** `high`, `medium` or `low`. */
  confidence: string;
  /** Device wall-clock time (epoch millis). */
  occurredAt: number;
  /** Server wall-clock time (epoch millis). Filled in by the backend. */
  receivedAt?: number | null;
  seq: number;
  appVersion: string;
  versionCode: number;
  metadata?: Record<string, string>;
}

export interface EventBatch {
  deviceId: string;
  appVersion?: string;
  versionCode?: number;
  events: MonitorEvent[];
}

// ---------------------------------------------------------------------------
// Heartbeats
// ---------------------------------------------------------------------------

export interface PermissionState {
  notificationAccess: boolean;
  usageAccess: boolean;
  batteryUnrestricted: boolean;
  notificationsEnabled: boolean;
}

export interface Heartbeat {
  deviceId: string;
  occurredAt: number;
  seq: number;
  appVersion: string;
  versionCode: number;
  label?: string;
  foregroundServiceActive?: boolean;
  uptimeMillis?: number;
  networkAvailable?: boolean;
  permissions?: PermissionState;
  watchedPackages?: string[];
  pairingCode?: string | null;
  pairingExpiresAt?: number | null;
}

// ---------------------------------------------------------------------------
// Devices, incidents and registration
// ---------------------------------------------------------------------------

export type DeviceState = "ONLINE" | "STALE" | "OFFLINE" | "UNKNOWN";

export interface DeviceRecord {
  deviceId: string;
  label: string;
  appVersion: string;
  versionCode: number;
  watchedPackages: string[];
  registeredAt: number;
  updatedAt: number;
  /** The current 6-digit pairing code and its expiry. */
  pairingCode?: string | null;
  pairingExpiresAt?: number | null;
  /** The heartbeat period the device reports. */
  heartbeatIntervalMs: number;
}

export interface HeartbeatRecord {
  deviceId: string;
  lastSeenAt: number;
  /** Bucket in which the heartbeat was recorded (see HEARTBEAT_BUCKET_MS). */
  bucket: number;
  seq: number;
  appVersion: string;
  versionCode: number;
  foregroundServiceActive: boolean;
  networkAvailable: boolean;
  permissions?: PermissionState;
  watchedPackages?: string[];
  uptimeMillis?: number;
}

export type IncidentState = "OPEN" | "CLOSED";

export interface IncidentRecord {
  key: string;
  deviceId: string;
  packageName: string | null;
  code: string;
  state: IncidentState;
  openedAt: number;
  lastEventAt: number;
  closedAt: number | null;
  eventCount: number;
  confidence: string;
  detection: string;
  /** Push de-duplication and cooldown bookkeeping. */
  lastPushAt?: number;
  lastPushEventId?: string;
}

export interface SubscriptionRecord {
  alertDeviceId: string;
  label: string;
  fcmTokens: string[];
  monitorDeviceIds: string[];
  updatedAt: number;
}

export interface RegisterRequest {
  alertDeviceId: string;
  fcmToken?: string | null;
  pairingCode?: string | null;
  label?: string;
  appVersion?: string;
  versionCode?: number;
  platform?: string;
}

export interface RegisterResponse {
  ok: boolean;
  message: string;
  subscribedDeviceIds: string[];
  alertDeviceId: string;
}

export interface DeviceStatus {
  deviceId: string;
  label: string;
  state: DeviceState;
  lastHeartbeatAt: number | null;
  lastEventAt: number | null;
  appVersion: string;
  versionCode: number;
  watchedPackages: string[];
  openIncidentCount: number;
  foregroundServiceActive: boolean;
  networkAvailable: boolean;
}

export interface StatusResponse {
  devices: DeviceStatus[];
  openIncidents: IncidentRecord[];
  recentIncidents: IncidentRecord[];
  serverTime: number;
}

export interface EventsResponse {
  events: MonitorEvent[];
  nextCursor: string | null;
}

// ---------------------------------------------------------------------------
// Environment
// ---------------------------------------------------------------------------

export interface Env {
  /** The Workers KV namespace shared by the Pages project and the cron worker. */
  KV: KVNamespace;

  /** API key for Cloud Monitor (event, heartbeat). */
  MONITOR_API_KEY: string;

  /** API key for Cloud Alert (status, events, register). */
  ALERT_API_KEY: string;

  /** Comma separated list of allowed CORS origins. Empty means "no browser origin allowed". */
  ALLOWED_ORIGINS?: string;

  /**
   * The Firebase service account JSON, used to sign the FCM HTTP v1 JWT.
   * When absent, pushes are disabled and the backend only stores data.
   */
  FCM_SERVICE_ACCOUNT_JSON?: string;

  /** Set to "false" to disable push delivery entirely. */
  PUSH_ENABLED?: string;
}
