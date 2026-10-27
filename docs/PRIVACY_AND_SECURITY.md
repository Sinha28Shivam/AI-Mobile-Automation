# Privacy and security

Summary: JARVIS processes your voice **on the phone**. The only network use is the
one-time speech-model download you start yourself. These statements describe the code;
they have **not yet been verified on a device** (see checklist items 25–27 in
[TEST_PLAN.md](TEST_PLAN.md)).

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
| Recognized command text | `CommandRouter` and tools | Not logged. The last command/reply is shown on screen **only if history is enabled** (memory only). |
| JARVIS's last reply | Memory (for "repeat that") | Lost when listening stops / app ends. |
| Notification title/text (only if you enable notification access) | `NotificationStore`, max 5 entries, max 300 chars per field | **Memory only**; never logged, saved or uploaded; cleared when access is disabled or the listener disconnects. |
| Reminder title | `AlarmManager` pending intent → notification | In the system alarm until it fires; lost on reboot. |
| Command history (opt-in, **off by default**) | `LocalHistoryRepository` → app-private `files/command_history.txt`, max 20 entries | Only when enabled (Settings & help → Command history); removed with **Clear history**, Clear data, or uninstall. |
| Settings (onboarding done, history on/off, sensitivity) | DataStore in app-private storage | Yes, locally. |
| Speech model | Downloaded from `https://alphacephei.com/vosk/models/…` or imported | App-private `files/models/`. |

### Network
- `INTERNET` is used only by `LocalModelManager.download()` (plain `HttpURLConnection` GET of
  the model zip over HTTPS). No analytics, crash reporting, ads, or cloud speech services are included.
- You can avoid the network entirely by using **Import**.
- The download request reveals your IP address and a standard HTTP user agent to alphacephei.com, as any download does.

### Backup
`android:allowBackup="false"`, `fullBackupContent="false"`, and
`data_extraction_rules.xml` excludes all domains from cloud backup and device transfer, so
settings, history and the model are not copied off the phone by Android backup.

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
| Model output executing arbitrary actions | No LLM yet. Future output must pass `ModelCommandValidator` (fixed tool allow-list, exact typed args, 2 KB limit, no arrays/floats/nesting > 2). Tools call fixed APIs only; there is no shell/ADB/`Runtime.exec` path. |
| Settings changes without consent | Settings are only **opened** via official `Settings` intents; nothing is toggled. Sensitive follow-ups (e.g. opening notification access) ask "yes/no" first. |
| False success claims | Replies say "Opening …" only when `startActivity` succeeded; failures are reported. |
| Malicious/corrupt model archive | SHA-256 pinned; zip-slip protection; entry-count and 150 MB uncompressed limits; download size cap; required files checked. |
| Other apps abusing components | Foreground service and reminder receiver are `exported="false"`; the notification listener is exported but guarded by `BIND_NOTIFICATION_LISTENER_SERVICE` (only the system can bind). PendingIntents are `FLAG_IMMUTABLE`. |
| Hidden recording | Listening only in a foreground service with a visible notification (Stop/Talk), started from a user action; Android also shows its microphone privacy indicator. |
| Background package snooping | No `QUERY_ALL_PACKAGES`; only launcher apps and TTS engines are visible. |
| Secrets in source control | `.gitignore` excludes `keystore.properties`, `*.jks`, `*.keystore`, `.env`, `local.properties`, model files (`models/`, `*.gguf`, `*.onnx`, `*.ppn`), and `*.apk`/`*.aab`. No access keys are used. |
| Code shrinking | Release builds use R8 (`isMinifyEnabled`, `isShrinkResources`); `proguard-rules.pro` keeps JNA and `org.vosk` classes. Signing secrets live only in the untracked `keystore.properties`. |

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
Before each release: re-check the manifest permissions, `grep` for `Log.` calls that could
include user text, confirm no secrets/models are tracked (`git status`, `git ls-files`), and
run the privacy items in the manual checklist.
