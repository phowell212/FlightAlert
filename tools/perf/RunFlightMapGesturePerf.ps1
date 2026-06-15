param(
    [string]$Device = "RFCX40KPN3B",
    [Parameter(Mandatory = $true)]
    [string]$TestName,
    [string]$ArtifactName = "",
    [double]$TargetHz = 120.0,
    [string]$OutputName = "",
    [switch]$KeepDeviceArtifacts
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$gradlew = Join-Path $repoRoot "gradlew.bat"
$summarizer = Join-Path $PSScriptRoot "SummarizeFrameStats.ps1"
$outRoot = Join-Path $PSScriptRoot "out"
$packageName = "com.flightalert"
$testClass = "com.flightalert.perf.FlightMapGesturePerfTest"

if (-not (Test-Path $adb)) { throw "adb.exe was not found at $adb" }
if (-not (Test-Path $gradlew)) { throw "gradlew.bat was not found at $gradlew" }
if (-not (Test-Path $summarizer)) { throw "SummarizeFrameStats.ps1 was not found next to this script." }

function Invoke-Adb {
    & $adb -s $Device @args
}

function Get-DefaultArtifactName {
    param([string]$Name)
    switch ($Name) {
        "quickZoomJumpsOverTraffic" { return "quickZoomJumpsOverTrafficStreet" }
        "quickZoomJumpsOverTrafficSatellite" { return "quickZoomJumpsOverTrafficSatellite" }
        "quickZoomJumpsOverTrafficStreetPerf" { return "quickZoomJumpsOverTrafficStreetPerf" }
        "quickZoomJumpsOverTrafficSatellitePerf" { return "quickZoomJumpsOverTrafficSatellitePerf" }
        "zoomLowToHighSweep" { return "zoomLowToHighSweepSatellite" }
        "zoomLowToHighSweepStreet" { return "zoomLowToHighSweepStreet" }
        "morphTransitionSweepStreet" { return "morphTransitionSweepStreet" }
        "morphTransitionSweepSatellite" { return "morphTransitionSweepSatellite" }
        "morphTransitionSweepStreetPerf" { return "morphTransitionSweepStreetPerf" }
        "morphTransitionSweepSatellitePerf" { return "morphTransitionSweepSatellitePerf" }
        "countryScaleZoomContinuityStreet" { return "countryScaleZoomContinuityStreet" }
        "countryScaleZoomContinuitySatellite" { return "countryScaleZoomContinuitySatellite" }
        "countryScaleZoomContinuityStreetPerf" { return "countryScaleZoomContinuityStreetPerf" }
        "countryScaleZoomContinuitySatellitePerf" { return "countryScaleZoomContinuitySatellitePerf" }
        "wideScaleZoomContinuityStreet" { return "wideScaleZoomContinuityStreet" }
        "wideScaleZoomContinuitySatellite" { return "wideScaleZoomContinuitySatellite" }
        "wideScaleZoomContinuityStreetPerf" { return "wideScaleZoomContinuityStreetPerf" }
        "wideScaleZoomContinuitySatellitePerf" { return "wideScaleZoomContinuitySatellitePerf" }
        "closeScaleZoomContinuitySatellite" { return "closeScaleZoomContinuitySatellite" }
        "closeScaleZoomContinuitySatellitePerf" { return "closeScaleZoomContinuitySatellitePerf" }
        "panAcrossZoomLevels" { return "panAcrossZoomLevels" }
        "launchOnly" { return "launchOnly" }
        default { return $Name }
    }
}

function Get-RemoteMatches {
    param([string]$Pattern)
    $escaped = $Pattern.Replace("'", "'\''")
    $lines = Invoke-Adb shell "sh -c 'ls -1 $escaped 2>/dev/null || true'"
    return @($lines | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object { $_.Trim() })
}

function Remove-RemoteMatches {
    param([string[]]$Patterns)
    foreach ($pattern in $Patterns) {
        $escaped = $pattern.Replace("'", "'\''")
        Invoke-Adb shell "sh -c 'rm -f $escaped 2>/dev/null || true'" | Out-Null
    }
}

function Pull-RemoteFile {
    param(
        [string]$RemotePath,
        [string]$DestinationDirectory
    )
    $fileName = Split-Path -Path $RemotePath -Leaf
    $destination = Join-Path $DestinationDirectory $fileName
    & $adb -s $Device pull $RemotePath $destination | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Failed to pull $RemotePath" }
    return $destination
}

if ([string]::IsNullOrWhiteSpace($ArtifactName)) {
    $ArtifactName = Get-DefaultArtifactName -Name $TestName
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if ([string]::IsNullOrWhiteSpace($OutputName)) {
    $OutputName = "instrumentation-$ArtifactName-$stamp"
}
$outDir = Join-Path $outRoot $OutputName
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$artifactPrefix = "flightalert-perf-$ArtifactName"
$remoteAppPattern = "/sdcard/Android/data/$packageName/files/$artifactPrefix-*"
$remoteRootPattern = "/sdcard/$artifactPrefix*"
$remoteDownloadPattern = "/sdcard/Download/$artifactPrefix*"

Write-Host "Running $testClass#$TestName on $Device. Artifact prefix=$artifactPrefix"
Invoke-Adb logcat -c | Out-Null
Remove-RemoteMatches -Patterns @($remoteAppPattern, $remoteRootPattern, $remoteDownloadPattern)

Push-Location $repoRoot
try {
    $gradleLog = Join-Path $outDir "$OutputName-gradle.txt"
    $runnerArg = "-Pandroid.testInstrumentationRunnerArguments.class=$testClass#$TestName"
    & $gradlew connectedDebugAndroidTest $runnerArg --no-daemon 2>&1 | Tee-Object -FilePath $gradleLog
    $gradleExit = $LASTEXITCODE
} finally {
    Pop-Location
}

$logcatPath = Join-Path $outDir "$OutputName-logcat.txt"
Invoke-Adb logcat -d | Set-Content -Path $logcatPath

$remoteArtifacts = @()
$remoteArtifacts += Get-RemoteMatches -Pattern $remoteAppPattern
$remoteArtifacts += Get-RemoteMatches -Pattern $remoteRootPattern
$remoteArtifacts += Get-RemoteMatches -Pattern $remoteDownloadPattern
$remoteArtifacts = @($remoteArtifacts | Sort-Object -Unique)

$pulledPaths = @()
foreach ($remotePath in $remoteArtifacts) {
    $pulledPaths += Pull-RemoteFile -RemotePath $remotePath -DestinationDirectory $outDir
}

if (-not $KeepDeviceArtifacts) {
    Remove-RemoteMatches -Patterns @($remoteAppPattern, $remoteRootPattern, $remoteDownloadPattern)
}

$framestats = @($pulledPaths | Where-Object { $_ -like "*-framestats.txt" })
if ($framestats.Count -eq 0) {
    throw "No same-run framestats artifact was pulled for $artifactPrefix. See $gradleLog and $logcatPath."
}

$summaryCsv = Join-Path $outDir "$OutputName-summary-120hz.csv"
$summaryLines = & $summarizer -Path $framestats -TargetHz $TargetHz -Csv
$summaryLines | Set-Content -Path $summaryCsv
Write-Host "Summary table:"
& $summarizer -Path $framestats -TargetHz $TargetHz
Write-Host "Summary CSV:"
$summaryLines | ForEach-Object { Write-Host $_ }

$screenshots = @($pulledPaths | Where-Object { $_ -like "*.png" })
Write-Host "Saved artifacts: $outDir"
Write-Host "Saved summary CSV: $summaryCsv"
if ($screenshots.Count -gt 0) {
    Write-Host "Saved screenshots:"
    $screenshots | ForEach-Object { Write-Host "  $_" }
} else {
    Write-Host "No screenshots were pulled for $artifactPrefix."
}

if ($gradleExit -ne 0) {
    throw "Instrumentation test failed with exit code $gradleExit. Pulled available artifacts into $outDir."
}
