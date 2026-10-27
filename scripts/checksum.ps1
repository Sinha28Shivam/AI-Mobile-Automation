<#
.SYNOPSIS
  Prints SHA-256 checksums of release APK(s).
.EXAMPLE
  .\scripts\checksum.ps1
#>
param(
    [string]$Path = (Join-Path $PSScriptRoot '..\app\build\outputs\apk\release')
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $Path)) {
    Write-Error "No release output at $Path. Run .\gradlew.bat assembleRelease first."
    exit 1
}
$apks = Get-ChildItem -Path $Path -Filter *.apk -Recurse
if (-not $apks) {
    Write-Error "No APK files found under $Path."
    exit 1
}
foreach ($apk in $apks) {
    $hash = (Get-FileHash -Algorithm SHA256 -Path $apk.FullName).Hash.ToLowerInvariant()
    '{0}  {1}  ({2:N0} bytes)' -f $hash, $apk.Name, $apk.Length
}