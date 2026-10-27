# Android permissions

JARVIS requests only what its implemented features need. This table reflects
`app/src/main/AndroidManifest.xml`. Behavior on the Redmi Note 10S has **not yet been
verified** — see [TEST_PLAN.md](TEST_PLAN.md).

## 1. Declared permissions

| Permission | Type | Why | When it is requested | How to revoke |
|---|---|---|---|---|
| `RECORD_AUDIO` | Runtime (dangerous) | Hear the wake phrase and your command. Audio stays in memory, never saved or uploaded. | Only when you tap **Start Listening** or **Tap to talk** for the first time (never during onboarding). If denied, status shows "Microphone permission required" with an **Open app settings** button. | Settings → Apps → Manage apps → JARVIS → App permissions → Microphone → Don't allow. Android stops the app process when you revoke it. |
| `POST_NOTIFICATIONS` | Runtime on Android 13+ only | Show the "listening" notification (with Stop/Talk) and reminder notifications. | Together with the microphone on Start, **only on Android 13+**. On Android 12 (Redmi Note 10S) it is granted at install; app notifications can be turned off in Settings. Denial is tolerated: listening still works, but the notification may be hidden from the shade (Android still shows the foreground-service indicator) and reminders cannot be shown. | Settings → Apps → JARVIS → Notifications. |
| `FOREGROUND_SERVICE` | Normal (install-time) | Run listening as a visible foreground service. | Automatic at install. | Not user-revocable; stop the service with **Stop**. |
| `FOREGROUND_SERVICE_MICROPHONE` | Normal (install-time) | Required on Android 14+ for a foreground service of type `microphone` (declared on `JarvisForegroundService`). Harmless on older versions. | Automatic at install. | Not user-revocable; revoking `RECORD_AUDIO` disables it. |
| `INTERNET` | Normal (install-time) | **Only** for the one-time, user-initiated download of the Vosk speech model from `alphacephei.com`. Audio, text and notifications are never sent anywhere. | Automatic at install; used only when you tap **Download** on the Speech model screen. Use **Import** instead if you prefer the app never to go online. | Not user-revocable on stock Android. MIUI: Security app → Data usage / Network → restrict JARVIS (Wi-Fi and mobile data). |

`<uses-feature android:name="android.hardware.microphone" android:required="true">`
is also declared (affects store filtering only).

## 2. Components that need user enablement

| Component | What it is | How to enable / disable |
|---|---|---|
| `JarvisNotificationListenerService` (protected by `BIND_NOTIFICATION_LISTENER_SERVICE`) | Optional. Lets "read my notifications" speak the newest notification. Keeps at most the latest 5 (title/text, max 300 chars each) **in memory only**; never logged, saved or uploaded; cleared when access is turned off. | Not requested automatically. Say "open notification access" or use Settings → Apps → Special app access → Notification access → JARVIS. When access is off, JARVIS offers to open that page. |
| `ReminderReceiver` (not exported) | Shows reminder notifications set by voice. Uses inexact `AlarmManager.set(ELAPSED_REALTIME_WAKEUP)` — no exact-alarm permission. | Nothing to enable. Reminders are lost on reboot. |

## 3. Package visibility (`<queries>`)

Instead of `QUERY_ALL_PACKAGES`, the manifest declares only:
- apps with a `MAIN`/`LAUNCHER` activity — needed for "open WhatsApp" etc.;
- `android.intent.action.TTS_SERVICE` — to find installed Text-to-Speech engines.

## 4. Deliberately NOT requested

- Contacts, SMS, call log, phone state, location, camera, body sensors.
- Accessibility service.
- `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`.
- `QUERY_ALL_PACKAGES`.
- `RECEIVE_BOOT_COMPLETED` (no auto-start after reboot).
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SYSTEM_ALERT_WINDOW`.
- Root, device administrator, VPN, usage access.
- Storage permissions: model import uses the system file picker
  (Storage Access Framework, `ACTION_OPEN_DOCUMENT`), which grants access to only the file you pick.

Installing the APK manually (sideloading) does **not** bypass any runtime permission prompt.

## 5. Background behavior and limits

- Listening starts **only from a visible user action** (Start Listening / Tap to talk, or
  the notification's Talk/Stop buttons while the service is already running). Android 12+
  restricts starting foreground services from the background, and Android 14+ only lets a
  microphone foreground service capture audio if it was started while the app was visible.
- The service is `START_NOT_STICKY`: if the system kills it, it does **not** restart by itself.
- It does not survive **force-stop** (Settings → Force stop, or MIUI "clear all" with some settings).
- It does not start after **reboot**.
- Locked-screen behavior has **not been tested** yet; the expected behavior is that
  capture continues while the foreground service runs, but this must be recorded on the Redmi.

### MIUI (Redmi Note 10S) — optional, OS-version dependent

MIUI's battery management may stop background apps more aggressively than stock Android.
If listening stops unexpectedly while the screen is off, you may try (menu names vary):

1. Settings → Apps → Manage apps → JARVIS → **Battery saver → No restrictions**.
2. Settings → Apps → Manage apps → JARVIS → **Autostart** → on (only allows MIUI to keep/relaunch
   the app; JARVIS still never auto-starts listening).
3. In the Recent apps screen, long-press JARVIS → **Lock** (padlock).

These are optional and not guaranteed. They increase battery use. Nothing in the app
requires them.
