<#
.SYNOPSIS
  Builds the minified release APK and verifies its packaging.
.DESCRIPTION
  1. Runs assembleRelease (unless -SkipBuild).
  2. Checks that libvosk.so and libjnidispatch.so are packaged for every ABI in abiFilters.
  3. Checks that R8 kept org.vosk / com.sun.jna classes (apkanalyzer, if available).
  4. Runs apksigner verify when the APK is signed; otherwise reports it as UNSIGNED.
  5. Prints the SHA-256 checksum.
  Exits non-zero on any failed check. Never prints signing secrets.
.EXAMPLE
  .\scripts\verify-release.ps1
  .\scripts\verify-release.ps1 -SkipBuild
#>
param(
    [switch]$SkipBuild,
    [string[]]$Abis = @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $root

if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$failures = New-Object System.Collections.Generic.List[string]

if (-not $SkipBuild) {
    & .\gradlew.bat --console=plain assembleRelease
    if ($LASTEXITCODE -ne 0) { Write-Error "assembleRelease failed (exit $LASTEXITCODE)"; exit 1 }
}

$outDir = Join-Path $root 'app\build\outputs\apk\release'
$signedApk = Join-Path $outDir 'app-release.apk'
$unsignedApk = Join-Path $outDir 'app-release-unsigned.apk'
if (Test-Path $signedApk) { $apk = $signedApk; $signed = $true }
elseif (Test-Path $unsignedApk) { $apk = $unsignedApk; $signed = $false }
else { Write-Error "No release APK found in $outDir"; exit 1 }
Write-Host "APK: $apk"

# --- Native libraries per ABI ---
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($apk)
try {
    $entries = $zip.Entries | ForEach-Object { $_.FullName }
} finally {
    $zip.Dispose()
}
Write-Host "`nNative libraries:"
$entries | Where-Object { $_ -like 'lib/*' } | Sort-Object | ForEach-Object { Write-Host "  $_" }
foreach ($abi in $Abis) {
    foreach ($lib in 'libvosk.so', 'libjnidispatch.so') {
        $path = "lib/$abi/$lib"
        if ($entries -contains $path) { Write-Host "OK      $path" }
        else { Write-Host "MISSING $path" -ForegroundColor Red; $failures.Add("missing $path") }
    }
}
$unexpected = $entries | Where-Object { $_ -like 'lib/*' } | ForEach-Object { ($_ -split '/')[1] } |
    Sort-Object -Unique | Where-Object { $Abis -notcontains $_ }
if ($unexpected) { Write-Host "Note: extra ABIs packaged: $($unexpected -join ', ')" }

# --- R8 keep check ---
$analyzer = Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin\apkanalyzer.bat'
if (Test-Path $analyzer) {
    Write-Host "`nR8 keep check (apkanalyzer dex packages):"
    $dex = & $analyzer dex packages --defined-only $apk 2>$null
    foreach ($cls in 'org.vosk.Model', 'org.vosk.Recognizer', 'org.vosk.LibVosk', 'com.sun.jna.Native', 'com.sun.jna.Pointer', 'com.sun.jna.Library') {
        $pattern = '^C\s.*\s' + [regex]::Escape($cls) + '$'
        if ($dex | Where-Object { $_ -match $pattern }) { Write-Host "OK      $cls" }
        else { Write-Host "MISSING $cls" -ForegroundColor Red; $failures.Add("R8 removed/renamed $cls") }
    }
} else {
    Write-Host "`nSKIPPED R8 keep check: apkanalyzer not found at $analyzer" -ForegroundColor Yellow
    $failures.Add('R8 keep check skipped (apkanalyzer missing)')
}

# --- Signature ---
Write-Host ''
if ($signed) {
    $bt = Get-ChildItem (Join-Path $env:ANDROID_HOME 'build-tools') -Directory |
        Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } | Select-Object -Last 1
    $apksigner = Join-Path $bt.FullName 'apksigner.bat'
    & $apksigner verify --print-certs $apk
    if ($LASTEXITCODE -eq 0) { Write-Host 'Signature: VERIFIED' -ForegroundColor Green }
    else { Write-Host 'Signature: FAILED' -ForegroundColor Red; $failures.Add('apksigner verify failed') }
} else {
    Write-Host 'Signature: UNSIGNED (no keystore.properties). This APK cannot be installed or distributed.' -ForegroundColor Yellow
}

# --- Checksum ---
$hash = (Get-FileHash -Algorithm SHA256 -Path $apk).Hash.ToLowerInvariant()
$size = (Get-Item $apk).Length
Write-Host ("`nSHA-256  {0}  {1}  ({2:N0} bytes)" -f $hash, (Split-Path $apk -Leaf), $size)

if ($failures.Count -gt 0) {
    Write-Host "`nFAILED checks:" -ForegroundColor Red
    $failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}
Write-Host "`nAll release checks passed$(if (-not $signed) { ' (APK is UNSIGNED)' })." -ForegroundColor Green
exit 0
