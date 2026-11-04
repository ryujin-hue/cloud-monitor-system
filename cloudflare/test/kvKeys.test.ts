import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  HEARTBEAT_BUCKET_MS,
  HEARTBEAT_STALE_BUCKETS,
  KV_KEYS,
  KV_NAMESPACE_PREFIX,
  KV_TTL,
  effectiveBucketMs,
  heartbeatBucket,
  incidentKey,
} from "../src/kvKeys";

/**
 * The KV key design is the backbone of the write budget, so it is unit tested.
 */
describe("KV key builder", () => {
  it("puts every key under the same namespace prefix", () => {
    const keys = [
      KV_KEYS.device("dev-1"),
      KV_KEYS.deviceIndex(),
      KV_KEYS.heartbeat("dev-1"),
      KV_KEYS.incidents(),
      KV_KEYS.subscriptions(),
      KV_KEYS.events(),
    ];
    for (const key of keys) {
      assert.ok(key.startsWith(`${KV_NAMESPACE_PREFIX}:`), `${key} must be namespaced`);
    }
  });

  it("stores every device under its OWN key", () => {
    // A shared device key would lose records: KV is eventually consistent, so two
    // devices writing at the same time would overwrite each other.
    assert.notEqual(KV_KEYS.device("dev-1"), KV_KEYS.device("dev-2"));
    assert.ok(KV_KEYS.device("dev-1").endsWith(":device:dev-1"));
    assert.notEqual(KV_KEYS.device("dev-1"), KV_KEYS.deviceIndex());
  });

  it("uses a bounded number of shared keys", () => {
    const shared = new Set([
      KV_KEYS.deviceIndex(),
      KV_KEYS.incidents(),
      KV_KEYS.subscriptions(),
      KV_KEYS.events(),
    ]);
    assert.equal(shared.size, 4);
  });

  it("stores the liveness of every device in its OWN key", () => {
    // A shared liveness key would lose records: KV is eventually consistent, so two
    // cloud phones that heartbeat at the same instant would overwrite each other.
    assert.notEqual(KV_KEYS.heartbeat("dev-1"), KV_KEYS.heartbeat("dev-2"));
    assert.ok(KV_KEYS.heartbeat("dev-1").endsWith(":hb:dev-1"));
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
    // One write per device per bucket.
    const singleDevice = (24 * 60 * 60 * 1000) / HEARTBEAT_BUCKET_MS;
    assert.equal(singleDevice, 144);
    assert.ok(singleDevice < 1000, "the KV free plan allows 1000 writes per day");
  });

  it("grows the bucket with the fleet so the budget always fits", () => {
    // Every four devices add one bucket step, so the total heartbeat cost stays bounded.
    const cost = (devices: number) =>
      devices * ((24 * 60 * 60 * 1000) / effectiveBucketMs(devices));
    assert.ok(cost(1) <= 200);
    assert.ok(cost(4) <= 600);
    assert.ok(cost(8) <= 600);
    assert.ok(cost(16) <= 600);
    // The bucket is capped at one hour, so the fleet is limited by
    // 1000 writes/day / 24 writes/day/device = ~41 cloud phones on the free plan.
    assert.ok(cost(40) <= 1000, "the free plan must still fit 40 cloud phones");
    assert.ok(cost(41) <= 1000);
  });

  it("caps the bucket at one hour", () => {
    assert.equal(effectiveBucketMs(1000), 60 * 60 * 1000);
    assert.equal(effectiveBucketMs(4), 10 * 60 * 1000);
    assert.equal(effectiveBucketMs(5), 20 * 60 * 1000);
  });

  it("detects an offline device after two buckets", () => {
    assert.equal(HEARTBEAT_STALE_BUCKETS, 2);
    assert.equal(HEARTBEAT_BUCKET_MS * HEARTBEAT_STALE_BUCKETS, 20 * 60 * 1000);
  });

  it("keeps every key alive long enough to be useful", () => {
    assert.ok(KV_TTL.heartbeat >= HEARTBEAT_BUCKET_MS * HEARTBEAT_STALE_BUCKETS * 4);
    assert.ok(KV_TTL.device >= 24 * 60 * 60, "a device must not be forgotten after a day");
    assert.ok(KV_TTL.events >= 24 * 60 * 60, "event history must survive at least a day");
  });

  it("gives every device key its own TTL budget", () => {
    // The device record holds the pairing code, which is rotated regularly.
    assert.ok(KV_TTL.device >= KV_TTL.deviceIndex);
  });
});
