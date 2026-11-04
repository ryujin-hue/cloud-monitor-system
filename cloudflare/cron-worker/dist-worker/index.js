var __defProp = Object.defineProperty;
var __name = (target, value) => __defProp(target, "name", { value, configurable: true });

// ../src/kvKeys.ts
var KV_NAMESPACE_PREFIX = "cloud-monitor";
var KV_KEYS = {
  /** Device registry: `{ [deviceId]: DeviceRecord }`. */
  devices: /* @__PURE__ */ __name(() => `${KV_NAMESPACE_PREFIX}:devices`, "devices"),
  /** Liveness: `{ [deviceId]: HeartbeatRecord }`. */
  heartbeats: /* @__PURE__ */ __name(() => `${KV_NAMESPACE_PREFIX}:heartbeats`, "heartbeats"),
  /** Incidents: `{ [incidentKey]: IncidentRecord }`. */
  incidents: /* @__PURE__ */ __name(() => `${KV_NAMESPACE_PREFIX}:incidents`, "incidents"),
  /** Alert subscriptions: `{ [alertDeviceId]: SubscriptionRecord }`. */
  subscriptions: /* @__PURE__ */ __name(() => `${KV_NAMESPACE_PREFIX}:subscriptions`, "subscriptions"),
  /** Rolling event history: `{ events: MonitorEvent[] }`. */
  events: /* @__PURE__ */ __name(() => `${KV_NAMESPACE_PREFIX}:events`, "events")
};
var KV_TTL = {
  devices: 365 * 24 * 60 * 60,
  heartbeats: 365 * 24 * 60 * 60,
  incidents: 365 * 24 * 60 * 60,
  subscriptions: 365 * 24 * 60 * 60,
  events: 30 * 24 * 60 * 60
};
var HEARTBEAT_BUCKET_MS = 5 * 60 * 1e3;
function incidentKey(deviceId, packageName, code) {
  return `${deviceId}|${packageName ?? "-"}|${code}`;
}
__name(incidentKey, "incidentKey");

// ../src/incidents.ts
function reduceIncident(previous, event) {
  const key = incidentKey(event.deviceId, event.packageName, event.code);
  if (event.type === "HEARTBEAT") {
    return { incident: previous, transition: "unchanged", shouldPush: false };
  }
  if (event.type === "DOWN") {
    if (previous === null || previous.state === "CLOSED") {
      const incident2 = {
        key,
        deviceId: event.deviceId,
        packageName: event.packageName ?? null,
        code: event.code,
        state: "OPEN",
        openedAt: event.occurredAt,
        lastEventAt: event.occurredAt,
        closedAt: null,
        eventCount: 1,
        confidence: event.confidence,
        detection: event.detection
      };
      return { incident: incident2, transition: "opened", shouldPush: true };
    }
    const incident = {
      ...previous,
      lastEventAt: Math.max(previous.lastEventAt, event.occurredAt),
      eventCount: previous.eventCount + 1,
      confidence: event.confidence,
      detection: event.detection
    };
    return { incident, transition: "duplicate_down", shouldPush: false };
  }
  if (previous !== null && previous.state === "OPEN") {
    const incident = {
      ...previous,
      state: "CLOSED",
      lastEventAt: Math.max(previous.lastEventAt, event.occurredAt),
      closedAt: event.occurredAt,
      eventCount: previous.eventCount + 1
    };
    return { incident, transition: "recovered", shouldPush: true };
  }
  return { incident: previous, transition: "unchanged", shouldPush: false };
}
__name(reduceIncident, "reduceIncident");

// ../src/state.ts
function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
__name(sleep, "sleep");
function isRateLimited(error) {
  const status = error?.status;
  if (status === 429) return true;
  return /429|rate limit|too many/i.test(String(error));
}
__name(isRateLimited, "isRateLimited");
async function kvPut(kv, key, value, expirationTtl) {
  let lastError;
  for (let attempt = 0; attempt < 4; attempt++) {
    try {
      await kv.put(key, value, expirationTtl ? { expirationTtl } : void 0);
      return;
    } catch (error) {
      lastError = error;
      if (!isRateLimited(error)) throw error;
      await sleep(1100 + attempt * 250);
    }
  }
  throw new Error(`KV write failed for ${key}: ${String(lastError)}`);
}
__name(kvPut, "kvPut");
async function kvGetJson(kv, key) {
  const raw = await kv.get(key, "json");
  return raw ?? null;
}
__name(kvGetJson, "kvGetJson");
async function readIncidents(env) {
  return await kvGetJson(env.KV, KV_KEYS.incidents()) ?? {};
}
__name(readIncidents, "readIncidents");
async function writeIncidents(env, incidents) {
  await kvPut(env.KV, KV_KEYS.incidents(), JSON.stringify(incidents), KV_TTL.incidents);
}
__name(writeIncidents, "writeIncidents");
async function readSubscriptions(env) {
  return await kvGetJson(env.KV, KV_KEYS.subscriptions()) ?? {};
}
__name(readSubscriptions, "readSubscriptions");
async function writeSubscriptions(env, subscriptions) {
  await kvPut(
    env.KV,
    KV_KEYS.subscriptions(),
    JSON.stringify(subscriptions),
    KV_TTL.subscriptions
  );
}
__name(writeSubscriptions, "writeSubscriptions");
async function readEvents(env) {
  const log = await kvGetJson(env.KV, KV_KEYS.events());
  return log?.events ?? [];
}
__name(readEvents, "readEvents");

// ../src/fcm.ts
var TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
var SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
function base64UrlEncode(input) {
  let bytes;
  if (typeof input === "string") {
    bytes = new TextEncoder().encode(input);
  } else if (input instanceof Uint8Array) {
    bytes = input;
  } else {
    bytes = new Uint8Array(input);
  }
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
__name(base64UrlEncode, "base64UrlEncode");
function pemToDer(pem) {
  const body = pem.replace(/-----BEGIN PRIVATE KEY-----/g, "").replace(/-----END PRIVATE KEY-----/g, "").replace(/-----BEGIN RSA PRIVATE KEY-----/g, "").replace(/-----END RSA PRIVATE KEY-----/g, "").replace(/\s+/g, "");
  const binary = atob(body);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}
__name(pemToDer, "pemToDer");
async function signJwt(sa) {
  const now = Math.floor(Date.now() / 1e3);
  const header = { alg: "RS256", typ: "JWT" };
  const claims = {
    iss: sa.client_email,
    scope: SCOPE,
    aud: TOKEN_ENDPOINT,
    iat: now,
    exp: now + 3600
  };
  const signingInput = `${base64UrlEncode(JSON.stringify(header))}.${base64UrlEncode(
    JSON.stringify(claims)
  )}`;
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToDer(sa.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(signingInput)
  );
  return `${signingInput}.${base64UrlEncode(new Uint8Array(signature))}`;
}
__name(signJwt, "signJwt");
var cachedToken = null;
function parseServiceAccount(raw) {
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    if (!parsed.project_id || !parsed.client_email || !parsed.private_key) return null;
    return parsed;
  } catch {
    return null;
  }
}
__name(parseServiceAccount, "parseServiceAccount");
async function getAccessToken(sa) {
  if (cachedToken && cachedToken.expiresAt > Date.now() + 6e4) {
    return cachedToken.token;
  }
  const assertion = await signJwt(sa);
  const response = await fetch(TOKEN_ENDPOINT, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion
    })
  });
  if (!response.ok) {
    throw new Error(`token endpoint returned ${response.status}: ${await response.text()}`);
  }
  const data = await response.json();
  cachedToken = {
    token: data.access_token,
    expiresAt: Date.now() + (data.expires_in ?? 3600) * 1e3
  };
  return data.access_token;
}
__name(getAccessToken, "getAccessToken");
function isPushConfigured(env) {
  if ((env.PUSH_ENABLED ?? "true").toLowerCase() === "false") return false;
  return parseServiceAccount(env.FCM_SERVICE_ACCOUNT_JSON) !== null;
}
__name(isPushConfigured, "isPushConfigured");
async function sendDataMessage(env, token, data) {
  const sa = parseServiceAccount(env.FCM_SERVICE_ACCOUNT_JSON);
  if (!sa) {
    return { ok: false, unregistered: false, error: "FCM is not configured" };
  }
  try {
    const accessToken = await getAccessToken(sa);
    const response = await fetch(
      `https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${accessToken}`,
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          message: {
            token,
            // High priority + data only: the app wakes up and renders the notification.
            data,
            android: {
              priority: "high",
              // Do not let the message be dropped when the device is in Doze.
              ttl: "0s"
            }
          }
        })
      }
    );
    if (response.ok) return { ok: true, unregistered: false };
    const body = await response.text();
    const unregistered = body.includes("UNREGISTERED") || body.includes("NOT_FOUND") || response.status === 400 && body.includes("INVALID_ARGUMENT");
    return {
      ok: false,
      unregistered,
      error: `FCM returned ${response.status}: ${body.slice(0, 300)}`
    };
  } catch (error) {
    return { ok: false, unregistered: false, error: String(error) };
  }
}
__name(sendDataMessage, "sendDataMessage");

// ../src/types.ts
var ERROR_CODES = {
  E_101: "E-101",
  E_102: "E-102",
  E_204: "E-204",
  E_SHARE_01: "E-SHARE-01",
  E_SHARE_02: "E-SHARE-02",
  E_HB_01: "E-HB-01",
  E_NET_01: "E-NET-01"
};
var ALL_ERROR_CODES = Object.values(ERROR_CODES);
var ERROR_CODE_TITLE = {
  "E-101": "App process died",
  "E-102": "App terminated by the system",
  "E-204": "App not responding (ANR)",
  "E-SHARE-01": "Discord screen share stopped",
  "E-SHARE-02": "Discord died during screen share",
  "E-HB-01": "Cloud phone stopped reporting",
  "E-NET-01": "Network unavailable"
};
var ERROR_CODE_SEVERITY = {
  "E-101": "WARNING",
  "E-102": "WARNING",
  "E-204": "CRITICAL",
  "E-SHARE-01": "CRITICAL",
  "E-SHARE-02": "CRITICAL",
  "E-HB-01": "CRITICAL",
  "E-NET-01": "INFO"
};
function isErrorCode(value) {
  return ALL_ERROR_CODES.includes(value);
}
__name(isErrorCode, "isErrorCode");
function errorTitle(code) {
  return isErrorCode(code) ? ERROR_CODE_TITLE[code] : code;
}
__name(errorTitle, "errorTitle");
function errorSeverity(code) {
  return isErrorCode(code) ? ERROR_CODE_SEVERITY[code] : "INFO";
}
__name(errorSeverity, "errorSeverity");

// ../src/push.ts
var PUSH_COOLDOWN_MS = 6e4;
function describeEvent(event, deviceLabel) {
  const title = errorTitle(event.code);
  const target = event.packageName ? ` (${event.packageName})` : "";
  switch (event.type) {
    case "DOWN":
      return {
        title,
        message: `${deviceLabel || event.deviceId}: ${title}${target}`
      };
    case "RECOVERED":
      return {
        title: `${title} \u2014 recovered`,
        message: `${deviceLabel || event.deviceId}: ${title}${target} is back`
      };
    default:
      return {
        title,
        message: `${deviceLabel || event.deviceId}: ${title}${target}`
      };
  }
}
__name(describeEvent, "describeEvent");
async function pushEvent(env, event, incident, deviceLabel) {
  if (!isPushConfigured(env)) {
    return { attempted: 0, sent: 0, removedTokens: 0, skipped: true, reason: "push disabled" };
  }
  const now = Date.now();
  if (incident) {
    if (incident.lastPushEventId === event.eventId) {
      return { attempted: 0, sent: 0, removedTokens: 0, skipped: true, reason: "duplicate" };
    }
    if (incident.lastPushAt !== void 0 && now - incident.lastPushAt < PUSH_COOLDOWN_MS) {
      return { attempted: 0, sent: 0, removedTokens: 0, skipped: true, reason: "cooldown" };
    }
  }
  const subscriptions = await readSubscriptions(env);
  const targets = Object.values(subscriptions).filter(
    (subscription) => subscription.monitorDeviceIds.includes(event.deviceId)
  );
  let attempted = 0;
  let sent = 0;
  let removedTokens = 0;
  for (const subscription of targets) {
    for (const token of subscription.fcmTokens) {
      attempted += 1;
      const { title, message } = describeEvent(event, deviceLabel);
      const result = await sendDataMessage(env, token, {
        type: event.type,
        deviceId: event.deviceId,
        deviceLabel,
        code: event.code,
        packageName: event.packageName ?? "",
        eventId: event.eventId,
        occurredAt: String(event.occurredAt),
        receivedAt: String(event.receivedAt ?? now),
        title,
        message,
        severity: errorSeverity(event.code)
      });
      if (result.ok) {
        sent += 1;
      } else if (result.unregistered) {
        subscription.fcmTokens = subscription.fcmTokens.filter((entry) => entry !== token);
        removedTokens += 1;
      }
    }
  }
  if (removedTokens > 0) {
    await writeSubscriptions(env, subscriptions);
  }
  return { attempted, sent, removedTokens, skipped: false };
}
__name(pushEvent, "pushEvent");

// src/index.ts
var index_default = {
  async scheduled(_event, env, _ctx) {
    await scanForMissingHeartbeats(env);
  }
};
var HEARTBEAT_STALE_BUCKETS = 3;
async function scanForMissingHeartbeats(env) {
  const now = Date.now();
  const devices = await kvGetJson(env.KV, KV_KEYS.devices()) ?? {};
  const heartbeats = await kvGetJson(env.KV, KV_KEYS.heartbeats()) ?? {};
  const incidents = await readIncidents(env);
  const deviceIds = Object.keys(devices);
  let reported = 0;
  for (const deviceId of deviceIds) {
    const device = devices[deviceId];
    const heartbeat = heartbeats[deviceId];
    if (!heartbeat) continue;
    const age = now - heartbeat.lastSeenAt;
    if (age <= HEARTBEAT_BUCKET_MS * HEARTBEAT_STALE_BUCKETS) continue;
    const key = incidentKey(deviceId, null, ERROR_CODES.E_HB_01);
    const existing = incidents[key];
    if (existing && existing.state === "OPEN") {
      continue;
    }
    const event = {
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
        bucketMs: String(HEARTBEAT_BUCKET_MS)
      }
    };
    const result = reduceIncident(existing ?? null, event);
    const incident = result.incident;
    incidents[key] = incident;
    const events = await readEvents(env);
    await kvPut(
      env.KV,
      KV_KEYS.events(),
      JSON.stringify({ events: [...events, event].slice(-1e3) }),
      KV_TTL.events
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
__name(scanForMissingHeartbeats, "scanForMissingHeartbeats");
async function pruneUnregisteredTokens(env) {
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
__name(pruneUnregisteredTokens, "pruneUnregisteredTokens");
export {
  HEARTBEAT_STALE_BUCKETS,
  index_default as default,
  pruneUnregisteredTokens,
  scanForMissingHeartbeats
};
//# sourceMappingURL=index.js.map
