# User Guide (English)

This guide is for people who are new to the project. You do **not** need to know anything
about programming to use the apps.

---

## 1. What are Cloud Monitor and Cloud Alert?

This project has **two different Android apps**. They are installed on **two different
phones**.

| App | Installed on | What it does |
|-----|--------------|--------------|
| **Cloud Monitor** (`com.cloudmonitor`) | The "cloud" phone — the phone you want to watch (for example, a phone that runs Discord all the time) | Watches the apps you choose and Discord's screen-share notification. It sends a status report (heartbeat) and events to the server. |
| **Cloud Alert** (`com.cloudalert`) | Your personal phone | Shows the state of every cloud phone and warns you (notification) when a watched app stops or a screen share ends. |

In the middle there is a **server (backend)** on Cloudflare. Both apps talk to it over the
Internet. Firebase is used **only** to deliver push notifications — your status data is
**not** stored in Firebase.

```
[Cloud phone: Cloud Monitor] --Internet--> [Cloudflare server] <--Internet-- [Your phone: Cloud Alert]
```

> **Important:** Cloud Alert does **not** read your personal notifications, does not need
> Usage Access and does not need Accessibility. It only needs the notification permission
> and an API key.

---

## 2. Installation

1. Get the APK files from the project administrator (or from a `assembleDebug` build /
   GitHub Release).
2. There are **two APKs**: one for Cloud Monitor, one for Cloud Alert. Make sure you install
   each one on the correct phone.
3. On the phone, open the APK file. If Android asks, choose **"Install anyway" / "Allow from
   this source"** only for a source you trust. This is a direct install (sideload), not from
   Google Play.
4. After installing, do not delete the APK until you are sure the next update works.

**Minimum requirement:** Android 8.0 (API 26) or newer. The main target of the project is
**Android 16 (API 36)**.

> Never switch off Android security features (for example *Verify apps*, *Play Protect*, or
> the screen lock) just to install this app. If installation is blocked, ask the project
> administrator to check the APK signature.

---

## 3. First-time setup

### 3.1 On the cloud phone (Cloud Monitor)

1. Open **Cloud Monitor**.
2. You will see the **"Permissions required"** screen. Complete them one by one (see
   section 4).
3. Go to **Settings**, paste the **monitor API key** given by the administrator, then tap
   **Save key**. The key is stored encrypted using Android Keystore.
4. Go to **Choose apps** and select the apps you want to watch.
5. (Optional) To watch a Discord screen share: start a screen share, open the
   **Notification inspector**, then tap **"Use as Discord target"** on the matching
   notification.
6. On the **Dashboard**, note the **6-digit pairing code**. It expires after **30 minutes**.
   Tap **New code** if you need a fresh one.

### 3.2 On your personal phone (Cloud Alert)

1. Open **Cloud Alert**.
2. If there is a banner **"Alerts are hidden"**, tap **Allow notifications** and allow it.
   (See section 4.)
3. Go to **Settings**, paste the **alert API key** (a **different** key from the monitor
   key), tap **Save key**, then **Register**.
4. Tap **Pair** and enter the 6-digit code shown on the cloud phone.
5. Repeat for every cloud phone. Cloud Alert can watch several phones at once.

> **Never share API keys or pairing codes in the repository, logs, or documents.** The
> pairing code is valid for 30 minutes only.

---

## 4. What each permission is for

### Cloud Monitor

| Permission | How to grant | Why it is needed |
|------------|--------------|------------------|
| **Notification access** | Settings → Notifications → *Device & app notifications* → Cloud Monitor → turn on | The main way to detect when an app's ongoing notification disappears, and when Discord's screen-share notification appears/disappears. Android has no runtime dialog for this; it must be enabled in Settings. |
| **Usage access** | Settings → Apps → *Special app access* → *Usage access* → Cloud Monitor | To know when an app moves from foreground to background. |
| **Ignore battery optimisations** | The **Allow** button on the permissions screen | So the service is not "frozen" while the screen is off / in Doze mode. This does **not** turn off Android security; it only exempts this one app from Doze. |
| **Post notifications** | The **Allow** button (Android 13+) | For the ongoing "monitor is running" notification. If you chose *"Don't ask again"*, use the **App notification settings** button. |
| **Accessibility** (optional) | Settings → Accessibility → *Cloud Monitor ANR watcher* | Only to detect "app not responding" (ANR) dialogs. Without it, an ANR is reported as `E-101` with low confidence. **Safe to leave off.** |
| **OEM autostart** (Xiaomi, Huawei, Oppo, Vivo, Samsung, OnePlus, Asus) | Manufacturer settings → *Auto start* / *Protected apps* | Some manufacturers kill background services separately from Android. |

### Cloud Alert

| Permission | Why it is needed |
|------------|------------------|
| **Post notifications** (Android 13+ / Android 16) | So outage alerts can be shown. It is requested only when the app is open and the permission is still missing. |
| Internet | To talk to the server. |

Cloud Alert does **not** ask for Notification access, Usage access, Accessibility, location,
camera, or microphone.

### If a permission is denied

- The app will **not crash**. The permissions screen keeps showing **MISSING**.
- If a permission is denied permanently (*"Don't ask again"*), use **Open settings** /
  **App notification settings** to open the correct settings page.
- Special permissions (Notification access, Usage access, Accessibility) have no runtime
  dialog — they must be switched on directly in system Settings.

---

## 5. Starting and stopping monitoring

- **Start:** Cloud Monitor tries to start the service automatically when the app is opened.
  You can also tap **Start / Restart** on the Dashboard.
- **Stop:** tap **Stop** on the Dashboard.
- **Heartbeat now:** sends one status report immediately.

> **Important:** The app shows **"Monitor started"** only after the service has really
> entered the foreground. If Android blocks it, a clear error message is shown (snackbar) —
> not a fake success message.

After the phone **reboots** or after an **APK update**, the service tries to restart itself
automatically.

---

## 6. Checking the app status

On the **Cloud Monitor Dashboard** you will see:

- **Monitor is running / Monitor is stopped** — the real service state.
- **Device id** — the fixed identity of this cloud phone. If it says *"not initialised yet"*,
  wait a few seconds or tap Start.
- **Version** — the Cloud Monitor version.
- **Detection tier** — `tier 2 (privileged)` if root access is granted, usually
  `tier 1 (no privileges)`.
- **Root check** — a short explanation of the root check result (see section 7).
- **Queued events** — events not yet delivered (they are retried when the Internet returns).
- **Pairing code** and the **Watched apps** list.

On the **Cloud Alert Dashboard** you will see each cloud phone with its state
`ONLINE`, `OFFLINE` or `STALE`, its Cloud Monitor version, and the number of open
incidents. The **History** page can be filtered by error code.

---

## 7. Notification setup

1. Make sure **Post notifications** is on (section 4).
2. In Settings → Apps → Cloud Alert/Cloud Monitor → Notifications, make sure the
   **"Cloud alerts"** channel (alerts) and the **"Monitor service"** / **"Alert service"**
   channel (status) are not silenced.
3. The service status notification is deliberately silent and ongoing — Android requires
   this for a foreground service; it is not an advert.
4. If you want alerts to make a sound, raise the importance of the **"Cloud alerts"**
   channel in Settings.

**Root (if needed):** Cloud Monitor can use a higher detection tier (*tier 2*) only if the
device is rooted **and** this app has been granted root access by the root manager
(Magisk/KernelSU). The mere presence of a `su` file is **not** treated as root access.
If root is not detected, the app keeps working with *tier 1*. See
`docs/TROUBLESHOOTING.md`.

---

## 8. Android 16 (API 36) compatibility notes

- Both apps now target **targetSdk 36** (Android 16). `compileSdk` is 37 because the
  bundled AndroidX / Compose libraries require it; this affects compilation only, not the
  runtime behaviour your phone applies to the app.
- **Edge-to-edge:** on Android 16 an app can no longer opt out of edge-to-edge display.
  These apps already use `enableEdgeToEdge()` and `Scaffold`, so content does not overlap
  the status bar / navigation bar.
- **Predictive back:** the system back animation is on by default. The apps use compatible
  Compose navigation.
- **Notification permission:** Android 13+ (including 16) requires `POST_NOTIFICATIONS`.
  Without it, alerts are hidden by the system.
- **Background start restrictions (Android 12+):** Android can refuse to start a foreground
  service from the background. `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` are exempt, but a
  *watchdog* (WorkManager) is **not**. So if the service stops, **open the app and tap
  Start** — that is an allowed action. Setting the battery mode to **Unrestricted** also
  helps.
- **Android 16 background work quota:** work scheduled from a foreground service is now
  subject to JobScheduler/WorkManager quotas. The watchdog runs every 15 minutes and cannot
  be relied on to restart the service from the background.
- **Service type:** the service is declared as `specialUse` with a justification, plus the
  `FOREGROUND_SERVICE_SPECIAL_USE` permission (an Android 14+ requirement).
- **16 KB page size:** some Android 15/16 devices need native libraries aligned to 16 KB.
  This must be checked on a real device (see "Known limitations" in the README).
- **Safer Intents / Local Network Protection** are opt-in features on Android 16 and do not
  affect these apps at present.

> Do not turn off *Play Protect*, the screen lock, or device encryption to "fix" Android 16
> restrictions. The recommended steps are only to grant the permissions that are genuinely
> needed and to set the battery mode to Unrestricted for this app alone.

---

## 9. Common problems and quick fixes

| Problem | Quick fix |
|---------|-----------|
| App keeps crashing when opened | Make sure you are using the correct APK and the latest version. See `docs/TROUBLESHOOTING.md`. |
| Cloud Alert fails to open | Older builds had a bug in `AlertViewModel` (missing `@HiltViewModel`). Update to the fixed build. |
| No alert notifications | Turn on **Post notifications** and check the "Cloud alerts" channel. |
| Monitoring stops when the screen turns off | Allow **Ignore battery optimisations** and turn on *Autostart* on OEM phones. |
| Status still "MISSING" after granting | Tap **Check again**; special permissions sometimes take time to sync. |
| Root not detected | Check **Root check** on the Dashboard. A `su` file alone is not enough — grant root to this app. |
| API connection fails | Check the Internet, the API key, and whether the Cloudflare server is running. |
| Pairing code not accepted | The code expires after 30 minutes; tap **New code** on the cloud phone. |

The full list is in **[docs/TROUBLESHOOTING.md](TROUBLESHOOTING.md)**.

---

## 10. Contacting the project administrator

Please contact your **project administrator / repository manager** (the owner of the
`cloud-monitor-system` repository) through the channel used by your organisation.

Include the following so the problem can be solved quickly:

1. App name (Cloud Monitor or Cloud Alert) and version (Dashboard → Version).
2. Phone model and Android version (Settings → About phone).
3. The exact error message shown (or a screenshot).
4. Permission status on the *Permissions required* screen.
5. The **Root check** and **Detection tier** values (for Cloud Monitor).
6. The time it happened, and whether the phone had just rebooted / been updated.

**Do not** send API keys, FCM tokens, passwords, or any secret in a report, log, or ticket.
Keys and tokens are never included in the project source or documentation.
