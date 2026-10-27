# JARVIS Android Assistant --- Codex Implementation Specification

**Document type:** Product requirements + engineering implementation
plan\
**Project:** JARVIS --- private, on-device Android voice assistant\
**Primary device:** Xiaomi Redmi Note 10S, 8 GB RAM, 128 GB storage,
Android 12\
**Development machine:** Windows 10/11, 16 GB RAM, SSD with at least 20
GB free\
**Distribution:** Personal-use APK, installed manually; no Google Play
release required for v1\
**Primary goal:** Deliver a working, testable Android application from
initial setup through signed APK installation, with background wake-word
detection, local speech/AI processing, and permission-based phone
automation.

------------------------------------------------------------------------

## 1. Instructions to Codex

Act as the lead Android engineer. Implement this project in small,
testable increments rather than generating a large unverified code dump.

### Required working style

1.  First inspect the repository and current machine/project state. Do
    not overwrite existing user work.
2.  If no Android project exists, create a native Android project using
    **Kotlin** and AndroidX. Prefer native Android over Flutter for v1
    because microphone foreground services and system integrations are
    core requirements.
3.  Establish a buildable baseline before adding AI or hardware
    integrations.
4.  After each phase, run the relevant Gradle build, unit tests, and
    lint checks where feasible. Fix failures before moving on.
5.  Do not claim a build, test, install, or device check succeeded
    unless you actually ran it and observed the result. If device access
    is unavailable, state the exact manual verification steps.
6.  Keep dependencies stable and compatible. Verify current official
    documentation and dependency compatibility before selecting
    versions; do not invent Maven coordinates or APIs.
7.  Use clear, beginner-friendly documentation because the owner has
    limited Android-development experience.
8.  Avoid hard-coded secrets, access tokens, machine-specific absolute
    paths, and personal data. Any SDK requiring a key must have a
    documented setup path and a no-secret alternative where feasible.
9.  Keep all normal command interpretation and AI inference on-device in
    the intended offline mode. Do not silently upload audio,
    transcripts, notifications, or personal data.
10. Keep actions explicit and allow the user to stop listening at any
    time.

### Deliverables

-   A complete Android Studio project that builds.
-   All source code and resource files.
-   Gradle wrapper and dependency declarations.
-   Unit tests for command parsing/routing and core utilities.
-   `README.md` with beginner-friendly Windows setup, build, debug,
    install, troubleshooting, and release instructions.
-   `docs/ARCHITECTURE.md`
-   `docs/ANDROID_PERMISSIONS.md`
-   `docs/LOCAL_MODELS.md`
-   `docs/TEST_PLAN.md`
-   `docs/RELEASE_AND_INSTALL.md`
-   `docs/PRIVACY_AND_SECURITY.md`
-   `.gitignore` that excludes local model files, secrets, build output,
    and local IDE state as appropriate.
-   A sample configuration file with safe defaults and no secrets.
-   A clear list of anything that remains incomplete or requires manual
    action.

------------------------------------------------------------------------

## 2. Product vision and v1 scope

JARVIS is a personal voice assistant installed on the owner's own
Android phone. The user should be able to say **"Hey Jarvis"**, speak a
command, and receive a spoken response. It should run without a remote
AI API for core features after required models/assets are installed.

### v1 must-have capabilities

1.  A clear setup/onboarding screen explaining permissions and local
    processing.
2.  Start/stop listening controls.
3.  A persistent foreground-service notification while microphone
    capture is active.
4.  A local wake-word detection interface for "Hey Jarvis" (or an
    explicitly documented supported equivalent if custom phrase
    assets/licensing prevent immediate use).
5.  Local speech-to-text after wake-word detection.
6.  A deterministic command router for common actions, with a local LLM
    used only when it is available and appropriate.
7.  Spoken replies using Android TextToSpeech. Prefer offline TTS voice
    where the device supports it; clearly document when Android TTS may
    download a voice or use a system service.
8.  A command to open an installed app.
9.  A command to report battery level and current time.
10. A command to open supported Android settings screens rather than
    bypass protected system controls.
11. A notification-listener integration that is optional and disabled
    until the user explicitly enables it in Android Settings.
12. Local preferences and a small local history setting, both
    controllable by the user.
13. Error states that explain what failed and what the user can do.
14. A manual APK build/install workflow for Windows and the Redmi Note
    10S.

### v1 stretch goals (only after must-haves pass)

-   Local quantized LLM for natural-language command interpretation.
-   Read a user-selected notification aloud after explicit notification
    access is granted.
-   Reminder creation using Android-supported APIs.
-   Optional accessibility integration for user-authorized screen
    interaction, only where appropriate and compliant with Android
    policy.
-   Optional autostart/recovery UX where Android and MIUI permit it.

### Explicit non-goals

-   No root access, bootloader unlocking, exploit, hidden surveillance,
    or bypass of Android permission boundaries.
-   No promise of uninterrupted always-on microphone operation after
    force-stop, reboot, battery killing, or system restrictions.
-   No covert recording or transmission.
-   No arbitrary automatic sending of messages, purchases, financial
    actions, deletion, or other sensitive actions without confirmation.
-   No unrestricted "AI can tap anything" promise.
-   No cloud LLM as a required runtime dependency.
-   No Google Play release requirements for v1.

------------------------------------------------------------------------

## 3. Target environment and compatibility

### Owner's hardware

-   Phone: Xiaomi Redmi Note 10S.
-   RAM: 8 GB.
-   Storage: 128 GB.
-   OS: Android 12 / MIUI build to be confirmed on device.
-   Computer: Windows 10/11, 16 GB RAM, SSD with at least 20 GB free.

### Android build configuration

-   Use a current stable Android Gradle Plugin/Kotlin combination that
    is compatible with the installed JDK and Android Studio.
-   Use the Gradle wrapper committed with the project.
-   Set `minSdk` to 26 unless a dependency or feature requires a higher
    level.
-   Set `compileSdk` and `targetSdk` to a current stable level supported
    by the chosen Android Gradle Plugin. Document the selected versions
    and verify foreground-service requirements against the target SDK.
-   Do not downgrade `targetSdk` just to avoid platform restrictions.
-   Request runtime permissions only when the relevant feature is
    enabled.
-   Declare foreground service types and permissions correctly for the
    selected target SDK.
-   The app must be testable on Android 12 even if it targets a newer
    SDK.

Codex must check the current official Android documentation for
background execution, microphone foreground services, notification
permissions, package visibility, and relevant target-SDK changes before
implementation.

------------------------------------------------------------------------

## 4. Architecture

Use a modular, testable architecture. Keep Android APIs at the edge and
command/business logic independently testable.

``` text
app/
  src/main/
    AndroidManifest.xml
    java/<package>/
      MainActivity.kt
      onboarding/
      service/
        JarvisForegroundService.kt
        AudioCaptureController.kt
        ServiceState.kt
      wakeword/
        WakeWordDetector.kt
        WakeWordEngineAdapter.kt
      speech/
        SpeechToTextEngine.kt
        LocalSpeechEngineAdapter.kt
      ai/
        LocalAiEngine.kt
        LocalModelManager.kt
        PromptBuilder.kt
      commands/
        Command.kt
        CommandParser.kt
        CommandRouter.kt
        CommandResult.kt
      tools/
        AppLauncher.kt
        DeviceInfoTool.kt
        SettingsNavigationTool.kt
        ReminderTool.kt
        NotificationReader.kt
      tts/
        SpeechOutput.kt
      data/
        PreferencesRepository.kt
        LocalHistoryRepository.kt
      permissions/
        PermissionManager.kt
      ui/
        HomeScreen...
      util/
        ...
    res/
  src/test/
  src/androidTest/
docs/
```

Use package names and folder structures that match the actual generated
project. Avoid creating empty abstraction layers without a real use.

### Architecture requirements

-   `JarvisForegroundService` owns the lifecycle of active microphone
    listening.
-   Only one audio capture owner may be active at a time.
-   Wake-word detector receives short audio frames; audio should not be
    persisted by default.
-   On wake-word detection, pause or coordinate detector capture
    correctly, capture a bounded command window, run local speech
    recognition, route the command, execute an allow-listed tool, and
    speak the result.
-   Resume wake-word detection only after command handling finishes and
    the service remains enabled.
-   Add timeouts, cancellation, exception handling, and a stop path.
-   Do not block the main/UI thread with inference, audio reads, or file
    I/O.
-   Do not start an unbounded thread per audio frame or command.
-   Use lifecycle-aware UI state to show stopped, listening, detecting,
    processing, speaking, permission-required, and error states.
-   Do not expose raw notification contents or transcripts in logs.
-   Keep a local history opt-in and provide a clear-history action.
-   Make AI tool calls structured and validated; the model must not
    execute arbitrary shell commands, arbitrary code, or unvalidated
    intents.

------------------------------------------------------------------------

## 5. Voice pipeline and local models

The intended pipeline is:

``` text
Microphone
  -> local wake-word detector (“Hey Jarvis”)
  -> bounded command capture
  -> local speech-to-text
  -> deterministic command router / optional local LLM
  -> allow-listed Android tool
  -> result verification
  -> Android TextToSpeech
  -> resume wake-word detection
```

### 5.1 Wake-word engine

Create a provider-neutral `WakeWordDetector` interface. Evaluate viable
Android-compatible engines, including licensing, custom keyword support,
offline runtime, ARM64 support, model distribution requirements, and
ongoing costs.

Candidate options may include Porcupine or another supported offline
engine. Do not assume "Hey Jarvis" is a built-in keyword. If custom
keyword training requires an account, access key, or model file,
document that setup and keep the secret out of source control. If no
practical custom-phrase option is available, provide an explicit
alternative phrase and document the limitation; never falsely label a
generic voice activity detector as wake-word recognition.

Required behavior: - Detector runs locally. - Include sensitivity
configuration if supported. - Add debounce/cooldown to reduce repeated
activations. - Handle audio focus and microphone contention. - Report
model/engine initialization errors in the UI. - Provide a tap-to-talk
fallback so the app remains usable if wake-word setup is unavailable.

### 5.2 Speech recognition

Create a `SpeechToTextEngine` interface. Evaluate Vosk and a lightweight
Whisper implementation or other supported offline recognizer. Choose one
based on current Android support, model size, license, language support,
RAM use, and ease of packaging.

Requirements: - English command support initially. - Keep model files in
app-private storage or a clearly documented model directory. - Provide a
model download/import screen if needed; verify file integrity before
loading. - Explain that downloading a model is a one-time setup
operation and may require internet. - Bound command duration and handle
no-speech/low-confidence results. - No audio upload to a remote service.

### 5.3 Local language model

Do not make the first build depend on a large model. First implement
deterministic command routing. Then add an optional small quantized
model compatible with the phone, using a maintained Android-compatible
runtime such as llama.cpp via JNI if appropriate.

Requirements: - Evaluate 0.5B--1.5B parameter quantized models as
starting candidates, but benchmark on the actual device before selecting
one. - Check each model's license and redistribution terms. Do not
bundle a model unless its license permits it. - Make model path, load
state, memory errors, and availability visible. - Keep inference off the
main thread. - Use constrained JSON/function-like outputs and validate
them against an allow-list. - Fall back to deterministic parsing if the
model is absent, too slow, or fails. - Never let model output directly
execute shell commands or arbitrary code. - Document estimated disk/RAM
needs only after checking the selected model files.

### 5.4 Text-to-speech

Use Android `TextToSpeech` first. Handle initialization failures and
unavailable language data. Do not assume every installed voice works
offline; provide a settings/help page explaining how to install an
offline voice if the device supports one.

------------------------------------------------------------------------

## 6. Android permissions and settings

Request only permissions required for implemented features. Document the
purpose and user action for each.

### Core

-   `RECORD_AUDIO`: request at runtime before listening.
-   `FOREGROUND_SERVICE`: declare the foreground service.
-   `FOREGROUND_SERVICE_MICROPHONE`: declare if required by the selected
    target SDK.
-   `POST_NOTIFICATIONS`: request on Android 13+ where applicable;
    handle denial gracefully.
-   Notification channel for listening status.
-   Foreground service must call `startForeground()` promptly and
    display an honest notification.

### Optional integrations

-   Notification listener: implement `NotificationListenerService`; user
    must enable it in Android notification-access settings. Treat
    notification contents as sensitive. Do not forward them to cloud
    services.
-   Accessibility service: do not add by default. Add only if a concrete
    feature needs it and it is appropriate for the use case. Explain
    what it can observe/do, obtain explicit enablement, and avoid using
    it to bypass security prompts or user consent.
-   Reminders/alarms: use normal alarm/notification APIs first. Request
    exact-alarm privileges only if a feature genuinely needs them and
    Android policy permits.
-   App discovery: account for Android package visibility restrictions.
    Declare appropriate `<queries>` or use supported launch intents; do
    not request broad package visibility without justification.
-   Settings: use official intents/panels and supported APIs. If Android
    prevents a direct toggle, open the relevant settings page and tell
    the user what to do.

### Explicitly do not request by default

-   Contacts, SMS, call logs, location, camera, accessibility, exact
    alarms, or broad package visibility unless a clearly specified
    feature requires them.
-   Root, device administrator, VPN, or usage-access privileges for the
    basic assistant.

### Background behavior

-   Start microphone service only from a visible activity/user action
    unless a currently supported Android rule clearly permits another
    flow.
-   Do not promise microphone service can be launched freely from the
    background.
-   Do not promise it survives force-stop or all MIUI battery
    management.
-   Do not auto-start recording immediately after boot without explicit
    setup and a verified, platform-compliant design.
-   Add a setup page with MIUI battery/autostart guidance, clearly
    labeled as optional and OS-version dependent.
-   Test locked-screen behavior and record the actual outcome on the
    Redmi.

------------------------------------------------------------------------

## 7. Command design and supported tools

Use a typed command model, for example:

``` kotlin
sealed interface JarvisCommand {
    data class OpenApp(val appName: String) : JarvisCommand
    data object GetBattery : JarvisCommand
    data object GetTime : JarvisCommand
    data class OpenSettings(val destination: SettingsDestination) : JarvisCommand
    data class SpeakNotification(val notificationId: String) : JarvisCommand
    data class CreateReminder(val title: String, val time: /* validated type */) : JarvisCommand
    data class Unknown(val originalText: String) : JarvisCommand
}
```

Adjust syntax to the selected Kotlin version. Do not leave invalid
placeholder types in the final code.

### Initial supported natural-language examples

-   "Open YouTube."
-   "Open WhatsApp."
-   "What is my battery level?"
-   "What time is it?"
-   "Open Wi-Fi settings."
-   "Stop listening."
-   "Repeat that."
-   "I didn't understand" should be handled politely after
    low-confidence recognition.

### Command execution requirements

-   Resolve app names safely; if ambiguous, ask the user to choose.
-   Use launch intents and supported Android APIs.
-   Never use arbitrary shell/ADB commands from the app.
-   Treat "stop listening" as a high-priority local command that can
    interrupt processing.
-   Sensitive actions must require confirmation.
-   Verify what can be verified. Do not claim an app opened or a setting
    changed unless the action returned successfully or a subsequent
    check confirms it.
-   Keep a deterministic fallback path available when the LLM is not
    installed.

------------------------------------------------------------------------

## 8. User interface and onboarding

Build a simple native interface first. It must be usable by someone
without mobile-development experience.

### Home screen

-   JARVIS title and a concise privacy status.
-   Large Start Listening / Stop Listening control.
-   Current state: Stopped, Listening, Heard Wake Word, Processing,
    Speaking, or Error.
-   Last command and result only if local history is enabled.
-   Settings/help entry.
-   A visible way to stop listening immediately.

### Onboarding

Explain in plain language: 1. What microphone access is used for. 2.
That listening uses a foreground service with a persistent notification.
3. Whether wake-word detection and speech recognition are local. 4.
Which optional features require extra permissions. 5. How to revoke
permissions and stop the service. 6. Model downloads, their size, and
license/source information.

No permission should be requested on first launch without an explanation
and a user action to enable the feature.

------------------------------------------------------------------------

## 9. Privacy, security, and reliability

-   Audio is processed in memory and discarded by default.
-   No hidden recording, remote upload, analytics, advertising SDK, or
    cloud AI calls.
-   Do not log audio, full transcripts, notification text, secrets, or
    private model prompts.
-   Store preferences in app-private storage.
-   If command history is implemented, make it opt-in and easy to clear.
-   Keep any API/access key required by a third-party wake-word SDK out
    of Git. If the SDK requires a key, explain the privacy/licensing
    implications and make it optional where practical.
-   Validate every model-produced command against typed schemas and
    allow-listed tools.
-   Require confirmation before sending a message, deleting data, making
    a purchase, or performing other consequential actions.
-   Handle permission revocation while the service is running.
-   Handle microphone unavailable, audio-record initialization errors,
    model load errors, out-of-memory conditions, no speech, and TTS
    initialization errors.
-   Ensure Stop Listening stops audio capture, releases audio resources,
    and removes the foreground notification.
-   Avoid retaining audio buffers beyond what is required for inference.
-   Provide a clear privacy document and explain all third-party
    components and licenses.

------------------------------------------------------------------------

## 10. Development phases and acceptance criteria

Codex must complete these phases sequentially. Do not proceed past a
failed acceptance criterion.

### Phase 0 --- Environment and baseline

-   Inspect the repository.
-   Document required JDK, Android Studio, SDK, and Gradle versions.
-   Create the native Kotlin project and wrapper.
-   Add a minimal home screen.
-   Build debug APK.

**Pass criteria:** Gradle build succeeds; app installs and opens on an
emulator or physical device. If the physical phone is not connected,
provide manual device steps and report that device testing was not
performed.

### Phase 1 --- Redmi connection and app lifecycle

-   Document USB debugging and ADB checks for Windows.
-   Add permission-aware onboarding.
-   Add service state UI and Start/Stop controls.
-   Implement a correctly declared microphone foreground service with
    persistent notification.
-   Capture audio only while service is active; do not persist it.

**Pass criteria:** app starts/stops the service, handles microphone
permission denial, and releases the microphone after stopping. Verify on
Android 12 where possible.

### Phase 2 --- Wake-word detection

-   Integrate a viable local wake-word engine.
-   Document any required custom keyword model and setup/key.
-   Add sensitivity and cooldown.
-   Keep tap-to-talk fallback.

**Pass criteria:** a real detector recognizes its configured phrase; a
normal non-matching phrase does not trigger every time in a basic manual
test. Document false-positive limitations and actual device test
results.

### Phase 3 --- Local speech recognition

-   Integrate offline speech-to-text.
-   Add bounded command capture, timeout, no-speech handling, and
    confidence/error state.

**Pass criteria:** sample commands are transcribed on the device without
network access after models are installed.

### Phase 4 --- Deterministic command router

-   Implement typed commands and allow-listed tools.
-   Add open-app, battery, time, settings navigation, and
    stop-listening.
-   Add TextToSpeech output and verification.

**Pass criteria:** unit tests cover supported commands, unknown
commands, ambiguous app names, and invalid model output. Basic commands
work with the LLM disabled.

### Phase 5 --- Local LLM (optional enhancement, after baseline works)

-   Evaluate a compact model and a maintained on-device runtime.
-   Document model license, acquisition, storage, memory, and
    performance.
-   Add constrained structured output and validation.
-   Add fallback to deterministic command routing.

**Pass criteria:** the app continues to work if the model is absent or
fails. No arbitrary model-generated code or shell commands can run.

### Phase 6 --- Notification access and optional phone integrations

-   Add a separate settings screen explaining notification access.
-   Implement notification reading only after the user enables it.
-   Avoid showing sensitive notification content on the home screen by
    default.
-   Add supported reminders and settings navigation as separate tools.
-   Add accessibility only if a concrete, justified feature requires it.

**Pass criteria:** each optional integration works only after explicit
user enablement and fails gracefully when permission is revoked.

### Phase 7 --- Performance, battery, and device compatibility

-   Measure idle wake-word CPU/RAM and battery impact on the Redmi.
-   Check thermal behavior during repeated local inference.
-   Test screen locked/unlocked, app backgrounded, permission revoked,
    service stopped, and OS/MIUI restrictions.
-   Add graceful degradation and user-facing troubleshooting.

**Pass criteria:** report real measurements if available; otherwise
document unmeasured items honestly. No claim of guaranteed uninterrupted
background operation.

### Phase 8 --- Release and shipment

-   Configure app name, icon, version code/name, and release signing.
-   Provide a safe local signing workflow. Do not commit keystore files
    or passwords.
-   Build and validate a release APK.
-   Document install/update/uninstall and backup considerations.
-   Include checksum generation and release notes.

**Pass criteria:** release APK builds, installs on the target phone,
launches, and core features pass the test checklist. Keep debug and
release artifacts clearly separated.

------------------------------------------------------------------------

## 11. Testing requirements

### Unit tests

-   Command parser maps supported utterances to expected commands.
-   Unknown and malformed inputs are rejected safely.
-   Model JSON with unknown tools or invalid parameters is rejected.
-   App-name resolution handles missing and ambiguous matches.
-   Permission/state transitions are covered where practical.
-   Stop-listening cancels active work safely.

### Instrumentation/manual tests

-   Fresh install and onboarding.
-   Microphone permission granted and denied.
-   Notification permission granted and denied on Android versions where
    applicable.
-   Start/stop service repeatedly.
-   Lock and unlock screen.
-   Open a requested app.
-   Test no-speech and noisy-room behavior.
-   Test wake-word false positives.
-   Test model missing/corrupt/out-of-memory.
-   Disable notification access while app is active.
-   Reboot and force-stop behavior documented honestly.
-   Verify audio/transcripts are not uploaded and not logged.
-   Verify no unexpected permissions are requested.

### Build checks

Run and report the actual results of appropriate commands, such as:

``` powershell
.\gradlew.bat --version
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
```

Use commands that match the actual project and installed toolchain. Fix
compilation and test failures before declaring a phase complete.

------------------------------------------------------------------------

## 12. Windows setup and beginner documentation

`README.md` must explain every step from zero:

1.  Install Android Studio from the official Android developer website.
2.  Install a compatible JDK/SDK through the supported Android Studio
    setup.
3.  Install Android SDK Platform-Tools and build tools via SDK Manager.
4.  Open/import the project and wait for Gradle sync.
5.  Connect Redmi Note 10S by a data-capable USB cable.
6.  Enable Developer options and USB debugging.
7.  Accept the USB authorization prompt.
8.  Verify the device with `adb devices`.
9.  Select the phone in Android Studio and press Run.
10. Build a debug APK using Android Studio or Gradle.
11. Locate and install the APK.
12. Troubleshoot drivers, USB cable, unauthorized device, Gradle/network
    failures, missing SDK, theme/package mismatch, and insufficient
    storage.
13. Explain how to inspect Logcat without exposing private transcripts.
14. Explain how to stop listening and revoke microphone/notification
    access.
15. Explain how to build and install updates.

Useful initial Windows check:

``` powershell
adb devices
```

If `adb` is not on PATH, use Android Studio's SDK Platform-Tools
location or add that directory to the current PowerShell PATH. Explain
this with the actual SDK path rather than assuming a username or
installation directory.

------------------------------------------------------------------------

## 13. APK signing and personal distribution

-   Debug APKs are for development only.
-   Provide instructions for creating a release signing key locally
    through Android Studio or a documented Gradle signing setup.
-   Never check a keystore, passwords, signing credentials, access keys,
    or `.env` secrets into source control.
-   Do not put signing secrets directly in `build.gradle` or source
    code.
-   Explain that Android updates must be signed with the same signing
    identity and use a compatible package name/version.
-   Document enabling "Install unknown apps" for the app used to open
    the APK, if Android requests it.
-   Provide steps to transfer the APK by USB or another user-chosen
    method.
-   Explain that installing an APK manually does not bypass runtime
    permissions.
-   Keep the generated APK at a clearly documented location, for example
    `app/build/outputs/apk/release/`, only if that is the actual
    configured output path.
-   Generate a SHA-256 checksum for the final artifact and include it in
    the release notes.

------------------------------------------------------------------------

## 14. Final handoff checklist

Before declaring the work complete, provide a concise report with:

-   [ ] Project file tree.
-   [ ] Exact JDK, Gradle, Android Gradle Plugin, Kotlin, compile SDK,
    target SDK, and minimum SDK versions.
-   [ ] Commands executed and actual build/test/lint results.
-   [ ] Debug APK location and whether the path was verified.
-   [ ] Release APK location and whether the path was verified.
-   [ ] Installation steps for Redmi Note 10S / Android 12.
-   [ ] Required runtime permissions and optional integrations.
-   [ ] Local models used, sizes, licenses, and download/import
    instructions.
-   [ ] Wake phrase actually supported and any custom model
    requirements.
-   [ ] Battery, RAM, and performance measurements only if genuinely
    measured.
-   [ ] Known limitations, unimplemented features, and manual tasks.
-   [ ] Security/privacy review and confirmation that secrets/model
    files are not accidentally committed.

**Do not mark a feature complete merely because its class or UI
exists.** Mark it complete only when it builds and has been tested at
the appropriate level. Distinguish automated tests, emulator tests, and
real Redmi-device tests.

------------------------------------------------------------------------

## 15. Official documentation starting points

Use these official sources as the starting point and verify current
guidance during implementation:

-   Android Studio: https://developer.android.com/studio
-   Run apps on a hardware device:
    https://developer.android.com/studio/run/device
-   Android SDK Manager: https://developer.android.com/tools/sdkmanager
-   Foreground services:
    https://developer.android.com/develop/background-work/services/foreground-services
-   Microphone foreground service type:
    https://developer.android.com/develop/background-work/services/fgs/service-types#microphone
-   Runtime permissions:
    https://developer.android.com/training/permissions/requesting
-   Notification listener service:
    https://developer.android.com/reference/android/service/notification/NotificationListenerService
-   Android accessibility services:
    https://developer.android.com/guide/topics/ui/accessibility/service
-   Android TextToSpeech:
    https://developer.android.com/reference/android/speech/tts/TextToSpeech

Third-party wake-word, speech, and LLM runtimes must also be checked
against their own current official documentation and licenses.

------------------------------------------------------------------------

## Final instruction to Codex

Start with **Phase 0 only**. Inspect the current repository, create or
repair the Android project, document the environment, and produce a
buildable minimal app. Run the build and tests, report the actual
results, and list the exact next steps for the owner. Then continue
phase by phase, preserving a working build at every stage. Do not
attempt to implement every advanced feature in one unverified change.
