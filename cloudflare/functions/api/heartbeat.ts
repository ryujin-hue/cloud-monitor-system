import { isAuthorized } from "../../src/auth";
import { errorResponse, jsonResponse, methodNotAllowed } from "../../src/cors";
import { HEARTBEAT_BUCKET_MS, heartbeatBucket } from "../../src/kvKeys";
import {
  readDevices,
  readHeartbeats,
  writeDevices,
  writeHeartbeats,
} from "../../src/state";
import type { DeviceRecord, Env, Heartbeat, HeartbeatRecord } from "../../src/types";

/**
 * `POST /api/heartbeat` — liveness signal from a Cloud Monitor.
 *
 * The heartbeat is written to KV at most once per bucket (5 minutes). This is what keeps
 * the whole system inside the Workers KV free plan:
 *
 *   * 1 write per 5 minutes = 288 writes per day, regardless of the number of cloud phones,
 *   * the price is liveness granularity: the backend knows a device was alive inside the
 *     current bucket, which is why `E-HB-01` is raised after 3 buckets (15 minutes).
 *
 * The heartbeat also doubles as the pairing channel: the 6-digit code shown on the cloud
 * phone travels with it.
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

  let heartbeat: Heartbeat;
  try {
    heartbeat = (await request.json()) as Heartbeat;
  } catch {
    return errorResponse(request, env, 400, "invalid_json", "The body is not valid JSON.");
  }

  if (!heartbeat || typeof heartbeat.deviceId !== "string" || !heartbeat.deviceId) {
    return errorResponse(request, env, 400, "invalid_heartbeat", "deviceId is required.");
  }

  const now = Date.now();
  const bucket = heartbeatBucket(now);

  const devices = await readDevices(env);
  const heartbeats = await readHeartbeats(env);

  let devicesChanged = false;
  const deviceId = heartbeat.deviceId;
  const existing: DeviceRecord | undefined = devices[deviceId];
  const device: DeviceRecord = existing ?? {
    deviceId,
    label: "",
    appVersion: heartbeat.appVersion ?? "",
    versionCode: heartbeat.versionCode ?? 0,
    watchedPackages: [],
    registeredAt: now,
    updatedAt: now,
    pairingCode: null,
    pairingExpiresAt: null,
    heartbeatIntervalMs: 60_000,
  };

  if (!existing) {
    devicesChanged = true;
  }
  if (heartbeat.label && heartbeat.label !== device.label) {
    device.label = heartbeat.label;
    devicesChanged = true;
  }
  if (heartbeat.appVersion && heartbeat.appVersion !== device.appVersion) {
    device.appVersion = heartbeat.appVersion;
    devicesChanged = true;
  }
  if ((heartbeat.versionCode ?? 0) !== device.versionCode) {
    device.versionCode = heartbeat.versionCode ?? 0;
    devicesChanged = true;
  }
  const watched = (heartbeat.watchedPackages ?? []).slice().sort();
  if (watched.join(",") !== (device.watchedPackages ?? []).slice().sort().join(",")) {
    device.watchedPackages = watched;
    devicesChanged = true;
  }
  if (heartbeat.pairingCode !== undefined && heartbeat.pairingCode !== device.pairingCode) {
    device.pairingCode = heartbeat.pairingCode ?? null;
    device.pairingExpiresAt = heartbeat.pairingExpiresAt ?? null;
    devicesChanged = true;
  }
  device.updatedAt = now;

  if (devicesChanged) {
    await writeDevices(env, devices);
  }

  // Only write the heartbeat key when the bucket changed: this is the write budget.
  const current = heartbeats[deviceId];
  let recorded = false;
  if (!current || current.bucket !== bucket) {
    const record: HeartbeatRecord = {
      deviceId,
      lastSeenAt: now,
      bucket,
      seq: heartbeat.seq ?? 0,
      appVersion: heartbeat.appVersion ?? "",
      versionCode: heartbeat.versionCode ?? 0,
      foregroundServiceActive: heartbeat.foregroundServiceActive ?? true,
      networkAvailable: heartbeat.networkAvailable ?? true,
      permissions: heartbeat.permissions,
      watchedPackages: watched,
      uptimeMillis: heartbeat.uptimeMillis ?? 0,
    };
    heartbeats[deviceId] = record;
    await writeHeartbeats(env, heartbeats);
    recorded = true;
  }

  return jsonResponse(request, env, {
    ok: true,
    recorded,
    bucket,
    bucketMs: HEARTBEAT_BUCKET_MS,
    serverTime: now,
  });
};

export const onRequestGet = async ({ request, env }: { request: Request; env: Env }) =>
  methodNotAllowed(request, env);
