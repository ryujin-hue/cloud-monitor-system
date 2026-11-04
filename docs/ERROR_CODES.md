# Error codes

Every code is defined **once**, in
`shared/src/main/kotlin/com/cloudmonitor/shared/ErrorCode.kt`.

The TypeScript backend uses a mirror in `cloudflare/src/types.ts`. A unit test
(`cloudflare/test/parity.test.ts`) parses the Kotlin file and fails the build if the two
lists ever differ, so a code can never exist on only one side.

**Never write a code as a string literal.** Use `ErrorCode.E_101.wire` in Kotlin and
`ERROR_CODES.E_101` in TypeScript.

---

## The codes

| Code | Title | Severity | Origin | What it means |
|------|-------|----------|--------|---------------|
| `E-101` | App process died | WARNING | Cloud Monitor | The monitored app's process disappeared and we have **no** exit-reason or ANR evidence. This is the low-confidence fallback. |
| `E-102` | App terminated by the system | WARNING | Cloud Monitor | The process was terminated with a known exit reason (`CRASH`, `LOW_MEMORY`, `USER_REQUESTED`, ...). **Requires privileged evidence** (`dumpsys activity exit-info`). |
| `E-204` | App not responding (ANR) | CRITICAL | Cloud Monitor | The app froze. **Requires ANR evidence**: the system "isn't responding" dialog (AccessibilityService) or an ANR state in a privileged dump. |
| `E-SHARE-01` | Discord screen share stopped | CRITICAL | Cloud Monitor | Discord's screen-share notification was removed and did not come back within the 4 s debounce window. |
| `E-SHARE-02` | Discord died during screen share | CRITICAL | Cloud Monitor | The Discord process died while a share was active. **Takes precedence over `E-101`.** |
| `E-HB-01` | Cloud phone stopped reporting | CRITICAL | Backend cron Worker | The cloud phone has not been seen for 15 minutes. To Cloud Alert, an unreachable cloud phone always looks like `E-HB-01`. |
| `E-NET-01` | Network unavailable | INFO | Cloud Monitor | The cloud phone lost its network. Queued in the offline outbox and delivered later with the **original** timestamp. Never opens an incident and never pushes. |

---

## Ownership rules

| Code | May be originated by | May be sent by |
|------|----------------------|----------------|
| `E-101`, `E-102`, `E-204`, `E-SHARE-01`, `E-SHARE-02`, `E-NET-01` | Cloud Monitor | `POST /api/event` with the monitor key |
| `E-HB-01` | Backend cron Worker | never accepted from a device |

The backend **rejects** any event whose code is owned by the backend, so a compromised or
buggy cloud phone cannot make the system claim that another phone is dead.

---

## Precedence

When several conditions hold at the same time, the first matching rule wins:

1. **Discord died while a screen share was active** → `E-SHARE-02`, never `E-101`.
2. **ANR evidence present** → `E-204`, even if there is also an exit reason.
3. **Exit-reason evidence present** → `E-102`.
4. **Nothing else** → `E-101` with LOW confidence.

The rules live in `shared/src/main/kotlin/com/cloudmonitor/shared/Detection.kt`
(`CodeMapper.mapDeathCode`) and are unit tested in `CodeMapperTest`.

---

## Confidence

| Situation | Confidence |
|-----------|------------|
| Tier 2 (privileged) detection, with or without evidence | HIGH |
| Tier 1 with ANR or exit-reason evidence | MEDIUM |
| Tier 1, ongoing-notification removal | MEDIUM |
| Tier 1, `E-SHARE-01` / `E-SHARE-02` | MEDIUM |
| Tier 1, usage-event staleness only | LOW |

Confidence is shown in the Cloud Alert history so that a user can judge a tier-1 report
accordingly.

---

## Event semantics

| Field | Meaning |
|-------|---------|
| `eventId` | UUID v4 generated on the device. **The idempotency key.** Re-delivering the same event never creates a second incident or a second push. |
| `deviceId` | Stable id of the cloud phone, generated once and preserved across updates. |
| `type` | `DOWN`, `RECOVERED` or `HEARTBEAT`. |
| `code` | One of the codes above. |
| `packageName` | The monitored package, or `null` for device-level codes such as `E-HB-01`. |
| `detection` | `tier1`, `tier2` or `backend`. |
| `confidence` | `high`, `medium` or `low`. |
| `occurredAt` | **Device** wall-clock time. The original value is kept even when the event is delivered much later. |
| `receivedAt` | **Server** wall-clock time. Filled in by the backend. |
| `seq` | Monotonic per-device counter, used to detect gaps. |
| `appVersion`, `versionCode` | The version of Cloud Monitor that produced the event. Cloud Alert shows it per device. |

### Incidents

* An incident is identified by `(deviceId, packageName, code)`.
* There is at most **one open incident** per incident.
* `DOWN` opens an incident (or keeps an existing one open).
* `RECOVERED` closes an open incident. It never opens one, and a `RECOVERED` for an
  incident that was never opened is ignored, so a stale recovery can never produce a
  spurious notification.
* `HEARTBEAT` never changes incident state.

The rules are implemented twice, identically: in Kotlin
(`shared/src/main/kotlin/com/cloudmonitor/shared/IncidentStateMachine.kt`, used by Cloud
Monitor to decide DOWN vs RECOVERED and by Cloud Alert to render incidents) and in
TypeScript (`cloudflare/src/incidents.ts`, where the backend is the authority). Both are
unit tested.
