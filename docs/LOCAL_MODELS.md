# Local models

No model file is bundled in the APK or committed to the repository (`.gitignore`
excludes `models/`, `*.gguf`, `*.onnx`, `*.ppn`).

## 1. Speech-to-text: Vosk small English

| Item | Value |
|---|---|
| Engine | Vosk, `com.alphacephei:vosk-android:0.3.75` (Apache-2.0), using JNA 5.18.1 (Android AAR) |
| Model | `vosk-model-small-en-us-0.15` |
| Language | English (US) |
| License | Apache-2.0 (Alpha Cephei) |
| Download URL | `https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip` |
| Archive size | 41,205,931 bytes (≈ 39.3 MiB / ≈ 41 MB) |
| SHA-256 | `30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498` |
| MD5 (upstream) | `09ab50ccd62b674cbaa231b825f9c1cb` |
| Unpacked | 20 entries, 70,898,967 bytes |
| Install location | App-private storage: `<filesDir>/models/vosk-model-small-en-us-0.15/` (i.e. `/data/data/com.jarvis.assistant/files/models/…`; not readable by other apps; excluded from backup) |
| Native ABIs packaged | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` (checked with `scripts\verify-release.ps1`) |

### Provenance and trust basis
- **Upstream publishes no SHA-256.** The pinned SHA-256 is a hash we computed ourselves.
  The archive at the download URL was downloaded again independently and re-hashed on
  2026-10-27 to confirm it.
- **Upstream does publish** an MD5 and a byte size for each model in
  <https://alphacephei.com/vosk/models/model-list.json>. For this model that is MD5
  `09ab50ccd62b674cbaa231b825f9c1cb` and 41,205,931 bytes, and they matched the same download.
- The installer checks **all three**: exact size, SHA-256 and MD5. An archive is rejected
  if it differs from either the pinned hash or upstream's own manifest. MD5 alone is
  not collision-resistant; it is used only as a cross-check against an independent
  upstream source, and SHA-256 remains the primary check.
- Trust still comes down to the HTTPS connection to `alphacephei.com` at the time of
  pinning, plus upstream's manifest. If Alpha Cephei replaces the file, installation fails
  safely and the values in `ModelCatalog.VOSK_SMALL_EN_US` (`ai/ModelInstaller.kt`) must be
  checked again by hand before they are updated.

### Install pipeline (`ai/ModelInstaller.kt`, driven by `ai/LocalModelManager.kt`)
`ModelInstaller` is pure JVM code, so every stage below is covered by unit tests
(`ModelInstallerTest`, `ModelArchiveTest`, `ModelDownloadPolicyTest`).

1. **Recover:** on start, leftovers of an interrupted install are removed. If the process
   died between the two swap renames, the backup is restored.
2. **Preparing:** checks free space with `StatFs`. It needs download + unpacked size +
   10 MB margin; an unknown free-space value does not block the install.
3. **Downloading** (user tap; internet needed once) or **Import** (system file picker, no
   internet). The download must use `https://` and only `alphacephei.com` /
   `www.alphacephei.com`, and so must every redirect (max 5, default port, no user-info).
   Bytes stream into `models/.work/download.zip` while SHA-256, MD5 and size are computed.
   Anything beyond the expected size is aborted.
4. **Verifying:** exact size, SHA-256 and MD5 must match. The file on disk is then hashed
   again, so the bytes that get extracted are exactly the bytes that were verified.
5. **Extracting** into `models/.work/extract/`: zip-slip protection (absolute paths, `..`,
   Windows drive/backslash forms, sibling-prefix tricks), duplicate entries rejected,
   **max 100 MB** unpacked and **max 64 entries**, free space checked again.
6. **Validating:** the 13 required files (`am/final.mdl`, `conf/*.conf`, `graph/*`,
   `ivector/*`) must exist, be readable and be non-empty. Vosk then **test-loads** the
   model (`Model(path)`). Missing native library, OOM, or a load failure give
   "Speech engine is not supported on this device", "Not enough memory to load the speech
   model", or "The speech engine could not load this model".
7. **Installing:** writes a `.installed` marker (contains the SHA-256), moves the current
   model to `.old-<id>`, renames the new one into place, then deletes the backup. The last
   known-good model is kept until the new one has passed validation.
8. **Cancellation** is checked in every stage. A failure or cancel deletes only the staging
   folder; an existing model stays installed ("Update failed; your installed model was kept").

The Speech model screen shows the stage, progress and actionable errors. **Re-download /
repair** reinstalls over a damaged or outdated install.

To import manually: download the zip above on any computer/phone, copy it to the phone
(USB, cloud drive of your choice, etc.), then in JARVIS → **Speech model → Import zip from this phone**.
You can verify the file on Windows first:
```powershell
Get-FileHash .\vosk-model-small-en-us-0.15.zip -Algorithm SHA256
Get-FileHash .\vosk-model-small-en-us-0.15.zip -Algorithm MD5
```
### Deletion
JARVIS → **Speech model → Delete model** (confirmation dialog). Delete is **refused while
listening** ("Stop listening first, then delete the model"), so the model is never deleted
while Vosk still has it open.
Uninstalling the app or **Settings → Apps → JARVIS → Storage → Clear data** also removes it.

### RAM / disk / speed
Only one figure has been measured so far. On the Redmi Note 10S (Android 13, debug build),
Start → Listening took 5.8–8.0 s, which includes loading the model. The home screen
**Show diagnostics** panel shows the last model load time. RAM and CPU have not been
measured.

## 2. Wake word: "Hey Jarvis"

**Approach implemented:** `wakeword/VoskKeywordWakeWordDetector.kt` runs a second Vosk
recognizer from the same model, restricted by a grammar to `["hey jarvis", "jarvis", "[unk]"]`.
`WakeWordDecider` decides whether a recognizer result counts as the wake word. In grammar
mode Vosk word confidences are usually about 1.0, so the confidence threshold alone barely
changes anything. The **sensitivity slider** therefore controls several gates:

| Sensitivity | Accepted phrase | Early trigger on partial result | Loudness floor (peak frame RMS, PCM16) | Required word confidence |
|---|---|---|---|---|
| 0 % … < 35 % (strict) | **"hey jarvis" only** | no | 300 → ~195 | 0.95 → ~0.74 |
| 35 % … < 80 % | "hey jarvis" **or "jarvis" alone** | no | ~195 → 60 | ~0.74 → 0.47 |
| ≥ 80 % (lenient) | "hey jarvis" or "jarvis" | **yes** | 60 → **0** at 100 % | 0.47 → 0.35 |

The values in each band scale linearly: RMS floor = 300 × (1 − s), confidence = 0.95 − 0.60 × s.

Other rules:
- a 2-second cooldown prevents repeated triggers. It starts again when a command session
  ends, so JARVIS's own reply or the end of your command cannot trigger it right away.
  One utterance starts at most one session;
- the wake phrase is removed from the start of the command ("hey jarvis open youtube" →
  "open youtube"). The wake phrase alone gets "Yes? Say "Hey Jarvis" followed by a command.";
- detection is paused while JARVIS is processing or speaking, and those frames are thrown away.

These mappings are unit-tested (`WakeWordDeciderTest`, `WakePhraseTest`). Whether they make
a **measurable** difference on the phone has **not** been measured yet.
Supported phrases: **"Hey Jarvis"**, and **"Jarvis"** alone at sensitivity ≥ 35 %.

**Honest limitations**
- This is **not a dedicated wake-word neural network** — it is a small speech recognizer
  decoding all audio continuously, so CPU and battery use are higher than a purpose-built
  engine (not yet measured).
- False positives are possible with similar-sounding words. The `[unk]` filler, the
  full-phrase requirement at low sensitivity, the loudness floor and the cooldown are the
  main defenses. Detection rate, false activations per hour and latency have **not** been
  measured. The diagnostics panel counts wake detections and shows wake → command-start
  latency (avg/median/p95), so measurements can be taken on the phone.
- Accuracy in noisy rooms, at distance, and with accents has not been tested.
- **Tap to talk** (home screen or notification) always works without the wake word.

**Alternative not integrated:** Picovoice **Porcupine** supports custom keywords such as
"Hey Jarvis" via a trained `.ppn` file, but requires a Picovoice account and an
**AccessKey**, subject to Picovoice's license terms. It is not included. If added later,
the AccessKey and `.ppn` must stay out of source control (they are git-ignored patterns:
`.env`, `*.ppn`) and the engine would plug in behind the existing `WakeWordDetector` interface.

## 3. Local language model (LLM): guard rails only, no runtime

Status: **roadmap Phase 9 (P2), partial.** No LLM runtime, JNI code or model file ships.
JARVIS understands only the fixed command set handled by `CommandParser`.

What exists (all unit-tested):
- `LocalLanguageModel`: a `suspend fun propose(prompt): String?` interface. No
  implementation is included.
- `PromptBuilder`: trusted instructions plus the user's speech inside
  `<<<BEGIN_UNTRUSTED_USER_SPEECH>>>` … `<<<END_UNTRUSTED_USER_SPEECH>>>`. Control and format
  characters and `<`/`>` are removed from the speech, so it cannot close the block.
- `GuardedAiInterpreter`: input limited to 300 chars, an 8-second timeout, output limited to
  2 KB. Any exception or error, including `OutOfMemoryError`, gives "no proposal". The
  output must pass `ModelCommandValidator`, and Stop/Repeat from a model are ignored.
- `CommandRouter` calls the interpreter **only** for text the deterministic parser could not
  understand, and only at confidence ≥ 0.5. Model-proposed consequential commands
  (reminders) always need a spoken "yes".
- `ModelCommandValidator`: strict schema. The root must be exactly `{"tool": "...", "args": {...}}`
  using one of `open_app(app_name ≤ 50)`, `get_battery`, `get_time`, `get_date`,
  `open_settings(destination)`, `read_latest_notification`,
  `create_reminder(title, delay_minutes 1..1440)`. Rejected: unknown tools, extra or
  missing keys/args, wrong types, floats, arrays, nesting > 2, input over 2 KB, Unicode
  tricks, and out-of-range values. Model output can never run shell commands, code or
  arbitrary intents.

### Plan (not started)
- Runtime: **llama.cpp** built for `arm64-v8a` and called via JNI, implementing `LocalLanguageModel` with inference on a background thread.
- Candidate models (quantized GGUF, e.g. Q4_K_M), to be **benchmarked on the Redmi first**:

| Candidate | License (verify at selection time) |
|---|---|
| Qwen2.5-0.5B-Instruct | Apache-2.0 |
| Qwen2.5-1.5B-Instruct | Apache-2.0 |
| SmolLM2-360M/1.7B-Instruct | Apache-2.0 |
| Llama 3.2 1B Instruct | Llama 3.2 Community License (has use restrictions) |
| Gemma 3 1B | Gemma Terms of Use (custom terms) |

- Check each model card's license and redistribution terms before use; prefer letting the
  user download the model rather than bundling it.
- Show model path, load state, memory errors and availability in the UI; fall back to the
  deterministic parser when the model is absent, slow, or fails.
- Disk/RAM requirements will be documented only after checking the actual chosen files on the device.