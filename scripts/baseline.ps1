<#
.SYNOPSIS
  Runs the Phase 0 baseline command set and saves the full output with timestamps.
.DESCRIPTION
  Output goes to build\reports\baseline\<timestamp>\ (gitignored): one log per command,
  environment.txt (git/JDK/SDK/device info), and summary.txt with exit codes and durations.
  Commands keep running after a failure so every outcome is recorded.
.EXAMPLE
  .\scripts\baseline.ps1
  .\scripts\baseline.ps1 -Install   # also installs the debug APK on the connected device(s)
#>
param([switch]$Install)
$ErrorActionPreference = 'Continue'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $root
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outDir = Join-Path $root "build\reports\baseline\$stamp"
New-Item -ItemType Directory -Force $outDir | Out-Null
$summary = Join-Path $outDir 'summary.txt'

function Write-Stamped([string]$file, [string]$line) {
    Add-Content -Path $file -Value ("[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $line)
}

$envFile = Join-Path $outDir 'environment.txt'
Write-Stamped $envFile "branch: $(git rev-parse --abbrev-ref HEAD 2>&1)"
Write-Stamped $envFile "commit: $(git rev-parse HEAD 2>&1)"
Write-Stamped $envFile "dirty files: $((git status --porcelain 2>&1 | Measure-Object).Count)"
Write-Stamped $envFile "JAVA_HOME: $env:JAVA_HOME"
Write-Stamped $envFile "ANDROID_HOME: $env:ANDROID_HOME"
$gradleFile = Get-Content app\build.gradle.kts -Raw
foreach ($key in 'compileSdk', 'targetSdk', 'minSdk', 'versionCode', 'versionName') {
    $m = [regex]::Match($gradleFile, "$key\s*=\s*(\S+)")
    if ($m.Success) { Write-Stamped $envFile "${key}: $($m.Groups[1].Value)" }
}
Get-Content gradle\libs.versions.toml | Where-Object { $_ -match '^(agp|kotlin|composeBom)\s*=' } |
    ForEach-Object { Write-Stamped $envFile "catalog: $_" }
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
if (Test-Path $adb) {
    & $adb devices -l 2>&1 | ForEach-Object { Write-Stamped $envFile "adb: $_" }
}

$commands = @(
    @{ Name = 'version'; Args = @('--version') },
    @{ Name = 'test'; Args = @('test') },
    @{ Name = 'lint'; Args = @('lint') },
    @{ Name = 'assembleDebug'; Args = @('assembleDebug') },
    @{ Name = 'assembleRelease'; Args = @('assembleRelease') }
)
if ($Install) { $commands += @{ Name = 'installDebug'; Args = @('installDebug') } }

Write-Stamped $summary "Baseline run $stamp in $root"
foreach ($c in $commands) {
    $log = Join-Path $outDir "$($c.Name).log"
    Write-Host "==> gradlew $($c.Args -join ' ')  (log: $log)"
    Write-Stamped $log "START gradlew $($c.Args -join ' ')"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    & .\gradlew.bat --console=plain @($c.Args) 2>&1 | ForEach-Object { Write-Stamped $log "$_" }
    $code = $LASTEXITCODE
    $sw.Stop()
    Write-Stamped $log "END exit=$code duration=$([int]$sw.Elapsed.TotalSeconds)s"
    $warnings = (Select-String -Path $log -Pattern '\bw: |warning:' -SimpleMatch:$false | Measure-Object).Count
    $result = if ($code -eq 0) { 'PASS' } else { 'FAIL' }
    Write-Stamped $summary ("{0,-16} {1}  exit={2}  {3}s  warnings={4}" -f $c.Name, $result, $code, [int]$sw.Elapsed.TotalSeconds, $warnings)
}

# Separate warnings from failures for the report.
Get-ChildItem $outDir -Filter *.log | ForEach-Object {
    Select-String -Path $_.FullName -Pattern '\bw: |warning:' | ForEach-Object { $_.Line }
} | Set-Content (Join-Path $outDir 'warnings.txt')

Write-Stamped $summary 'Report paths:'
foreach ($p in 'app\build\reports\tests', 'app\build\reports\lint-results-debug.html', 'app\build\outputs\apk') {
    if (Test-Path $p) { Write-Stamped $summary "  $(Resolve-Path $p)" }
}
Write-Host "`nSummary ($summary):"
Get-Content $summary
