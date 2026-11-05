# Troubleshooting

This document lists the known failure modes of Cloud Monitor (`com.cloudmonitor`) and
Cloud Alert (`com.cloudalert`), why they happen, and what to do. It assumes Android 16
(API 36) but most items also apply to Android 12–15.

> **Security first:** none of the steps below require turning off Play Protect, the screen
> lock, device encryption, or SELinux. Granting a permission or exempting **one app** from
> battery optimisation is not the same as disabling an Android security protection. If some
> guide elsewhere tells you to disable a protection, check with the project administrator
> first.

> **Never paste API keys, FCM tokens, pairing codes, service-account JSON, or keystore
> passwords into a log, a ticket, or this repository.** Logs produced by the apps contain
> only non-secret metadata (event type, event id, error class names).

---

## 1. Cloud Alert crashes immediately on opening

### Symptoms
The app closes as soon as it is launched; `logcat` shows a Hilt/ViewModel creation error.

### Cause (verified in source, fixed)
`AlertViewModel` had an `@Inject constructor(...)` but was **missing**
`@HiltViewModel`, while `MainActivity` created it with `hiltViewModel()`. Hilt does not
generate a ViewModel factory binding for a ViewModel without the annotation, so Compose
could not create it and the app crashed at startup.

Other startup paths were checked before changing anything: `@HiltAndroidApp` is present in
`AlertApplication`, `MainActivity` is `@AndroidEntryPoint`, and all constructor
dependencies (`AlertRepository`, `AlertPreferences`, `AlertEventDao`, `FcmGateway`) are
provided in `di/AppModule.kt`.

### Fix
`@HiltViewModel` was added to `AlertViewModel`. The ViewModel launch bodies were also made
non-crashing: a failing refresh/register/pair or a Keystore error now produces a readable
message instead of an uncaught exception.

### What you can do
Update to a build that contains the fix. If it still crashes, capture `logcat` and hand it
to the administrator — but redact any key or token first.

---

## 2. "Monitor started" is not shown / the service will not start

### Symptoms
Tapping **Start** shows an error snackbar instead of a success message, or the status stays
**Monitor is stopped**.

### Cause
Android 12+ forbids starting a foreground service from the background
(`ForegroundServiceStartNotAllowedException`), and Android 14+ requires a declared
foreground service type. Android 16 additionally applies JobScheduler/WorkManager quotas
to work started from a foreground service.

Previously `start()` swallowed the exception and the UI always showed "Monitor started" —
a **false success**. This was fixed: the service now returns `StartOutcome` and the
ViewModel waits for the real `isRunning` flag before showing success.

### What you can do
1. **Open Cloud Monitor and tap Start / Restart.** A user-visible Activity start is an
   allowed path; a background watchdog restart is not.
2. If the message mentions **notifications**, grant **Post notifications** first (section 3).
3. Check the exact message shown — it distinguishes:
   - *background start not allowed* → open the app and start manually,
   - *notification permission missing* → grant it,
   - *security / foreground service type* → the APK/manifest is wrong, report it.
4. Set the app's battery mode to **Unrestricted**
   (Settings → Apps → Cloud Monitor → Battery).
5. On Xiaomi / Huawei / Oppo / Vivo / Samsung / OnePlus / Asus, also enable **Autostart**
   / **Protected apps** for Cloud Monitor.

### After a reboot or an APK update
`BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` are exempt from the
background-start restriction, so `BootReceiver` and `PackageReplacedReceiver` may restart
the service. The receivers now log a warning when Android refuses. If it was refused, open
the app and tap Start.

### Watchdog limitation (important)
The WorkManager `WatchdogWorker` runs every ~15 minutes but is **not** a background-start
exemption. On Android 16 it also competes for job quota. It reports a refused restart in
the log and returns success (to avoid burning quota with endless retries). **Do not rely on
it to revive the service** — open the app.

---

## 3. No notifications / no alerts

### Symptoms
The monitor runs but you see no ongoing notification; Cloud Alert shows no outage push.

### Causes and fixes
- **`POST_NOTIFICATIONS` not granted (Android 13+ / 16).** Both apps declare it and now
  request it at runtime when the app is opened. If you chose *"Don't ask again"*, the
  runtime dialog will not appear again — use **App notification settings**
  (`Settings.ACTION_APP_NOTIFICATION_SETTINGS`) from the permissions/settings screen.
- **Channel muted.** Open Settings → Apps → the app → Notifications. Check the
  **"Cloud alerts"** channel (raisable importance) and the silent **"Monitor service"** /
  **"Alert service"** channel. The service notification is intentionally silent and
  ongoing; that is an Android requirement for a foreground service.
- **Firebase not configured.** Push is optional. Without `google-services.json` the app
  still builds and Cloud Alert falls back to polling; the backend still stores events.
- **FCM payload is not logged.** Since the fix, only `type` and `eventId` are logged, so
  you will not see message bodies in `logcat` — that is intentional.

---

## 4. A permission stays "MISSING"

### Special permissions have no runtime dialog
Notification access, Usage access and Accessibility must be enabled directly in system
Settings. The app opens the correct screen for each:

| Permission | Settings screen opened by the app |
|------------|-----------------------------------|
| Notification access | `ACTION_NOTIFICATION_LISTENER_SETTINGS` |
| Usage access | `ACTION_USAGE_ACCESS_SETTINGS` |
| Accessibility | `ACTION_ACCESSIBILITY_SETTINGS` |
| Battery optimisation | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| App notifications | `ACTION_APP_NOTIFICATION_SETTINGS` |
| OEM autostart | manufacturer-specific screen, only when detected |

### What you can do
1. Tap the row's **Open settings** button, enable the toggle, go back.
2. Tap **Check again / Refresh** — special-permission state sometimes takes a moment to
   propagate (`AppOpsManager`, `NotificationManager.getEnabledNotificationListeners()`).
3. The app never crashes on a missing permission; the onboarding screen simply keeps
   showing **MISSING**.
4. Do **not** grant Accessibility unless you need ANR evidence (`E-204`). Without it an ANR
   is still reported as `E-101` with low confidence.

---

## 5. Monitoring stops when the screen is off / after a while

### Causes
Doze, battery optimisation, OEM background killing, or Android killing a foreground
service whose start was refused.

### What you can do
- Allow **Ignore battery optimisations** for Cloud Monitor (or set Battery →
  **Unrestricted**).
- Enable the OEM **Autostart / Protected apps** toggle.
- Keep the app updated: `MY_PACKAGE_REPLACED` restarts the service after an in-place
  update, but `BOOT_COMPLETED` is **not** sent on an update.
- Check `adb shell dumpsys activity services com.cloudmonitor` (section 9) to see whether
  the service is actually running.
- Remember that a queued event is retried when the Internet returns — check **Queued
  events** on the Dashboard.

---

## 6. Root / detection tier problems

### Symptoms
Dashboard shows `tier 1 (no privileges)` on a rooted phone, or **Root check** says the
`su` binary was found but root was not granted.

### Cause (verified in source, fixed)
The old `detectRoot()` ran `runShellBlocking("id")` while `rootAvailable` was still
`null`, so it never actually executed `su`; the `isAvailable` getter also did blocking I/O
on whichever thread read it. Root detection was unreliable and could stall the main thread.

### Current behaviour
`RootProcessProbe.refreshRootStatus()` is an explicit suspend function on `Dispatchers.IO`
with a `Mutex`, process timeouts, and output draining. It checks common `su` locations,
runs `sh -c id` and `su -c id`, and reports a `RootStatus` with three separate facts:

- `suBinaryFound` — a `su` binary exists,
- `rootGranted` — `id` actually returned `uid=0`,
- `detail` — a human-readable explanation.

**A `su` file alone is never treated as proof of root access.** A rooted device and an app
that has been *granted* root are different things: the root manager (Magisk / KernelSU)
must approve this app.

### What you can do
- Open **Dashboard → Root check** for the exact reason.
- In your root manager, grant Cloud Monitor root/superuser access, then restart monitoring.
- If root is unavailable, nothing breaks — tier 1 detection (notification listener +
  usage access) keeps working.

---

## 7. API key cannot be saved / "device encryption key unavailable"

### Symptoms
Saving the API key shows a message about the device encryption key being unavailable; or
after an OS update / screen-lock change the app behaves as if no key is configured.

### Cause
Android Keystore keys can be **permanently invalidated** when the secure lock screen
changes or a secure hardware key becomes unusable. Previously `decrypt()` used
`runCatching {}.getOrNull()` and the preferences used `.orEmpty()`, so an invalidated key
silently looked like "no key set", and `encrypt()` could throw into the settings screen.

### Current behaviour
Both `KeystoreCipher` implementations detect `KeyPermanentlyInvalidatedException`, delete
the unusable key alias, and `encrypt()` retries once with a fresh key. The ViewModels catch
persistence errors and show a readable message instead of crashing.

### What you can do
1. Re-enter the API key in **Settings** and save it again.
2. If it keeps failing: Settings → Apps → the app → Storage → **Clear data**, then set the
   key up again. Clearing data removes the stale encrypted value; you will need to
   re-enter the API key, watched apps, and pairing.
3. Never back up or share the keystore file or the API key.

---

## 8. Pairing code rejected / "too many attempts"

### Symptoms
Cloud Alert cannot pair with a cloud phone, or the backend returns HTTP 429.

### Causes and fixes
- **Expired code.** The 6-digit code expires after **30 minutes**. Tap **New code** on the
  cloud phone and try again.
- **Wrong key.** Cloud Alert needs the **alert** key and Cloud Monitor needs the
  **monitor** key. They are different values with different permissions.
- **Rate limit (429).** The `/api/register` endpoint now allows at most 10 pairing attempts
  per alert device per 10-minute window and returns 429 when exceeded. Wait for the window
  to reset instead of retrying in a loop.
- **Clock / network.** Confirm both phones have Internet and the Cloudflare server is up.
- Codes are generated with `SecureRandom` (not a predictable PRNG) and compared in
  constant time, so guessing is not a viable workaround — do not try.

---

## 9. API / backend failures

### Symptoms
Events stay queued, status is `STALE`, or `E-HB-01` (heartbeat missing) is raised.

### What you can do
- Check the phone's Internet connection; queued events are flushed on reconnect.
- Verify the API key in **Settings** and that it matches the deployment's
  `MONITOR_API_KEY` / `ALERT_API_KEY` Wrangler secrets. Keys are **never** stored in source
  or docs — only placeholders exist in `.env.example` / `.dev.vars.example`.
- Confirm the Cloudflare Pages Functions and the cron Worker are deployed and share the
  **same** KV namespace id.
- Heartbeat bucketing and the per-device KV layout are explained in
  `docs/ARCHITECTURE.md`; error codes are in `docs/ERROR_CODES.md`.
- Network calls have explicit timeouts (connect 10 s, request/socket 20 s). A failed call
  becomes a queued event or a readable error, not a crash.

### Manual cron trigger
Prefer the header form — a secret in a URL query string can leak into logs, history, and
`Referer`:

```bash
curl -H "x-cron-secret: <CRON_SECRET>" \
  "https://cloud-monitor-cron.<account>.workers.dev/"
```

The legacy `x-api-key` header and `?key=` parameter still work but are discouraged.
Comparison uses a constant-time helper. Rotate `CRON_SECRET` if a URL containing it was
ever shared.

---

## 10. Android 16 background restrictions, battery optimisation and OEM limits

### What the platform actually does

| Rule | Applies to | Consequence for Cloud Monitor |
|------|------------|-------------------------------|
| Apps targeting API 31+ cannot start a foreground service (FGS) from the background unless exempt | Android 12+ | A refusal throws `ForegroundServiceStartNotAllowedException`. `MonitorForegroundService.start()` now returns a `StartOutcome` with a readable reason; the UI never shows "Monitor started" when `startForeground()` failed. |
| Legal exemptions used here | Android 12+ | `ACTION_BOOT_COMPLETED`, `ACTION_LOCKED_BOOT_COMPLETED`, `ACTION_MY_PACKAGE_REPLACED` in a broadcast receiver, a user-visible Activity (open the app → Start), high-priority FCM, exact alarms, and **the user turning off battery optimisation for the app**. |
| `WorkManager` is **not** an exemption | Android 12+ | `WatchdogWorker` (every 15 min) records a refused restart and returns success instead of retrying forever. **Open the app and tap Start** to revive the service. |
| FGS must declare a type + type-specific permission | Android 14+ | `foregroundServiceType="specialUse"`, `FOREGROUND_SERVICE_SPECIAL_USE`, and a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` justification. The 3-arg `startForeground()` is used only on API 34+ (passing `specialUse` on API 29–33 throws). |
| `BOOT_COMPLETED` may not launch `dataSync` / `mediaProcessing` FGS | Android 15+ | This app uses `specialUse`, which is not restricted. The start result is checked anyway. |
| Jobs started from an FGS obey JobScheduler/WorkManager runtime quotas | Android 16, all apps | The 15-minute watchdog is quota-bound and is a safety net, not a restart guarantee. |
| Edge-to-edge is mandatory (no opt-out), predictive back on by default, orientation/resizability ignored on large screens (≥600dp) | targetSdk 36 | The apps use `enableEdgeToEdge()` + `Scaffold`, declare `enableOnBackInvokedCallback`, and never lock orientation. |
| Safer Intents (`intentMatchingFlags`) and Local Network Protection are opt-in | Android 16 | Not enabled; no cross-app implicit intents or LAN sockets. Re-evaluate when they become mandatory. |
| 16 KB page-size devices | Android 15/16 | Native library alignment must be checked on a real device — see Known limitations. |

### Battery optimisation (per-app, not a security bypass)

Use the **Allow** button on the Cloud Monitor permissions screen (it opens
`Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for this one package), or
Settings → Apps → Cloud Monitor → Battery → **Unrestricted**. This exempts only this app
from Doze; it does **not** disable Play Protect, verified boot, SELinux, the screen lock or
device encryption. It is justified here because a monitoring service must stay alive while
the screen is off; without it Android may freeze the service and Cloud Alert will report
`E-HB-01`.

```bash
# Test only; the UI path is preferred.
adb shell dumpsys deviceidle whitelist +com.cloudmonitor
adb shell dumpsys deviceidle whitelist | grep cloudmonitor
```

### Manufacturer-specific background killing

Xiaomi (MIUI), Huawei, Oppo, Vivo, Samsung, OnePlus and Asus kill background services from
their own managers, independently of Android. Enable Cloud Monitor in:

- **Xiaomi**: Security → Permissions → Autostart; Battery → No restrictions
- **Huawei**: Phone Manager → Protected apps / Launch manager
- **Oppo / OnePlus**: Settings → Battery → Allow background running
- **Vivo**: iManager → Permission manager → Autostart
- **Samsung**: Settings → Apps → Cloud Monitor → Battery → Unrestricted; remove from
  "Sleeping apps"

The Cloud Monitor permissions screen shows a vendor tip when such a screen is detected. Do
not use "RAM cleaner" apps or `am force-stop` — both break `START_STICKY` and the watchdog.

### Do **not** do this

- Do not disable Play Protect / app verification, the screen lock, device encryption,
  SELinux or verified boot to "fix" a background restriction. None is required.
- Do not install an APK from an untrusted source just because installation was blocked;
  ask the administrator to check the signing certificate instead.
- Do not retry a rate-limited pairing code in a loop (see section 8).

---

## 11. App was updated and now behaves differently

- DataStore keys are stable, so the API key, label and watched packages survive.
- Room uses explicit migrations and no destructive fallback, so the outbox and history
  survive.
- `BOOT_COMPLETED` is **not** delivered on an update — `MY_PACKAGE_REPLACED` handles the
  restart. `BOOT_COMPLETED` is also not delivered on first install.
- `versionCode` is `git rev-list --count HEAD`; a build made outside a git checkout uses
  the fallback from `version.properties` and **is not guaranteed** to update in place.
- If a release key changes, an APK can never be installed over an already published one.
  Keep the release keystore backed up outside the repository.

---

## 12. Build / development problems

| Problem | Fix |
|---------|-----|
| `Could not compile initialization script '/root/.gradle/init.gradle'` | A broken global Gradle init script (unquoted `https://` URL, `mavelCentral()` typo). It also conflicts with `RepositoriesMode.FAIL_ON_PROJECT_REPOS`. Remove/fix that machine-level file; the repository itself is fine. |
| `SDK location not found` | Create `local.properties` with `sdk.dir=/path/to/android-sdk`, or set `ANDROID_HOME`. |
| `compileSdk`/`targetSdk` mismatch | `targetSdk` is **36** (Android 16, the runtime target) but `compileSdk` must be **37**: the pinned AndroidX / Compose / Ktor releases declare `minCompileSdk 37` in their AAR metadata. Install SDK platforms `android-37.0` + `android-37.2` and build-tools `37.0.0`. `gradle.properties` keeps `android.suppressUnsupportedCompileSdk=37` because AGP 9.4.1 does not yet officially support API 37. |
| `checkDebugAarMetadata FAILED: … requires … compile against version 37` | Do **not** lower `compileSdk` to 36 — that is what triggers this error. Keep `compileSdk = 37` and install the API 37 platforms. `targetSdk` stays 36, so Android 16 remains the runtime target. |
| Unsupported Gradle/JDK | Gradle 9.8.2, AGP 9.4.1, Kotlin 2.4.21; JDK 17 or newer. |
| `google-services.json` missing | Expected. The Google Services plugin is applied only when the file exists; the build still succeeds and FCM is disabled with a warning. |
| Hilt / KSP errors after editing a ViewModel | Ensure `@HiltViewModel` + `@Inject constructor` and `@AndroidEntryPoint` on the Activity; clean and rebuild. |

Standard checks:

```bash
./gradlew clean
./gradlew assembleDebug
./gradlew test
./gradlew lint

cd cloudflare
npm ci
npm run typecheck
npm test
```

---

## 12. Collecting diagnostics for the administrator

Include:

1. App name and version (Dashboard → Version), phone model, Android version.
2. The exact on-screen message (or a screenshot).
3. **Permissions required** screen status, **Root check**, **Detection tier**, and
   **Queued events** values.
4. Whether the phone had just rebooted, been updated, or lost power/Internet.
5. Relevant `logcat` lines (see tags below), with **all secrets removed**.

Useful log tags:

```bash
adb logcat -s MonitorRuntime:D DeathDetector:D MonitorForegroundService:D \
  MonitorActivity:D BootReceiver:D WatchdogWorker:D AlertViewModel:D \
  MonitorNotificationListener:D EventRepository:D
```

Do **not** attach: API keys, FCM tokens, pairing codes, `google-services.json`, the service
account JSON, the release keystore, or its passwords.

---

## 13. Known limitations (not bugs)

- **No device/emulator runtime verification yet.** Builds, JVM unit tests, lint and the
  Cloudflare unit tests can be run in CI; behaviour that needs a real device (foreground
  service start/stop under Android 16, Hilt startup at runtime, permission dialogs, root on
  a real rooted device, 16 KB page-size devices, FCM delivery, OEM autostart) must be
  checked on hardware. See the README's "Testing status" section.
- **Background restart is best-effort by design.** Android 12+ / 16 restrictions mean the
  watchdog cannot guarantee a restart; opening the app and tapping Start is the reliable
  path.
- **Tier 2 detection needs granted root.** Tier 1 works everywhere.
- **Push is optional.** Without Firebase, Cloud Alert polls.
- **16 KB page alignment** of any native library must be verified on a real Android 15/16
  device; it cannot be proven by a JVM build.
