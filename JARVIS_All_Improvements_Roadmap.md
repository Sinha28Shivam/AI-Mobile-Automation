# JARVIS Android --- Consolidated Improvement Plan

**Repository:** https://github.com/Sinha28Shivam/AI-Mobile-Automation\
**Target device:** Redmi Note 10S\
**Development environment:** Windows 10/11, Android Studio, Kotlin\
**Document purpose:** Implementation-ready roadmap for improving
reliability, privacy, safety, testing, and release quality.

> **Important status note:** This document is a plan, not proof that the
> work has been implemented. Confirm each finding against the current
> branch before changing code. Run the existing build and tests first,
> record the baseline, and update this document as work is completed.

------------------------------------------------------------------------

## 1. Goals

1.  Make the existing voice-command flow stable before adding more
    features.
2.  Verify microphone and foreground-service behavior on the target
    Android device.
3.  Improve wake-word accuracy while managing battery and false
    activations.
4.  Make model installation recoverable and resistant to corrupted or
    untrusted archives.
5.  Keep all actions explicit, typed, permission-aware, and constrained
    to approved Android APIs.
6.  Protect voice-related data, notifications, and optional command
    history.
7.  Add automated and real-device tests with measurable acceptance
    criteria.
8.  Produce a reproducible, signed, tested release APK.

## 2. Scope and non-goals

### In scope

-   Build, lint, unit tests, instrumentation tests, and device testing.
-   Foreground microphone service and audio resource lifecycle.
-   Wake-word and speech-recognition quality.
-   Command parsing, follow-up confirmation, and error recovery.
-   Speech-model download/import/verification and safe installation.
-   Notifications, reminders, history, and privacy controls.
-   Optional local LLM command interpretation behind a strict validator.
-   Release signing, shrinker validation, documentation, and CI.

### Out of scope unless separately approved

-   Bypassing lock screens, passwords, patterns, biometric prompts, or
    app authentication.
-   Extracting credentials or private data from other apps.
-   Arbitrary shell execution, arbitrary code execution, or unrestricted
    intent launching.
-   Secretly recording, uploading, or retaining microphone audio.
-   Automatically enabling listening after reboot without an explicit,
    compliant product design.
-   Accessibility automation used as a general workaround for other
    apps' security boundaries.

## 3. Priority definitions

-   **P0 --- Stabilize first:** blocks confidence in core operation or
    creates a high-risk reliability issue.
-   **P1 --- Important:** required for dependable daily use and a
    credible release.
-   **P2 --- Expansion:** useful capability improvements after the core
    system is stable.

Do not begin a lower-priority feature while a P0 issue remains
unresolved.

------------------------------------------------------------------------

# Phase 0 --- Establish a reproducible baseline (P0)

> **Status (2026-10-27):** PARTIAL. `test` (252 unit tests pass), `lint` (0 errors), `assembleDebug`, `assembleRelease` and `assembleDebugAndroidTest` all run and succeed. Toolchain is recorded in README §2. The debug APK was installed on an API 31 emulator and on the Redmi Note 10S (Android 13, MIUI V140). Still open: one saved full baseline report (`scripts\baseline.ps1`), warnings listed separately, and a check that no secrets/models are tracked.

## Tasks

-   [ ] Confirm the branch and latest commit being reviewed.
-   [x] Record Android Studio, JDK, Gradle, Android Gradle Plugin,
    Kotlin, compile SDK, target SDK, and minimum SDK versions.
-   [x] Run the baseline checks from the repository root in PowerShell:

``` powershell
.\gradlew.bat --version
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

-   [ ] Save the complete console output and test report paths.
-   [ ] Record current warnings separately from failures.
-   [x] Install the debug APK on an emulator and the Redmi Note 10S.
-   [x] Do not claim tests pass unless the command was actually run and
    the result captured.
-   [ ] Confirm that secrets, keystores, local model files, and
    generated build output are not tracked by Git.

## Acceptance criteria

-   A clean checkout builds reproducibly.
-   Unit tests, lint, debug assembly, and release assembly have recorded
    outcomes.
-   Any existing failures are filed as issues with reproduction steps.
-   A baseline build can be installed and launched.

## Deliverables

-   Baseline build/test report.
-   Device/OS information.
-   List of existing failures and warnings.

------------------------------------------------------------------------

# Phase 1 --- Stabilize microphone and service lifecycle (P0)

> **Status (2026-10-27):** PARTIAL. The `ListeningSessionController` lifecycle (session tokens, idempotent start, `Stopping`, `stopAndJoin`, new pipeline per session, AudioRecord errors → `Error` with a recovery action, `MicrophoneSilenced`) is unit-tested for every §1.3 race case except the two device-only ones. On the Redmi, 5 Start/Stop cycles reached Listening in 5.8–8.0 s and released the mic ≤ 2.8–3.7 s after Stop. Still open: permission revoked from Settings, another app taking the mic and audio route changes on a device, ≥ 10 rapid cycles, and navigating away during each state.

**Primary files to inspect:** `AudioCaptureController.kt`,
`VoicePipeline.kt`, `JarvisForegroundService.kt`, `ServiceState.kt`,
`VoicePipelineConfig.kt`.

## 1.1 Define lifecycle ownership

-   [x] Document a single owner for each resource: `AudioRecord`,
    capture coroutine, voice pipeline, Vosk recognizers, shared Vosk
    model, TTS instance, command handler, and foreground notification.
-   [x] Ensure resources are closed exactly once.
-   [x] Ensure native recognizers are not closed while another coroutine
    is using them.
-   [x] Make shutdown completion observable, rather than assuming
    cancellation has completed immediately.
-   [x] Ensure Stop cannot be undone by a late callback or stale state
    update.
-   [x] Ensure repeated Start requests do not create duplicate capture
    jobs, recognizers, or notifications.
-   [x] Ensure an instance whose coroutine scope has been cancelled
    cannot be reused accidentally.

## 1.2 Handle capture failures

-   [x] Handle `AudioRecord` initialization failures and negative read
    return codes.
-   [x] Move the user-visible service state to a meaningful
    error/stopped state when capture ends unexpectedly.
-   [x] Stop or release the rest of the voice pipeline when capture
    fails.
-   [x] Ensure the UI cannot display `Listening` after the microphone
    has stopped.
-   [x] Ensure errors are reported without logging private audio or
    transcripts.
-   [ ] Verify behavior when microphone permission is revoked while the
    service is active.
-   [ ] Verify behavior when another application takes microphone
    priority or the audio route changes.

## 1.3 Race-condition test cases

-   [x] Start and Stop repeatedly, including rapid taps.
-   [x] Stop while the model is loading.
-   [x] Stop during wake-word recognition.
-   [x] Stop during command recognition.
-   [x] Stop while the command handler is executing.
-   [x] Stop while TTS is speaking.
-   [x] Start immediately after Stop.
-   [x] Trigger a capture error and then restart.
-   [ ] Revoke microphone permission from Android Settings while JARVIS
    is active.
-   [ ] Navigate away from and back to the app during each service
    state.

## Acceptance criteria

-   No duplicate microphone owners or active voice pipelines.
-   No crash, native-resource error, or stale `Listening` state in
    repeated lifecycle tests.
-   After Stop, the microphone indicator disappears within a measured
    and documented interval.
-   A failure produces a clear status and a safe recovery path.

------------------------------------------------------------------------

# Phase 2 --- Android and Redmi background compatibility (P0)

> **Status (2026-10-27):** PARTIAL. FGS start exceptions are handled, Talk works only while Listening, `START_NOT_STICKY`, nothing starts listening at boot, and `onTaskRemoved` keeps listening with the notification. Notification Stop passed in androidTest (API 31 emulator). Not yet tested: screen lock/off, reboot, force-stop, MIUI battery restrictions, the Android 13 notification prompt on the Redmi, and other API levels.

**Primary files to inspect:** `AndroidManifest.xml`,
`JarvisForegroundService.kt`, permission management and UI start/stop
code.

## Tasks

-   [ ] Verify foreground-service type and permissions against every
    supported Android version.
-   [x] Verify the service is started from an eligible, visible user
    action.
-   [x] Verify microphone permission is granted before capture starts.
-   [ ] Verify notification permission handling on Android 13+.
-   [x] Handle foreground-service start restrictions and exceptions
    explicitly.
-   [x] Ensure notification Stop works while listening.
-   [x] Ensure notification Talk behaves correctly only when the
    app/service state allows it.
-   [x] Ensure the UI status matches the actual service state.
-   [x] Document that force-stop terminates the service and that the app
    does not auto-start listening after reboot unless the design is
    explicitly changed.
-   [ ] Test screen lock, screen off, app backgrounding, process
    termination, and device reboot.
-   [ ] Test MIUI battery restrictions using default settings, then
    repeat with the user-selected no-restrictions option if necessary.
-   [x] Keep battery-optimization exemptions optional and
    user-controlled; do not silently change device settings.

## Device test matrix

  -----------------------------------------------------------------------
  Scenario                            Expected result
  ----------------------------------- -----------------------------------
  Start from visible app              Permission-aware foreground service
                                      starts

  Start without microphone permission Clear permission explanation; no
                                      capture attempt

  Notification permission denied on   App explains which notifications
  Android 13+                         may be unavailable

  Screen locked for 2 minutes         Record actual behavior; no assumed
                                      result

  Screen off for 30 minutes           Record service survival and battery
                                      drain

  Notification Stop                   Service stops and microphone is
                                      released

  Force-stop in system settings       Service ends; no false claim of
                                      continued listening

  Reboot                              Listening remains stopped unless a
                                      separately approved design
                                      specifies otherwise

  Permission revoked while listening  Safe failure and clear UI recovery
  -----------------------------------------------------------------------

## Acceptance criteria

-   No unsupported background start is relied upon.
-   The target device's real behavior is recorded with OS/MIUI version
    and test duration.
-   Any limitation is visible in user-facing documentation.

------------------------------------------------------------------------

# Phase 3 --- Wake-word quality and battery performance (P0/P1)

> **Status (2026-10-27):** PARTIAL (logic only). Wake phrase stripping, cooldown after a session, one session per utterance and the sensitivity gates (< 0.35 full phrase, ≥ 0.8 partials, RMS floor 300 → 0) are unit-tested. A diagnostics panel exists for measuring. **No accuracy, false-activation, latency, CPU or battery measurements yet.**

**Primary files to inspect:** `VoskKeywordWakeWordDetector.kt`,
`WakeWordDecider.kt`, `VoicePipeline.kt`, audio configuration.

## Current design consideration

The current wake-word implementation uses a grammar-constrained Vosk
recognizer. It is not a dedicated wake-word neural model. Its accuracy,
false activations, CPU use, and battery drain must be measured on the
actual device before deciding whether to replace it.

## Tasks

-   [ ] Create a repeatable test corpus for quiet speech, different
    distances, background conversation, music/TV, and different speaking
    volumes.
-   [ ] Measure true wake-word detections, missed detections, false
    activations, and time-to-trigger.
-   [ ] Measure CPU and battery use during idle listening and active
    recognition.
-   [ ] Test each sensitivity setting; confirm that the slider produces
    a meaningful measured difference.
-   [x] Review how partial and final recognizer results interact with
    cooldown and recognizer reset.
-   [x] Ensure one utterance cannot trigger multiple command sessions.
-   [x] Ensure the wake phrase is not included as a command unless
    intentionally supported.
-   [x] Keep tap-to-talk as a reliable fallback.
-   [ ] Compare a dedicated wake-word engine only after a baseline is
    available.
-   [ ] Evaluate model license, Android ABI support, offline operation,
    memory footprint, and maintenance status before adopting another
    engine.

## Suggested initial acceptance targets

Treat these as targets to validate and adjust, not existing results:

-   At least 95% wake-word detection in a controlled quiet-room test
    set.
-   No more than 1 false activation per hour in a defined ordinary
    conversation/media test.
-   No duplicate trigger from a single utterance.
-   Documented median and 95th-percentile wake-word latency.
-   Battery drain measured over at least 30 minutes of screen-off
    listening.

If targets cannot be achieved, document the limitation and improve the
detector or use tap-to-talk rather than claiming reliability.

------------------------------------------------------------------------

# Phase 4 --- Speech recognition and command-endpoint reliability (P1)

> **Status (2026-10-27):** PARTIAL. No stuck sessions on `finish()`/handler/TTS failure, a low-confidence gate, frames dropped during processing/TTS, and resume only for the current session, all unit-tested. On-device recognition and timeout tests are not yet run.

**Primary files to inspect:** `LocalSpeechEngineAdapter.kt`,
`CommandEndpointer.kt`, `VoskJson.kt`, `VoicePipeline.kt`.

## Tasks

-   [ ] Test short commands, long commands, pauses, no speech, and
    speech with background noise.
-   [ ] Test the 4-second no-speech timeout, trailing-silence timeout,
    and maximum utterance duration on-device.
-   [ ] Verify final Vosk segments are not lost or duplicated when the
    utterance ends.
-   [x] Test unknown-token handling and confidence aggregation.
-   [x] Verify low-confidence speech cannot trigger a consequential
    action.
-   [x] Ensure frames captured during command processing and TTS are
    discarded as intended.
-   [ ] Measure command recognition accuracy for every supported phrase.
-   [x] Provide an understandable retry prompt for low confidence and no
    speech.
-   [x] Verify TTS failure does not leave the pipeline stuck in
    `Speaking`.
-   [x] Verify the pipeline resumes listening only if the service
    remains active.

## Acceptance criteria

-   No stuck command session after no speech, timeout, malformed
    recognizer output, or TTS failure.
-   A test matrix records recognition results for each supported
    command.
-   Uncertain recognition does not execute a potentially consequential
    action.

------------------------------------------------------------------------

# Phase 5 --- Command parser, confirmations, and recovery (P0/P1)

> **Status (2026-10-27):** PASS for JVM tests (CommandParserTest 20, CommandRouterTest 41). On-device phrase checks remain in Phase 14.

**Primary files to inspect:** `CommandParser.kt`, `CommandRouter.kt`,
`ModelCommandValidator.kt`, command result types.

## Tasks

-   [x] Preserve deterministic parsing as the first command path.
-   [x] Add tests for punctuation, wake prefixes, common
    speech-recognition substitutions, and unsupported wording.
-   [x] Test ambiguous app names and apps that are not installed.
-   [x] Test malformed reminder phrases, invalid delays, and empty
    reminder titles.
-   [x] Test every yes/no phrase used for confirmations.
-   [x] Add an expiry time for pending confirmation and app-choice
    states.
-   [x] Ensure unrelated speech does not accidentally confirm a pending
    action.
-   [x] When a response is not a valid confirmation, ask the user to
    repeat the answer or cancel the pending flow.
-   [x] Ensure Stop always cancels pending follow-ups and takes priority
    over other commands.
-   [x] Ensure Repeat replays only the intended previous response and
    does not execute a previous action again.
-   [x] Ensure failures are reported honestly; do not say an action
    succeeded if Android rejected it.
-   [x] Keep the recognized text out of normal logs.

## Acceptance criteria

-   All parser and router tests pass.
-   Pending questions cannot remain indefinitely.
-   Unrelated speech does not trigger a pending action.
-   Every tool result maps to a truthful spoken response.

------------------------------------------------------------------------

# Phase 6 --- Model download, archive security, and recoverable installation (P1)

> **Status (2026-10-27):** PASS for unit tests, PARTIAL on device. `ModelInstaller`: HTTPS host allow-list, SHA-256 + upstream MD5 + exact size, limits, zip-slip, free space, staging + atomic swap + backup recovery, cancellation, Vosk test load, delete refused while listening, repair. Download + checksum done on the emulator. Upstream publishes no SHA-256, so that item stays open (trust basis in docs/LOCAL_MODELS.md).

**Primary files to inspect:** `LocalModelManager.kt`, `ModelArchive.kt`,
model specification/configuration.

## Tasks

-   [x] Verify the download host uses HTTPS and redirects are handled
    safely.
-   [ ] Verify the expected SHA-256 hash from a trusted upstream
    artifact or independently trusted release manifest.
-   [x] Do not assume a hardcoded checksum is trustworthy merely because
    it is in source code.
-   [x] Enforce compressed and uncompressed size limits.
-   [x] Retain zip-slip protection and test absolute paths, `..`
    traversal, Windows drive prefixes, and sibling-prefix path tricks.
-   [x] Ensure installation files remain inside app-private storage.
-   [x] Test truncated, corrupt, empty, wrong-model, and oversized
    archives.
-   [x] Test cancellation during download, hashing, extraction,
    validation, and installation.
-   [x] Check available disk space before extraction when practical.
-   [x] Extract into a staging directory and validate all required files
    before switching to the active model.
-   [x] Preserve the last known-good model until the replacement has
    passed validation.
-   [x] Clean up temporary files after failure without deleting a
    working model.
-   [x] Verify installed files are readable and the Vosk native runtime
    can load them.
-   [x] Show model size, progress, checksum/validation status, and
    actionable errors in the UI.
-   [x] Test update/install behavior when a model is already installed.
-   [x] Ensure deletion waits for the listening service and model
    resources to close.

## Acceptance criteria

-   A failed or cancelled install does not destroy a previously working
    model.
-   Unsafe archives are rejected.
-   A valid model can be installed, loaded, deleted, and reinstalled.
-   Model provenance and license are documented.

------------------------------------------------------------------------

# Phase 7 --- Privacy and security controls (P0/P1)

> **Status (2026-10-27):** PARTIAL. Notification cache (5 entries / 10 min, memory only, secret-visibility skipped, sensitive apps + OTP heuristic + exclusions, cleared on revoke), `HistorySanitizer`, a confirmation before enabling history, and backups off are implemented and unit-tested. No transcripts appeared in Redmi logcat during Start/Stop. Network inspection, an offline run, a full log review and Clear History from the UI are still open.

**Primary files to inspect:** notification listener, history repository,
manifest, logs, model downloads, command router.

## Audio and transcripts

-   [x] Do not save raw microphone audio by default.
-   [x] Do not upload audio or transcripts.
-   [ ] Ensure logs contain no audio, transcript, notification body,
    reminder title, or private command payload.
-   [x] Use generic diagnostic messages and exception class names rather
    than private content.
-   [ ] Verify offline speech and command functions work with the
    network disconnected after model installation.

## Notification access

-   [x] Explain that notification access may expose message previews,
    OTPs, and other sensitive content.
-   [x] Keep notification access optional.
-   [x] Consider per-app exclusions or a user setting to disable reading
    selected apps.
-   [x] Keep notification content in memory only for the shortest
    practical time.
-   [x] Clear retained notification content when access is revoked and
    on appropriate lifecycle transitions.
-   [x] Do not store notification text in command history by default.
-   [x] Test that notification access is checked at the time of use, not
    only at startup.

## Command history

-   [x] Keep history disabled by default.
-   [x] Clearly explain what is stored before the user enables history.
-   [x] Keep retention bounded and provide a working Clear History
    action.
-   [ ] Test clearing history from both the UI and repository.
-   [x] Confirm backup settings match the privacy statement.
-   [x] Do not silently add analytics or crash-reporting SDKs that
    transmit sensitive content.

## Android actions and authentication

-   [x] Use fixed, reviewed Android APIs and allow-listed destinations.
-   [x] Do not implement arbitrary shell commands or arbitrary intent
    dispatch.
-   [x] Do not bypass app locks, password screens, patterns, or
    biometric prompts.
-   [x] When opening a protected app, allow Android and that app to
    handle authentication normally.
-   [ ] Ask for confirmation before any future action with meaningful
    consequences.
-   [x] Make it easy to stop listening and revoke optional access.

## Acceptance criteria

-   Privacy claims match observed behavior.
-   Network inspection confirms that audio/transcripts are not
    transmitted.
-   Logs contain no private command or notification content.
-   Users can stop listening and clear optional history.

------------------------------------------------------------------------

# Phase 8 --- Reminders and notification delivery (P1)

> **Status (2026-10-27):** PARTIAL. Persistent `ReminderStore` (unique ids, max 50), 1–1440 min, notifications-enabled check, strict receiver validation, `ReminderBootReceiver` (reschedules only), missed notice ≤ 24 h, rescheduling on app start, inexact alarms, all unit-tested. A forged intent is rejected in androidTest. Delivery after process death/reboot and with the channel disabled has not been tested on a device.

**Primary files to inspect:** `AndroidReminderTool.kt`,
`ReminderReceiver`, notification channels, manifest.

## Current limitation

The current reminder implementation uses inexact `AlarmManager.set()`
and is documented as not surviving reboot. It should not be marketed as
an exact alarm or guaranteed persistent reminder.

## Tasks

-   [ ] Test reminder scheduling for minimum and maximum supported
    delays.
-   [x] Verify notifications are enabled and explain when they are
    disabled.
-   [x] Verify the reminder receiver rejects malformed or unexpected
    intents.
-   [x] Ensure pending intents use appropriate immutable flags and
    unique identifiers.
-   [x] Ensure two reminders with the same title can coexist.
-   [ ] Verify behavior after app process death and device reboot.
-   [ ] If reminders must survive reboot, design and test persistence
    and rescheduling explicitly.
-   [x] Explain inexact delivery and do not promise exact timing.
-   [x] Request exact-alarm capabilities only if a justified product
    requirement exists and applicable Android policies are satisfied.
-   [ ] Test notification channel configuration and user-disabled
    channels.

## Acceptance criteria

-   A scheduled reminder either produces the expected notification or
    reports a meaningful limitation.
-   Duplicate reminders do not overwrite each other unexpectedly.
-   The UI accurately explains reboot and timing behavior.

------------------------------------------------------------------------

# Phase 9 --- Add an optional local LLM safely (P2)

> **Status (2026-10-27):** PARTIAL (guard rails only). `LocalLanguageModel`, `GuardedAiInterpreter`, `PromptBuilder` and the strict `ModelCommandValidator` are unit-tested. **No LLM runtime or model ships**; benchmarking and model selection have not started.

**Primary files to inspect:** `CommandRouter.kt`,
`ModelCommandValidator.kt`, `LocalModelManager.kt`, model configuration.

## Design

The local language model must not execute tools directly. It may propose
one typed action, which must pass validation before the existing command
router executes it.

``` text
Recognized speech
    -> Deterministic parser
        -> Known command: execute approved tool
        -> Unknown command: optional local LLM
            -> Strict structured-output parser
                -> Schema and argument validation
                    -> Approved command type
                        -> Existing command router
```

## Tasks

-   [ ] Benchmark the existing deterministic parser before adding a
    model.
-   [ ] Select a quantized model only after measuring RAM, latency,
    storage, license, and Android runtime support.
-   [ ] Use an offline-capable runtime with a supported Android ABI.
-   [ ] Run inference off the main thread.
-   [x] Enforce a bounded inference timeout and cancellation.
-   [x] Limit input and output sizes.
-   [x] Accept only an exact, documented schema.
-   [x] Reject unknown tool names, missing arguments, extra arguments,
    invalid types, and out-of-range values.
-   [x] Never allow model output to request arbitrary code, shell
    commands, or unrestricted intents.
-   [x] Require confirmation for future consequential actions.
-   [x] Fall back to the deterministic parser when the model is absent,
    slow, invalid, or out of memory.
-   [ ] Provide model download/import, verification, deletion, and
    license information.
-   [x] Test prompt injection in recognized speech and malicious strings
    in model output.
-   [x] Do not transmit speech to a cloud model without a separate,
    explicit opt-in design.

## Acceptance criteria

-   Core commands work with the LLM removed.
-   Invalid model output never executes a tool.
-   Inference failure returns a safe response and the pipeline recovers.
-   The model's resource use is measured on the target phone.

------------------------------------------------------------------------

# Phase 10 --- Controlled expansion of phone automation (P2)

> **Status (2026-10-27):** PARTIAL. App-name ranking (max 3 options), read-only date/charging, and settings navigation (open only, never toggle) are unit-tested. No new app integrations.

## Feature design rules

Every new action must have:

1.  A typed command and explicit input schema.
2.  A dedicated Android tool interface and implementation.
3.  A permission and privacy review.
4.  Clear success and failure outcomes.
5.  A confirmation rule for consequential actions.
6.  Unit tests and device-level tests.
7.  Documentation of unsupported devices or app-specific limitations.

## Suggested sequence

-   [x] Improve app launch and app-name resolution.
-   [x] Add read-only device information using approved Android APIs.
-   [x] Add user-requested navigation to supported settings screens.
-   [ ] Add a controlled app-specific integration only where an official
    API or supported intent exists.
-   [ ] Evaluate AccessibilityService only for a clearly justified
    accessibility/assistive use case, with informed user enablement and
    narrow scope.
-   [x] Do not promise universal automation: apps may block external
    control or require user interaction.
-   [ ] Treat banking, payments, authentication, password managers, and
    other sensitive apps as protected workflows that require their own
    explicit security design.

## Acceptance criteria

-   Each capability is implemented and tested independently.
-   Unsupported actions fail honestly.
-   No feature bypasses another app's security controls.

------------------------------------------------------------------------

# Phase 11 --- Automated tests and continuous integration (P0/P1)

> **Status (2026-10-27):** PARTIAL. 252 JVM tests pass. The androidTest suite ran on the API 31 emulator: 20 passed, 2 skipped by design. The Redmi run is blocked by MIUI `INSTALL_FAILED_USER_RESTRICTED` (enable Install via USB). `android-ci.yml`, `release.yml` and `dependabot.yml` were added, but no GitHub run or branch protection is recorded yet.

## JVM unit tests

-   [x] Keep parser, router, validator, endpointer, wake-word decision
    logic, archive helpers, and state-machine tests.
-   [ ] Add regression tests for every discovered bug.
-   [x] Add tests for pending confirmation expiry and unrelated
    follow-up speech.
-   [x] Add cancellation and repeated-start/stop tests.
-   [ ] Ensure tests do not depend on wall-clock timing where a fake
    clock can be used.

## Android instrumentation tests

-   [x] Add `androidTest` coverage for permission flows.
-   [ ] Test foreground-service start and stop on supported API levels.
-   [x] Test notification channels and actions.
-   [ ] Test model import and UI state transitions.
-   [x] Test TTS initialization failure and success where practical.
-   [x] Use fakes or injectable adapters where native hardware cannot be
    reliably tested in CI.

## CI pipeline

-   [ ] Run unit tests, lint, and debug assembly on each pull request.
-   [ ] Run release assembly and shrinker-related smoke checks in a
    protected workflow.
-   [ ] Store test reports as CI artifacts.
-   [x] Do not put signing secrets or API keys in source control or
    public logs.
-   [ ] Do not label a workflow green if a required task was skipped.
-   [ ] Test the minified release build separately from the debug build.

## Acceptance criteria

-   Pull requests cannot merge with required CI checks failing.
-   Failures are reproducible from documented commands.
-   Test reports are retained and linked to the build.

------------------------------------------------------------------------

# Phase 12 --- Performance and usability (P1)

> **Status (2026-10-27):** PARTIAL. The UI shows real states, including Stopping and MicrophoneSilenced, with recovery buttons and a diagnostics panel. Only Start → Listening (5.8–8.0 s, includes model load) and Stop → mic released (≤ 2.8–3.7 s) were measured on the Redmi. Memory, CPU, battery and latency are not measured.

## Measurements

Record measurements on the Redmi Note 10S for:

-   [ ] Model load time.
-   [ ] Memory use after model load and during active listening.
-   [ ] CPU usage during idle listening and active speech.
-   [ ] Battery consumption over a defined screen-off period.
-   [ ] Wake-word latency.
-   [ ] Speech endpointing time.
-   [ ] End-to-end command-to-response latency.
-   [ ] Recovery time after an error.
-   [ ] App size and installed model storage size.

## UI improvements

-   [x] Show the actual service state: stopped, starting, listening,
    processing, speaking, and error.
-   [x] Explain microphone and notification permissions at the point of
    need.
-   [x] Show whether the speech model is installed and ready.
-   [ ] Make Stop Listening prominent.
-   [x] Show actionable recovery guidance instead of raw stack traces.
-   [ ] Explain unavailable TTS voices and how to install an offline
    voice.
-   [ ] Make the privacy and history settings easy to find.
-   [x] Avoid claiming that the assistant is listening when the service
    is inactive.
-   [x] Provide a useful fallback when wake-word detection or TTS is
    unavailable.

## Acceptance criteria

-   Performance figures are measured rather than guessed.
-   UI status reflects real service state.
-   Errors tell the user what they can do next.

------------------------------------------------------------------------

# Phase 13 --- Release, signing, and distribution (P0 before shipment)

> **Status (2026-10-27):** PARTIAL. R8 release builds. `scripts\verify-release.ps1` confirms `libvosk.so` + `libjnidispatch.so` for all 4 ABIs and that the Vosk/JNA classes were kept. The CI release workflow signs from secrets or produces an UNSIGNED artifact. OSV scan of 113 release deps: no advisories. A signed APK has not been installed on a device; update/reinstall and the licence review are still open.

## Tasks

-   [ ] Confirm application ID, version name, version code, minimum SDK,
    target SDK, and release notes.
-   [ ] Generate a release keystore and keep it outside the repository.
-   [ ] Store signing secrets in a secure local or CI secret store.
-   [ ] Verify `.gitignore` excludes keystores, signing properties,
    secrets, model binaries, and generated build output.
-   [x] Build a release APK with R8/minification enabled.
-   [ ] Install and test the minified release APK on the target phone.
-   [x] Verify Vosk/JNA and any native libraries survive shrinking and
    packaging.
-   [ ] Verify APK signing and signature consistency across updates.
-   [ ] Test an in-place update to confirm model and user settings are
    preserved as intended.
-   [ ] Test uninstall/reinstall and document what user data is removed.
-   [ ] Review dependency licenses and model license/redistribution
    terms.
-   [x] Scan dependencies and review relevant security advisories.
-   [ ] Provide a checksum for the distributed APK.
-   [ ] Document installation, permissions, model setup, known
    limitations, and rollback steps.

## Acceptance criteria

-   The signed release APK installs and launches.
-   Core voice commands work in the minified build.
-   Updates work with the same signing key.
-   No signing secrets or private model artifacts are committed.
-   Release notes accurately state known limitations.

------------------------------------------------------------------------

# Phase 14 --- Final real-device regression checklist

> **Status (2026-10-27):** NOT STARTED for the signed release. Debug build on the Redmi: install/launch OK, microphone granted, 5 Start/Stop cycles OK. Everything else is open; see docs/TEST_PLAN.md §2 and docs/ROADMAP_STATUS.md.

Complete this checklist on the actual target device and record **Pass /
Fail / Blocked**, Android version, MIUI version, app version, and notes.

-   [ ] Fresh install and onboarding.
-   [x] Microphone permission granted.
-   [ ] Microphone permission denied and later granted.
-   [ ] Model download and checksum verification.
-   [ ] Model import through the system file picker.
-   [ ] Corrupt archive rejection.
-   [ ] Start/Stop repeated at least 10 times.
-   [ ] Wake word in a quiet room.
-   [ ] Wake word with television/music in the background.
-   [ ] No-speech timeout.
-   [ ] Low-confidence recognition recovery.
-   [ ] Tap-to-talk.
-   [ ] Open an installed app.
-   [ ] Handle a missing or ambiguous app.
-   [ ] Battery and time responses.
-   [ ] Open supported settings pages.
-   [ ] Repeat and Stop commands.
-   [ ] Notification access enabled, disabled, and revoked while
    running.
-   [ ] Reminder delivery and notification-disabled behavior.
-   [ ] TTS unavailable or offline voice missing.
-   [ ] Screen locked and screen off.
-   [ ] MIUI background restrictions.
-   [ ] Network disabled after model installation.
-   [ ] Verify no transcript or notification content appears in logs.
-   [ ] Verify no audio/transcript network transmission.
-   [ ] Model deletion while listening.
-   [ ] App force-stop and reboot behavior.
-   [ ] Signed release install and in-place update.
-   [ ] Clear history and verify it is no longer displayed.
-   [ ] Review permissions after installation.

------------------------------------------------------------------------

# 4. Recommended implementation order

  ------------------------------------------------------------------------
                         Order Phase                 Exit condition
  ---------------------------- --------------------- ---------------------
                             1 Baseline              Build/test results
                                                     recorded

                             2 Lifecycle             Stop/start/error
                                                     recovery stable

                             3 Android compatibility Foreground microphone
                                                     behavior verified on
                                                     target device

                             4 Wake word and speech  Accuracy and power
                                                     measured

                             5 Command routing       Confirmation and
                                                     recovery tests pass

                             6 Model installation    Corrupt/interrupted
                                                     installs recover
                                                     safely

                             7 Privacy               Data retention and
                                                     network behavior
                                                     verified

                             8 Reminders             Limitations and
                                                     notification delivery
                                                     verified

                             9 CI and                Required automated
                               instrumentation       checks run
                                                     consistently

                            10 Local LLM             Strict validated tool
                                                     proposals work with
                                                     fallback

                            11 Expanded automation   Each new action has a
                                                     safety and test
                                                     contract

                            12 Release               Signed minified APK
                                                     passes regression
                                                     checklist
  ------------------------------------------------------------------------

------------------------------------------------------------------------

# 5. Instructions for an AI coding agent

Use these instructions if implementing the roadmap with Codex or another
coding agent.

1.  Read the current repository and this plan before editing.
2.  Work in small phases, one phase per change set.
3.  Inspect the current implementation before assuming a documented
    issue still exists.
4.  Do not rewrite working modules wholesale when a targeted change is
    sufficient.
5.  Do not invent build results, device test results, benchmark numbers,
    or feature completion.
6.  Preserve the offline-first default and existing command allow-list.
7.  Add regression tests for every bug fix.
8.  Keep private speech and notification content out of logs.
9.  Do not add permissions without a documented feature requirement and
    user-facing explanation.
10. Do not add cloud processing by default.
11. Do not implement credential extraction, lock-screen bypasses, or
    unrestricted UI automation.
12. Run the relevant tests after every change; report failures honestly.
13. Keep documentation synchronized with actual code and observed
    behavior.
14. If a task requires physical-device access, mark it **Blocked ---
    device verification required** and provide precise manual steps
    instead of claiming success.
15. Before finishing each phase, provide:
    -   Files changed.
    -   Design decisions.
    -   Tests run and exact results.
    -   Remaining risks.
    -   Manual device checks still required.
    -   Suggested next phase.

## Required phase completion report template

``` text
Phase:
Status: PASS / PARTIAL / BLOCKED

Changes:
- ...

Tests run:
- Command:
- Result:

Device verification:
- Device / Android / MIUI:
- Result or Blocked reason:

Known limitations:
- ...

Next recommended phase:
- ...
```

------------------------------------------------------------------------

# 6. Definition of done

JARVIS is ready for a controlled personal release only when:

-   [ ] Core build and required automated checks pass.
-   [ ] Microphone lifecycle and foreground-service behavior are
    verified on the target phone.
-   [ ] Wake-word accuracy, false activation, latency, and battery
    measurements are documented.
-   [ ] Model installation handles corruption and cancellation safely.
-   [ ] Command confirmations and error recovery are tested.
-   [ ] Privacy claims are verified against observed behavior.
-   [ ] The signed, minified release APK passes the device regression
    checklist.
-   [ ] All remaining limitations are documented honestly.

**Final principle:** prioritize reliability, user control, privacy, and
measurable evidence over feature count. Add advanced AI and broader
phone automation only after the existing assistant is dependable.
