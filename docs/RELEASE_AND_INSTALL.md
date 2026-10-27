# Release build and installation

> **Status (2026-10-27):** `assembleRelease` builds, and `scripts\verify-release.ps1` confirms
> that the minified APK contains `libvosk.so` + `libjnidispatch.so` for all 4 ABIs and that R8
> kept the Vosk/JNA classes. An OSV scan of the 113 release dependencies found no advisories.
> A **signed** release APK has **not yet been installed on a device** (no release keystore is
> configured yet), and in-place update and reinstall have not been tested. Release signing
> reads the untracked `keystore.properties` (template: `keystore.properties.example`) or, in
> CI, repository secrets. Release builds use R8 (`isMinifyEnabled = true`, `isShrinkResources = true`).

## 1. Debug vs release

| | Debug | Release |
|---|---|---|
| Command | `.\gradlew.bat assembleDebug` | `.\gradlew.bat assembleRelease` |
| Output | `app\build\outputs\apk\debug\app-debug.apk` | `app\build\outputs\apk\release\` (`app-release.apk` when signed, `app-release-unsigned.apk` if no signing config is found) |
| Signed with | Android SDK's auto-generated debug key (`%USERPROFILE%\.android\debug.keystore`) | **Your own** release key |
| Use | Development only | Your personal installs |

Android only installs an update over an existing app if it is signed with the **same key**.
A debug build cannot update a release build (or vice-versa) — you must uninstall first,
which deletes the app's data (including the downloaded speech model).

## 2. Create a release signing key (once)

Keep the keystore file and passwords **outside source control** and back them up
(e.g. an encrypted USB drive / password manager). If you lose them, you can never update
the installed app — only uninstall and reinstall.

### Option A — Android Studio
1. **Build → Generate Signed App Bundle / APK… → APK → Next**.
2. **Create new…**: choose a path **outside** the project, e.g. `C:\keys\jarvis-release.jks`,
   set a store password, alias `jarvis`, key password, validity 25+ years, and your name.
3. You may finish the wizard to build once, but for repeatable builds use `keystore.properties` (section 3).

### Option B — keytool (PowerShell)
`keytool` ships with the JDK (e.g. `C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe`).
```powershell
New-Item -ItemType Directory -Force C:\keys | Out-Null
keytool -genkeypair -v -keystore C:\keys\jarvis-release.jks -alias jarvis `
  -keyalg RSA -keysize 2048 -validity 10000
```
It asks for passwords and your name/organization. If `keytool` isn't found:
`& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" ...`

## 3. Configure `keystore.properties` (untracked)

1. Copy the template in the project root:
   ```powershell
   Copy-Item keystore.properties.example keystore.properties
   ```
2. Edit `keystore.properties` and replace the placeholders (`CHANGE_ME`, path):
   ```properties
   storeFile=C:/keys/jarvis-release.jks
   storePassword=********
   keyAlias=jarvis
   keyPassword=********
   ```
   Use forward slashes (or doubled backslashes) in the path. All four keys are required;
   if one is missing the build stops with "keystore.properties is missing: …".
   Without a `keystore.properties` file, `assembleRelease` produces an **unsigned** APK
   (`app-release-unsigned.apk`), which Android will not install.
3. `keystore.properties`, `*.jks` and `*.keystore` are in `.gitignore`. Check with
   `git status` that they never appear as files to commit. Never put passwords in
   `build.gradle.kts` or source code.

## 4. Build the release APK

```powershell
.\gradlew.bat test lint assembleRelease
```
Find the APK in `app\build\outputs\apk\release\`. Release builds are shrunk with R8 (`proguard-rules.pro` keeps JNA and Vosk classes); test the release APK on
the phone, since shrinking problems only show at runtime.

Before each new release, increase `versionCode` (and `versionName`) in
`app\build.gradle.kts` — currently `versionCode = 1`, `versionName = "1.0.0"`.

### Verify the release build locally

```powershell
.\scripts\verify-release.ps1             # runs assembleRelease, then checks
.\scripts\verify-release.ps1 -SkipBuild  # only checks an existing APK
```
The script:
1. checks that `libvosk.so` and `libjnidispatch.so` are packaged for every ABI
   (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`);
2. checks with `apkanalyzer` that R8 kept `org.vosk` / `com.sun.jna` classes;
3. runs `apksigner verify` when the APK is signed, otherwise reports it as **UNSIGNED**;
4. prints the SHA-256.

It exits non-zero on any failed check and never prints signing secrets. A passing script
does not prove the app works at runtime; still install the APK and run the regression
checklist ([TEST_PLAN.md](TEST_PLAN.md) §2).

## 5. CI release workflow (`.github/workflows/release.yml`)

Runs on manual dispatch or when a `v*` tag is pushed. It uses the GitHub environment
`release`; configure required reviewers on that environment so the signing secrets are
protected. The Gradle cache is disabled for release builds.

| Repository / environment secret | Content |
|---|---|
| `KEYSTORE_BASE64` | The `.jks` file, base64-encoded (`[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\keys\jarvis-release.jks"))`) |
| `KEYSTORE_PASSWORD` | Store password |
| `KEY_ALIAS` | Key alias (e.g. `jarvis`) |
| `KEY_PASSWORD` | Key password |

Steps: when all four secrets exist, it decodes the keystore into `$RUNNER_TEMP`, writes a
temporary `keystore.properties`, and runs `assembleRelease`. The signing material is then
deleted (always). Next come the R8 smoke checks: native libraries for `arm64-v8a` and
`armeabi-v7a`, plus `org.vosk.Model`, `Recognizer`, `LibVosk` and `com.sun.jna.Native`,
`Pointer`, `Library` present in the dex. It then runs `apksigner verify` and uploads the
APK, its `.sha256` and `mapping.txt` as artifacts.
**Without the secrets** the workflow prints a warning and uploads an artifact labelled
**UNSIGNED**, which Android will not install. It never prints the secret values.

`.github/workflows/android-ci.yml` (PRs and `main`) covers unit tests, lint, debug and
androidTest builds, and API 31 emulator tests. `.github/dependabot.yml` checks dependency
updates weekly. No GitHub run of these workflows has been recorded yet; enable branch
protection with the CI job as a required check before relying on it.

## 6. Verify the signature

`apksigner` is in the SDK build-tools:
```powershell
$bt = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0"
& "$bt\apksigner.bat" verify --verbose --print-certs app\build\outputs\apk\release\app-release.apk
```
Expect `Verifies`, `Verified using v2 scheme (APK Signature Scheme v2): true`, and the
certificate DN/SHA-256 of **your** key (not `CN=Android Debug`). Compare the certificate
SHA-256 with:
```powershell
keytool -list -v -keystore C:\keys\jarvis-release.jks -alias jarvis
```

## 7. SHA-256 checksum for release notes

```powershell
.\scripts\checksum.ps1
```
It prints `<sha256>  <file name>  (<bytes>)` for every APK under
`app\build\outputs\apk\release\` (or pass `-Path <folder>`). Equivalent manual command:
```powershell
Get-FileHash app\build\outputs\apk\release\app-release.apk -Algorithm SHA256
```
Record the hash with the version in your release notes, and re-check it on the copy you
transfer to the phone if you want to be sure it was not corrupted.

If PowerShell blocks the script ("running scripts is disabled"), run it for this window only:
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\checksum.ps1
```

## 8. Install on the Redmi Note 10S (MIUI; the test device runs Android 13, MIUI V140)

### Option A — USB with adb (recommended)
1. Enable Developer options, **USB debugging** and **Install via USB** (see README §3.5).
2. ```powershell
   adb devices
   adb install -r app\build\outputs\apk\release\app-release.apk
   ```
3. MIUI shows an **"Install via USB"** prompt on the phone — tap **Install** within ~10 seconds,
   otherwise the install fails with `INSTALL_FAILED_USER_RESTRICTED`.
   If **Install via USB** is off, every adb install fails with that error, including
   `connectedDebugAndroidTest` (which installs a second test APK). This currently blocks
   the instrumentation run on the test Redmi.

### Option B — copy the file
1. Connect by USB in **File transfer** mode and copy the APK to `Download\`
   (or use any transfer method you choose).
2. Open it with the MIUI **File Manager**.
3. If asked, allow **Install unknown apps** for File Manager:
   Settings → Apps → Manage apps → File Manager → **Install unknown apps → Allow**
   (or tap **Settings** in the prompt). MIUI may also run a security scan — you can proceed.
4. Tap **Install**. Afterwards you may turn "Install unknown apps" off again.

Installing an APK manually does **not** grant any permissions: JARVIS still asks for the
microphone at first Start, and notification access must still be enabled manually.

## 9. Updating
- Build a new APK with a higher `versionCode`, signed with the **same** release key.
- Install over the existing app: `adb install -r ...` or open the new APK on the phone.
  App data (settings, history, downloaded model) is kept.
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means a different signing key — uninstall first (data is lost).
- `INSTALL_FAILED_VERSION_DOWNGRADE` means the `versionCode` is lower than the installed one.

## 10. Uninstall
- Phone: long-press JARVIS → **Uninstall**, or Settings → Apps → Manage apps → JARVIS → Uninstall.
- PC: `adb uninstall com.jarvis.assistant`

Uninstalling removes all app data: settings, history, saved reminders and the speech model.
