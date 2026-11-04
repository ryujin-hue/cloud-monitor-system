# Architecture

This document explains the design decisions, the hard limits we work against, the KV write
budget and who is allowed to originate which event.

---

## 1. System overview

```
┌───────────────────────────┐        HTTPS + x-api-key         ┌──────────────────────────┐
│  Cloud Monitor (API 1)    │  ────────────────────────────▶   │  Cloudflare Pages        │
│  • runs on the cloud      │   POST /api/event                │  Functions (/api/*)      │
│    Android phone          │   POST /api/heartbeat            │                          │
│  • foreground service     │                                  │  ┌────────────────────┐  │
│  • NotificationListener   │   x-api-key: MONITOR_API_KEY     │  │ Workers KV         │  │
│  • UsageStats monitor     │                                  │  │ (5 keys, 1 ns)     │  │
│  • Room outbox + history  │                                  │  └────────────────────┘  │
└───────────────────────────┘                                  └───────────┬──────────────┘
             │                                                             │
             │ offline queue                                               │ FCM HTTP v1
             │ (exponential backoff, original timestamps kept)             │ (data-only)
             ▼                                                             ▼
   Room `queued_events`                                     ┌──────────────────────────┐
                                                             │  Cloud Alert (API 2)    │
                                                             │  • personal phone       │
                                                             │  • FCM receiver builds  │
                                                             │    the notification     │
                                                             │  • dashboard, history   │
                                                             └──────────────────────────┘

                    ┌──────────────────────────────────────────────┐
                    │  Cloudflare cron Worker (every 5 minutes)     │
                    │  scans KV, raises E-HB-01 for silent phones   │
                    └──────────────────────────────────────────────┘
```

Two applications, one monorepo, one backend. Firebase is used **only** to deliver pushes:
no status data is ever written to Firebase.

---

## 2. Error codes and ownership

The codes are defined **once**, in `shared/src/main/kotlin/com/cloudmonitor/shared/ErrorCode.kt`.
The TypeScript mirror in `cloudflare/src/types.ts` is checked against the Kotlin source by
`cloudflare/test/parity.test.ts`, which fails the build if the two ever drift apart.

| Code       | Title                              | Severity | Origin              | Meaning                                                            |
|------------|------------------------------------|----------|---------------------|--------------------------------------------------------------------|
| `E-101`    | App process died                   | WARNING  | Cloud Monitor       | The process disappeared and there is **no** exit-reason or ANR evidence. Low confidence. |
| `E-102`    | App terminated by the system       | WARNING  | Cloud Monitor       | The process was terminated with a known exit reason. **Requires privileged evidence.** |
| `E-204`    | App not responding (ANR)           | CRITICAL | Cloud Monitor       | The OS "isn't responding" dialog was observed, or the process was seen in an ANR state. **Requires ANR evidence.** |
| `E-SHARE-01` | Discord screen share stopped     | CRITICAL | Cloud Monitor       | Discord's screen-share notification was removed and did not come back (after a 4 s debounce). |
| `E-SHARE-02` | Discord died during screen share | CRITICAL | Cloud Monitor       | The Discord process died while a share was active. **Takes precedence over `E-101`.** |
| `E-HB-01`  | Cloud phone stopped reporting      | CRITICAL | Backend cron Worker | No heartbeat for 2 buckets (20 minutes for a small fleet). To Cloud Alert, an unreachable cloud phone always looks like `E-HB-01`. |
| `E-NET-01` | Network unavailable                | INFO     | Cloud Monitor       | The cloud phone lost its network. Local only: queued and delivered later with the original timestamp. Never opens an incident, never pushes. |

**Ownership is enforced.** `POST /api/event` rejects any event whose code is not owned by
`MONITOR`, so a compromised cloud phone cannot forge `E-HB-01` and make the backend claim
that another phone is dead.

### Why `E-NET-01` is also used for a degraded monitor

The canonical code set is closed by specification, so there is no dedicated code for "the
monitor lost Notification Access". We therefore report the degraded state as `E-NET-01`
with `metadata.reason` carrying the precise cause (`notification_access_revoked`,
`usage_access_revoked`, ...). The heartbeat also carries an explicit `permissions` object,
so Cloud Alert can tell "the cloud phone died" apart from "somebody revoked a permission on
the cloud phone". The Cloud Monitor UI blocks the dashboard until the essential permissions
are granted again.

---

## 3. Detection tiers

`UsageStatsManager` reports **activity transitions, not process death**. A process that is
killed leaves its last activity event in the log, and an app that is simply idle in the
background looks exactly like a dead app if you only look at staleness. We therefore
implement two tiers and always tag every event with a `detection` tier and a `confidence`.

| Tier | Needs | Signal | Resulting confidence |
|------|-------|--------|----------------------|
| **Tier 1** | nothing (works on a stock phone) | removal of the app's **ongoing notification** (an app that is alive keeps it) | MEDIUM |
| **Tier 1** | nothing | usage-event staleness: the app was in the foreground ≤ 2 min ago and has produced no usage event for > 45 s | LOW |
| **Tier 2** | root shell | `pidof <pkg>` says the process is gone | HIGH |
| **Tier 2** | root shell | `dumpsys activity exit-info <pkg>` → `E-102` with the real exit reason | HIGH |
| **Tier 2** | root shell | `dumpsys activity processes` shows an ANR → `E-204` | HIGH |
| **ANR** | optional AccessibilityService | the system "isn't responding" dialog was observed → `E-204` | MEDIUM |

### The rules we had to be honest about

* **`ApplicationExitInfo` only covers the caller's own UID.** It can therefore never tell us
  why *another* app died. `E-102` therefore requires either a privileged
  `dumpsys activity exit-info` dump or nothing at all.
* **Without evidence we fall back to `E-101` with LOW confidence.** We never claim `E-102`
  or `E-204` on a guess.
* **`E-204` needs ANR evidence.** The AccessibilityService is the only stock-phone route; it
  is optional and off by default. Attribution of the dialog to a package is *heuristic*: the
  dialog is drawn on top of the app that froze, so we attribute it to the watched package
  that was in the foreground most recently.
* **Tier 2 needs root.** We use `su -c`. Shizuku would provide the same shell without root;
  wiring the Shizuku API is a documented extension point, not implemented here.
* **`E-SHARE-02` wins over `E-101`.** A Discord death during a share is never reported as a
  generic death.

### Discord screen share

The Discord package, notification id, tag and channel are **discovered, not guessed**:
the Cloud Monitor app ships a *Notification Inspector* screen that lists the notifications
that are currently posted with their package, id, tag, channel and ongoing flag. The user
starts a screen share, opens the inspector and picks the matching notification.

Detection details:

* `onNotificationRemoved(sbn, ranking, reason)` is the removal hook.
* A removal is **debounced by 4 s** (the specification allows 3-5 s). Discord re-posts its
  ongoing notification frequently; without the debounce every re-post would look like the
  end of the share.
* `onListenerDisconnected` calls `requestRebind(null)` so the platform binds us again.
* On startup the listener reconciles with `activeNotifications`, so a share that was already
  running when the app started (or after an update) is recognised.

---

## 4. Targets and how they are met

| Target | Requirement | How |
|--------|-------------|-----|
| Notification removal → push | ≤ 10 s | The listener publishes to an in-memory bus; the foreground service reacts in the same process, enqueues the event in Room and flushes immediately. No WorkManager, no polling. |
| Process death → push | ≤ one scan interval + 10 s | The detection loop runs every 15 s, so a death is reported within ~25 s. |
| Heartbeat → `E-HB-01` | not specified | 20 minutes (2 heartbeat buckets) for a fleet of up to four phones, plus up to 5 minutes of cron delay. The bucket grows with the fleet, see section 5. |
| Clock skew | — | The server clamps `occurredAt` to `receivedAt` (5 minutes of future drift allowed, anything older than 30 days is clamped). The client uses `max(previous.lastEventAt, event.occurredAt)` so a backwards clock never moves an incident backwards. |

---

## 5. KV design and the write budget

### Verified limits (checked against the Cloudflare documentation, 2026)

| Limit | Free plan | Paid plan |
|-------|-----------|-----------|
| Keys read | 100,000 / day | 10 million / month |
| Keys written | **1,000 / day** | 1 million / month |
| Writes to the same key | **1 per second** | 1 per second |
| Keys deleted | 1,000 / day | 1 million / month |
| List requests | 1,000 / day | 1 million / month |
| Stored data | 1 GB | 1 GB + $0.50 / GB-month |

Sources: [Workers KV pricing](https://developers.cloudflare.com/kv/platform/pricing/) and
[Workers KV limits](https://developers.cloudflare.com/kv/platform/limits/) (both retrieved
2026-11). The Workers free plan additionally allows 100,000 requests/day and 10 ms of CPU
per request.

### The keys

All state lives in **one** namespace. Everything that belongs to a *cloud phone* is stored
per device; only genuinely global state is shared.

| Key | Content | TTL | Written when |
|-----|---------|-----|--------------|
| `cloud-monitor:device:<deviceId>` | one device: label, watched packages, version, pairing code | 1 year | a metadata change or a pairing-code rotation |
| `cloud-monitor:hb:<deviceId>` | liveness of **one** device | 1 year | **at most once per heartbeat bucket** |
| `cloud-monitor:deviceindex` | `{ deviceId: { label, updatedAt } }` | 1 year | **by the cron Worker only** |
| `cloud-monitor:incidents` | incident state + push bookkeeping | 1 year | an incident changes state |
| `cloud-monitor:subscriptions` | alert devices, FCM tokens, subscriptions | 1 year | register / token rotation |
| `cloud-monitor:events` | rolling history (last 1000 events) | 30 days | an event batch arrives |

#### Why nothing device related is a single shared key

This was found the hard way, in production, during development. Twice.

Workers KV is **eventually consistent**. A read can return a value that is up to ~60 s old.
A shared key that is written with a read-modify-write therefore loses records whenever two
writers overlap: the second writer read a stale copy and wrote it back, deleting the first
writer's entry.

* **First incident — the pairing code disappeared.** The heartbeat endpoint had written the
  registration with the pairing code; the event endpoint read the same key a moment later,
  got the stale version and wrote it back, silently deleting it.
* **Second incident — the device index lost devices.** Five cloud phones that heartbeated
  in the same second reduced to two in the index, because all five wrote the same
  `deviceindex` key with a read-modify-write.

The fix has three parts:

* **Every device has its own keys** (`cloud-monitor:device:<id>` and
  `cloud-monitor:hb:<id>`), so two cloud phones can never overwrite each other.
* **The device index has exactly one writer** — the cron Worker rebuilds it from a KV
  list. A key with one writer cannot race with anything.
* **The event endpoint never writes the device registry.** It only reads it. That removes
  the read-modify-write race between the two hottest endpoints.

The shared keys that are still written with a read-modify-write (events, incidents,
subscriptions) use a **merge with verification**: after writing, the key is read again, and
when our own change did not survive, the merge is retried.

Two more properties fall out of this design:

* The **liveness record is self healing**. Losing one bucket only delays the `E-HB-01`
  detection by one bucket, because the next bucket writes the record again.
* The **device index is rebuilt, not patched**. A device that is missing from the index
  (because it was created 5 seconds ago and the KV key list has not propagated yet) is
  picked up by the next cron run, at the latest 5 minutes later.

### The write budget calculation

The dominating cost is the heartbeat. A naive implementation writes one KV value per
heartbeat:

```
60 s heartbeat  ->  1440 writes/day/device  ->  1 device already blows the 1000/day budget
```

**Strategy: bucket coalescing.** A heartbeat is written to KV only when the bucket
changes. The bucket length is not fixed, it **adapts to the fleet size**:

```
bucket = 10 min x ceil(devices / 4)          (capped at 60 min)

writes/day for heartbeats = devices x (24 h / bucket)
```

| Cloud phones | Bucket | Heartbeat writes/day | `E-HB-01` after |
|--------------|--------|----------------------|------------------|
| 1 – 4        | 10 min | 144 per device       | 20 min + cron delay |
| 5 – 8        | 20 min | 72 per device        | 40 min + cron delay |
| 9 – 12       | 30 min | 48 per device        | 60 min + cron delay |
| 13 – 16      | 40 min | 36 per device        | 80 min + cron delay |
| 17 – 41      | 60 min | 24 per device        | 120 min + cron delay |

The total heartbeat cost therefore stays at or below ~600 writes/day for *any* fleet size
up to ~41 cloud phones, and the free-plan budget is never the reason a phone goes silent.

Full daily budget for a fleet of **5 cloud phones** (bucket = 20 min):

| Source | Writes/day | Notes |
|--------|-----------|-------|
| Heartbeats | 72 x 5 = 360 | one write per device per bucket, one key per device |
| Device records | 48 per device = 240 | the pairing code rotates every 30 minutes; everything else changes rarely |
| Device index | ~1 | rewritten by the cron Worker every 5 minutes (1 list request each) |
| Incidents | ~2 per incident | one on open, one on close |
| Events (history) | ~1 per incident batch | one write per `POST /api/event` |
| Subscriptions | ~5 | one per register call (app start, token rotation, pairing) |
| **Total, 1 cloud phone** | **~195** | ≈ 20 % of the free-plan budget |
| **Total, 4 cloud phones** | **~620** | ≈ 62 % of the free-plan budget |
| **Total, 5 cloud phones** | **~650** | ≈ 65 % of the free-plan budget |
| **Total, 40 cloud phones** | **~1,000** | at the limit — use the paid plan above that |

**Reads** are far cheaper (100,000/day) but still budgeted:

| Source | Reads/day |
|--------|-----------|
| Heartbeats (1 per heartbeat, 3 keys each) | 21,600 for 5 devices at a 60 s interval |
| Dashboard polling (1 per minute x (4 shared keys + 2 keys per device)) | 7,200 for 5 devices |
| Cron (every 5 min x 1 list + 2 keys per device) | ~2,000 |
| Events | a few hundred |
| **Total** | **~31,000** ≈ 31 % of the free-plan budget |

**List requests** (1,000/day) are the scarcest resource of the three, because they are the
only way to enumerate the device keys:

| Source | List requests/day |
|---------|------------------|
| Cron Worker (rebuilds the device index) | 288 |
| Pairing lookup (one per pairing attempt) | a handful |
| **Total** | **~290** ≈ 29 % of the free-plan budget |

### The deviation we had to make, and why

**Deviation:** heartbeat liveness is recorded with a bucket granularity (10 minutes for a
small fleet), not per heartbeat.

*Why:* a per-heartbeat write costs 1440 writes/day/device and the free plan allows 1000
writes/day **in total**. There is no configuration of "one KV write per heartbeat" that
fits. Bucketing is the smallest change that keeps the whole fleet inside the budget, and it
degrades only the *heartbeat-miss* signal.

*What it costs:* `E-HB-01` is raised after 20 minutes of silence (for a fleet of up to four
phones) instead of after 2-3 minutes, and the window grows with the fleet. The detection of
an app death is **not** affected: that path goes through the event queue, not through the
heartbeat key.

*Why the bucket is adaptive:* a fixed 10 minute bucket would exceed the free plan at four
cloud phones (4 x 144 = 576 heartbeats + 4 x 48 device records = 768 writes/day, plus
incidents and events). Growing the bucket with the fleet keeps the total flat.

*If you need faster heartbeat-miss detection:* switch to the paid plan (1 M writes/month),
or move the liveness state into a Durable Object, which is not rate limited the same way.

### The 1 write/second-per-key limit

Two cloud phones reporting at the same instant would both write `cloud-monitor:events` and
the second one would receive HTTP 429. Every KV write in this codebase therefore retries
with a backoff (`src/state.ts`, `kvPut`), and a retried merge **re-reads the key first**,
so a retry never writes a stale merge. A lost event is not acceptable, so we retry instead
of swallowing the error.

### Eventual consistency

KV is eventually consistent (up to ~60 s to propagate globally). We rely on this only for
read-mostly paths. Writes are always followed by a read of the same key in the same
request when the result is needed immediately, and the incident state is authoritative on
the write path.

---

## 6. Push delivery

* **FCM HTTP v1 only**, no `firebase-admin`. The service account JWT is signed with
  WebCrypto (`crypto.subtle`) and exchanged for an OAuth access token directly against
  Google's token endpoint (`cloudflare/src/fcm.ts`). The access token is cached per isolate.
* **Data-only, high priority messages.** Cloud Alert builds the notification itself, so the
  wording, the channel and the deep link stay under our control.
* **De-duplication:** the incident remembers the `eventId` of the last push. The same event
  is never pushed twice, even when the cloud phone re-delivers it from its offline queue.
* **Cooldown:** 60 s per incident, so a flapping app cannot spam the alert phone.
* **Token rotation:** `onNewToken` stores the new token and re-registers with the backend.
* **Dead tokens:** a token that FCM reports as `UNREGISTERED` / `INVALID_ARGUMENT` is
  removed from the subscription record and never used again.

---

## 7. Security

| Concern | Decision |
|---------|----------|
| API keys | Two separate keys: `MONITOR_API_KEY` (event, heartbeat) and `ALERT_API_KEY` (status, events, register). A leaked alert key cannot post fake events. |
| Comparison | Constant time (`src/auth.ts`), including the length check. |
| Missing configuration | Fails **closed**: an unset key never authorises anything. |
| Key storage on device | The API key and the FCM token are encrypted with AES-256/GCM using an Android Keystore key, and only the ciphertext is stored in DataStore. |
| CORS | Explicit allow-list. An unknown origin is never echoed back. Native apps do not send an `Origin` header. |
| Firebase | Only the FCM token and the push payload transit through Firebase. No status data. |
| Pairing | The cloud phone shows a 6-digit code that expires after 30 minutes. Cloud Alert exchanges it for a subscription. The code can be rotated at any time from the Cloud Monitor UI. |

**Known limitation:** both API keys are *shared* between all installations of an app. A
leaked monitor key therefore lets an attacker post events for any device. Per-device keys
would fix this but need a registration handshake the specification does not describe. The
mitigation today is that the monitor key is only ever configured on the dedicated cloud
phone, which is not a personal device.

---

## 8. Android specifics

### Foreground service

* `foregroundServiceType="specialUse"` with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
  justification, as required on Android 14+.
* `START_STICKY`, so the platform restarts the service after a low-memory kill.
* `BOOT_COMPLETED` receiver for a reboot of the cloud phone.
* **`MY_PACKAGE_REPLACED` receiver.** `BOOT_COMPLETED` is *not* delivered on an in-place
  update, so without this receiver the monitor would stay dead after every update until
  the phone is rebooted. This is the single most important line of code for the update story.
* WorkManager is used **only as a watchdog** (every 15 minutes, it checks whether the
  service is alive and restarts it). WorkManager is deliberately *not* used for event
  delivery: its minimum periodic interval of 15 minutes is far too coarse for the
  "notification removal to push ≤ 10 s" target.
* Battery-optimisation exemption is requested in the onboarding flow, and the onboarding
  screen also points at the OEM auto-start screens (Xiaomi, Huawei, Oppo, Vivo, Samsung,
  OnePlus, Asus), which kill background services independently of Android.

### Offline queue

* Every event is written to Room **first** and delivered afterwards.
* Exponential backoff: 5 s → 10 s → 20 s → … capped at 30 minutes, with ±20 % jitter.
* A rejected API key (401) uses a flat 10 minute delay: retrying immediately cannot help.
* The queue is bounded at 2000 events. Beyond that the oldest entries are dropped and the
  drop is logged.
* Duplicate `eventId`s are ignored, so re-delivering a batch is harmless.

### Data survives updates

* Room uses `exportSchema = true`; the schemas are committed under `app-*/schemas/`.
* Explicit migrations only. **No `fallbackToDestructiveMigration()` anywhere** — a
  destructive migration would delete the outbox exactly when it matters most.
* Migration 1 → 2 is implemented for both apps and covered by a Robolectric test that
  creates the old schema by hand, runs the migration and asserts that the data survived
  *and* that Room's own schema validation accepts the result.
* DataStore keys are stable: they are never renamed and never reused.

---

## 9. Versioning and in-place updates

* `version.properties` holds `MAJOR.MINOR`.
* `versionCode = git rev-list --count HEAD` — monotonic, because the history of `main` is
  never rewritten.
* `versionName = MAJOR.MINOR.<commit count>`.
* The computation lives in `settings.gradle.kts` and is consumed by both apps through
  `gradle.extra`, so it is computed exactly once.
* CI checks out with `fetch-depth: 0`. In CI, a failure to read the git history is a hard
  error, never a fallback: a wrong `versionCode` would break updates over an already
  published APK.
* The application ids `com.cloudmonitor` and `com.cloudalert` are fixed forever.
* One signing key for every distributed build, stored as the GitHub secrets
  `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. CI **fails**
  when they are missing; it never falls back to the debug key, because debug keys differ
  per machine and would break updates.
* `scripts/verify-update.sh` builds two consecutive versions and asserts, with
  `apksigner verify --print-certs` and `aapt dump badging`, that the package name is the
  same, the certificate SHA-256 is the same and the versionCode is higher. The second build
  comes from a synthetic commit created with `git commit-tree`: no branch, no tag, no
  history rewrite, nothing pushed.

---

## 10. Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| The cloud phone's OEM kills the service | The monitor goes blind; Cloud Alert only sees `E-HB-01` | Battery exemption, OEM auto-start instructions, `START_STICKY`, WorkManager watchdog, `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` receivers |
| Notification Access is revoked | Tier 1 detection dies | The UI blocks until it is granted again; the heartbeat reports the permission state |
| The device clock is wrong | Incident timestamps are wrong | The server clamps `occurredAt`; the client never moves `lastEventAt` backwards |
| A Discord fork uses a different notification | The screen share is not detected | The Notification Inspector makes the target configurable, nothing is hard-coded |
| The pairing code is photographed | Someone subscribes to the cloud phone | The code expires after 30 minutes and can be rotated at any time |
| KV is eventually consistent | A stale read | The write path is authoritative; reads are only used for reporting |
| The KV free budget is exhausted | Writes fail with HTTP 1101 | The heartbeat bucket grows with the fleet, so the total stays flat (~600 writes/day for the heartbeats); every write retries on 429 |
| Two cloud phones write at the same instant | One overwrites the other | Every device has its own keys, and the device index has exactly one writer (the cron Worker). This was a real bug: 5 phones became 2 |
| The release key is lost | No APK can ever update an installed one | The key is backed up outside the repository; see the README |
| `E-101` false positives on tier 1 | A noisy alert | Tier 1 is reported with LOW confidence and the UI shows the tier, so the user can judge it |
