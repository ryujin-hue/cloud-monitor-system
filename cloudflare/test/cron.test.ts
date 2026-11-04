import assert from "node:assert/strict";
import test from "node:test";

import { scanForMissingHeartbeats } from "../cron-worker/src/index";
import { KV_KEYS, effectiveBucketMs, incidentKey } from "../src/kvKeys";
import { writeDevice, writeHeartbeat } from "../src/state";
import { ERROR_CODES, type DeviceRecord, type HeartbeatRecord } from "../src/types";
import { FakeKv, fakeEnv } from "./fakeKv";

function deviceRecord(deviceId: string, label: string): DeviceRecord {
  return {
    deviceId,
    label,
    appVersion: "1.0.0",
    versionCode: 1,
    watchedPackages: [],
    registeredAt: Date.now(),
    updatedAt: Date.now(),
    pairingCode: null,
    pairingExpiresAt: null,
    heartbeatIntervalMs: 60_000,
  };
}

function heartbeatRecord(deviceId: string, lastSeenAt: number, bucketMs: number): HeartbeatRecord {
  return {
    deviceId,
    lastSeenAt,
    bucket: Math.floor(lastSeenAt / bucketMs),
    bucketMs,
    seq: 1,
    appVersion: "1.0.0",
    versionCode: 1,
    foregroundServiceActive: true,
    networkAvailable: true,
    permissions: undefined,
    watchedPackages: [],
    uptimeMillis: 1000,
  };
}

test("the cron worker rebuilds the device index from the device keys", async () => {
  const kv = new FakeKv();
  const env = fakeEnv(kv);

  await writeDevice(env, deviceRecord("phone-a", "Phone A"));
  await writeDevice(env, deviceRecord("phone-b", "Phone B"));

  const result = await scanForMissingHeartbeats(env);
  assert.equal(result.scanned, 2);

  const index = JSON.parse(kv.peek(KV_KEYS.deviceIndex()) ?? "{}");
  assert.deepEqual(Object.keys(index).sort(), ["phone-a", "phone-b"]);
  assert.equal(index["phone-a"].label, "Phone A");
});

test("the cron worker raises E-HB-01 for a phone that stopped reporting", async () => {
  const kv = new FakeKv();
  const env = fakeEnv(kv);
  const bucketMs = effectiveBucketMs(1);

  await writeDevice(env, deviceRecord("phone-a", "Phone A"));
  await writeHeartbeat(env, heartbeatRecord("phone-a", Date.now() - 5 * bucketMs, bucketMs));

  const result = await scanForMissingHeartbeats(env);
  assert.equal(result.reported, 1);

  const events = JSON.parse(kv.peek(KV_KEYS.events()) ?? "{}").events ?? [];
  assert.equal(events.length, 1);
  assert.equal(events[0].code, ERROR_CODES.E_HB_01);
  assert.equal(events[0].deviceId, "phone-a");
  assert.equal(events[0].type, "DOWN");

  const incidents = JSON.parse(kv.peek(KV_KEYS.incidents()) ?? "{}");
  const key = incidentKey("phone-a", null, ERROR_CODES.E_HB_01);
  assert.ok(incidents[key], "the incident must be stored");
  assert.equal(incidents[key].state, "OPEN");
  assert.equal(incidents[key].code, ERROR_CODES.E_HB_01);
});

test("the cron worker does not raise E-HB-01 for a phone that is still reporting", async () => {
  const kv = new FakeKv();
  const env = fakeEnv(kv);
  const bucketMs = effectiveBucketMs(1);

  await writeDevice(env, deviceRecord("phone-a", "Phone A"));
  await writeHeartbeat(env, heartbeatRecord("phone-a", Date.now(), bucketMs));

  const result = await scanForMissingHeartbeats(env);
  assert.equal(result.reported, 0);
  assert.equal(kv.peek(KV_KEYS.events()), undefined);
});

test("E-HB-01 is raised only once per outage", async () => {
  const kv = new FakeKv();
  const env = fakeEnv(kv);
  const bucketMs = effectiveBucketMs(1);

  await writeDevice(env, deviceRecord("phone-a", "Phone A"));
  await writeHeartbeat(env, heartbeatRecord("phone-a", Date.now() - 5 * bucketMs, bucketMs));

  const first = await scanForMissingHeartbeats(env);
  assert.equal(first.reported, 1);

  const second = await scanForMissingHeartbeats(env);
  assert.equal(second.reported, 0, "the incident is already open");

  const events = JSON.parse(kv.peek(KV_KEYS.events()) ?? "{}").events ?? [];
  assert.equal(events.length, 1, "no second push");
});

test("a phone that never sent a heartbeat is unknown, not offline", async () => {
  const kv = new FakeKv();
  const env = fakeEnv(kv);

  await writeDevice(env, deviceRecord("phone-a", "Phone A"));

  const result = await scanForMissingHeartbeats(env);
  assert.equal(result.reported, 0);
  assert.equal(kv.peek(KV_KEYS.events()), undefined);
});

test("the detection window grows with the fleet, the write budget does not", async () => {
  // One bucket write per device per bucket: the fleet may grow without breaking the
  // 1,000 writes/day limit of the free plan.
  const dailyCost = (devices: number) =>
    devices * ((24 * 60 * 60 * 1000) / effectiveBucketMs(devices));
  assert.ok(dailyCost(1) <= 150);
  assert.ok(dailyCost(4) <= 600);
  assert.ok(dailyCost(40) <= 1000);
});
