param(
    [string]$Device = "RFCX40KPN3B",
    [ValidateSet("pinch", "quick", "sweep", "stress", "select", "soak")]
    [string]$Mode = "stress",
    [double]$Zoom = 5.4,
    [int]$SoakSeconds = 75,
    [switch]$RebuildHarness,
    [string]$CityName = "",
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
    $size = Invoke-Adb shell wm size
    $line = ($size | Select-String -Pattern "(\d+)x(\d+)" | Select-Object -First 1).Matches.Value
    if (-not $line) { throw "Could not read device display size." }
    $parts = $line.Split("x")
    return @{ Width = [int]$parts[0]; Height = [int]$parts[1] }
}

function Build-Harness {
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    Remove-Item -Recurse -Force (Join-Path $PSScriptRoot "mtclasses"), (Join-Path $PSScriptRoot "mtdex") -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path (Join-Path $PSScriptRoot "mtclasses"), (Join-Path $PSScriptRoot "mtdex") | Out-Null
    javac -cp $androidJar -d (Join-Path $PSScriptRoot "mtclasses") (Join-Path $PSScriptRoot "MultiTouchGestureRunner.java")
    jar cf (Join-Path $outDir "multitouch-classes.jar") -C (Join-Path $PSScriptRoot "mtclasses") .
    & $d8 --min-api 23 --classpath $androidJar --output (Join-Path $PSScriptRoot "mtdex") (Join-Path $outDir "multitouch-classes.jar")
    jar cf $localJar -C (Join-Path $PSScriptRoot "mtdex") classes.dex
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

Push-Location $repoRoot
try {
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    if ($Mode -ne "soak" -and ($RebuildHarness -or -not (Test-Path $localJar))) {
        Build-Harness
    }
    if ($Mode -ne "soak") {
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        & $adb -s $Device push $localJar $jarPath 2>$null | Out-Null
        $pushExitCode = $LASTEXITCODE
        $ErrorActionPreference = $previousErrorActionPreference
        if ($pushExitCode -ne 0) { throw "Failed to push gesture harness to $Device." }
    }

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

    Write-Host "Testing $($city.Name) at lat=$($city.Lat), lon=$($city.Lon), zoom=$Zoom with $Mode gestures. runId=$runId"
    Invoke-Adb logcat -c | Out-Null
    Invoke-Adb shell am start --display 0 --activity-single-top -n com.flightalert/.MainActivity `
        --es com.flightalert.PERF_LAT $city.Lat `
        --es com.flightalert.PERF_LON $city.Lon `
        --es com.flightalert.PERF_ZOOM $Zoom `
        --es com.flightalert.PERF_RUN_ID $runId | Out-Null
    Assert-FlightAlertForeground
    Start-Sleep -Seconds 8
    Assert-FlightAlertForeground
    [void](Assert-PerfRunOwnership -RunId $runId)

    Invoke-Adb shell dumpsys gfxinfo com.flightalert reset | Out-Null
    if ($Mode -eq "soak") {
        Write-Host "Soaking Flight Alert for $SoakSeconds seconds over $($city.Name)."
        Start-Sleep -Seconds $SoakSeconds
    } else {
        $display = Get-DisplaySize
        Invoke-Adb shell CLASSPATH=$jarPath app_process / com.flightalert.perf.MultiTouchGestureRunner $Mode $display.Width $display.Height | Out-Host
        Start-Sleep -Milliseconds 700
    }
    Assert-FlightAlertForeground
    $recentLogs = Assert-PerfRunOwnership -RunId $runId -RequireViewport $false

    $statsPath = Join-Path $outDir "$OutputName-framestats.txt"
    Invoke-Adb shell dumpsys gfxinfo com.flightalert framestats | Set-Content -Path $statsPath
    $statsSummary = Read-FrameStatsSummary -Path $statsPath

    $devicePng = "/sdcard/$OutputName.png"
    $localPng = Join-Path $outDir "$OutputName.png"
    Invoke-Adb shell screencap -p $devicePng | Out-Null
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & $adb -s $Device pull $devicePng $localPng 2>$null | Out-Null
    $pullExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorActionPreference
    if ($pullExitCode -ne 0) { throw "Failed to pull screenshot from $Device." }
    Assert-FlightAlertForeground

    $logPath = Join-Path $outDir "$OutputName-logcat.txt"
    Set-Content -Path $logPath -Value (($recentLogs | Out-String).TrimEnd())

    Write-Host "Saved framestats: $statsPath"
    Write-Host "Frame summary: $statsSummary"
    Write-Host "Saved screenshot: $localPng"
    Write-Host "Saved FlightAlert logs: $logPath"
} finally {
    Pop-Location
}
