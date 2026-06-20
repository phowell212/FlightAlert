param(
    [string]$Device = "",
    [Parameter(Mandatory = $true)]
    [string]$TestName,
    [string]$ArtifactName = "",
    [double]$TargetHz = 120.0,
    [string]$OutputName = "",
    [switch]$KeepDeviceArtifacts,
    [switch]$RecordVideo,
    [int]$VideoTimeLimitSeconds = 90,
    [string]$VideoDisplayId = "",
    [string]$City = "",
    [ValidateSet("Current", "On", "Off")]
    [string]$MapRoads = "Current",
    [ValidateSet("Current", "On", "Off")]
    [string]$MapBorders = "Current",
    [switch]$SkipChrome,
    [switch]$SkipTopStatus,
    [switch]$SkipControls,
    [switch]$SkipTrafficPanel,
    [switch]$SkipTraffic,
    [switch]$TrafficDetailTiming,
    [ValidateSet("Unchecked", "Pass", "Fail")]
    [string]$VisibleEvidenceLandSafe = "Unchecked",
    [string]$VisibleEvidenceReviewer = ""
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
$landSafeTargets = @{
    "dallasfortworth" = [pscustomobject]@{ Name = "Dallas-Fort Worth"; Lat = 32.90; Lon = -97.04; MinLat = 29.7; MaxLat = 35.8; MinLon = -101.6; MaxLon = -92.8; MaxDistanceKm = 620.0 }
    "dfw" = [pscustomobject]@{ Name = "Dallas-Fort Worth"; Lat = 32.90; Lon = -97.04; MinLat = 29.7; MaxLat = 35.8; MinLon = -101.6; MaxLon = -92.8; MaxDistanceKm = 620.0 }
    "atlanta" = [pscustomobject]@{ Name = "Atlanta"; Lat = 33.64; Lon = -84.43; MinLat = 30.6; MaxLat = 36.5; MinLon = -88.8; MaxLon = -80.2; MaxDistanceKm = 620.0 }
    "atl" = [pscustomobject]@{ Name = "Atlanta"; Lat = 33.64; Lon = -84.43; MinLat = 30.6; MaxLat = 36.5; MinLon = -88.8; MaxLon = -80.2; MaxDistanceKm = 620.0 }
    "denver" = [pscustomobject]@{ Name = "Denver"; Lat = 39.86; Lon = -104.67; MinLat = 37.1; MaxLat = 42.6; MinLon = -109.4; MaxLon = -100.0; MaxDistanceKm = 620.0 }
    "den" = [pscustomobject]@{ Name = "Denver"; Lat = 39.86; Lon = -104.67; MinLat = 37.1; MaxLat = 42.6; MinLon = -109.4; MaxLon = -100.0; MaxDistanceKm = 620.0 }
    "phoenix" = [pscustomobject]@{ Name = "Phoenix"; Lat = 33.43; Lon = -112.01; MinLat = 31.7; MaxLat = 36.0; MinLon = -116.0; MaxLon = -108.4; MaxDistanceKm = 560.0 }
    "phx" = [pscustomobject]@{ Name = "Phoenix"; Lat = 33.43; Lon = -112.01; MinLat = 31.7; MaxLat = 36.0; MinLon = -116.0; MaxLon = -108.4; MaxDistanceKm = 560.0 }
    "lasvegas" = [pscustomobject]@{ Name = "Las Vegas"; Lat = 36.08; Lon = -115.15; MinLat = 34.2; MaxLat = 38.5; MinLon = -118.5; MaxLon = -111.9; MaxDistanceKm = 520.0 }
    "las" = [pscustomobject]@{ Name = "Las Vegas"; Lat = 36.08; Lon = -115.15; MinLat = 34.2; MaxLat = 38.5; MinLon = -118.5; MaxLon = -111.9; MaxDistanceKm = 520.0 }
    "vegas" = [pscustomobject]@{ Name = "Las Vegas"; Lat = 36.08; Lon = -115.15; MinLat = 34.2; MaxLat = 38.5; MinLon = -118.5; MaxLon = -111.9; MaxDistanceKm = 520.0 }
}

if (-not (Test-Path $adb)) { throw "adb.exe was not found at $adb" }
if (-not (Test-Path $gradlew)) { throw "gradlew.bat was not found at $gradlew" }
if (-not (Test-Path $summarizer)) { throw "SummarizeFrameStats.ps1 was not found next to this script." }
if (($MapRoads -eq "Current") -xor ($MapBorders -eq "Current")) {
    throw "When testing split map labels, provide both -MapRoads and -MapBorders so the run does not inherit half of the state from device preferences."
}

function Invoke-Adb {
    if ([string]::IsNullOrWhiteSpace($Device)) {
        & $adb @args
    } else {
        & $adb -s $Device @args
    }
}

function Normalize-FlightAlertCity {
    param([string]$Name)
    if ([string]::IsNullOrWhiteSpace($Name)) { return "" }
    return ($Name.ToLowerInvariant() -replace "[^a-z0-9]", "")
}

function Get-LandSafeTarget {
    param([string]$Name)
    $key = Normalize-FlightAlertCity -Name $Name
    if ($landSafeTargets.ContainsKey($key)) { return $landSafeTargets[$key] }
    return $null
}

function Assert-InlandCityArgument {
    if ([string]::IsNullOrWhiteSpace($City)) { return }
    $target = Get-LandSafeTarget -Name $City
    if (-not $target) {
        throw "City '$City' is not a land-safe harness target. Use Dallas-Fort Worth, Atlanta, Denver, Phoenix, or Las Vegas for route-proof performance tests."
    }
}

function ConvertTo-InvariantDouble {
    param([string]$Value)
    return [double]::Parse($Value, [System.Globalization.CultureInfo]::InvariantCulture)
}

function Get-DistanceKm {
    param(
        [double]$Lat1,
        [double]$Lon1,
        [double]$Lat2,
        [double]$Lon2
    )
    $earthKm = 6371.0
    $dLat = ($Lat2 - $Lat1) * [Math]::PI / 180.0
    $dLon = ($Lon2 - $Lon1) * [Math]::PI / 180.0
    $rLat1 = $Lat1 * [Math]::PI / 180.0
    $rLat2 = $Lat2 * [Math]::PI / 180.0
    $a = [Math]::Sin($dLat / 2.0) * [Math]::Sin($dLat / 2.0) +
        [Math]::Cos($rLat1) * [Math]::Cos($rLat2) *
        [Math]::Sin($dLon / 2.0) * [Math]::Sin($dLon / 2.0)
    return 2.0 * $earthKm * [Math]::Atan2([Math]::Sqrt($a), [Math]::Sqrt([Math]::Max(0.0, 1.0 - $a)))
}

function Get-TargetArtifactCity {
    param([string[]]$TargetArtifacts)
    foreach ($target in $TargetArtifacts) {
        if (-not (Test-Path $target)) { continue }
        $line = Get-Content -Path $target | Where-Object { $_ -match "^city=" } | Select-Object -First 1
        if ($line) { return ($line -replace "^city=", "").Trim() }
    }
    return ""
}

function Test-RouteFocusEvidence {
    param(
        [string]$LogcatPath,
        [string[]]$TargetArtifacts
    )
    $cityName = if ([string]::IsNullOrWhiteSpace($City)) { Get-TargetArtifactCity -TargetArtifacts $TargetArtifacts } else { $City }
    $target = Get-LandSafeTarget -Name $cityName
    $result = [ordered]@{
        City = $cityName
        ExpectedCity = if ($target) { $target.Name } else { "" }
        Passed = $false
        RejectReason = ""
        FocusSamples = 0
        MaxDistanceKm = 0.0
        MaxAbsLatDelta = 0.0
        MaxAbsLonDelta = 0.0
        OutOfEnvelopeSamples = 0
    }
    if (-not $target) {
        $result.RejectReason = "target city is missing or not in the inland route-proof allowlist"
        return [pscustomobject]$result
    }
    if (-not (Test-Path $LogcatPath)) {
        $result.RejectReason = "logcat artifact is unavailable"
        return [pscustomobject]$result
    }
    $focusLines = @(Select-String -Path $LogcatPath -Pattern "Debug draw perf.*focusLat=" -ErrorAction SilentlyContinue)
    foreach ($entry in $focusLines) {
        $line = $entry.Line
        if ($line -notmatch "focusLat=([-+]?\d+(?:\.\d+)?)\s+focusLon=([-+]?\d+(?:\.\d+)?)") { continue }
        $lat = ConvertTo-InvariantDouble -Value $Matches[1]
        $lon = ConvertTo-InvariantDouble -Value $Matches[2]
        $result.FocusSamples++
        $distance = Get-DistanceKm -Lat1 $target.Lat -Lon1 $target.Lon -Lat2 $lat -Lon2 $lon
        $result.MaxDistanceKm = [Math]::Max([double]$result.MaxDistanceKm, $distance)
        $result.MaxAbsLatDelta = [Math]::Max([double]$result.MaxAbsLatDelta, [Math]::Abs($lat - $target.Lat))
        $result.MaxAbsLonDelta = [Math]::Max([double]$result.MaxAbsLonDelta, [Math]::Abs($lon - $target.Lon))
        if ($lat -lt $target.MinLat -or $lat -gt $target.MaxLat -or $lon -lt $target.MinLon -or $lon -gt $target.MaxLon -or $distance -gt $target.MaxDistanceKm) {
            $result.OutOfEnvelopeSamples++
        }
    }
    if ($result.FocusSamples -eq 0) {
        $result.RejectReason = "no usable Debug draw perf focusLat/focusLon samples were found"
        return [pscustomobject]$result
    }
    if ($result.OutOfEnvelopeSamples -gt 0) {
        $result.RejectReason = "$($result.OutOfEnvelopeSamples) focus samples left the inland route envelope for $($target.Name)"
        return [pscustomobject]$result
    }
    $result.Passed = $true
    $result.RejectReason = "none"
    return [pscustomobject]$result
}

function New-PngContactSheet {
    param(
        [string[]]$PngPaths,
        [string]$DestinationPath
    )
    $existing = @($PngPaths | Where-Object { Test-Path $_ } | Sort-Object)
    if ($existing.Count -eq 0) { return "" }
    Add-Type -AssemblyName System.Drawing
    $thumbWidth = 360
    $thumbHeight = 220
    $padding = 12
    $columns = [Math]::Min(4, [Math]::Max(1, $existing.Count))
    $rows = [Math]::Ceiling($existing.Count / [double]$columns)
    $sheetWidth = $columns * $thumbWidth + ($columns + 1) * $padding
    $sheetHeight = [int]$rows * ($thumbHeight + 44) + ([int]$rows + 1) * $padding
    $sheet = New-Object System.Drawing.Bitmap $sheetWidth, $sheetHeight
    $graphics = [System.Drawing.Graphics]::FromImage($sheet)
    $graphics.Clear([System.Drawing.Color]::FromArgb(18, 24, 22))
    $font = New-Object System.Drawing.Font "Segoe UI", 10
    $brush = [System.Drawing.Brushes]::White
    try {
        for ($i = 0; $i -lt $existing.Count; $i++) {
            $image = [System.Drawing.Image]::FromFile($existing[$i])
            try {
                $col = $i % $columns
                $row = [Math]::Floor($i / $columns)
                $x = $padding + $col * ($thumbWidth + $padding)
                $y = $padding + $row * ($thumbHeight + 44 + $padding)
                $scale = [Math]::Min($thumbWidth / [double]$image.Width, $thumbHeight / [double]$image.Height)
                $drawWidth = [int]($image.Width * $scale)
                $drawHeight = [int]($image.Height * $scale)
                $drawX = $x + [int](($thumbWidth - $drawWidth) / 2)
                $drawY = $y + [int](($thumbHeight - $drawHeight) / 2)
                $graphics.DrawImage($image, $drawX, $drawY, $drawWidth, $drawHeight)
                $label = Split-Path -Path $existing[$i] -Leaf
                $graphics.DrawString($label, $font, $brush, $x, $y + $thumbHeight + 8)
            } finally {
                $image.Dispose()
            }
        }
        $sheet.Save($DestinationPath, [System.Drawing.Imaging.ImageFormat]::Png)
        return $DestinationPath
    } finally {
        $font.Dispose()
        $graphics.Dispose()
        $sheet.Dispose()
    }
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
        "satelliteTileTransitionBandContinuity" { return "satelliteTileTransitionBandContinuity" }
        "satelliteTileTransitionBandContinuityPerf" { return "satelliteTileTransitionBandContinuityPerf" }
        "satelliteFastZoomOutTileLoad" { return "satelliteFastZoomOutTileLoad" }
        "satelliteFastZoomOutTileLoadPerf" { return "satelliteFastZoomOutTileLoadPerf" }
        "streetFastZoomOutTileLoad" { return "streetFastZoomOutTileLoad" }
        "streetFastZoomOutTileLoadPerf" { return "streetFastZoomOutTileLoadPerf" }
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
    if ([string]::IsNullOrWhiteSpace($Device)) {
        & $adb pull $RemotePath $destination | Out-Null
    } else {
        & $adb -s $Device pull $RemotePath $destination | Out-Null
    }
    if ($LASTEXITCODE -ne 0) { throw "Failed to pull $RemotePath" }
    return $destination
}

function Get-ActivePhysicalDisplayId {
    $displayDump = Invoke-Adb shell dumpsys display
    foreach ($line in $displayDump) {
        if ($line -match "DisplayViewport\{type=INTERNAL, valid=true, isActive=true, displayId=0, uniqueId='local:(\d+)'") {
            return $Matches[1]
        }
    }
    return ""
}

function Start-ScreenRecord {
    param(
        [string]$RemotePath,
        [int]$TimeLimitSeconds,
        [string]$DisplayId
    )
    Invoke-Adb shell "pkill -2 screenrecord >/dev/null 2>&1 || true" | Out-Null
    Invoke-Adb shell "rm -f $RemotePath" | Out-Null
    $displayArgs = @()
    if (-not [string]::IsNullOrWhiteSpace($DisplayId)) {
        $displayArgs += "--display-id"
        $displayArgs += $DisplayId
    }
    return Start-Job -ScriptBlock {
        param($AdbPath, $DeviceId, $Path, $LimitSeconds, $DisplayArgs)
        if ([string]::IsNullOrWhiteSpace($DeviceId)) {
            & $AdbPath shell screenrecord @DisplayArgs --bit-rate 12000000 --time-limit $LimitSeconds $Path 2>$null | Out-Null
        } else {
            & $AdbPath -s $DeviceId shell screenrecord @DisplayArgs --bit-rate 12000000 --time-limit $LimitSeconds $Path 2>$null | Out-Null
        }
    } -ArgumentList $adb, $Device, $RemotePath, $TimeLimitSeconds, $displayArgs
}

function Stop-ScreenRecord {
    param(
        [System.Management.Automation.Job]$Job,
        [string]$RemotePath
    )
    Invoke-Adb shell "pkill -2 screenrecord >/dev/null 2>&1 || true" | Out-Null
    if ($Job) {
        Wait-Job -Job $Job -Timeout 8 | Out-Null
        if ($Job.State -eq "Running") {
            Invoke-Adb shell "pkill screenrecord >/dev/null 2>&1 || true" | Out-Null
            Wait-Job -Job $Job -Timeout 4 | Out-Null
        }
        Receive-Job -Job $Job -ErrorAction SilentlyContinue | Out-Null
        Remove-Job -Job $Job -Force -ErrorAction SilentlyContinue
    }
    $probe = Invoke-Adb shell "sh -c 'test -s $RemotePath && echo yes || true'"
    return ($probe | Select-Object -First 1) -eq "yes"
}

function Test-FlightAlertForeground {
    $activity = Invoke-Adb shell dumpsys activity activities
    $window = Invoke-Adb shell dumpsys window windows
    $foreground = @($activity; $window) -join "`n"
    $fullActivity = "$packageName/$packageName.MainActivity"
    $shortActivity = "$packageName/.MainActivity"
    foreach ($line in ($foreground -split "`r?`n")) {
        if (($line.Contains("mCurrentFocus=") -or
             $line.Contains("mFocusedApp=") -or
             $line.Contains("topResumedActivity=") -or
             $line.Contains("mResumedActivity:") -or
             $line.Contains("ResumedActivity:")) -and
            ($line.Contains($fullActivity) -or $line.Contains($shortActivity))) {
            return $true
        }
    }
    return $false
}

function Wait-FlightAlertForeground {
    param([int]$TimeoutSeconds = 90)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-FlightAlertForeground) {
            return $true
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Start-GradleInstrumentationJob {
    param(
        [string]$RepoRoot,
        [string]$GradlewPath,
        [string[]]$RunnerArgs,
        [string]$GradleLogPath,
        [string]$DeviceSerial
    )
    Start-Job -ScriptBlock {
        param($JobRepoRoot, $JobGradlewPath, $JobRunnerArgs, $JobGradleLogPath, $JobDeviceSerial)
        Set-Location $JobRepoRoot
        if (-not [string]::IsNullOrWhiteSpace($JobDeviceSerial)) {
            $env:ANDROID_SERIAL = $JobDeviceSerial
        }
        & $JobGradlewPath connectedDebugAndroidTest @JobRunnerArgs --no-daemon 2>&1 | Tee-Object -FilePath $JobGradleLogPath
        "FLIGHTALERT_GRADLE_EXIT_CODE=$LASTEXITCODE"
    } -ArgumentList $RepoRoot, $GradlewPath, $RunnerArgs, $GradleLogPath, $DeviceSerial
}

Assert-InlandCityArgument

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

$videoJob = $null
$remoteVideoPath = "/sdcard/$OutputName-screenrecord.mp4"
$videoStartedAfterForeground = $false
$videoSkippedReason = ""

Push-Location $repoRoot
$previousAndroidSerial = $env:ANDROID_SERIAL
try {
    $gradleLog = Join-Path $outDir "$OutputName-gradle.txt"
    $runnerArgs = @("-Pandroid.testInstrumentationRunnerArguments.class=$testClass#$TestName")
    if (-not [string]::IsNullOrWhiteSpace($Device)) {
        $env:ANDROID_SERIAL = $Device
        $runnerArgs += "-Pandroid.injected.device.serial=$Device"
    }
    if (-not [string]::IsNullOrWhiteSpace($City)) {
        $runnerArgs += "-Pandroid.testInstrumentationRunnerArguments.targetCity=$City"
    }
    if ($MapRoads -ne "Current") {
        $mapRoadsValue = if ($MapRoads -eq "On") { "true" } else { "false" }
        $runnerArgs += "-Pandroid.testInstrumentationRunnerArguments.mapRoads=$mapRoadsValue"
    }
    if ($MapBorders -ne "Current") {
        $mapBordersValue = if ($MapBorders -eq "On") { "true" } else { "false" }
        $runnerArgs += "-Pandroid.testInstrumentationRunnerArguments.mapBorders=$mapBordersValue"
    }
    if ($SkipChrome) {
        $runnerArgs += "-Pandroid.testInstrumentationRunnerArguments.skipChrome=true"
    }
    if ($SkipTopStatus) {
        $runnerArgs += "-Pandroid.testInstrumentationRunnerArguments.skipTopStatus=true"
    }
    if ($SkipControls) {
        $runnerArgs += "-Pandroid.testInstrumentationRunnerArguments.skipControls=true"
    }
    if ($SkipTrafficPanel) {
        $runnerArgs += "-Pandroid.testInstrumentationRunnerArguments.skipTrafficPanel=true"
    }
    if ($SkipTraffic) {
        $runnerArgs += "-Pandroid.testInstrumentationRunnerArguments.skipTraffic=true"
    }
    if ($TrafficDetailTiming) {
        $runnerArgs += "-Pandroid.testInstrumentationRunnerArguments.trafficDetailTiming=true"
    }
    if ($RecordVideo) {
        $gradleExit = 1
        $gradleJob = Start-GradleInstrumentationJob -RepoRoot $repoRoot -GradlewPath $gradlew -RunnerArgs $runnerArgs -GradleLogPath $gradleLog -DeviceSerial $Device
        if (Wait-FlightAlertForeground -TimeoutSeconds 90) {
            if ([string]::IsNullOrWhiteSpace($VideoDisplayId)) {
                $VideoDisplayId = Get-ActivePhysicalDisplayId
            }
            if ([string]::IsNullOrWhiteSpace($VideoDisplayId)) {
                Write-Host "Recording screen video to $remoteVideoPath for up to $VideoTimeLimitSeconds seconds after Flight Alert reached foreground."
            } else {
                Write-Host "Recording screen video to $remoteVideoPath for up to $VideoTimeLimitSeconds seconds on display $VideoDisplayId after Flight Alert reached foreground."
            }
            $videoJob = Start-ScreenRecord -RemotePath $remoteVideoPath -TimeLimitSeconds $VideoTimeLimitSeconds -DisplayId $VideoDisplayId
            $videoStartedAfterForeground = $true
            Start-Sleep -Milliseconds 300
        } else {
            $videoSkippedReason = "Flight Alert did not reach foreground within 90 seconds; video skipped to avoid launcher/home-screen evidence."
            Write-Host $videoSkippedReason
        }
        $jobOutput = Receive-Job -Job $gradleJob -Wait
        Remove-Job -Job $gradleJob -Force -ErrorAction SilentlyContinue
        foreach ($entry in $jobOutput) {
            $line = "$entry"
            if ($line -match "^FLIGHTALERT_GRADLE_EXIT_CODE=(\d+)$") {
                $gradleExit = [int]$Matches[1]
            } else {
                Write-Host $line
            }
        }
    } else {
        & $gradlew connectedDebugAndroidTest @runnerArgs --no-daemon 2>&1 | Tee-Object -FilePath $gradleLog
        $gradleExit = $LASTEXITCODE
    }
} finally {
    if ($null -eq $previousAndroidSerial) {
        Remove-Item Env:\ANDROID_SERIAL -ErrorAction SilentlyContinue
    } else {
        $env:ANDROID_SERIAL = $previousAndroidSerial
    }
    Pop-Location
    if ($RecordVideo -and $videoJob) {
        $videoReady = Stop-ScreenRecord -Job $videoJob -RemotePath $remoteVideoPath
        if ($videoReady) {
            Pull-RemoteFile -RemotePath $remoteVideoPath -DestinationDirectory $outDir | Out-Null
            if (-not $KeepDeviceArtifacts) {
                Invoke-Adb shell "rm -f $remoteVideoPath" | Out-Null
            }
        } else {
            Write-Host "Screen recording did not produce a non-empty video."
        }
    }
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

$screenshots = @($pulledPaths | Where-Object { $_ -like "*.png" })
$videos = @(Get-ChildItem -Path $outDir -Filter "*.mp4" -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
$targetArtifacts = @($pulledPaths | Where-Object { $_ -like "*-target.txt" })
$routeFocusEvidence = Test-RouteFocusEvidence -LogcatPath $logcatPath -TargetArtifacts $targetArtifacts
$contactSheetPath = ""
if ($screenshots.Count -gt 0) {
    try {
        $contactSheetPath = New-PngContactSheet -PngPaths $screenshots -DestinationPath (Join-Path $outDir "$OutputName-contact-sheet.png")
    } catch {
        Write-Host "Contact sheet generation skipped: $($_.Exception.Message)"
    }
}
$routeProofPath = Join-Path $outDir "$OutputName-route-proof.txt"
$routeProof = @()
$routeProof += "test_class=$testClass"
$routeProof += "test_name=$TestName"
$routeProof += "artifact_prefix=$artifactPrefix"
$routeProof += "device=$Device"
$routeProof += "city_argument=$City"
$routeProof += "map_roads_argument=$MapRoads"
$routeProof += "map_borders_argument=$MapBorders"
$routeProof += "record_video=$RecordVideo"
$routeProof += "video_started_after_flightalert_foreground=$videoStartedAfterForeground"
$routeProof += "video_skipped_reason=$videoSkippedReason"
$routeProof += "visible_evidence_land_safe=$VisibleEvidenceLandSafe"
$routeProof += "visible_evidence_reviewer=$VisibleEvidenceReviewer"
$routeProof += "visible_evidence_rule=Pass only after screenshots/video and focusLat/focusLon logs show active gesture focus over inland land/traffic. Incidental coastline/water in a continent-scale viewport is acceptable only when the focus stays on the requested inland target."
$routeProof += "screenshots_count=$($screenshots.Count)"
$routeProof += "videos_count=$($videos.Count)"
$routeProof += "contact_sheet=$contactSheetPath"
$routeProof += "pulled_target_artifacts=$($targetArtifacts.Count)"
$routeProof += "route_focus_passed=$($routeFocusEvidence.Passed)"
$routeProof += "route_focus_city=$($routeFocusEvidence.City)"
$routeProof += "route_focus_expected_city=$($routeFocusEvidence.ExpectedCity)"
$routeProof += "route_focus_samples=$($routeFocusEvidence.FocusSamples)"
$routeProof += ("route_focus_max_distance_km={0:N1}" -f [double]$routeFocusEvidence.MaxDistanceKm)
$routeProof += ("route_focus_max_abs_lat_delta={0:N4}" -f [double]$routeFocusEvidence.MaxAbsLatDelta)
$routeProof += ("route_focus_max_abs_lon_delta={0:N4}" -f [double]$routeFocusEvidence.MaxAbsLonDelta)
$routeProof += "route_focus_out_of_envelope_samples=$($routeFocusEvidence.OutOfEnvelopeSamples)"
$routeProof += "route_focus_reject_reason=$($routeFocusEvidence.RejectReason)"
foreach ($target in $targetArtifacts) {
    $routeProof += ""
    $routeProof += "----- $(Split-Path -Path $target -Leaf) -----"
    $routeProof += Get-Content -Path $target
}
if (Test-Path $logcatPath) {
    $phaseLines = @(Select-String -Path $logcatPath -Pattern "FlightAlertPerfPhase|Debug perf viewport|Debug perf focus anchored|Debug draw perf.*camera centerLat" -ErrorAction SilentlyContinue | Select-Object -First 220)
    $routeProof += ""
    $routeProof += "----- focus_and_phase_log_excerpt -----"
    if ($phaseLines.Count -eq 0) {
        $routeProof += "unavailable"
    } else {
        $routeProof += @($phaseLines | ForEach-Object { $_.Line })
    }
}
$acceptedEvidence = ($VisibleEvidenceLandSafe -eq "Pass" -and $routeFocusEvidence.Passed -and (-not $RecordVideo -or $videoStartedAfterForeground))
if (-not $acceptedEvidence) {
    $routeProof += ""
    $routeProof += "accepted_optimizer_evidence=false"
    $routeProof += "accepted_optimizer_evidence_reason=visible evidence is $VisibleEvidenceLandSafe; route_focus_passed=$($routeFocusEvidence.Passed); video_started_after_flightalert_foreground=$videoStartedAfterForeground."
} else {
    $routeProof += ""
    $routeProof += "accepted_optimizer_evidence=true"
}
$routeProof | Set-Content -Path $routeProofPath
Write-Host "Saved route-proof manifest: $routeProofPath"

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

Write-Host "Saved artifacts: $outDir"
Write-Host "Saved summary CSV: $summaryCsv"
Write-Host ("Route focus evidence: passed={0}; city={1}; samples={2}; maxDistanceKm={3:N1}; rejectReason={4}" -f `
    $routeFocusEvidence.Passed, $routeFocusEvidence.ExpectedCity, $routeFocusEvidence.FocusSamples, [double]$routeFocusEvidence.MaxDistanceKm, $routeFocusEvidence.RejectReason)
if (-not [string]::IsNullOrWhiteSpace($contactSheetPath)) {
    Write-Host "Saved contact sheet: $contactSheetPath"
}
if ($screenshots.Count -gt 0) {
    Write-Host "Saved screenshots:"
    $screenshots | ForEach-Object { Write-Host "  $_" }
} else {
    Write-Host "No screenshots were pulled for $artifactPrefix."
}
if ($videos.Count -gt 0) {
    Write-Host "Saved videos:"
    $videos | ForEach-Object { Write-Host "  $_" }
}

if ($gradleExit -ne 0) {
    throw "Instrumentation test failed with exit code $gradleExit. Pulled available artifacts into $outDir."
}
if ($RecordVideo -and -not $videoStartedAfterForeground) {
    throw "Route-proof video was not started after Flight Alert reached foreground; discard this run and inspect $routeProofPath."
}
if (-not $routeFocusEvidence.Passed) {
    throw "Route focus proof failed: $($routeFocusEvidence.RejectReason). Discard this run and inspect $routeProofPath."
}
if ($VisibleEvidenceLandSafe -eq "Fail") {
    throw "Visible evidence was marked Fail; discard this run and inspect $routeProofPath."
}
