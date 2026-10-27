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
| Install location | App-private storage: `<filesDir>/models/vosk-model-small-en-us-0.15/` (i.e. `/data/data/com.jarvis.assistant/files/models/…`; not readable by other apps; excluded from backup) |
| Native ABIs packaged | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |

The SHA-256 above was computed from the official archive; upstream does not publish one.
If Alpha Cephei ever replaces the file at that URL, the check will fail and the app will
refuse to install it (the hash in `LocalModelManager.VOSK_SMALL_EN_US` would need updating).

### Install flow (`ai/LocalModelManager.kt`)
1. **Download** (user taps it; internet needed once) or **Import** (system file picker, no internet).
2. Bytes are streamed to `models/.work/download.zip` while the SHA-256 is computed;
   download larger than 2× the expected size is aborted.
3. Hash compared with the expected value — mismatch → "File check failed…" and nothing is installed.
4. Unzip with protection against zip-slip (`../` paths), entry-count and
   uncompressed-size limits (150 MB max).
5. The model root must contain `am/final.mdl`, `conf/model.conf`, `conf/mfcc.conf`.
6. A `.installed` marker is written, the folder is moved into place and the work folder deleted.
7. The model is loaded only when you start listening; load errors are shown as
   "Speech model could not be loaded", "Not enough memory to load the speech model",
   or "Speech engine is not supported on this device".

To import manually: download the zip above on any computer/phone, copy it to the phone
(USB, cloud drive of your choice, etc.), then in JARVIS → **Speech model → Import zip from this phone**.
You can verify the file on Windows first:
```powershell
Get-FileHash .\vosk-model-small-en-us-0.15.zip -Algorithm SHA256
```

### Deletion
JARVIS → **Speech model → Delete model** (confirmation dialog). Stop listening first.
Uninstalling the app or **Settings → Apps → JARVIS → Storage → Clear data** also removes it.

### RAM / disk / speed
Not measured on the target phone. Do not rely on any figures until measured on the
Redmi Note 10S.

## 2. Wake word: "Hey Jarvis"

**Approach implemented:** `wakeword/VoskKeywordWakeWordDetector.kt` runs a second Vosk
recognizer from the same model, restricted by a grammar to `["hey jarvis", "jarvis", "[unk]"]`.
`WakeWordDecider` triggers when the word "jarvis" is recognized with enough confidence:

- sensitivity slider 0 → 100 % maps to required word confidence 0.95 → 0.35;
- at sensitivity ≥ 80 % a partial (in-progress) hypothesis may trigger early;
- a 2-second cooldown prevents repeated triggers;
- detection is suspended while JARVIS is processing or speaking.

Supported phrases: **"Hey Jarvis"** and **"Jarvis"**.

**Honest limitations**
- This is **not a dedicated wake-word neural network** — it is a small speech recognizer
  decoding all audio continuously, so CPU and battery use are higher than a purpose-built
  engine (not yet measured).
- False positives are possible with similar-sounding words; Vosk confidences in grammar
  mode are often near 1.0, so the sensitivity slider has a limited effect. The `[unk]`
  filler and the cooldown are the main defenses.
- Accuracy in noisy rooms, at distance, and with accents has not been tested.
- **Tap to talk** (home screen or notification) always works without the wake word.

**Alternative not integrated:** Picovoice **Porcupine** supports custom keywords such as
"Hey Jarvis" via a trained `.ppn` file, but requires a Picovoice account and an
**AccessKey**, subject to Picovoice's license terms. It is not included. If added later,
the AccessKey and `.ppn` must stay out of source control (they are git-ignored patterns:
`.env`, `*.ppn`) and the engine would plug in behind the existing `WakeWordDetector` interface.

## 3. Local language model (LLM) — NOT implemented

Status: **Phase 5, pending.** No LLM runtime, JNI code or model file exists in the project.
JARVIS currently understands only the fixed command set handled by `CommandParser`.

What already exists to support it:
- `CommandRouter(aiInterpreter = …)`: an optional `suspend (String) -> String?` called
  **only** for text the deterministic parser could not understand, with an 8-second timeout.
  Exceptions/timeouts fall back to "Sorry, I didn't understand."
- `ModelCommandValidator`: strict JSON allow-list. The model must output exactly
  `{"tool": "...", "args": {...}}` using one of: `open_app(app_name)`, `get_battery`,
  `get_time`, `open_settings(destination)`, `read_latest_notification`,
  `create_reminder(title, delay_minutes)` (`stop_listening`/`repeat` are parsed but
  ignored when coming from a model). Unknown tools, extra/missing args, wrong types,
  floats, arrays, input over 2 KB, or out-of-range values are rejected. Model output can
  never run shell commands or code.

### Plan
- Runtime: **llama.cpp** built for `arm64-v8a` and called via JNI, inference on a background thread.
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
