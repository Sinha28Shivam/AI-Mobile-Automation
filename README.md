# JARVIS — offline voice assistant for Android

JARVIS is a native Kotlin Android app (Jetpack Compose UI) that listens for the
wake phrase **"Hey Jarvis"**, recognizes a short spoken command **on the phone**,
runs it through a deterministic, allow-listed command router, and answers with
Android's built-in Text-to-Speech. Speech recognition uses the open-source
[Vosk](https://alphacephei.com/vosk/) engine. Audio is processed in memory and is
never saved or uploaded.

> **Honest status:** the project builds and its unit tests run on the JVM, but the
> app has **not yet been tested on a real phone or on an emulator**. Treat every
> on-device behavior described here as *expected*, not *verified*. The target
> phone is a Xiaomi Redmi Note 10S (Android 12 / MIUI).

More documentation:

| Document | Contents |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Components, voice pipeline, threading, state machine, package map |
| [docs/ANDROID_PERMISSIONS.md](docs/ANDROID_PERMISSIONS.md) | Every permission, why, when requested, how to revoke |
| [docs/LOCAL_MODELS.md](docs/LOCAL_MODELS.md) | Speech model, wake-word approach, local LLM plan |
| [docs/TEST_PLAN.md](docs/TEST_PLAN.md) | Unit-test inventory and manual device checklist |
| [docs/RELEASE_AND_INSTALL.md](docs/RELEASE_AND_INSTALL.md) | Signing, release build, checksum, installing on the Redmi |
| [docs/PRIVACY_AND_SECURITY.md](docs/PRIVACY_AND_SECURITY.md) | Data flows, what is stored, third-party licenses |

---

## 1. Feature status

Legend:
- **Unit-tested** — implemented, and the logic is covered by JVM unit tests (`.\gradlew.bat test`).
- **Untested on device** — implemented and compiles, but depends on Android APIs / hardware and has not been run on a phone or emulator yet.
- **Not implemented** — planned or out of scope.

| Feature | Status | Notes |
|---|---|---|
| Deterministic command parser (open app, battery, time, settings, stop, repeat, notifications, reminders) | Unit-tested | `commands/CommandParser.kt` |
| Command router (confirmation, ambiguous app choice, "repeat", "stop" priority, AI hook fallback) | Unit-tested | `commands/CommandRouter.kt` |
| Allow-list validator for future model (LLM) output | Unit-tested | `commands/ModelCommandValidator.kt` |
| App-name matching (exact / prefix / contains / ambiguous / missing) | Unit-tested | `tools/AppNameResolver.kt` |
| Wake-word decision logic (threshold, cooldown/debounce) | Unit-tested | `wakeword/WakeWordDecider.kt` |
| Command length bounding (no-speech 4 s, trailing silence 1.2 s, max 6 s) | Unit-tested | `speech/CommandEndpointer.kt` |
| Voice pipeline orchestration (wake → command → reply → resume; tap-to-talk; stop) | Unit-tested with fakes | `service/VoicePipeline.kt` |
| Service state machine | Unit-tested | `service/ServiceState.kt` |
| Model archive safety (SHA-256, zip-slip, size/entry limits) | Unit-tested | `speech/ModelArchive.kt` |
| Opt-in local command history (file format, max 20 entries) | Unit-tested | `data/LocalHistoryRepository.kt` (class exists; see note below) |
| Foreground listening service with notification (Stop / Talk actions) | Untested on device | `service/JarvisForegroundService.kt` |
| Microphone capture (16 kHz mono PCM, in memory only) | Untested on device | `service/AudioCaptureController.kt` |
| "Hey Jarvis" wake word via grammar-limited Vosk recognizer | Untested on device | `wakeword/VoskKeywordWakeWordDetector.kt` — see limits in [LOCAL_MODELS.md](docs/LOCAL_MODELS.md) |
| Offline speech-to-text (Vosk small English) | Untested on device | `speech/LocalSpeechEngineAdapter.kt` |
| In-app model download / import / delete with SHA-256 check | Untested on device | `ai/LocalModelManager.kt`, `ui/ModelSetupScreen.kt` |
| Text-to-Speech replies (Android `TextToSpeech`) | Untested on device | `tts/SpeechOutput.kt` |
| Wiring of command router + TTS into the live pipeline | Untested on device (newly added) | `VoicePipelineConfig` defaults to `RouterCommandHandler` (wraps `CommandRouter`, records opt-in history) and `TtsSpeaker` (wraps `SpeechOutput`) |
| Android tools: launch app, battery, time, open settings pages, inexact reminders, read latest notification | Untested on device | `tools/` |
| Onboarding screen, home screen, wake-sensitivity slider | Untested on device | `onboarding/`, `ui/` |
| Settings & help screen (history toggle/clear, notification access, TTS settings, speech model, MIUI battery tips, permissions) | Untested on device (newly added) | `ui/SettingsScreen.kt` |
| Release signing from an untracked `keystore.properties`, R8 shrinking, `scripts\checksum.ps1` | Implemented (newly added); release APK not yet installed on a device | See [RELEASE_AND_INSTALL.md](docs/RELEASE_AND_INSTALL.md) |
| Sample configuration | Documentation only | `config\jarvis.sample.properties` lists the safe defaults (sensitivity 0.5, history off, LLM off); the in-app settings are the source of truth |
| Local LLM (llama.cpp) for free-form commands | **Not implemented** | Phase 5; the router already has an `aiInterpreter` hook + validator. See [LOCAL_MODELS.md](docs/LOCAL_MODELS.md) |
| Porcupine (or other dedicated) wake-word engine | Not implemented | Would require a Picovoice AccessKey |
| Auto-start after reboot, accessibility service, contacts/SMS/calls | Not implemented (deliberately) | See [ANDROID_PERMISSIONS.md](docs/ANDROID_PERMISSIONS.md) |
| Battery / RAM / latency measurements | Not measured | No numbers are claimed anywhere |

Note on history: history is **off by default**. Turn it on/off or clear it in
**Settings & help → Command history** (keeps the last 20 entries in app-private storage).

---

## 2. Toolchain (exact versions)

| Item | Version |
|---|---|
| JDK | 17 (Eclipse Adoptium; Android Studio's bundled JBR 17+ also works) |
| Gradle (wrapper) | 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.2.20 |
| Compose BOM | 2025.09.01 |
| compileSdk / targetSdk | 36 |
| minSdk | 26 (Android 8.0) |
| Build tools | 36.0.0 |
| Vosk | `com.alphacephei:vosk-android:0.3.75` + JNA 5.18.1 (AAR) |

Application id / package: `com.jarvis.assistant`, version `0.1.0` (versionCode 1).

---

## 3. Windows setup from zero

These steps assume you have never built an Android app before.

### 3.1 Install Android Studio (step 1)
1. Go to the official site: <https://developer.android.com/studio>.
2. Download Android Studio for Windows and run the installer with default options.
3. Start Android Studio. The **Setup Wizard** appears — choose **Standard**.

### 3.2 JDK and SDK (step 2)
- The Setup Wizard installs the Android SDK and Android Studio ships its own JDK (JBR).
  That is enough to build inside Android Studio.
- To build from the command line (`.\gradlew.bat`), Gradle needs a JDK 17+:
  - Either install **Eclipse Temurin 17** from <https://adoptium.net/> (tick "Set JAVA_HOME"), or
  - point `JAVA_HOME` at Android Studio's JBR, e.g. in PowerShell:
    ```powershell
    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
    ```
- Check: `java -version` should print 17 or newer.

### 3.3 SDK Platform-Tools and Build-Tools (step 3)
1. In Android Studio: **More Actions → SDK Manager** (or **Tools → SDK Manager**).
2. **SDK Platforms** tab: tick **Android 16 (API 36)**.
3. **SDK Tools** tab: tick **Show Package Details**, then tick
   **Android SDK Build-Tools 36.0.0** and **Android SDK Platform-Tools**. Click **Apply**.
4. The SDK normally lives at `%LOCALAPPDATA%\Android\Sdk`
   (the exact path is shown at the top of SDK Manager as "Android SDK Location").

### 3.4 Open the project (step 4)
1. **File → Open…** and select the project folder (the one containing `settings.gradle.kts`).
2. Trust the project when asked, then wait for **Gradle sync** to finish
   (bottom status bar). The first sync downloads Gradle 8.13 and all dependencies — it
   needs internet and can take several minutes.
3. Android Studio creates `local.properties` with `sdk.dir=...`. This file is machine-specific and is git-ignored.

### 3.5 Connect the Redmi Note 10S (steps 5–7)
1. Use a **data-capable** USB cable (many cheap cables are charge-only).
2. On the phone: **Settings → About phone → tap "MIUI version" 7 times** until it says you are a developer.
3. **Settings → Additional settings → Developer options**:
   - turn on **USB debugging**;
   - turn on **Install via USB** (MIUI asks you to sign in to a Mi account and may need a SIM card for this);
   - optionally **USB debugging (Security settings)**.
4. Plug the phone in. If MIUI asks for the USB mode, choose **File transfer**.
5. A dialog **"Allow USB debugging?"** appears on the phone, showing the computer's RSA fingerprint.
   Tick **Always allow from this computer** and tap **Allow**.

### 3.6 Verify with adb (step 8)
Open PowerShell:
```powershell
adb devices
```
You should see something like `XXXXXXXX    device`.

If PowerShell says `adb` is not recognized, it is not on your PATH. Use the SDK copy:
```powershell
$env:Path += ";$env:LOCALAPPDATA\Android\Sdk\platform-tools"
adb devices
```
(This only affects the current window. If your SDK is elsewhere, use the "Android SDK
Location" shown in SDK Manager plus `\platform-tools`.)

| `adb devices` shows | Meaning / fix |
|---|---|
| nothing | Cable, USB mode, or driver problem (see Troubleshooting) |
| `unauthorized` | Unlock the phone and accept the "Allow USB debugging?" prompt; if it never appears, Developer options → **Revoke USB debugging authorizations**, replug |
| `offline` | `adb kill-server` then `adb devices` again; replug |
| `device` | Ready |

### 3.7 Run from Android Studio (step 9)
1. In the toolbar device dropdown, select your Redmi.
2. Make sure the run configuration is **app**, then press the green **Run ▶** button.
3. MIUI may show **"Install via USB"** confirmation on the phone — tap **Install** (you have ~10 seconds).

### 3.8 Build a debug APK (step 10)
- Android Studio: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**, or
- PowerShell in the project folder (see section 4): `.\gradlew.bat assembleDebug`

### 3.9 Locate and install the APK (step 11)
- Debug APK: `app\build\outputs\apk\debug\app-debug.apk`
- Install over USB:
  ```powershell
  adb install -r app\build\outputs\apk\debug\app-debug.apk
  ```
  `-r` replaces an existing install and keeps its data.
- Or copy the file to the phone and open it with the file manager (see
  [RELEASE_AND_INSTALL.md](docs/RELEASE_AND_INSTALL.md) for "Install unknown apps").

Steps 12–15 (troubleshooting, Logcat, stopping/revoking, updates) are in sections 7–10 below.

---

## 4. Build commands

Run from the project root in PowerShell:

```powershell
.\gradlew.bat --version        # shows Gradle 8.13, Kotlin, JVM in use
.\gradlew.bat test             # JVM unit tests (no phone needed)
.\gradlew.bat lint             # Android Lint; the build fails on lint errors
.\gradlew.bat assembleDebug    # debug APK
.\gradlew.bat assembleRelease  # release APK (needs signing setup, see below)
```

| Output | Path |
|---|---|
| Debug APK | `app\build\outputs\apk\debug\app-debug.apk` |
| Release APK | `app\build\outputs\apk\release\` (`app-release.apk` when signed; `app-release-unsigned.apk` if no signing config is available) |
| Unit-test report | `app\build\reports\tests\testDebugUnitTest\index.html` |
| Lint report | `app\build\reports\lint-results-debug.html` |

Debug APKs are for development only. For a release build signed with your own key,
follow [docs/RELEASE_AND_INSTALL.md](docs/RELEASE_AND_INSTALL.md). Release signing reads
an **untracked** `keystore.properties`; never commit keystores or passwords.

The APK includes native Vosk libraries for `arm64-v8a`, `armeabi-v7a`, `x86`, and
`x86_64` (the Redmi Note 10S uses `arm64-v8a`). **No speech model is bundled.**

---

## 5. First run

1. Open **JARVIS**. The **Welcome** (onboarding) screen explains microphone use, the
   foreground notification, on-device processing and the one-time model download.
   It does not request any permission. Tap **Got it**.
2. The home screen shows **"Speech model not installed"**. Tap **Set up speech model**.
3. Either:
   - **Download (~39 MB)** — one-time, needs internet (Wi-Fi recommended). Downloads
     `vosk-model-small-en-us-0.15.zip` from `alphacephei.com`, or
   - **Import zip from this phone** — download that exact zip on any device yourself,
     copy it to the phone and pick it. Only the exact expected file is accepted.
4. The app verifies the SHA-256 checksum, unpacks the model into app-private storage,
   and shows **Status: Ready**. Tap **Back**.
5. Tap **Start Listening**. Android asks for **Microphone** permission (and on
   Android 13+ **Notifications**). Microphone is required; notifications are optional
   (on Android 12, the Redmi, there is no notification prompt).
6. A persistent notification "JARVIS" appears with **Talk** and **Stop** actions.
   Status shows *Starting (loading speech model)* then *Listening for "Hey Jarvis"*.
7. Say **"Hey Jarvis"**, wait for *Heard wake word*, then say a command.
   Or press **Tap to talk** to skip the wake word.

If the wake word reacts to similar words, lower **Wake word sensitivity** on the home screen.
Tap **Settings & help** for notification access, voice (TTS) settings, command history,
MIUI battery tips and permissions.

---

## 6. Voice command examples

English only. Wake phrase: **"Hey Jarvis"** (or just "Jarvis"). Polite fillers like
"please", "can you" are ignored.

| Say | What happens |
|---|---|
| "Open YouTube" / "Launch WhatsApp" | Launches the matching installed app. If several match, JARVIS lists up to 4 and you answer with the name. If none match: "I couldn't find an app called …". |
| "What is my battery level?" / "Is my phone charging?" | Speaks battery percent and charging state |
| "What time is it?" | Speaks the current time |
| "Open Wi-Fi settings" / "Bluetooth" / "Open sound settings" / "display settings" / "battery saver" | Opens that settings page. JARVIS **never toggles** settings itself. |
| "Open notification access" | Opens the notification-access settings page |
| "Read my notifications" / "Any new notifications?" | Reads the latest notification (needs notification access; if off, JARVIS asks "Should I open notification access settings?" — answer "yes"/"no") |
| "Remind me to call mom in 10 minutes" / "Remind me in two hours to stretch" | Schedules a reminder notification (1 minute – 24 hours). Inexact timing; lost on reboot. |
| "Repeat that" / "Say that again" | Repeats JARVIS's last reply |
| "Stop listening" / "Stop" / "Cancel" / "Go to sleep" | Stops listening and the foreground service (always wins) |

Unclear or low-confidence speech gives "Sorry, I didn't catch that." Silence gives
"I didn't hear a command." Anything else gives "Sorry, I didn't understand."

---

## 7. Troubleshooting (step 12)

| Problem | What to try |
|---|---|
| Phone not listed by `adb devices` | Use a different (data) cable and a USB port directly on the PC; set USB mode to **File transfer**; enable USB debugging again. Install the **Google USB Driver** (SDK Manager → SDK Tools) or Xiaomi's driver; in Device Manager the phone should appear as "Android Composite ADB Interface". |
| `unauthorized` | Unlock the phone, accept the RSA prompt. Developer options → *Revoke USB debugging authorizations*, then replug. |
| "INSTALL_FAILED_USER_RESTRICTED" / install blocked on MIUI | Enable **Install via USB** in Developer options; tap **Install** on the phone's prompt within ~10 s. |
| Gradle sync / build fails downloading | Check internet/proxy; **File → Settings → Build, Execution, Deployment → Gradle** make sure "Offline work" is off; retry with `.\gradlew.bat --refresh-dependencies assembleDebug`. |
| "SDK location not found" | Open the project once in Android Studio (creates `local.properties`), or create it with `sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`. |
| "Failed to find target android-36" / Build-Tools 36.0.0 missing | Install them in SDK Manager (section 3.3). |
| "Unsupported class file major version" / JDK errors | Use JDK 17+: set `JAVA_HOME` (section 3.2) or **Settings → Gradle → Gradle JDK** = 17+. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (signature mismatch) | The installed app was signed with a different key (e.g. debug vs release). Uninstall first: `adb uninstall com.jarvis.assistant` (this deletes app data incl. the model). |
| Theme / package mismatch, "App not installed" | Make sure you install the APK built from this project (`com.jarvis.assistant`); remove older copies with a different signature. |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | Free space. Plan for the APK plus ~40 MB download + extracted model (temporarily both). |
| "Speech model not installed" | Open **Speech model** and download or import it. |
| "File check failed: this is not the expected … archive" | The download was corrupt or you picked a different file. Retry; import only `vosk-model-small-en-us-0.15.zip`. |
| "Download failed (HTTP …)" | Network issue or the server is unavailable; retry later or use Import. |
| "Not enough memory to load the speech model" | Close other apps and try again. |
| "Microphone is in use by another app" | Close the call/recorder/other assistant using the mic. |
| Wake word does not trigger / triggers too often | Speak clearly, closer; adjust the sensitivity slider; use **Tap to talk**. See limits in [LOCAL_MODELS.md](docs/LOCAL_MODELS.md). |
| No spoken replies | Install/enable a TTS voice: Android **Settings → Additional settings → Languages & input → Text-to-speech output** (MIUI path may vary). Download the offline English voice data. |
| Listening stops by itself in the background | MIUI battery saver may kill it. See "Background limits" in [ANDROID_PERMISSIONS.md](docs/ANDROID_PERMISSIONS.md). |

---

## 8. Logcat without exposing private speech (step 13)

JARVIS is written so it does **not** log transcripts, replies, notification content
or audio — only short status messages and exception class names (tags such as
`JarvisService`, `VoicePipeline`, `AudioCapture`, `LocalModelManager`, `JarvisTts`).

- In Android Studio open **Logcat** and filter to the app: `package:com.jarvis.assistant`.
- From PowerShell:
  ```powershell
  adb logcat --pid=$(adb shell pidof -s com.jarvis.assistant)
  ```
- Other apps and system components (e.g. the TTS engine, the keyboard) may log their own data.
  Before sharing a log, read it and remove anything personal. Prefer sharing only the
  lines with JARVIS tags.
- Clear old logs with `adb logcat -c` before reproducing an issue.
- Do not add `Log` calls that print recognized text when debugging.

---

## 9. Stop listening and revoke access (step 14)

- **Stop now:** tap **Stop Listening** in the app, tap **Stop** in the notification, or say "stop listening".
  The microphone is released and the notification disappears.
- **Revoke microphone:** Android **Settings → Apps → Manage apps → JARVIS → App permissions → Microphone → Don't allow**.
- **Revoke notification access** (only if you enabled it): **Settings → Apps → Special app access (Special permissions) → Notification access → JARVIS → off**
  (you can also say "open notification access").
- **Notifications:** Settings → Apps → JARVIS → Notifications.
- **Delete the speech model:** JARVIS → **Speech model → Delete model**.
- **Remove everything:** uninstall JARVIS (deletes all app data, the model and history).

Exact MIUI menu names vary by version.

---

## 10. Building and installing updates (step 15)

1. Get the new source, then rebuild: `.\gradlew.bat assembleDebug` (or `assembleRelease`).
2. Install over the existing app, keeping its data (including the downloaded model):
   ```powershell
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   ```
3. Updates must be signed with the **same key** as the installed app (debug→debug on the
   same PC, or release→release with your keystore) and must not lower `versionCode`.
   For release builds, bump `versionCode`/`versionName` in `app\build.gradle.kts`.
   Details in [RELEASE_AND_INSTALL.md](docs/RELEASE_AND_INSTALL.md).

---

## 11. Known limitations / manual tasks

- Not yet tested on any real device or emulator; the manual checklist in
  [TEST_PLAN.md](docs/TEST_PLAN.md) is entirely "Not yet run".
- Wake word is a grammar-constrained speech recognizer, not a dedicated wake-word
  network: higher CPU/battery use, possible false triggers.
- English only; the model must be downloaded or imported once.
- Reminders are inexact and do not survive a reboot.
- No local LLM yet; only the fixed command set above is understood.
- Listening must be started by you from the visible app; it does not auto-start after
  reboot and may be stopped by MIUI battery management or force-stop.
- No battery, RAM or latency figures have been measured.
