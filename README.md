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

* [Architecture and design decisions](docs/ARCHITECTURE.md)
* [Error codes](docs/ERROR_CODES.md)

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
├── docs/                   ARCHITECTURE.md, ERROR_CODES.md
├── .github/workflows/      android.yml, cloudflare.yml
├── gradle/libs.versions.toml
└── version.properties
```

---

## 1. Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 17 | AGP 9.x requires JDK 17 or newer |
| Android SDK | platform 37 + build-tools 37 | `compileSdk`/`targetSdk` = 37 (Android 17) |
| Node.js | 22 | for the backend |
| `gh` | any | optional, for creating the repo and setting secrets |

---

## 2. Android setup

```bash
git clone https://github.com/<owner>/cloud-monitor-system.git
cd cloud-monitor-system

# Point Gradle at your SDK (or set ANDROID_HOME)
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew assembleRelease      # both apps
./gradlew test                 # unit tests
```

### Build configuration

| Flag | Default | Meaning |
|------|---------|---------|
| `-PbackendBaseUrl=https://…` | `https://cloud-monitor-api.pages.dev` | the backend the apps talk to. The environment variable `BACKEND_BASE_URL` works too. |

The backend URL is baked into `BuildConfig.BACKEND_BASE_URL` at build time. It can also be
changed at runtime in the app's settings screen (the API key lives there).

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

### 3.4 Deploy

```bash
cd cloudflare

npm run typecheck        # tsc --noEmit
npm test                 # unit tests
npm run dry-run:worker   # wrangler deploy --dry-run (cron Worker)
npm run dry-run:pages    # wrangler pages deploy --dry-run (Pages project)

npm run deploy:worker    # cron Worker
npm run deploy:pages     # Pages project
```

Local development:

```bash
cp .dev.vars.example .dev.vars   # fill in the keys
npx wrangler pages dev dist
```

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
6. The dashboard shows the **6-digit pairing code** and the device id. It expires after 15
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
- [ ] The pairing code is not written down anywhere permanent (it expires after 15 minutes).

---

## 8. Licence

MIT — see [LICENSE](LICENSE).
