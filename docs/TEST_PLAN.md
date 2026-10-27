# Test plan

Three levels of testing:

| Level | Status (2026-10-27) |
|---|---|
| Automated JVM unit tests (`.\gradlew.bat test`) | **252 tests in 21 classes, all pass.** Report: `app\build\reports\tests\testDebugUnitTest\index.html`. Lint: 0 errors. |
| Instrumentation tests (`androidTest`, `.\gradlew.bat connectedDebugAndroidTest`) | 22 tests in 7 classes. **Android 12 (API 31) emulator: 20 passed, 2 skipped by design** (assumption-gated, see §1b). **Redmi Note 10S: blocked**, MIUI returns `INSTALL_FAILED_USER_RESTRICTED` until Developer options → **Install via USB** is on. |
| Real-device manual tests (Redmi Note 10S, model M2101K7BI, Android 13, MIUI V140) | **Partly run** with the debug build on 2026-10-27; see §2. |

Build checks (all run on 2026-10-27; debug, release and androidTest assemble OK):

```powershell
.\gradlew.bat --version
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
.\gradlew.bat assembleDebugAndroidTest
.\scripts\verify-release.ps1 -SkipBuild   # native libs for all 4 ABIs, R8-kept Vosk/JNA classes, signature, SHA-256
.\scripts\baseline.ps1                    # runs the set above and saves timestamped logs
```

Unit tests run on the JVM with `unitTests.isReturnDefaultValues = true` (so `android.util.Log`
calls are no-ops) and real `org.json` for Vosk-JSON parsing. Vosk native code, `AudioRecord`,
`TextToSpeech`, and Android intents are **not** exercised by unit tests. Lifecycle code is
tested through fakes (`FakeAudioSource`, fake detectors, engines, handlers and speakers) and
injectable clocks, not through wall-clock sleeps.

## 1. Unit test inventory (`app/src/test/java/com/jarvis/assistant/`)

Counts are the number of `@Test` methods in each file.

| Test class | Class under test | Tests | What is covered |
|---|---|---|---|
| `ai/ModelInstallerTest` | `ModelInstaller` | 22 | Valid install + load check; delete and reinstall; wrong SHA-256 / MD5 mismatch keeps the working model; truncated, oversized, empty, corrupt-but-hash-matching, wrong-model, empty-required-file archives; uncompressed/entry limits; zip-slip with matching hash; failed load check keeps old model; insufficient space reported before download; unknown free space; cancellation at every stage; cancel mid-download with no prior model; update replaces model; recovery of an interrupted swap; marker with wrong hash; real spec matches upstream size/limits |
| `ai/ModelDownloadPolicyTest` | `ModelDownloadPolicy`, error messages | 3 | HTTPS + exact host allow-list (scheme, port, user-info, look-alike hosts); relative/absolute redirect resolution; actionable error messages |
| `commands/CommandParserTest` | `CommandParser` | 20 | Spec examples; open-app/battery/time/settings/stop/repeat/notification/reminder variants; typed invalid reminders; number words 1–60; wake prefixes & punctuation; Vosk substitutions; toggles typed but unsupported; unsupported wording; date and charging; yes/no classification; unknown/empty; low confidence; normalization |
| `commands/CommandRouterTest` | `CommandRouter` | 41 | Stop/repeat; tools without AI; honest failures; ambiguous app → choice (by name or ordinal), capped options, duplicate labels; not-installed app; notification access offer; reminders; AI absent/valid/invalid/throwing/slow/OOM; Stop beats pending, works at low confidence and cancels app choice; pending confirmation and app choice expire after the TTL; every yes and every no phrase; unrelated speech → one re-prompt then cancel; low-confidence answer does not confirm; Repeat never re-executes and keeps the pending question; invalid reminders explained; reminder failure reason spoken; tool exception → failure; low-confidence reminder needs confirmation; toggle → settings only; date/charging; model-proposed reminder needs confirmation; model not used for low-confidence or known commands; delimited prompt; `describeDelay` |
| `commands/ModelCommandValidatorTest` | `ModelCommandValidator` | 11 | Valid commands; unknown tools; missing/extra args; bad types; out-of-range reminders; malformed JSON; oversize input; exact schema required; app-name limits; Unicode tricks; injection strings |
| `commands/GuardedAiInterpreterTest` | `GuardedAiInterpreter`, `PromptBuilder` | 8 | Valid proposal accepted; malicious outputs rejected; prompt injection in speech delimited and sanitized; oversize input never reaches the model; timeout; exceptions/errors → null; absent model falls back to the parser; prompt lists only allow-listed tools |
| `data/LocalHistoryRepositoryTest` | `LocalHistoryRepository`, `HistorySanitizer` | 6 | Disabled records nothing; max 20 + clear; clear then record; notification read-out never stored; retained notification text redacted from any reply; custom sanitizer |
| `service/ListeningSessionControllerTest` | `ListeningSessionController` | 21 | Listening only after mic opens; Stopped only after full release; idempotent start; rapid start/stop cycles leak nothing; start right after stop gets a fresh session; stop during model load / wake word / command recognition / handler / speaking; capture error → stop → restart; mic init failure shows Error; missing permission never opens mic; permission revoked while running; model load failure; stale callback from an old session ignored; notification Talk only while Listening; tap-to-talk queued while starting; stop command; external inactive state shuts the session down; mic silenced and recovered |
| `service/AudioCaptureControllerTest` | `AudioCaptureController` | 9 | Error constants match AudioRecord; frames delivered and released exactly once; repeated start opens mic once; each negative read code → actionable message; start failure reports and releases; permission revoked during start/read; read error after user stop not reported; stop before start; silence callback only while running |
| `service/VoicePipelineTest` | `VoicePipeline` | 22 | Wake → command → spoken; resume after reply; low-confidence gate (configurable threshold, handler never called); no-speech reply; tap-to-talk; frames ignored while speaking; stop request; handler exception/timeout; stop releases engines (also before start); wake phrase stripped / wake-phrase-only never reaches handler; TTS failure and hang return to Listening; recognizer `finish()` failure gives retry prompt; duplicate trigger in one utterance → one session; cooldown restarts at session end; rejected state write stops pipeline; `stopAndJoin` waits; frames after stop dropped |
| `service/RouterCommandHandlerTest` | `RouterCommandHandler` | 4 | Result mapping, history skipped when disabled / recorded when enabled; stop propagated; background-blocked launch reported honestly; TTS unavailable posts a notice |
| `service/ServiceStateTest` | `ServiceState`, `ServiceStateRepository` | 6 | Initial Stopped; `isActive` per state (incl. Stopping, MicrophoneSilenced); transitions; `updateIfActive`; error label |
| `service/VoiceMetricsTest` | `VoiceMetrics`, `LatencyStats` | 2 | Nearest-rank median/p95; counters and sample window |
| `speech/CommandEndpointerTest` | `CommandEndpointer` | 6 | Frame duration; no-speech 4 s; trailing silence; max duration; final segment semantics; sticky end |
| `speech/ModelArchiveTest` | `ModelArchive` | 19 | Path resolution; zip-slip (incl. sibling prefix, Windows/encoded traversal, backslashes); SHA-256/MD5/size digests; max bytes; extraction & model-root discovery; missing files; size/entry limits; symlink-like entries; duplicate entries; cancellation; non-zip and empty archives |
| `speech/VoskJsonTest` | `VoskJson` | 6 | Word/confidence parsing; malformed JSON; text-only fallback; averaging, `[unk]` dropped; grammar |
| `tools/AppNameResolverTest` | `AppNameResolver` | 8 | Exact beats prefix; prefix/contains; ambiguous; duplicate package; diacritics/"app" suffix/multi-word; capped at 3, shortest first; exact tier beats contains tier; missing |
| `tools/NotificationPrivacyTest` | `NotificationPrivacy`, `NotificationStore`, reader | 9 | Sensitive apps and OTP-like content blocked by default; toggle off still honors exclusions; package-list parsing; cap 5 + 10-min TTL; clock reset; retainOnly/removePackage; access checked at time of use and cleared when revoked; retained-content detection |
| `tools/ReminderStoreTest` | `ReminderStore`, `ReminderValidation` | 12 | Same-title reminders coexist with unique ids; monotonic, persisted ids; special characters; invalid titles; corrupt lines; cap 50; clear/remove; delay limits; receiver rejects malformed intents; reschedule plan (future / missed / expired); late detection; notification ids |
| `wakeword/WakeWordDeciderTest` | `WakeWordDecider` | 15 | Threshold; `[unk]`/other words; whole-word match; sensitivity map; strict rejection; cooldown; rejected detections don't start cooldown; partial only ≥ 0.8; reset; full phrase required < 0.35; keyword alone ≥ 0.35; loudness floor scaling; RMS; restartCooldown; blank keyword |
| `wakeword/WakePhraseTest` | `WakePhrase` | 2 | Leading wake phrase stripped; other text unchanged |

Total: **252** unit tests in **21** classes (`FakeAudioSource.kt` is a test helper with no tests).

Spec §11 / roadmap requirements → coverage:

| Requirement | Covered by |
|---|---|
| Parser maps supported utterances | `CommandParserTest` |
| Unknown/malformed input rejected safely | `CommandParserTest`, `ModelCommandValidatorTest`, `GuardedAiInterpreterTest` |
| Model JSON with unknown tools/invalid params rejected | `ModelCommandValidatorTest`, `CommandRouterTest.aiInvalidOutputRejected`, `GuardedAiInterpreterTest.maliciousOutputsRejected` |
| App-name missing/ambiguous | `AppNameResolverTest`, `CommandRouterTest.ambiguousAppThenChoice`, `notInstalledAppIsHonest` |
| Pending confirmation expiry / unrelated follow-up | `CommandRouterTest.pendingConfirmationExpires`, `appChoiceExpires`, `unrelatedSpeechRepromptsOnceThenCancels` |
| Permission/state transitions | `ServiceStateTest`, `ListeningSessionControllerTest.permissionMissingNeverOpensMic`, `permissionRevokedWhileRunningFailsSafely`; androidTest `PermissionFlowTest`, `PermissionDenialDialogTest` |
| Repeated start/stop, cancellation, race cases (roadmap §1.3) | `ListeningSessionControllerTest` (stop during load / wake / command / handler / TTS, rapid cycles, restart after error) |
| Stop-listening cancels active work | `VoicePipelineTest.stopRequestedInvokesCallbackInsteadOfResuming`, `stopAndJoinWaitsForRelease`, `ListeningSessionControllerTest.stopCommandEndsSession`, `CommandRouterTest.stopWinsOverPendingConfirmation` |
| Unsafe/corrupt/cancelled model install keeps working model | `ModelInstallerTest`, `ModelArchiveTest` |

## 1b. Instrumentation test inventory (`app/src/androidTest/java/com/jarvis/assistant/`)

| Test class | Tests | What is covered |
|---|---|---|
| `LaunchFlowTest` | 2 | Onboarding → home; relaunch skips onboarding |
| `PermissionFlowTest` | 3 | Granted permissions are visible to the app; service never reports PermissionRequired when granted; recovery guidance for PermissionRequired |
| `PermissionDenialDialogTest` | 2 | Denying the mic in the real dialog shows "Microphone permission required"; service start without permission. Skipped unless run through `scripts\run-permission-denial-test.ps1` (revoking `RECORD_AUDIO` kills an instrumented process); the second test also needs an installed model. |
| `ForegroundServiceTest` | 3 | Start posts the FGS notification (or reports a missing model) and Stop removes it; notification Stop action; Stop when not running |
| `NotificationChannelsTest` | 4 | Listening channel is low importance without badge; reminders channel high importance; forged reminder intent posts nothing; channels named and described |
| `ModelSetupScreenTest` | 6 | Missing / downloading / verifying / ready (delete behind confirmation) / error states; home reflects model state |
| `TtsInitTest` | 2 | Platform TTS init completes; `SpeechOutput` settles in a known state |

Total: **22** tests. On the API 31 emulator, 20 passed and 2 were skipped by design (`Assume`-gated:
the denial tests need the adb-revoke script, and parts of the suite need an installed model or
enabled notifications).
## 2. Manual device checklist (spec §11)

Device: Redmi Note 10S (M2101K7BI), **Android 13**, MIUI **V140**. APK: **debug**, version 1.0.0 (versionCode 1).
Date of runs: 2026-10-27. Rows marked "(emulator)" were run on an Android 12 (API 31) emulator,
not on the Redmi. Fill in **Result** (Pass / Fail / Partial / Blocked / Not yet run) and **Notes** with what actually happened.

| # | Test | Steps | Expected | Result | Notes |
|---|---|---|---|---|---|
| 1 | Fresh install & onboarding | Uninstall, install APK, open | Welcome screen; no permission prompt; "Got it" → home; "Speech model not installed" | Redmi: install + launch **Pass** (no crash). Onboarding: **Pass (emulator)** | Onboarding flow on the Redmi not separately recorded |
| 2 | Model download | Speech model → Download on Wi-Fi | Progress, "Verifying", then "Ready (… MB)" | **Pass (emulator)** | Download + checksum verified on the emulator; a model was installed on the Redmi for the Start/Stop runs |
| 3 | Model import | Import the official zip via file picker | Ready; a different/renamed zip is rejected | Not yet run | |
| 4 | Microphone granted | Start Listening → Allow | Notification appears; status Listening | **Pass** | Listening reached in 5.8–8.0 s (includes model load); FGS notification shown |
| 5 | Microphone denied | Fresh install → Start → Don't allow | Status "Microphone permission required" + Open app settings | **Pass (emulator)** | Not yet run on the Redmi (`scripts\run-permission-denial-test.ps1` blocked by MIUI install restriction) |
| 6 | Notification permission granted/denied | Android 13+ (the Redmi under test runs Android 13) | Listening works either way | Not yet run | |
| 7 | Start/stop repeatedly | Start/Stop 10× quickly, also via notification Stop | No crash; mic indicator goes off after Stop | **Partial** | 5 cycles (not 10, not rapid): each reached Listening, AudioRecord stopped ≤ 2.8–3.7 s after Stop (adb polling, upper bound), FGS removed, no leftover notification, final state Stopped. Notification Stop: **Pass (emulator, androidTest)** |
| 8 | Lock and unlock screen | Start, lock 2 min, say "Hey Jarvis …", unlock | Record actual behavior | Not yet run | |
| 9 | Open a requested app | "Hey Jarvis" → "Open YouTube" | YouTube opens; JARVIS says "Opening YouTube" | Not yet run | |
| 10 | Ambiguous / missing app | "Open Google" / "Open Foobar" | Asks which one / "couldn't find" | Not yet run | |
| 11 | Battery, time, Wi-Fi settings, repeat, stop | Spec example phrases | Correct spoken replies / settings page opens / stops | Not yet run | |
| 12 | Tap to talk | Tap to talk, say command | Works without wake word | Not yet run | |
| 13 | No speech | Wake, then stay silent | "I didn't hear a command." after ~4 s; back to Listening | Not yet run | |
| 14 | Noisy room | TV/music on, try wake word and commands | Record accuracy | Not yet run | |
| 15 | Wake-word false positives | 30 min of normal conversation/TV without saying it | Record count of false triggers per sensitivity | Not yet run | |
| 16 | Model missing | Delete model, Start | Start disabled / "Speech model not installed" | Not yet run | |
| 17 | Model corrupt | Import a truncated/other zip | "Checksum mismatch…" / "Download incomplete or wrong file"; nothing installed, existing model kept | Not yet run | Covered by unit tests only |
| 18 | Out of memory | Many heavy apps open, Start | Error message, no crash (may be hard to reproduce) | Not yet run | |
| 19 | Notification access | Enable, "read my notifications"; then disable while app active and ask again | Reads latest; after disable offers to open settings | Not yet run | |
| 20 | Reminder | "Remind me to test in 1 minute" | Notification within a few minutes (inexact) | Not yet run | |
| 21 | TTS voice missing/offline | Airplane mode; remove offline voice | Record behavior | Not yet run | |
| 22 | Reboot | Start listening, reboot | Listening does not auto-start (documented behavior) | Not yet run | |
| 23 | Force-stop | Settings → Force stop while listening | Service ends, notification gone; does not restart | Not yet run | |
| 24 | MIUI background | Screen off 30 min while listening, default battery settings, then "No restrictions" | Record whether service survives | Not yet run | |
| 25 | Nothing uploaded | After model install, airplane mode → full voice flow works; optionally monitor traffic | Works offline; no network traffic | Not yet run | |
| 26 | Nothing logged | `adb logcat` during commands | No transcript/reply/notification text in JARVIS log lines | **Partial** | No transcript content in logcat during the 5 Start/Stop cycles; not yet checked during spoken commands, notification read or reminders |
| 27 | No unexpected permissions | Settings → Apps → JARVIS → Permissions; `adb shell dumpsys package com.jarvis.assistant` | Only Microphone (+ Notifications); install-time ones listed in ANDROID_PERMISSIONS.md | Not yet run | |
| 28 | Update keeps data | `adb install -r` newer build, same key | Model still installed | Not yet run | |
| 29 | Instrumentation suite on device | Enable **Install via USB**, then `.\gradlew.bat connectedDebugAndroidTest` | 20 pass / 2 skip as on the emulator | **Blocked** | `INSTALL_FAILED_USER_RESTRICTED` on MIUI |
| 30 | Signed release install | `.\scripts\verify-release.ps1`, then `adb install -r` the signed APK | Installs, launches, voice flow works in the minified build | Not yet run | No release keystore configured yet |
| 31 | Model deletion while listening | Start, then Speech model → Delete | "Stop listening first, then delete the model"; model kept | Not yet run | Unit/ViewModel logic only |
| 32 | Reminder after reboot | Set a 5-min reminder, reboot | Reminder delivered (or "Missed reminder"); listening stays stopped | Not yet run | |

## 3. Measurements (roadmap Phase 3 / 12)

| Metric | Value |
|---|---|
| Start tap → Listening (includes model load), Redmi, debug | 5.8–8.0 s over 5 runs |
| Stop tap → AudioRecord stopped, Redmi, debug | ≤ 2.8–3.7 s (adb polling, upper bound) |
| Battery drain per hour while listening (screen off) | Not measured |
| CPU while idle-listening / recognizing | Not measured |
| RAM (PSS) while listening | Not measured |
| Wake-word detection rate / false activations per hour | Not measured |
| Wake → command start, end of speech → reply latency | Not measured (shown in the app's **Show diagnostics** panel once used) |
| Model load time alone | Not recorded separately (diagnostics panel shows it) |
