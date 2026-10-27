# Privacy and security

Summary: JARVIS processes your voice **on the phone**. The only network use is the
speech-model download you start yourself. These statements describe the code as of
2026-10-27 and are backed by unit tests. One device check was done: on the Redmi Note 10S
(Android 13, debug build), logcat showed no transcript content during 5 Start/Stop cycles.
Network inspection, offline operation and a full log review have **not yet been done**
(see checklist items 25–27 in [TEST_PLAN.md](TEST_PLAN.md)).

## 1. Data flows

```text
Microphone ──► RAM frame buffers ──► Vosk (on-device) ──► text ──► CommandRouter ──► Android API / TTS
   │                                                          │
   └─ never written to disk, never uploaded                   └─ not logged; kept in memory only
                                                                 (on disk only if you enable history)

alphacephei.com ──(one-time HTTPS download, user-initiated)──► app-private files/models/
```

| Data | Where it goes | Stored? |
|---|---|---|
| Raw audio | `AudioCaptureController` → `VoicePipeline` → Vosk, in memory | **Never** stored or sent. Frames are discarded while JARVIS speaks. |
| Recognized command text | `CommandRouter` and tools | Not logged. The last command/reply is shown on screen **only if history is enabled** (memory only). Diagnostics (`VoiceMetrics`) keep counts and timings only, never text. |
| JARVIS's last reply | Memory (for "repeat that") | Lost when listening stops / app ends. |
| Notification title/text (only if you enable notification access) | `NotificationStore`: max 5 entries, max 300 chars per field, each dropped after **10 minutes** | **Memory only**; never logged, saved or uploaded. Cleared when access is disabled or revoked and when the listener disconnects or is destroyed. Notifications are **not kept at all** if they have secret lock-screen visibility, are ongoing or group summaries, come from a package on your exclusion list, or (default **on**, "never read messaging/banking apps") come from messaging/SMS/email/payment/banking/authenticator apps (known package list, default SMS app, `msg`/`email`/`call` category, or package-name hints like `bank`, `upi`, `wallet`, `pay`, `otp`) or look like an OTP (keyword + 4–8 digit code). Access is re-checked each time you ask. |
| Reminder title | `ReminderStore` (app-private `files/reminders.txt`, max 50 pending, title ≤ 100 chars) + `AlarmManager` pending intent → notification | Until the reminder fires or is dropped (overdue > 24 h). Rescheduled after reboot/app update and on app start. Never logged; excluded from backup. |
| Command history (opt-in, **off by default**) | `LocalHistoryRepository` → app-private `files/command_history.txt`, max 20 entries, max 500 chars per field | Only when enabled. Turning it on shows a confirmation dialog that explains what is stored. `HistorySanitizer` replaces notification read-outs, and any reply containing currently retained notification text, with `[notification content not stored]`. Removed with **Clear history**, Clear data, or uninstall. |
| Settings (onboarding done, history on/off, sensitivity, notification privacy toggle and excluded packages) | DataStore in app-private storage | Yes, locally. |
| Speech model | Downloaded from `https://alphacephei.com/vosk/models/…` or imported | App-private `files/models/`. |

### Network
- `INTERNET` is used only by `LocalModelManager` (plain `HttpURLConnection` GET of the model
  zip). `ModelDownloadPolicy` allows only `https://` URLs on exactly `alphacephei.com` /
  `www.alphacephei.com`, for the first request and every redirect (max 5). No analytics,
  crash reporting, ads, or cloud speech services are included.
- You can avoid the network entirely by using **Import**.
- The download request reveals your IP address and a standard HTTP user agent to alphacephei.com, as any download does.

### Backup
`android:allowBackup="false"`, `fullBackupContent="false"`, and
`data_extraction_rules.xml` excludes all domains from cloud backup and device transfer, so
settings, history, saved reminders and the model are not copied off the phone by Android backup.

## 2. Logging
- Code logs only short status strings and exception **class names** (never messages that
  might contain user data), under tags such as `JarvisService`, `VoicePipeline`,
  `AudioCapture`, `LocalModelManager`, `JarvisTts`.
- Transcripts, replies, notification contents and TTS text are never passed to `Log`.
- Vosk native logging is set to `WARNINGS`.
- See README section "Logcat without exposing private speech" for safe log sharing.

## 3. Security controls

| Risk | Control |
|---|---|
| Model output executing arbitrary actions | No LLM runtime ships. Any future model must go through `GuardedAiInterpreter`: delimited and sanitized prompt, 300-char input, 8 s timeout, 2 KB output, errors/OOM → fallback. Its output must pass `ModelCommandValidator` (exact schema, fixed tool allow-list, typed args, no arrays/floats/nesting > 2). Model-proposed reminders need confirmation. Tools call fixed APIs only; there is no shell/ADB/`Runtime.exec` path. |
| Settings changes without consent | Settings are only **opened** via official `Settings` intents; nothing is toggled. Sensitive follow-ups (e.g. opening notification access) ask "yes/no" first. |
| False success claims | Replies say "Opening …" only when `startActivity` succeeded; failures are reported. |
| Malicious/corrupt model archive | HTTPS + host allow-list (redirects included). Exact size + pinned SHA-256 + upstream MD5. Zip-slip protection (absolute, `..`, drive-letter, backslash, sibling-prefix forms), duplicate entries rejected, max 64 entries and 100 MB unpacked. Free-space check. Extraction into staging, required files checked, Vosk test load, atomic swap with backup recovery. Failure or cancel never removes a working model. See [LOCAL_MODELS.md](LOCAL_MODELS.md) for the trust basis. |
| Accidental or consequential actions | Low-confidence speech never reaches the handler (< 0.4). Low-confidence reminders (< 0.5) need confirmation. Pending questions expire after 30 s; one re-prompt, then cancel. Stop always wins. Repeat never runs an action again. |
| Forged reminder broadcasts | `ReminderReceiver` is not exported and accepts only the exact action with an id/title that match a saved reminder (androidTest `forgedReminderIntentPostsNothing`). The boot receiver only reschedules saved reminders and never starts listening. |
| Other apps abusing components | Foreground service, reminder receiver and boot receiver are `exported="false"`; the notification listener is exported but guarded by `BIND_NOTIFICATION_LISTENER_SERVICE` (only the system can bind). PendingIntents are `FLAG_IMMUTABLE`. |
| Hidden recording | Listening only in a foreground service with a visible notification (Stop/Talk), started from a user action. Android also shows its microphone privacy indicator. `START_NOT_STICKY`, and nothing starts listening at boot. On the Redmi the mic was released ≤ 2.8–3.7 s after Stop (5 cycles, adb polling upper bound). |
| Background package snooping | No `QUERY_ALL_PACKAGES`; only launcher apps and TTS engines are visible. |
| Secrets in source control | `.gitignore` excludes `keystore.properties`, `*.jks`, `*.keystore`, `.env`, `local.properties`, model files (`models/`, `*.gguf`, `*.onnx`, `*.ppn`), and `*.apk`/`*.aab`. No access keys are used. |
| Code shrinking | Release builds use R8 (`isMinifyEnabled`, `isShrinkResources`); `proguard-rules.pro` keeps JNA and `org.vosk` classes. `scripts\verify-release.ps1` and the release workflow check that native libraries and classes survive. Signing secrets live only in the untracked `keystore.properties` or in CI secrets. |
| Vulnerable dependencies | OSV scan of the 113 release dependencies on 2026-10-27: no advisories. Dependabot (`.github/dependabot.yml`) checks Gradle and Actions updates weekly. |

## 4. Third-party components and licenses

| Component | Version | License |
|---|---|---|
| Vosk Android (`com.alphacephei:vosk-android`) | 0.3.75 | Apache-2.0 |
| Vosk model `vosk-model-small-en-us-0.15` (downloaded, not bundled) | 0.15 | Apache-2.0 |
| JNA (`net.java.dev.jna:jna`, AAR) | 5.18.1 | Dual-licensed Apache-2.0 / LGPL-2.1+ (used under Apache-2.0) |
| AndroidX (core-ktx, lifecycle, activity-compose, Compose UI/Material3 via BOM 2025.09.01, DataStore) | see `gradle/libs.versions.toml` | Apache-2.0 |
| Kotlin stdlib, kotlinx-coroutines | 2.2.20 / 1.10.2 | Apache-2.0 |
| JUnit (tests only) | 4.13.2 | EPL-1.0 |
| org.json (tests only) | 20260814 | Public Domain (recent releases); tests only, not shipped in the APK |
| Android `TextToSpeech` | system | Part of the OS / installed TTS engine (vendor terms) |

Verify licenses again before any public distribution. For personal sideloading, keep this
table with your release notes.

## 5. Reporting / reviewing
Before each release: re-check the manifest permissions (currently `RECORD_AUDIO`,
`POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `INTERNET`,
`RECEIVE_BOOT_COMPLETED`), `grep` for `Log.` calls that could
include user text, confirm no secrets/models are tracked (`git status`, `git ls-files`), and
run the privacy items in the manual checklist.
