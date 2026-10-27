# Test plan

Three levels are distinguished, as required by the specification:

| Level | Status |
|---|---|
| Automated JVM unit tests (`.\gradlew.bat test`) | Exist (inventory below). Run the command for the current pass/fail result; report: `app\build\reports\tests\testDebugUnitTest\index.html`. |
| Emulator tests | **Not yet run.** No instrumentation (`androidTest`) tests exist yet. |
| Real-device tests (Redmi Note 10S, Android 12 / MIUI) | **Not yet run.** |

Build checks to run and record:

```powershell
.\gradlew.bat --version
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

Unit tests run on the JVM with `unitTests.isReturnDefaultValues = true` (so `android.util.Log`
calls are no-ops) and real `org.json` for Vosk-JSON parsing. Vosk native code, `AudioRecord`,
`TextToSpeech`, and Android intents are **not** exercised by unit tests.

## 1. Unit test inventory (`app/src/test/java/com/jarvis/assistant/`)

| Test class | Class under test | Tests | What is covered |
|---|---|---|---|
| `commands/CommandParserTest` | `CommandParser` | 13 | Spec examples ("Open YouTube", "What is my battery level?", "What time is it?", "Open Wi-Fi settings", "Stop listening", "Repeat that"); open-app, battery, time, settings, stop/repeat, notification and reminder variants; invalid reminders → Unknown; unknown/empty input; low confidence → Unknown; original text kept; normalization (wake prefix, fillers, punctuation) |
| `commands/CommandRouterTest` | `CommandRouter` | 14 | Stop sets `stopRequested`; repeat; basic tools without AI; honest failure messages; ambiguous app → follow-up choice; notification access off → confirmation offer; notification read; reminder scheduling; AI absent / valid / invalid / throwing or slow → fallback; stop wins over pending confirmation; `describeDelay` |
| `commands/ModelCommandValidatorTest` | `ModelCommandValidator` | 8 | Valid commands; unknown tools; missing/extra args; wrong types; out-of-range reminders; malformed JSON; oversize input; injection-style strings |
| `tools/AppNameResolverTest` | `AppNameResolver` | 5 | Exact beats prefix; prefix/contains; ambiguous; duplicate package not ambiguous; missing app |
| `wakeword/WakeWordDeciderTest` | `WakeWordDecider` | 10 | Trigger above threshold; ignores `[unk]`/other words; whole-word, case-insensitive match; sensitivity→threshold map; strict rejection; cooldown debounce; rejected detections don't start cooldown; partial only at high sensitivity; cooldown reset; blank keyword rejected |
| `speech/CommandEndpointerTest` | `CommandEndpointer` | 6 | Frame duration; no-speech timeout (4 s); trailing-silence end; max duration; final segment semantics; sticky end state |
| `speech/VoskJsonTest` | `VoskJson` | 6 | Word/confidence parsing; empty/malformed JSON; text without word array; confidence averaging, `[unk]` dropped; grammar contains `[unk]` once |
| `speech/ModelArchiveTest` | `ModelArchive` | 12 | Path resolution; zip-slip rejection (incl. sibling-prefix trick); SHA-256 of stream/file; max-byte limit; extraction & model-root discovery; missing files; uncompressed-size and entry-count limits; empty archive |
| `service/VoicePipelineTest` | `VoicePipeline` (with fake detector/engine/handler/speaker) | 10 | Wake word → command → handled → spoken; returns to Listening and restarts wake word; low confidence skips handler; no-speech reply; tap-to-talk; frames ignored while speaking; stop request invokes callback instead of resuming; handler exception → error reply and recovery; stop releases engines; stop before start still releases |
| `service/ServiceStateTest` | `ServiceState`, `ServiceStateRepository` | 6 | Initial Stopped; `isActive` per state; start/stop transitions; `updateIfActive` ignored when stopped / applied when listening; error label |
| `data/LocalHistoryRepositoryTest` | `LocalHistoryRepository` | 2 | Disabled history records nothing; max 20 entries kept; clear |

Total: **92** unit tests in 11 classes (counted from source; tests added by the parallel
integration work, if any, are not listed here).

Spec §11 unit-test requirements → coverage:

| Requirement | Covered by |
|---|---|
| Parser maps supported utterances | `CommandParserTest` |
| Unknown/malformed input rejected safely | `CommandParserTest`, `ModelCommandValidatorTest` |
| Model JSON with unknown tools/invalid params rejected | `ModelCommandValidatorTest`, `CommandRouterTest.aiInvalidOutputRejected` |
| App-name missing/ambiguous | `AppNameResolverTest`, `CommandRouterTest.ambiguousAppThenChoice` |
| Permission/state transitions | `ServiceStateTest` (permission handling itself is Android-only → manual) |
| Stop-listening cancels active work | `VoicePipelineTest.stopRequestedInvokesCallbackInsteadOfResuming`, `stopReleasesEngines`, `CommandRouterTest.stopWinsOverPendingConfirmation` |

## 2. Manual device checklist (spec §11)

Device: Redmi Note 10S, Android 12, MIUI version: ________. APK: debug / release, version ______.
Fill in **Result** (Pass / Fail / Partial) and **Notes** with what actually happened.

| # | Test | Steps | Expected | Result | Notes |
|---|---|---|---|---|---|
| 1 | Fresh install & onboarding | Uninstall, install APK, open | Welcome screen; no permission prompt; "Got it" → home; "Speech model not installed" | Not yet run | |
| 2 | Model download | Speech model → Download on Wi-Fi | Progress, "Verifying", then "Ready (… MB)" | Not yet run | |
| 3 | Model import | Import the official zip via file picker | Ready; a different/renamed zip is rejected | Not yet run | |
| 4 | Microphone granted | Start Listening → Allow | Notification appears; status Listening | Not yet run | |
| 5 | Microphone denied | Fresh install → Start → Don't allow | Status "Microphone permission required" + Open app settings | Not yet run | |
| 6 | Notification permission granted/denied | Android 13+ device only (not applicable to Android 12 Redmi) | Listening works either way | Not yet run | |
| 7 | Start/stop repeatedly | Start/Stop 10× quickly, also via notification Stop | No crash; mic indicator goes off after Stop | Not yet run | |
| 8 | Lock and unlock screen | Start, lock 2 min, say "Hey Jarvis …", unlock | Record actual behavior | Not yet run | |
| 9 | Open a requested app | "Hey Jarvis" → "Open YouTube" | YouTube opens; JARVIS says "Opening YouTube" | Not yet run | |
| 10 | Ambiguous / missing app | "Open Google" / "Open Foobar" | Asks which one / "couldn't find" | Not yet run | |
| 11 | Battery, time, Wi-Fi settings, repeat, stop | Spec example phrases | Correct spoken replies / settings page opens / stops | Not yet run | |
| 12 | Tap to talk | Tap to talk, say command | Works without wake word | Not yet run | |
| 13 | No speech | Wake, then stay silent | "I didn't hear a command." after ~4 s; back to Listening | Not yet run | |
| 14 | Noisy room | TV/music on, try wake word and commands | Record accuracy | Not yet run | |
| 15 | Wake-word false positives | 30 min of normal conversation/TV without saying it | Record count of false triggers per sensitivity | Not yet run | |
| 16 | Model missing | Delete model, Start | Start disabled / "Speech model not installed" | Not yet run | |
| 17 | Model corrupt | Import a truncated/other zip | "File check failed…"; nothing installed | Not yet run | |
| 18 | Out of memory | Many heavy apps open, Start | Error message, no crash (may be hard to reproduce) | Not yet run | |
| 19 | Notification access | Enable, "read my notifications"; then disable while app active and ask again | Reads latest; after disable offers to open settings | Not yet run | |
| 20 | Reminder | "Remind me to test in 1 minute" | Notification within a few minutes (inexact) | Not yet run | |
| 21 | TTS voice missing/offline | Airplane mode; remove offline voice | Record behavior | Not yet run | |
| 22 | Reboot | Start listening, reboot | Listening does not auto-start (documented behavior) | Not yet run | |
| 23 | Force-stop | Settings → Force stop while listening | Service ends, notification gone; does not restart | Not yet run | |
| 24 | MIUI background | Screen off 30 min while listening, default battery settings, then "No restrictions" | Record whether service survives | Not yet run | |
| 25 | Nothing uploaded | After model install, airplane mode → full voice flow works; optionally monitor traffic | Works offline; no network traffic | Not yet run | |
| 26 | Nothing logged | `adb logcat` during commands | No transcript/reply/notification text in JARVIS log lines | Not yet run | |
| 27 | No unexpected permissions | Settings → Apps → JARVIS → Permissions; `adb shell dumpsys package com.jarvis.assistant` | Only Microphone (+ Notifications); install-time ones listed in ANDROID_PERMISSIONS.md | Not yet run | |
| 28 | Update keeps data | `adb install -r` newer build, same key | Model still installed | Not yet run | |

## 3. Measurements (Phase 7) — not yet measured

| Metric | Value |
|---|---|
| Battery drain per hour while listening (screen off) | Not measured |
| RAM (PSS) while listening | Not measured |
| Wake → reply latency | Not measured |
| Model load time | Not measured |
