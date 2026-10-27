<#
.SYNOPSIS
  Runs the microphone-denial instrumentation tests (PermissionDenialDialogTest) on a device.
.DESCRIPTION
  Revoking RECORD_AUDIO from inside an instrumented process kills it, so this script installs
  the debug + test APKs, revokes the permissions with adb, runs only the denial tests through
  `am instrument`, then grants the permissions back.
  Set ANDROID_SERIAL to choose the device.
#>
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $root
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$pkg = 'com.jarvis.assistant'

& .\gradlew.bat --console=plain installDebug installDebugAndroidTest
if ($LASTEXITCODE -ne 0) { Write-Error 'install failed'; exit 1 }

# Native stderr must not abort the script (Windows PowerShell 5 turns it into error records).
$ErrorActionPreference = 'Continue'
$sdk = [int]((& $adb shell getprop ro.build.version.sdk) | Out-String).Trim()
$perms = @('android.permission.RECORD_AUDIO')
if ($sdk -ge 33) { $perms += 'android.permission.POST_NOTIFICATIONS' }

foreach ($p in $perms) {
    & $adb shell pm revoke $pkg $p
    # Let the real dialog appear again after an earlier "don't ask again".
    & $adb shell pm clear-permission-flags $pkg $p user-set user-fixed
}
try {
    $out = & $adb shell am instrument -w -r -e class "$pkg.PermissionDenialDialogTest" "$pkg.test/androidx.test.runner.AndroidJUnitRunner"
    $out | ForEach-Object { "$_" }
    $failed = $out | Select-String -Pattern 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -2'
    $ok = $out | Select-String -Pattern '^OK \('
} finally {
    foreach ($p in $perms) { & $adb shell pm grant $pkg $p }
}
if ($failed -or -not $ok) { Write-Host 'Denial tests FAILED' -ForegroundColor Red; exit 1 }
Write-Host 'Denial tests passed' -ForegroundColor Green
exit 0
