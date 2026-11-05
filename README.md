# cloud-monitor-system

Two Android apps and one Cloudflare backend that watch a **cloud phone** and tell you, on
your **personal phone**, when a monitored app dies or when a Discord screen share stops.

| App | Application id | Runs on | Purpose |
|-----|----------------|---------|---------|
| **Cloud Monitor** | `com.cloudmonitor` | the aggressive cloud Android phone | watches selected apps and Discord's screen-share notification, sends events and a heartbeat |
| **Cloud Alert** | `com.cloudalert` | your personal phone | shows the state of every cloud phone, receives push alerts, supports any number of cloud phones |

**Backend:** Cloudflare Pages Functions + Workers KV + one cron Worker.
**Firebase** is used **only** to deliver the push messages — no status data ever reaches
Firebase.

### Documentation

| Document | Audience |
|----------|----------|
| [Panduan Pengguna (Bahasa Melayu)](docs/USER_GUIDE_MS.md) | Beginner user guide in Malay |
| [User Guide (English)](docs/USER_GUIDE_EN.md) | Beginner user guide in simple English |
| [Troubleshooting / Penyelesaian Masalah](docs/TROUBLESHOOTING.md) | Crashes, permissions, notifications, Android 16, root, API |
| [Architecture and design decisions](docs/ARCHITECTURE.md) | Developers (unchanged) |
| [Error codes](docs/ERROR_CODES.md) | Developers (unchanged) |

> The technical architecture and developer documentation below is preserved from the
> original project. Only the compatibility target, verified bug fixes and the new user
> documentation were added.

---

## Repository layout

```
cloud-monitor-system/
├── app-monitor/            Cloud Monitor (Android application)
├── app-alert/              Cloud Alert (Android application)
├── shared/                 pure Kotlin: error codes + DTOs used by both apps
├── cloudflare/
│   ├── functions/api/      Pages Functions: event, heartbeat, status, events, register
│   ├── cron-worker/        the scheduled Worker that raises E-HB-01
│   ├── src/                shared TypeScript: types, auth, KV, incidents, FCM, push
│   └── test/               unit tests (KV key builder, incident state machine, parity)
├── scripts/                version, signing setup, update verification
├── docs/                   ARCHITECTURE.md, ERROR_CODES.md, USER_GUIDE_MS.md,
│                           USER_GUIDE_EN.md, TROUBLESHOOTING.md
├── .github/workflows/      android.yml, cloudflare.yml
├── gradle/libs.versions.toml
└── version.properties
```

---

## 1. Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 17 or newer | AGP 9.x requires JDK 17+ (build verified with JDK 20) |
| Android SDK | platforms **37.0 + 37.2** and **36**, build-tools **37.0.0 + 36.0.0** | `compileSdk` = **37** (required by the pinned AndroidX / Compose / Ktor AAR metadata), `targetSdk` = **36 (Android 16)** |
| Gradle | 9.8.2 (wrapper) | do not use a globally installed Gradle |
| Node.js | 22 | for the backend |
| `gh` | any | optional, for creating the repo and setting secrets |

---

## 2. Android setup

```bash
git clone https://github.com/<owner>/cloud-monitor-system.git
cd cloud-monitor-system

# Point Gradle at your SDK (or set ANDROID_HOME)
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew clean
./gradlew assembleDebug        # both apps (no release keystore required)
./gradlew test                 # JVM unit tests (shared + both app modules)
./gradlew lint                 # lint report (non-fatal by design)
./gradlew assembleRelease      # signed release: needs the keystore secrets, see 4.1

# Optional: override the backend URL at build time
./gradlew assembleDebug -PbackendBaseUrl=https://your-backend.example.com
```

> `local.properties` (`sdk.dir=...`) is machine-specific and gitignored. **`targetSdk` is
> 36 (Android 16)** — that is the runtime compatibility target. **`compileSdk` is 37**
> because the pinned AndroidX / Compose / Ktor releases declare `minCompileSdk 37` in their
> AAR metadata; setting it back to 36 makes `checkDebugAarMetadata` fail. Since AGP 9.4.1
> does not yet officially support API 37, `gradle.properties` keeps
> `android.suppressUnsupportedCompileSdk=37` (it only silences the warning — it does **not**
> change the runtime target). Install SDK platforms `android-37.0` + `android-37.2` before
> building; see [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) §12.

### Build configuration

| Flag | Default | Meaning |
|------|---------|---------|
| `-PbackendBaseUrl=https://…` | `https://cloud-monitor-api.pages.dev` | the backend the apps talk to. The environment variable `BACKEND_BASE_URL` works too. |

The backend URL is baked into `BuildConfig.BACKEND_BASE_URL` at build time and is **not**
editable from inside the app. The **API key** is entered in the app's settings screen at
runtime and is stored encrypted with Android Keystore — the key and the URL are separate
things. To point the apps at a different backend you must rebuild them with
`-PbackendBaseUrl=…` (or `BACKEND_BASE_URL`).

### Required Android permissions

Cloud Monitor needs four things. The app opens the right settings screen for each of them;
the onboarding screen stays visible until the two essential ones are granted.

| Permission | How to grant | Why |
|------------|--------------|-----|
| **Notification Access** | Settings → Notifications → Device & app notifications → *Cloud Monitor* → enable | The primary tier-1 detection channel: the removal of an app's ongoing notification, and Discord's screen-share notification. |
| **Usage Access** | Settings → Apps → Special app access → Usage access → enable *Cloud Monitor* | Foreground/background transitions of the watched apps. |
| **Ignore battery optimisations** | The onboarding button opens the system dialog | Without it Android may freeze the service during Doze and kill it in the background. |
| **Post notifications** | The onboarding button (Android 13+) | Needed for the ongoing "monitor is running" notification. |
| **Accessibility (optional)** | Settings → Accessibility → *Cloud Monitor ANR watcher* | The **only** way to obtain ANR evidence for a third-party app on a stock phone, which is what `E-204` needs. Without it, an ANR is reported as `E-101` with low confidence. |

**OEM autostart.** Xiaomi, Huawei, Oppo, Vivo, Samsung, OnePlus and Asus kill background
services from their own "auto start" / "protected apps" screens, independently of Android.
Enable Cloud Monitor there as well, otherwise the monitor will silently die.

### 2.1 Android 16 (API 36) compatibility

Both apps now use **`targetSdk = 36`** (previously 37), which is what opts the app into the
Android 16 behaviour changes. `compileSdk` stays **37** because the pinned dependency
versions require it; `compileSdk` only controls which APIs are visible at compile time and
has no effect on the runtime behaviour listed below. The following official Android 16 /
recent-platform behaviours were audited against the source:

| Platform behaviour | Applies to | What this project does |
|--------------------|------------|------------------------|
| **Edge-to-edge opt-out removed** (`targetSdk 36`) | Android 16 | `enableEdgeToEdge()` + Material 3 `Scaffold` in both activities; no `windowOptOutEdgeToEdgeEnforcement`. |
| **Predictive back on by default** (`targetSdk 36`) | Android 16 | `android:enableOnBackInvokedCallback="true"` declared; Compose navigation handles back. |
| **Adaptive layouts** (orientation/resizability ignored on ≥600dp) | Android 16 | No locked orientation or `resizeableActivity` restrictions; Compose layouts are scrollable/adaptive. |
| **`POST_NOTIFICATIONS` runtime permission** | Android 13+ | Requested with an explanation banner (Cloud Alert) and onboarding row (Cloud Monitor); permanent denial opens `ACTION_APP_NOTIFICATION_SETTINGS`. |
| **Background FGS start restriction** | Android 12+ | `start()` returns a `StartOutcome`; `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` are the legal exemptions used. WorkManager watchdog is **not** treated as an exemption. |
| **`ForegroundServiceStartNotAllowedException`** | Android 12+ | Caught and translated into a readable message; the UI never claims success until `isRunning` confirms `startForeground()` succeeded. |
| **FGS type + type-specific permission** | Android 14+ | `foregroundServiceType="specialUse"`, `FOREGROUND_SERVICE_SPECIAL_USE`, and `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` justification. The 3-arg `startForeground()` is used only on API 34+ (passing `specialUse` on API 29–33 throws). |
| **`BOOT_COMPLETED` FGS type restrictions** | Android 15+ | `specialUse` is not one of the restricted (`dataSync` / `mediaProcessing`) types; the start result is still checked. |
| **Job quotas for jobs started from an FGS** | Android 16, all apps | The 15-minute `WatchdogWorker` is quota-bound and does not retry forever; restart is user-initiated. |
| **PendingIntent mutability** | Android 12+ | All `PendingIntent`s use `FLAG_IMMUTABLE` (or `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`). |
| **Broadcast receiver export** | Android 12+ | `BootReceiver` is `exported="false"` with `RECEIVE_BOOT_COMPLETED`; `PackageReplacedReceiver` is `exported="false"`. |
| **Safer Intents / Local Network Protection** | Android 16, opt-in | Not enabled; no cross-app implicit intents or LAN sockets are used. Noted as future work. |
| **16 KB page size** | Android 15/16 devices | Not verifiable in this environment — see *Known limitations*. |

See [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) §9 for the user-facing version.

---

## 3. Backend setup

### 3.1 Create and bind the KV namespace

```bash
cd cloudflare
npm install

npx wrangler login
npx wrangler kv namespace create cloud-monitor
```

Copy the printed **id** into **both** configuration files:

* `cloudflare/wrangler.toml` → `[[kv_namespaces]] id = "…"`
* `cloudflare/cron-worker/wrangler.toml` → `[[kv_namespaces]] id = "…"`

Both the Pages project and the cron Worker must use the **same** namespace.

### 3.2 Create the API keys

```bash
openssl rand -base64 32   # MONITOR_API_KEY
openssl rand -base64 32   # ALERT_API_KEY   (a DIFFERENT value)
```

Store them as secrets — never as plain `vars`:

```bash
npx wrangler secret put MONITOR_API_KEY   # paste the monitor key
npx wrangler secret put ALERT_API_KEY     # paste the alert key
```

The two keys have different powers:

| Key | Endpoints | Used by |
|-----|-----------|---------|
| monitor key | `POST /api/event`, `POST /api/heartbeat` | Cloud Monitor |
| alert key | `GET /api/status`, `GET /api/events`, `POST /api/register` | Cloud Alert |

### 3.3 Firebase (push delivery)

Firebase is **optional**. Without it the backend stores everything and Cloud Alert falls
back to polling; the apps still build and work.

To enable push:

1. Create a Firebase project and add the two Android apps
   (`com.cloudmonitor` is not needed — only `com.cloudalert` receives pushes, but adding
   both is harmless).
2. Download the `google-services.json` of each app and place it next to the module:

   ```
   app-monitor/google-services.json
   app-alert/google-services.json
   ```

   These files are **gitignored**. The Google Services Gradle plugin is applied
   automatically when the file exists; when it does not, the build still succeeds and FCM
   is disabled with a warning in the log.
3. Create a service account (Project settings → Service accounts → Generate key), download
   the JSON and store it as a Cloudflare secret:

   ```bash
   npx wrangler secret put FCM_SERVICE_ACCOUNT_JSON   # paste the whole JSON file
   ```

   The backend signs the FCM HTTP v1 JWT with WebCrypto — there is no `firebase-admin`
   dependency.

### 3.4 The cron Worker

Pages Functions cannot run on a schedule, so one Worker does the periodic work: it
rebuilds the device index and raises `E-HB-01` for cloud phones that stopped reporting.

```bash
cd cloudflare

npx wrangler secret put CRON_SECRET   # protects the manual trigger
```

Its schedule (`*/5 * * * *`) is configured in `cloudflare/cron-worker/wrangler.toml`. The
Worker can also be triggered by hand, which is useful right after a phone was added:

```bash
# The secret goes in a HEADER, never in the URL (URLs leak into logs/history/Referer).
curl -H "x-cron-secret: <CRON_SECRET>" "https://cloud-monitor-cron.<account>.workers.dev/"
```

### 3.5 Deploy

```bash
cd cloudflare

npm run typecheck        # tsc --noEmit
npm test                 # unit tests
npm run dry-run:worker   # wrangler deploy --dry-run (cron Worker)
npm run dry-run:pages    # wrangler pages functions build (Pages project)

npm run deploy:worker    # cron Worker
npm run deploy:pages     # Pages project
```

> `wrangler secret put` only takes effect on the **next deployment**: always redeploy
> after setting or changing a secret.

Local development:

```bash
cp .dev.vars.example .dev.vars   # fill in the keys
npx wrangler pages dev dist
```

### 3.6 How the state is stored

Everything lives in **one** KV namespace. Everything that belongs to a cloud phone is
stored **per device**, because Workers KV is eventually consistent and a shared key that
is written with a read-modify-write loses records when two writers overlap:

| Key | Content |
|-----|---------|
| `cloud-monitor:device:<deviceId>` | one device: label, watched packages, version, pairing code |
| `cloud-monitor:hb:<deviceId>` | liveness of one device, written at most once per bucket |
| `cloud-monitor:deviceindex` | the list of known devices — written **only** by the cron Worker |
| `cloud-monitor:incidents` | incident state and push bookkeeping |
| `cloud-monitor:subscriptions` | alert phones, FCM tokens, subscriptions |
| `cloud-monitor:events` | rolling history of the last 1000 events |

The write budget of the free plan (1,000 writes/day) is the reason the heartbeat is
bucketed and the bucket **grows with the fleet**: one cloud phone needs 144 writes/day, 41
phones still fit. See `docs/ARCHITECTURE.md` section 5 for the full calculation.

---

## 4. Signing, versioning and updates

### 4.1 The release key

**One key, forever.** If the key changes, a new APK can never be installed over an already
published one.

```bash
./scripts/setup-signing.sh
```

This creates `release-keystore.jks` (RSA 4096, valid 10000 days) and prints the four
values you need. The keystore is **never committed** (it is gitignored).

> **Back up the keystore and the three passwords to a place outside this repository, right
> now.** If you lose them, no future release can update the apps that are already
> installed.

Upload them as GitHub secrets:

```bash
gh secret set KEYSTORE_BASE64 < release-keystore.jks.base64
gh secret set KEYSTORE_PASSWORD --body '...'
gh secret set KEY_ALIAS --body 'cloud-monitor'
gh secret set KEY_PASSWORD --body '...'
```

CI **fails** when these secrets are missing. It never falls back to the debug key: debug
keys are machine specific, so such an APK could never update a published one. A local
build without a keystore falls back to debug signing and prints a loud warning.

### 4.2 How the version is computed

```
version.properties      MAJOR.MINOR  (the only manual part)
versionCode = git rev-list --count HEAD
versionName = MAJOR.MINOR.<commit count>
```

The computation lives in `settings.gradle.kts` and is consumed by both apps, so it happens
exactly once per build. The commit count only ever grows because the history of `main` is
never rewritten — which is what makes `versionCode` monotonic and updates safe.

CI checks out with `fetch-depth: 0`; without the full history the count would be wrong and
**CI fails instead of guessing**.

### 4.3 The release pipeline

Every push to `main`:

1. builds both signed release APKs,
2. runs the unit tests,
3. runs `scripts/verify-update.sh`, which builds two consecutive versions and asserts with
   `apksigner verify --print-certs` and `aapt dump badging` that the package name is the
   same, the certificate SHA-256 is the same and the versionCode is higher,
4. creates the tag `v<versionName>` and a GitHub Release with `--generate-notes` and the
   two APKs attached.

**No commit is ever pushed back to the repository** — only a tag.

### 4.4 How an update behaves

* The application ids never change, so the new APK replaces the old one in place.
* The DataStore keys are stable, so your API key, device name, watched packages and pairing
  settings survive.
* Room uses explicit migrations and **no** `fallbackToDestructiveMigration()`, so the
  offline outbox and the history survive.
* `BOOT_COMPLETED` is **not** delivered on an update. The `MY_PACKAGE_REPLACED` receiver
  restarts the foreground service, so the monitor is back within seconds of the update.
* The heartbeat and the register payload carry `appVersion` and `versionCode`, and Cloud
  Alert shows the Cloud Monitor version of every device.

---

## 5. Running and using the apps

### Cloud Monitor (on the cloud phone)

1. Install `cloud-monitor-<version>.apk`.
2. Open it and follow the onboarding: grant Notification Access, Usage Access, the
   battery exemption and the notification permission.
3. **Settings** → paste the **monitor key** from the backend.
4. **Choose apps** → pick the packages to watch.
5. **Notification inspector** → start a Discord screen share, then pick the matching
   notification ("Use as Discord target"). Nothing about Discord is hard-coded.
6. The dashboard shows the **6-digit pairing code** and the device id. It expires after 30
   minutes; "New code" rotates it.

### Cloud Alert (on your personal phone)

1. Install `cloud-alert-<version>.apk`.
2. **Settings** → paste the **alert key** from the backend → *Save key* → *Register*.
3. **Pair** → type the 6-digit code shown on the cloud phone. Repeat for every cloud
   phone; Cloud Alert supports any number of them.
4. The dashboard shows each cloud phone with its state, the version of Cloud Monitor it
   runs and its open incidents. **History** filters the events by error code.

---

## 6. Testing with adb

```bash
adb devices

# --- kill the app (the foreground service is restarted by START_STICKY / the watchdog) ---
adb shell am force-stop com.cloudmonitor
adb shell am startservice -n com.cloudmonitor/.service.MonitorForegroundService

# --- crash the monitor on purpose ---
adb shell am crash com.cloudmonitor

# --- revoke Notification Access (the app must detect this and block) ---
adb shell cmd notification allow_listener com.cloudmonitor/com.cloudmonitor.monitor.MonitorNotificationListener
adb shell cmd notification disallow_listener com.cloudmonitor/com.cloudmonitor.monitor.MonitorNotificationListener

# --- revoke Usage Access ---
adb shell appops set com.cloudmonitor android:get_usage_stats deny
adb shell appops set com.cloudmonitor android:get_usage_stats allow

# --- reboot the cloud phone (BOOT_COMPLETED must bring the monitor back) ---
adb reboot
adb wait-for-device
adb shell dumpsys activity services com.cloudmonitor | head -20

# --- airplane mode (E-NET-01 is queued and delivered later) ---
adb shell cmd connectivity airplane-mode enable
adb shell cmd connectivity airplane-mode disable

# --- Doze ---
adb shell dumpsys deviceidle enable
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle unforce

# --- battery optimisation ---
adb shell dumpsys deviceidle whitelist +com.cloudmonitor

# --- watch the logs ---
adb logcat -s MonitorRuntime:D DeathDetector:D MonitorNotificationListener:D EventRepository:D

# --- install an update in place (data must survive) ---
adb install -r cloud-monitor-1.0.1.apk
adb install -r cloud-monitor-1.0.2.apk
```

### Verifying an update locally

```bash
./scripts/verify-update.sh                 # both apps
./scripts/verify-update.sh app-monitor     # one app
./scripts/verify-update.sh --with-device   # also install both APKs on a connected device
```

---

## 7. Security checklist

- [ ] `MONITOR_API_KEY` and `ALERT_API_KEY` are **different** and stored as Wrangler secrets.
- [ ] The release keystore is backed up **outside** the repository, together with its three passwords.
- [ ] `google-services.json` and the service account JSON are **not** in the repository.
- [ ] The GitHub secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` are set.
- [ ] The pairing code is not written down anywhere permanent (it expires after 30 minutes).

---

## 8. Known limitations

- **No Android 16 device or emulator was available in this build environment.** All
  Android 16 behaviour changes were verified against the official platform documentation
  and the source/manifest; runtime behaviour on a real API 36 device is **not yet tested**
  (see section 9).
- **16 KB page size.** Some Android 15/16 devices require 16 KB-aligned native
  libraries. The app uses Firebase Messaging, Ktor/OkHttp and Room; whether every bundled
  `.so` is 16 KB-aligned must be checked on a real device with
  `adb shell getconf PAGESIZE` / `zipalign`-style inspection. Not verified here.
- **`specialUse` foreground service type** is subject to Google Play policy review. This
  project is distributed as sideloaded APKs, not through Play; if you publish to Play you
  must justify the type in the declaration.
- **The WorkManager watchdog cannot restart a foreground service from the background** on
  Android 12+ unless the user disabled battery optimisation for the app. After a
  background-start refusal the user must open Cloud Monitor and tap Start.
- **Tier 1 detection is heuristic.** Without root, "app died" is inferred from ongoing
  notification removal and usage-event staleness (MEDIUM/LOW confidence). Tier 2 needs a
  rooted device **and** root granted to this app; an `su` file alone is not enough.
- **Push is optional.** Without `google-services.json` Firebase is disabled and Cloud
  Alert only refreshes the dashboard when opened or manually refreshed.
- **Pairing-attempt limiting is best-effort.** Workers KV is eventually consistent; for a
  hard brute-force guarantee configure Cloudflare Rate Limiting on `POST /api/register`.
- **`QUERY_ALL_PACKAGES`** is needed to list installed apps for the package picker; it is
  restricted by Google Play policy but acceptable for the sideloaded distribution used
  here.
- **Special permissions** (Notification access, Usage access, Accessibility, battery
  optimisation) have no runtime dialog — the user must visit a system Settings screen and
  some OEMs cache the state.
- **Safer Intents / Local Network Protection** are opt-in on Android 16 and not enabled;
  they should be re-evaluated when they become mandatory.
- **Release signing** requires the four CI secrets. A local build without them falls back
  to the debug key and produces an APK that **cannot** update a published one.
- **Unit tests are JVM-only** (no instrumentation / Hilt / end-to-end tests). Room
  migrations and pure-Kotlin state machines are covered; service startup, permission
  flows and FCM delivery are not covered by automated tests.

---

## 9. Testing status

> **Honest status — do not describe this build as production-ready.** The commands below
> were executed in a headless Linux container with Android SDK platforms 36 / 37.0 / 37.2,
> build-tools 36.0.0 / 37.0.0 and JDK 17. There is **no Android device or emulator** in
> this environment, so no on-device/instrumentation test was performed.

| Check | Command | Result |
|-------|---------|--------|
| Clean | `./gradlew clean` | ✅ BUILD SUCCESSFUL |
| Debug build (both apps) | `./gradlew assembleDebug` | ✅ BUILD SUCCESSFUL — `app-alert-debug.apk`, `app-monitor-debug.apk` produced |
| Unit tests | `./gradlew test` | ✅ 38 tests, 0 failures, 0 errors (6 test classes incl. `CancellationGuardTest`, `RootProcessProbeTest`, Room migration tests) |
| Lint | `./gradlew lint` | ✅ BUILD SUCCESSFUL — 0 errors, 12 warnings (non-fatal by design, `abortOnError=false`). See note below. |
| Backend typecheck | `npm run typecheck` | ✅ exit 0 |
| Backend unit tests | `npm test` | ✅ 33/33 passed |
| Hilt dependency injection | compile-time only (KSP annotation processing) | ✅ compile-time only — runtime graph **NOT TESTED** |
| Manifest validity | `assembleDebug` manifest merger | ✅ merged successfully |
| Service start / stop on device | — | **NOT TESTED** (no device/emulator) |
| Permission flows on device | — | **NOT TESTED** (no device/emulator) |
| Root detection on a rooted device | — | **NOT TESTED** (no rooted device) |
| Coroutine cancellation | `CancellationGuardTest`, `RootProcessProbeTest` | ✅ JVM unit tests only — no real service loop tested |
| API failure handling | code review + backend tests | ⚠️ no end-to-end test |
| Android 16 (API 36) runtime behaviour | — | **NOT TESTED** (no API 36 device/emulator) |
| 16 KB page-size compatibility | — | **NOT TESTED** |
| FCM push delivery | — | **NOT TESTED** (no `google-services.json` / Firebase project) |

The 12 remaining lint warnings are benign and intentional: `OldTargetApi` (targetSdk is
deliberately 36, not the newest), `UnusedAttribute` / `InlinedApi` (API-33/30 attributes
guarded by `minSdk 26`), `BatteryLife` (sideloaded app, not distributed via Play),
`ObsoleteSdkInt`, `UnusedResources`, `MonochromeLauncherIcon` and `UseKtx`. The two
`NewApi` **errors** for `AppOpsManager.unsafeCheckOpNoThrow` on `minSdk 26` were fixed by
branching to `checkOpNoThrow` on API 26–28.

> ⚠️ / **NOT TESTED** entries must not be reported as passing. A green CI/local build only
> proves the code compiles and the JVM unit tests pass; it does **not** prove the apps
> start, the foreground service survives, or the permission flows work on a real
> Android 16 device.

---

## 10. Licence

MIT — see [LICENSE](LICENSE).
Cloud Monitor backend deployment setup

