Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = Split-Path $PSScriptRoot -Parent
$packageDirectory = Join-Path $PSScriptRoot "world-reference-dictionary-v5"
if (-not (Test-Path (Join-Path $packageDirectory "manifest.json"))) {
    throw "world-reference-dictionary-v5 was not found in $PSScriptRoot."
}

$manifest = Get-Content (Join-Path $packageDirectory "manifest.json") -Raw | ConvertFrom-Json
$packageId = $manifest.packageId
if (-not $packageId) {
    throw "The reference dictionary manifest has no packageId."
}

$adbCommand = Get-Command adb.exe -ErrorAction SilentlyContinue
$adb = if ($adbCommand) { $adbCommand.Source } else { $null }
if (-not $adb) {
    $sdkLine = Get-Content (Join-Path $projectRoot "local.properties") |
        Where-Object { $_.StartsWith("sdk.dir=") } |
        Select-Object -First 1
    if (-not $sdkLine) {
        throw "Android SDK not found."
    }

    $sdkDirectory = $sdkLine.Substring(8) -replace "\\:", ":" -replace "\\\\", "\"
    $adb = Join-Path $sdkDirectory "platform-tools\adb.exe"
}

$connectedDevices = & $adb devices |
    Select-Object -Skip 1 |
    Where-Object { $_ -match "\sdevice(\s|$)" } |
    ForEach-Object { ($_ -split "\s+")[0] }

$serial = $env:ANDROID_SERIAL
if (-not $serial) {
    $serial = $connectedDevices |
        Where-Object { -not $_.StartsWith("emulator-") } |
        Select-Object -First 1
}
if (-not $serial) {
    $serial = $connectedDevices | Select-Object -First 1
}
if (-not $serial) {
    throw "No Android device is connected."
}

$remotePackage = "/sdcard/Android/data/com.flightalert/files/references/$packageId"
& $adb -s $serial shell test -f "$remotePackage/manifest.json"
if ($LASTEXITCODE -eq 0) {
    Write-Host "$packageId is already installed on $serial."
    exit 0
}

& $adb -s $serial shell am force-stop com.flightalert
& $adb -s $serial shell mkdir -p $remotePackage
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Get-ChildItem $packageDirectory -File |
    Where-Object Name -ne "manifest.json" |
    ForEach-Object {
        & $adb -s $serial push --sync $_.FullName "$remotePackage/"
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    }

& $adb -s $serial push (Join-Path $packageDirectory "manifest.json") "$remotePackage/manifest.json"
exit $LASTEXITCODE
