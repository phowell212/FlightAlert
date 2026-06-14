param(
    [string]$Device = "RFCX40KPN3B",
    [ValidateSet("pinch", "quick", "sweep", "stress", "select", "soak", "shellpan", "keyboardzoom")]
    [string]$Mode = "stress",
    [double]$Zoom = 5.4,
    [int]$SoakSeconds = 75,
    [switch]$RebuildHarness,
    [string]$CityName = "",
    [ValidateSet("Current", "Street", "Satellite")]
    [string]$MapSource = "Current",
    [ValidateSet("Current", "On", "Off")]
    [string]$RestrictedAirspaces = "Current",
    [switch]$ClearSelection,
    [switch]$SkipMap,
    [switch]$SkipTraffic,
    [switch]$SkipChrome,
    [switch]$NoScreenshots,
    [string]$OutputName = ""
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$androidJar = Join-Path $env:LOCALAPPDATA "Android\Sdk\platforms\android-35\android.jar"
$d8 = Join-Path $env:LOCALAPPDATA "Android\Sdk\build-tools\35.0.0\d8.bat"
$outDir = Join-Path $PSScriptRoot "out"
$jarPath = "/data/local/tmp/multitouch-gestures.jar"
$localJar = Join-Path $outDir "multitouch-gestures.jar"

$cities = @(
    @{ Name = "New York City"; Lat = 40.73; Lon = -73.93 },
    @{ Name = "Chicago"; Lat = 41.88; Lon = -87.63 },
    @{ Name = "Los Angeles"; Lat = 33.94; Lon = -118.40 },
    @{ Name = "Dallas-Fort Worth"; Lat = 32.90; Lon = -97.04 },
    @{ Name = "Atlanta"; Lat = 33.64; Lon = -84.43 },
    @{ Name = "Toronto"; Lat = 43.68; Lon = -79.63 },
    @{ Name = "Mexico City"; Lat = 19.44; Lon = -99.07 },
    @{ Name = "London"; Lat = 51.47; Lon = -0.45 },
    @{ Name = "Paris"; Lat = 49.01; Lon = 2.55 },
    @{ Name = "Amsterdam"; Lat = 52.31; Lon = 4.77 },
    @{ Name = "Frankfurt"; Lat = 50.04; Lon = 8.56 },
    @{ Name = "Madrid"; Lat = 40.49; Lon = -3.57 }
)

function Invoke-Adb {
    & $adb -s $Device @args
}

function Assert-FlightAlertForeground {
    param([int]$TimeoutSeconds = 20)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $windows = Invoke-Adb shell dumpsys window windows
        if ($windows -match "mCurrentFocus.*com\.flightalert|mFocusedApp.*com\.flightalert") { return }
        $activity = Invoke-Adb shell dumpsys activity activities
        if ($activity -match "topResumedActivity.*com\.flightalert|ResumedActivity.*com\.flightalert") { return }
        Start-Sleep -Milliseconds 300
    } while ((Get-Date) -lt $deadline)
    throw "Flight Alert is not foregrounded; refusing to test or screenshot the wrong app."
}

function Get-DisplaySize {
    $windows = Invoke-Adb shell dumpsys window windows
    $lines = @($windows)
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -notmatch "com\.flightalert/com\.flightalert\.MainActivity") { continue }
        $end = [Math]::Min($lines.Count - 1, $i + 80)
        for ($j = $i; $j -le $end; $j++) {
            $match = [regex]::Match($lines[$j], "frame=\[(\-?\d+),(\-?\d+)\]\[(\-?\d+),(\-?\d+)\]")
            if (-not $match.Success) { continue }
            $width = [int]$match.Groups[3].Value - [int]$match.Groups[1].Value
            $height = [int]$match.Groups[4].Value - [int]$match.Groups[2].Value
            if ($width -gt 0 -and $height -gt 0) {
                return @{ Width = $width; Height = $height }
            }
        }
    }
    $size = Invoke-Adb shell wm size
    $line = ($size | Select-String -Pattern "(\d+)x(\d+)" | Select-Object -First 1).Matches.Value
    if (-not $line) { throw "Could not read device display size." }
    $parts = $line.Split("x")
    return @{ Width = [int]$parts[0]; Height = [int]$parts[1] }
}

function Get-ScreencapDisplayId {
    $displayIds = Invoke-Adb shell dumpsys SurfaceFlinger --display-id
    $ids = @($displayIds | Select-String -Pattern "Display (\d+)" | ForEach-Object { $_.Matches[0].Groups[1].Value })
    if ($ids.Count -eq 0) { return "" }
    if ($ids.Count -eq 1) { return $ids[0] }
    $bestId = $ids[0]
    $bestSize = -1L
    foreach ($id in $ids) {
        $probePng = "/sdcard/flightalert-screencap-probe-$id.png"
        Invoke-Adb shell screencap -d $id -p $probePng 2>$null | Out-Null
        $sizeLine = Invoke-Adb shell wc -c $probePng
        Invoke-Adb shell rm -f $probePng | Out-Null
        $sizeMatch = $sizeLine | Select-String -Pattern "^\s*(\d+)"
        if (-not $sizeMatch) { continue }
        $size = [long]$sizeMatch.Matches[0].Groups[1].Value
        if ($size -gt $bestSize) {
            $bestSize = $size
            $bestId = $id
        }
    }
    return $bestId
}

function Build-Harness {
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    Remove-Item -Recurse -Force (Join-Path $PSScriptRoot "mtclasses"), (Join-Path $PSScriptRoot "mtdex") -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path (Join-Path $PSScriptRoot "mtclasses"), (Join-Path $PSScriptRoot "mtdex") | Out-Null
    javac -cp $androidJar -d (Join-Path $PSScriptRoot "mtclasses") (Join-Path $PSScriptRoot "MultiTouchGestureRunner.java")
    if ($LASTEXITCODE -ne 0) { throw "Failed to compile MultiTouchGestureRunner.java." }
    jar cf (Join-Path $outDir "multitouch-classes.jar") -C (Join-Path $PSScriptRoot "mtclasses") .
    if ($LASTEXITCODE -ne 0) { throw "Failed to package MultiTouchGestureRunner classes." }
    & $d8 --min-api 23 --classpath $androidJar --output (Join-Path $PSScriptRoot "mtdex") (Join-Path $outDir "multitouch-classes.jar")
    if ($LASTEXITCODE -ne 0) { throw "Failed to dex MultiTouchGestureRunner." }
    jar cf $localJar -C (Join-Path $PSScriptRoot "mtdex") classes.dex
    if ($LASTEXITCODE -ne 0) { throw "Failed to package MultiTouchGestureRunner dex." }
}

function Read-FrameStatsSummary {
    param([string]$Path)
    $lines = Get-Content -Path $Path
    $summary = $lines | Select-String -Pattern "Total frames rendered:|Janky frames:|50th percentile:|95th percentile:|99th percentile:" | Select-Object -First 5
    return ($summary | ForEach-Object { $_.Line.Trim() }) -join "; "
}

function Get-FlightAlertLog {
    Invoke-Adb logcat -d -s FlightAlert:D "*:S"
}

function Assert-PerfRunOwnership {
    param(
        [string]$RunId,
        [bool]$RequireViewport = $true
    )
    $logs = Get-FlightAlertLog
    $viewportLines = $logs | Select-String -Pattern "Debug perf viewport"
    if (-not $viewportLines) {
        if ($RequireViewport) {
            throw "No debug perf viewport log was captured for runId=$RunId."
        }
        return $logs
    }
    $unexpected = $viewportLines | Where-Object { $_.Line -notmatch [regex]::Escape("runId=$RunId") }
    if ($unexpected) {
        $details = ($unexpected | ForEach-Object { $_.Line.Trim() }) -join "`n"
        throw "Another perf viewport interrupted runId=${RunId}:`n$details"
    }
    return $logs
}

function Pull-Screenshot {
    param(
        [string]$Label,
        [string]$RemotePng
    )
    $localPng = Join-Path $outDir "$OutputName-$Label.png"
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & $adb -s $Device pull $RemotePng $localPng 2>$null | Out-Null
    $pullExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorActionPreference
    if ($pullExitCode -ne 0) { throw "Failed to pull $Label screenshot from $Device." }
    Invoke-Adb shell rm -f $RemotePng | Out-Null
    return $localPng
}

function Save-Screenshot {
    param([string]$Label)
    Assert-FlightAlertForeground
    $devicePng = "/sdcard/$OutputName-$Label.png"
    if ([string]::IsNullOrWhiteSpace($script:ScreencapDisplayId)) {
        Invoke-Adb shell screencap -p $devicePng | Out-Null
    } else {
        Invoke-Adb shell screencap -d $script:ScreencapDisplayId -p $devicePng | Out-Null
    }
    return Pull-Screenshot -Label $Label -RemotePng $devicePng
}

function Start-ScreenshotJob {
    param(
        [string]$Label,
        [int]$DelayMilliseconds
    )
    $devicePng = "/sdcard/$OutputName-$Label.png"
    $job = Start-Job -ScriptBlock {
        param($AdbPath, $DeviceId, $DisplayId, $DelayMs, $RemotePng)
        Start-Sleep -Milliseconds $DelayMs
        if ([string]::IsNullOrWhiteSpace($DisplayId)) {
            & $AdbPath -s $DeviceId shell screencap -p $RemotePng 2>$null | Out-Null
        } else {
            & $AdbPath -s $DeviceId shell screencap -d $DisplayId -p $RemotePng 2>$null | Out-Null
        }
        if ($LASTEXITCODE -ne 0) { throw "Failed to save delayed screenshot $RemotePng." }
    } -ArgumentList $adb, $Device, $script:ScreencapDisplayId, $DelayMilliseconds, $devicePng
    return [pscustomobject]@{
        Label = $Label
        RemotePng = $devicePng
        Job = $job
    }
}

function Schedule-DeviceScreenshot {
    param(
        [string]$Label,
        [int]$DelayMilliseconds
    )
    $devicePng = "/sdcard/$OutputName-$Label.png"
    $delaySeconds = [Math]::Max(0.0, $DelayMilliseconds / 1000.0).ToString("0.###", [Globalization.CultureInfo]::InvariantCulture)
    if ([string]::IsNullOrWhiteSpace($script:ScreencapDisplayId)) {
        Invoke-Adb shell "nohup sh -c 'sleep $delaySeconds; screencap -p $devicePng' >/dev/null 2>&1 &" | Out-Null
    } else {
        Invoke-Adb shell "nohup sh -c 'sleep $delaySeconds; screencap -d $script:ScreencapDisplayId -p $devicePng' >/dev/null 2>&1 &" | Out-Null
    }
    return [pscustomobject]@{
        Label = $Label
        RemotePng = $devicePng
    }
}

function Complete-DeviceScreenshots {
    param([array]$Jobs)
    $paths = @()
    foreach ($entry in $Jobs) {
        Assert-FlightAlertForeground
        $paths += Pull-Screenshot -Label $entry.Label -RemotePng $entry.RemotePng
    }
    return $paths
}

function Complete-ScreenshotJobs {
    param([array]$Jobs)
    $paths = @()
    foreach ($entry in $Jobs) {
        Wait-Job -Job $entry.Job | Out-Null
        Receive-Job -Job $entry.Job -ErrorAction Stop | Out-Null
        if ($entry.Job.State -ne "Completed") {
            throw "Delayed screenshot job '$($entry.Label)' failed with state $($entry.Job.State)."
        }
        Remove-Job -Job $entry.Job
        Assert-FlightAlertForeground
        $paths += Pull-Screenshot -Label $entry.Label -RemotePng $entry.RemotePng
    }
    return $paths
}

function Invoke-ShellPanGesture {
    param([hashtable]$Display)
    $w = [double]$Display.Width
    $h = [double]$Display.Height
    $left = [int]($w * 0.16)
    $right = [int]($w * 0.48)
    $centerX = [int]($w * 0.32)
    $top = [int]($h * 0.36)
    $middle = [int]($h * 0.57)
    $bottom = [int]($h * 0.71)

    Invoke-Adb shell input swipe $right $middle $left $middle 600 | Out-Null
    Invoke-Adb shell input swipe $left $middle $right $middle 600 | Out-Null
    Invoke-Adb shell input swipe $centerX $top $centerX $bottom 600 | Out-Null
    Invoke-Adb shell input swipe $centerX $bottom $centerX $top 600 | Out-Null
    Invoke-Adb shell input swipe $right $bottom $left $top 650 | Out-Null
    Invoke-Adb shell input swipe $left $top $right $bottom 650 | Out-Null
}

function Invoke-KeyboardZoomGesture {
    for ($i = 0; $i -lt 6; $i++) {
        Invoke-Adb shell input keyevent KEYCODE_PLUS | Out-Null
        Start-Sleep -Milliseconds 150
    }
    Start-Sleep -Milliseconds 300
    for ($i = 0; $i -lt 6; $i++) {
        Invoke-Adb shell input keyevent KEYCODE_MINUS | Out-Null
        Start-Sleep -Milliseconds 150
    }
    Start-Sleep -Milliseconds 300
    for ($i = 0; $i -lt 4; $i++) {
        Invoke-Adb shell input keyevent KEYCODE_PLUS | Out-Null
        Start-Sleep -Milliseconds 120
    }
}

Push-Location $repoRoot
try {
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    if ($Mode -ne "soak" -and $Mode -ne "shellpan" -and $Mode -ne "keyboardzoom" -and ($RebuildHarness -or -not (Test-Path $localJar))) {
        Build-Harness
    }
    if ($Mode -ne "soak" -and $Mode -ne "shellpan" -and $Mode -ne "keyboardzoom") {
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        & $adb -s $Device push $localJar $jarPath 2>$null | Out-Null
        $pushExitCode = $LASTEXITCODE
        $ErrorActionPreference = $previousErrorActionPreference
        if ($pushExitCode -ne 0) { throw "Failed to push gesture harness to $Device." }
    }
    $script:ScreencapDisplayId = Get-ScreencapDisplayId

    if ([string]::IsNullOrWhiteSpace($CityName)) {
        $city = $cities | Get-Random
    } else {
        $city = $cities | Where-Object { $_.Name -ieq $CityName } | Select-Object -First 1
        if (-not $city) { throw "Unknown perf city '$CityName'." }
    }
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $safeCity = ($city.Name -replace "[^A-Za-z0-9]+", "-").Trim("-").ToLowerInvariant()
    if ([string]::IsNullOrWhiteSpace($OutputName)) {
        $OutputName = "flightalert-$safeCity-$Mode-$stamp"
    }
    $runId = "perf-$safeCity-$Mode-$stamp-$([guid]::NewGuid().ToString('N').Substring(0, 8))"

    Write-Host "Testing $($city.Name) at lat=$($city.Lat), lon=$($city.Lon), zoom=$Zoom with $Mode gestures, map=$MapSource, restricted=$RestrictedAirspaces. runId=$runId"
    Invoke-Adb logcat -c | Out-Null
    $startArgs = @(
        "shell", "am", "start", "--display", "0", "--activity-single-top",
        "-n", "com.flightalert/.MainActivity",
        "--es", "com.flightalert.PERF_LAT", "$($city.Lat)",
        "--es", "com.flightalert.PERF_LON", "$($city.Lon)",
        "--es", "com.flightalert.PERF_ZOOM", "$Zoom",
        "--es", "com.flightalert.PERF_RUN_ID", "$runId"
    )
    if ($MapSource -ne "Current") {
        $startArgs += @("--es", "com.flightalert.PERF_MAP_SOURCE", $MapSource.ToUpperInvariant())
    }
    if ($RestrictedAirspaces -ne "Current") {
        $restrictedValue = if ($RestrictedAirspaces -eq "On") { "true" } else { "false" }
        $startArgs += @("--es", "com.flightalert.PERF_RESTRICTED_AIRSPACES_ENABLED", $restrictedValue)
    }
    if ($ClearSelection) {
        $startArgs += @("--es", "com.flightalert.PERF_CLEAR_SELECTION", "true")
    }
    if ($SkipMap) {
        $startArgs += @("--es", "com.flightalert.PERF_SKIP_MAP", "true")
    }
    if ($SkipTraffic) {
        $startArgs += @("--es", "com.flightalert.PERF_SKIP_TRAFFIC", "true")
    }
    if ($SkipChrome) {
        $startArgs += @("--es", "com.flightalert.PERF_SKIP_CHROME", "true")
    }
    Invoke-Adb @startArgs | Out-Null
    Assert-FlightAlertForeground
    Start-Sleep -Seconds 8
    Assert-FlightAlertForeground
    [void](Assert-PerfRunOwnership -RunId $runId)

    $screenshotPaths = @()
    if (-not $NoScreenshots) {
        $screenshotPaths += Save-Screenshot -Label "rest"
    }
    if ($Mode -eq "soak") {
        $screenshotJobs = @()
        if (-not $NoScreenshots) {
            $screenshotJobs = @(
                Schedule-DeviceScreenshot -Label "soak-active" -DelayMilliseconds ([Math]::Max(1000, [int]($SoakSeconds * 500)))
            )
        }
        Invoke-Adb shell dumpsys gfxinfo com.flightalert reset | Out-Null
        Write-Host "Soaking Flight Alert for $SoakSeconds seconds over $($city.Name)."
        Start-Sleep -Seconds $SoakSeconds
        if (-not $NoScreenshots) {
            $screenshotPaths += Complete-DeviceScreenshots -Jobs $screenshotJobs
        }
    } else {
        $display = Get-DisplaySize
        $screenshotJobs = @()
        if (-not $NoScreenshots) {
            $screenshotJobs = @(
                Schedule-DeviceScreenshot -Label "motion-start" -DelayMilliseconds 350
                Schedule-DeviceScreenshot -Label "motion-active" -DelayMilliseconds 1600
                Schedule-DeviceScreenshot -Label "motion-late" -DelayMilliseconds 2750
            )
        }
        Invoke-Adb shell dumpsys gfxinfo com.flightalert reset | Out-Null
        if ($Mode -eq "shellpan") {
            Invoke-ShellPanGesture -Display $display
        } elseif ($Mode -eq "keyboardzoom") {
            Invoke-KeyboardZoomGesture
        } else {
            Invoke-Adb shell CLASSPATH=$jarPath app_process / com.flightalert.perf.MultiTouchGestureRunner $Mode $display.Width $display.Height | Out-Host
        }
        Start-Sleep -Milliseconds 3800
        if (-not $NoScreenshots) {
            $screenshotPaths += Complete-DeviceScreenshots -Jobs $screenshotJobs
        }
    }
    Assert-FlightAlertForeground
    $recentLogs = Assert-PerfRunOwnership -RunId $runId -RequireViewport $false

    $statsPath = Join-Path $outDir "$OutputName-framestats.txt"
    Invoke-Adb shell dumpsys gfxinfo com.flightalert framestats | Set-Content -Path $statsPath
    $statsSummary = Read-FrameStatsSummary -Path $statsPath

    if (-not $NoScreenshots) {
        $screenshotPaths += Save-Screenshot -Label "end"
    }
    Assert-FlightAlertForeground

    $logPath = Join-Path $outDir "$OutputName-logcat.txt"
    Set-Content -Path $logPath -Value (($recentLogs | Out-String).TrimEnd())

    Write-Host "Saved framestats: $statsPath"
    Write-Host "Frame summary: $statsSummary"
    if ($NoScreenshots) {
        Write-Host "Screenshots disabled for this isolation run."
    } else {
        Write-Host "Saved screenshots:"
        $screenshotPaths | ForEach-Object { Write-Host "  $_" }
    }
    Write-Host "Saved FlightAlert logs: $logPath"
} finally {
    Pop-Location
}
