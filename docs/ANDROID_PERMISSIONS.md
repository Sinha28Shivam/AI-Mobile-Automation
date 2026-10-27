# Android permissions

JARVIS requests only what its implemented features need. This table reflects
`app/src/main/AndroidManifest.xml` (checked 2026-10-27). On the Redmi Note 10S (Android 13,
MIUI V140, debug build), granting the microphone, starting listening and stopping it were
checked by hand. Denying the microphone was checked on an Android 12 emulator. Everything
else here has **not yet been verified** on the device; see [TEST_PLAN.md](TEST_PLAN.md).

## 1. Declared permissions

| Permission | Type | Why | When it is requested | How to revoke |
|---|---|---|---|---|
| `RECORD_AUDIO` | Runtime (dangerous) | Hear the wake phrase and your command. Audio stays in memory, never saved or uploaded. | Only when you tap **Start Listening** or **Tap to talk** for the first time (never during onboarding). If denied, status shows "Microphone permission required" with an **Open app settings** button. | Settings → Apps → Manage apps → JARVIS → App permissions → Microphone → Don't allow. Android stops the app process when you revoke it. |
| `POST_NOTIFICATIONS` | Runtime on Android 13+ only | Show the "listening" notification (with Stop/Talk) and reminder notifications. | Together with the microphone on Start, **only on Android 13+**. On Android 12 it is granted at install (the Redmi under test runs Android 13, so it is prompted there); app notifications can be turned off in Settings. Denial is tolerated: listening still works, but the notification may be hidden from the shade (Android still shows the foreground-service indicator) and reminders cannot be shown. | Settings → Apps → JARVIS → Notifications. |
| `FOREGROUND_SERVICE` | Normal (install-time) | Run listening as a visible foreground service. | Automatic at install. | Not user-revocable; stop the service with **Stop**. |
| `FOREGROUND_SERVICE_MICROPHONE` | Normal (install-time) | Required on Android 14+ for a foreground service of type `microphone` (declared on `JarvisForegroundService`). Harmless on older versions. | Automatic at install. | Not user-revocable; revoking `RECORD_AUDIO` disables it. |
| `INTERNET` | Normal (install-time) | **Only** for the user-started download of the Vosk speech model. Scope in code: one HTTPS GET in `LocalModelManager`. The URL and every redirect must be `https://` to exactly `alphacephei.com` or `www.alphacephei.com` (default port, no user-info, max 5 redirects; `ModelDownloadPolicy`). The response size must match the expected archive. No other code opens a network connection. Audio, text and notifications are never sent anywhere. | Automatic at install; used only when you tap **Download** or **Re-download / repair** on the Speech model screen. Use **Import** if you never want the app to go online. | Not user-revocable on stock Android. MIUI: Security app → Data usage / Network → restrict JARVIS (Wi-Fi and mobile data). |
| `RECEIVE_BOOT_COMPLETED` | Normal (install-time) | Lets `ReminderBootReceiver` re-register **saved reminders** after a reboot (it also handles `MY_PACKAGE_REPLACED` after an app update). It **never starts listening**, the microphone or the foreground service. Reminders that came due while the phone was off (≤ 24 h) are shown as "Missed reminder"; older ones are dropped. | Automatic at install. | Not user-revocable. Turn off JARVIS notifications to suppress reminders, or uninstall. MIUI may block boot broadcasts unless **Autostart** is allowed, in which case reminders are rescheduled the next time you open JARVIS. |

`<uses-feature android:name="android.hardware.microphone" android:required="true">`
is also declared (affects store filtering only).

## 2. Components that need user enablement

| Component | What it is | How to enable / disable |
|---|---|---|
| `JarvisNotificationListenerService` (protected by `BIND_NOTIFICATION_LISTENER_SERVICE`) | Optional. Lets "read my notifications" speak the newest notification. Keeps at most the latest 5 (title/text, max 300 chars each) for at most 10 minutes, **in memory only**. Nothing is logged, saved or uploaded, and the cache is cleared when access is turned off or the listener disconnects. Not kept at all: notifications with secret lock-screen visibility, ongoing or group-summary notifications, and (by default) messaging, SMS, email, payment, banking and authenticator apps and OTP-looking texts. You can also add your own list of excluded package names. Access is checked again each time you ask. | Not requested automatically. Say "open notification access" or use Settings → Apps → Special app access → Notification access → JARVIS. When access is off, JARVIS offers to open that page. |
| `ReminderReceiver` (not exported) | Shows reminder notifications set by voice. Uses inexact `AlarmManager.set(RTC_WAKEUP)` (no exact-alarm permission). Accepts only the exact reminder action with an id and title that match a saved reminder; anything else is ignored. | Nothing to enable. If JARVIS notifications or the Reminders channel are off, JARVIS says it cannot set the reminder. |
| `ReminderBootReceiver` (not exported) | Reschedules saved reminders after boot or app update (see `RECEIVE_BOOT_COMPLETED`). Reminders are also rescheduled every time the app starts. | Nothing to enable. |

## 3. Package visibility (`<queries>`)

Instead of `QUERY_ALL_PACKAGES`, the manifest declares only:
- apps with a `MAIN`/`LAUNCHER` activity — needed for "open WhatsApp" etc.;
- `android.intent.action.TTS_SERVICE` — to find installed Text-to-Speech engines.

## 4. Deliberately NOT requested

- Contacts, SMS, call log, phone state, location, camera, body sensors.
- Accessibility service.
- `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`.
- `QUERY_ALL_PACKAGES`.
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
- It does not start after **reboot**. The only boot-time work is rescheduling reminders (above).
- **Swiping JARVIS out of Recents** does not stop listening: the foreground notification stays and still offers **Stop**.
- The notification **Talk** action only works while the status is *Listening*; it is ignored while JARVIS is busy.
- If Android refuses to start the foreground service (e.g. the app is not visible), JARVIS reports an error instead of crashing.
- Locked-screen and screen-off behavior has **not been tested** yet. Capture is expected
  to continue while the foreground service runs, but this must be recorded on the Redmi.

### MIUI (Redmi Note 10S) — optional, OS-version dependent

MIUI's battery management may stop background apps more aggressively than stock Android.
If listening stops unexpectedly while the screen is off, you may try (menu names vary):

1. Settings → Apps → Manage apps → JARVIS → **Battery saver → No restrictions**.
2. Settings → Apps → Manage apps → JARVIS → **Autostart** → on (only allows MIUI to keep/relaunch
   the app; JARVIS still never auto-starts listening).
3. In the Recent apps screen, long-press JARVIS → **Lock** (padlock).

These are optional and not guaranteed. They increase battery use. Nothing in the app
requires them.
