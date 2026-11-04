import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { reduceIncident } from "../src/incidents";
import { incidentKey } from "../src/kvKeys";
import type { MonitorEvent } from "../src/types";

function event(overrides: Partial<MonitorEvent> = {}): MonitorEvent {
  return {
    eventId: "evt-1",
    deviceId: "dev-1",
    type: "DOWN",
    code: "E-101",
    packageName: "com.example.game",
    detection: "tier1",
    confidence: "low",
    occurredAt: 1000,
    receivedAt: 1000,
    seq: 1,
    appVersion: "1.0.1",
    versionCode: 1,
    metadata: {},
    ...overrides,
  };
}

/**
 * The backend is the authority for incidents, so its state machine is unit tested
 * independently of the Kotlin twin.
 */
describe("incident state machine", () => {
  it("opens an incident on the first DOWN", () => {
    const result = reduceIncident(null, event());
    assert.equal(result.transition, "opened");
    assert.equal(result.shouldPush, true);
    assert.ok(result.incident);
    assert.equal(result.incident!.state, "OPEN");
    assert.equal(result.incident!.eventCount, 1);
    assert.equal(result.incident!.key, incidentKey("dev-1", "com.example.game", "E-101"));
  });

  it("keeps an open incident open on a second DOWN", () => {
    const first = reduceIncident(null, event());
    const second = reduceIncident(first.incident, event({ eventId: "evt-2", occurredAt: 1500 }));
    assert.equal(second.transition, "duplicate_down");
    assert.equal(second.shouldPush, false);
    assert.equal(second.incident!.state, "OPEN");
    assert.equal(second.incident!.eventCount, 2);
    assert.equal(second.incident!.lastEventAt, 1500);
  });

  it("closes an open incident on RECOVERED", () => {
    const first = reduceIncident(null, event());
    const second = reduceIncident(
      first.incident,
      event({ eventId: "evt-2", type: "RECOVERED", occurredAt: 2000 }),
    );
    assert.equal(second.transition, "recovered");
    assert.equal(second.shouldPush, true);
    assert.equal(second.incident!.state, "CLOSED");
    assert.equal(second.incident!.closedAt, 2000);
  });

  it("ignores a RECOVERED for an incident that was never opened", () => {
    const result = reduceIncident(null, event({ type: "RECOVERED" }));
    assert.equal(result.transition, "unchanged");
    assert.equal(result.shouldPush, false);
    assert.equal(result.incident, null);
  });

  it("ignores a duplicate RECOVERED", () => {
    const opened = reduceIncident(null, event());
    const closed = reduceIncident(
      opened.incident,
      event({ eventId: "e2", type: "RECOVERED", occurredAt: 2000 }),
    );
    const again = reduceIncident(
      closed.incident,
      event({ eventId: "e3", type: "RECOVERED", occurredAt: 3000 }),
    );
    assert.equal(again.transition, "unchanged");
    assert.equal(again.incident!.closedAt, 2000);
  });

  it("never touches incident state for a heartbeat", () => {
    const opened = reduceIncident(null, event());
    const result = reduceIncident(opened.incident, event({ type: "HEARTBEAT" }));
    assert.equal(result.transition, "unchanged");
    assert.equal(result.shouldPush, false);
    assert.equal(result.incident!.eventCount, 1);
  });

  it("opens a new incident after a recovery", () => {
    const opened = reduceIncident(null, event());
    const closed = reduceIncident(opened.incident, event({ eventId: "e2", type: "RECOVERED" }));
    const reopened = reduceIncident(closed.incident, event({ eventId: "e3", occurredAt: 3000 }));
    assert.equal(reopened.transition, "opened");
    assert.equal(reopened.incident!.openedAt, 3000);
    assert.equal(reopened.incident!.closedAt, null);
  });

  it("keeps device level incidents (no package) apart from package incidents", () => {
    const deviceLevel = reduceIncident(
      null,
      event({ packageName: null, code: "E-HB-01" }),
    );
    assert.equal(deviceLevel.incident!.key, "dev-1|-|E-HB-01");
  });
});
