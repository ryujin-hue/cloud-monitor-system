import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  HEARTBEAT_BUCKET_MS,
  HEARTBEAT_STALE_BUCKETS,
  KV_KEYS,
  KV_NAMESPACE_PREFIX,
  KV_TTL,
  heartbeatBucket,
  incidentKey,
} from "../src/kvKeys";

/**
 * The KV key design is the backbone of the write budget, so it is unit tested.
 */
describe("KV key builder", () => {
  it("puts every key under the same namespace prefix", () => {
    const keys = [
      KV_KEYS.devices(),
      KV_KEYS.heartbeats(),
      KV_KEYS.incidents(),
      KV_KEYS.subscriptions(),
      KV_KEYS.events(),
    ];
    for (const key of keys) {
      assert.ok(key.startsWith(`${KV_NAMESPACE_PREFIX}:`), `${key} must be namespaced`);
    }
    assert.equal(new Set(keys).size, keys.length, "every key must be distinct");
  });

  it("uses exactly five keys, so the write budget is easy to reason about", () => {
    const keys = new Set([
      KV_KEYS.devices(),
      KV_KEYS.heartbeats(),
      KV_KEYS.incidents(),
      KV_KEYS.subscriptions(),
      KV_KEYS.events(),
    ]);
    assert.equal(keys.size, 5);
  });

  it("builds a stable incident key", () => {
    assert.equal(incidentKey("dev-1", "com.example", "E-101"), "dev-1|com.example|E-101");
    assert.equal(incidentKey("dev-1", null, "E-HB-01"), "dev-1|-|E-HB-01");
    assert.equal(incidentKey("dev-1", undefined, "E-HB-01"), "dev-1|-|E-HB-01");
    assert.equal(
      incidentKey("dev-1", "com.example", "E-101"),
      incidentKey("dev-1", "com.example", "E-101"),
    );
  });

  it("keeps different packages and codes in different incidents", () => {
    assert.notEqual(incidentKey("d", "a", "E-101"), incidentKey("d", "b", "E-101"));
    assert.notEqual(incidentKey("d", "a", "E-101"), incidentKey("d", "a", "E-204"));
    assert.notEqual(incidentKey("d1", "a", "E-101"), incidentKey("d2", "a", "E-101"));
  });

  it("buckets timestamps deterministically", () => {
    // A bucket aligned base timestamp.
    const base = Math.floor(1_700_000_000_000 / HEARTBEAT_BUCKET_MS) * HEARTBEAT_BUCKET_MS;
    const bucket = heartbeatBucket(base);
    assert.equal(heartbeatBucket(base + 1000), bucket);
    assert.equal(heartbeatBucket(base + HEARTBEAT_BUCKET_MS - 1), bucket);
    assert.equal(heartbeatBucket(base + HEARTBEAT_BUCKET_MS), bucket + 1);
  });

  it("keeps the heartbeat write budget inside the free plan", () => {
    // One write per bucket, no matter how many devices are monitored.
    const writesPerDay = (24 * 60 * 60 * 1000) / HEARTBEAT_BUCKET_MS;
    assert.equal(writesPerDay, 288);
    assert.ok(writesPerDay < 1000, "the KV free plan allows 1000 writes per day");
  });

  it("detects an offline device after three buckets", () => {
    assert.equal(HEARTBEAT_STALE_BUCKETS, 3);
    assert.equal(HEARTBEAT_BUCKET_MS * HEARTBEAT_STALE_BUCKETS, 15 * 60 * 1000);
  });

  it("keeps every key alive long enough to be useful", () => {
    assert.ok(KV_TTL.heartbeats >= HEARTBEAT_BUCKET_MS * HEARTBEAT_STALE_BUCKETS * 4);
    assert.ok(KV_TTL.events >= 24 * 60 * 60, "event history must survive at least a day");
  });
});
