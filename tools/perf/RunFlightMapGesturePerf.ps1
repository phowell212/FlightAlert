param(
    [string]$Device = "",
    [Parameter(Mandatory = $true)]
    [string]$TestName,
    [string]$ArtifactName = "",
    [double]$TargetHz = 120.0,
    [string]$OutputName = "",
    [switch]$KeepDeviceArtifacts,
    [switch]$RecordVideo,
    [int]$VideoTimeLimitSeconds = 60,
    [int]$MaxRunSeconds = 150,
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
    [switch]$MapDetailTiming,
    [ValidateSet("GradleConnected", "SplitInstall")]
    [string]$HarnessExecutionMode = "GradleConnected",
    [ValidateSet("InstallDefault", "Verify", "Speed")]
    [string]$ArtCompileMode = "InstallDefault",
    [ValidateSet("Workbook", "Diagnostic")]
    [string]$BenchmarkRole = "Workbook",
    [switch]$RequireControlledPreflight,
    [int]$ControlledPreflightTimeoutSeconds = 120,
    [ValidateSet("None", "PostInstallResetV1")]
    [string]$ControlledDexoptNormalizationMode = "None",
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
$testPackageName = "com.flightalert.test"
$testClass = "com.flightalert.perf.FlightMapGesturePerfTest"
$instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
$landSafeTargets = @{
    "dallasfortworth" = [pscustomobject]@{ Name = "Dallas-Fort Worth"; Lat = 32.90; Lon = -97.04; MinLat = 29.7; MaxLat = 35.8; MinLon = -101.6; MaxLon = -92.8; MaxDistanceKm = 620.0 }
    "dfw" = [pscustomobject]@{ Name = "Dallas-Fort Worth"; Lat = 32.90; Lon = -97.04; MinLat = 29.7; MaxLat = 35.8; MinLon = -101.6; MaxLon = -92.8; MaxDistanceKm = 620.0 }
    "atlanta" = [pscustomobject]@{ Name = "Atlanta"; Lat = 33.64; Lon = -84.43; MinLat = 30.6; MaxLat = 36.5; MinLon = -88.8; MaxLon = -80.2; MaxDistanceKm = 620.0 }
    "atl" = [pscustomobject]@{ Name = "Atlanta"; Lat = 33.64; Lon = -84.43; MinLat = 30.6; MaxLat = 36.5; MinLon = -88.8; MaxLon = -80.2; MaxDistanceKm = 620.0 }
    "phoenix" = [pscustomobject]@{ Name = "Phoenix"; Lat = 33.43; Lon = -112.01; MinLat = 31.7; MaxLat = 36.0; MinLon = -116.0; MaxLon = -108.4; MaxDistanceKm = 560.0 }
    "phx" = [pscustomobject]@{ Name = "Phoenix"; Lat = 33.43; Lon = -112.01; MinLat = 31.7; MaxLat = 36.0; MinLon = -116.0; MaxLon = -108.4; MaxDistanceKm = 560.0 }
    "lasvegas" = [pscustomobject]@{ Name = "Las Vegas"; Lat = 36.08; Lon = -115.15; MinLat = 34.2; MaxLat = 38.5; MinLon = -118.5; MaxLon = -111.9; MaxDistanceKm = 520.0 }
    "las" = [pscustomobject]@{ Name = "Las Vegas"; Lat = 36.08; Lon = -115.15; MinLat = 34.2; MaxLat = 38.5; MinLon = -118.5; MaxLon = -111.9; MaxDistanceKm = 520.0 }
    "vegas" = [pscustomobject]@{ Name = "Las Vegas"; Lat = 36.08; Lon = -115.15; MinLat = 34.2; MaxLat = 38.5; MinLon = -118.5; MaxLon = -111.9; MaxDistanceKm = 520.0 }
    "chicago" = [pscustomobject]@{ Name = "Chicago"; Lat = 41.88; Lon = -87.63; MinLat = 39.4; MaxLat = 44.2; MinLon = -91.4; MaxLon = -83.8; MaxDistanceKm = 480.0 }
    "chi" = [pscustomobject]@{ Name = "Chicago"; Lat = 41.88; Lon = -87.63; MinLat = 39.4; MaxLat = 44.2; MinLon = -91.4; MaxLon = -83.8; MaxDistanceKm = 480.0 }
    "ord" = [pscustomobject]@{ Name = "Chicago"; Lat = 41.88; Lon = -87.63; MinLat = 39.4; MaxLat = 44.2; MinLon = -91.4; MaxLon = -83.8; MaxDistanceKm = 480.0 }
    "newyorkcity" = [pscustomobject]@{ Name = "New York City"; Lat = 40.73; Lon = -73.93; MinLat = 38.7; MaxLat = 43.1; MinLon = -77.4; MaxLon = -70.1; MaxDistanceKm = 460.0 }
    "newyork" = [pscustomobject]@{ Name = "New York City"; Lat = 40.73; Lon = -73.93; MinLat = 38.7; MaxLat = 43.1; MinLon = -77.4; MaxLon = -70.1; MaxDistanceKm = 460.0 }
    "nyc" = [pscustomobject]@{ Name = "New York City"; Lat = 40.73; Lon = -73.93; MinLat = 38.7; MaxLat = 43.1; MinLon = -77.4; MaxLon = -70.1; MaxDistanceKm = 460.0 }
    "jfk" = [pscustomobject]@{ Name = "New York City"; Lat = 40.73; Lon = -73.93; MinLat = 38.7; MaxLat = 43.1; MinLon = -77.4; MaxLon = -70.1; MaxDistanceKm = 460.0 }
    "losangeles" = [pscustomobject]@{ Name = "Los Angeles"; Lat = 33.94; Lon = -118.40; MinLat = 32.0; MaxLat = 36.4; MinLon = -121.8; MaxLon = -115.2; MaxDistanceKm = 460.0 }
    "la" = [pscustomobject]@{ Name = "Los Angeles"; Lat = 33.94; Lon = -118.40; MinLat = 32.0; MaxLat = 36.4; MinLon = -121.8; MaxLon = -115.2; MaxDistanceKm = 460.0 }
    "lax" = [pscustomobject]@{ Name = "Los Angeles"; Lat = 33.94; Lon = -118.40; MinLat = 32.0; MaxLat = 36.4; MinLon = -121.8; MaxLon = -115.2; MaxDistanceKm = 460.0 }
    "london" = [pscustomobject]@{ Name = "London"; Lat = 51.47; Lon = -0.45; MinLat = 48.6; MaxLat = 54.3; MinLon = -5.4; MaxLon = 4.6; MaxDistanceKm = 600.0 }
    "lhr" = [pscustomobject]@{ Name = "London"; Lat = 51.47; Lon = -0.45; MinLat = 48.6; MaxLat = 54.3; MinLon = -5.4; MaxLon = 4.6; MaxDistanceKm = 600.0 }
    "lon" = [pscustomobject]@{ Name = "London"; Lat = 51.47; Lon = -0.45; MinLat = 48.6; MaxLat = 54.3; MinLon = -5.4; MaxLon = 4.6; MaxDistanceKm = 600.0 }
    "amsterdam" = [pscustomobject]@{ Name = "Amsterdam"; Lat = 52.31; Lon = 4.77; MinLat = 49.6; MaxLat = 54.9; MinLon = 0.2; MaxLon = 9.6; MaxDistanceKm = 560.0 }
    "ams" = [pscustomobject]@{ Name = "Amsterdam"; Lat = 52.31; Lon = 4.77; MinLat = 49.6; MaxLat = 54.9; MinLon = 0.2; MaxLon = 9.6; MaxDistanceKm = 560.0 }
    "frankfurt" = [pscustomobject]@{ Name = "Frankfurt"; Lat = 50.04; Lon = 8.56; MinLat = 47.1; MaxLat = 53.0; MinLon = 4.0; MaxLon = 13.2; MaxDistanceKm = 560.0 }
    "fra" = [pscustomobject]@{ Name = "Frankfurt"; Lat = 50.04; Lon = 8.56; MinLat = 47.1; MaxLat = 53.0; MinLon = 4.0; MaxLon = 13.2; MaxDistanceKm = 560.0 }
    "paris" = [pscustomobject]@{ Name = "Paris"; Lat = 49.01; Lon = 2.55; MinLat = 46.1; MaxLat = 51.8; MinLon = -2.4; MaxLon = 7.4; MaxDistanceKm = 560.0 }
    "par" = [pscustomobject]@{ Name = "Paris"; Lat = 49.01; Lon = 2.55; MinLat = 46.1; MaxLat = 51.8; MinLon = -2.4; MaxLon = 7.4; MaxDistanceKm = 560.0 }
    "cdg" = [pscustomobject]@{ Name = "Paris"; Lat = 49.01; Lon = 2.55; MinLat = 46.1; MaxLat = 51.8; MinLon = -2.4; MaxLon = 7.4; MaxDistanceKm = 560.0 }
    "madrid" = [pscustomobject]@{ Name = "Madrid"; Lat = 40.49; Lon = -3.57; MinLat = 37.7; MaxLat = 43.3; MinLon = -8.4; MaxLon = 1.2; MaxDistanceKm = 560.0 }
    "mad" = [pscustomobject]@{ Name = "Madrid"; Lat = 40.49; Lon = -3.57; MinLat = 37.7; MaxLat = 43.3; MinLon = -8.4; MaxLon = 1.2; MaxDistanceKm = 560.0 }
}

if (-not (Test-Path $adb)) { throw "adb.exe was not found at $adb" }
if (-not (Test-Path $gradlew)) { throw "gradlew.bat was not found at $gradlew" }
if (-not (Test-Path $summarizer)) { throw "SummarizeFrameStats.ps1 was not found next to this script." }
if ($MaxRunSeconds -gt 180) { throw "MaxRunSeconds=$MaxRunSeconds is too long. Perf harness runs are capped at 180 seconds; use 60 seconds for short checks and 150 seconds or less for thorough checks." }
if ($MaxRunSeconds -lt 30) { throw "MaxRunSeconds=$MaxRunSeconds is too short for reliable instrumentation startup; use at least 30 seconds." }
if ($VideoTimeLimitSeconds -gt 180) { throw "VideoTimeLimitSeconds=$VideoTimeLimitSeconds is too long. Videos are capped at 180 seconds and should usually be 60 seconds for short checks or 150 seconds for thorough checks." }
if ($VideoTimeLimitSeconds -lt 5) { throw "VideoTimeLimitSeconds=$VideoTimeLimitSeconds is too short to produce useful visual evidence." }
if ($VideoTimeLimitSeconds -gt $MaxRunSeconds) { throw "VideoTimeLimitSeconds=$VideoTimeLimitSeconds cannot exceed MaxRunSeconds=$MaxRunSeconds." }
if ($RecordVideo -and $MaxRunSeconds -lt 45) { throw "RecordVideo needs MaxRunSeconds of at least 45 seconds so the app can stay foreground while the video finishes." }
if (($MapRoads -eq "Current") -xor ($MapBorders -eq "Current")) {
    throw "When testing split map labels, provide both -MapRoads and -MapBorders so the run does not inherit half of the state from device preferences."
}
if ($HarnessExecutionMode -ne "SplitInstall" -and $ArtCompileMode -ne "InstallDefault") {
    throw "ArtCompileMode=$ArtCompileMode requires -HarnessExecutionMode SplitInstall so compilation happens after install and before instrumentation launch."
}
if ($RequireControlledPreflight -and $HarnessExecutionMode -ne "SplitInstall") {
    throw "RequireControlledPreflight needs -HarnessExecutionMode SplitInstall so install, thermal, and dexopt can be checked before instrumentation."
}
if ($RequireControlledPreflight -and $BenchmarkRole -ne "Workbook") {
    throw "RequireControlledPreflight is only for chart-grade -BenchmarkRole Workbook runs."
}
if ($RequireControlledPreflight -and $ArtCompileMode -ne "InstallDefault") {
    throw "RequireControlledPreflight expects -ArtCompileMode InstallDefault; explicit compile modes are diagnostic-only."
}
if ($ControlledDexoptNormalizationMode -ne "None" -and -not $RequireControlledPreflight) {
    throw "ControlledDexoptNormalizationMode=$ControlledDexoptNormalizationMode is only allowed with -RequireControlledPreflight so normalized dexopt states are recorded as their own chart lane."
}
if ($ControlledDexoptNormalizationMode -ne "None" -and $HarnessExecutionMode -ne "SplitInstall") {
    throw "ControlledDexoptNormalizationMode=$ControlledDexoptNormalizationMode requires -HarnessExecutionMode SplitInstall."
}
if ($ControlledDexoptNormalizationMode -ne "None" -and $ArtCompileMode -ne "InstallDefault") {
    throw "ControlledDexoptNormalizationMode=$ControlledDexoptNormalizationMode expects -ArtCompileMode InstallDefault; explicit compile modes remain diagnostic-only."
}
if ($ControlledPreflightTimeoutSeconds -lt 30 -or $ControlledPreflightTimeoutSeconds -gt 180) {
    throw "ControlledPreflightTimeoutSeconds=$ControlledPreflightTimeoutSeconds must be between 30 and 180 seconds."
}
if ($HarnessExecutionMode -eq "SplitInstall" -and $BenchmarkRole -ne "Diagnostic" -and -not $RequireControlledPreflight) {
    throw "SplitInstall workbook runs require -RequireControlledPreflight. Otherwise pass -BenchmarkRole Diagnostic so they stay out of Chart Data."
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

function Get-TimetableDefaultCity {
    $utcHour = [DateTime]::UtcNow.Hour
    if (($utcHour -ge 5 -and $utcHour -lt 15) -or ($utcHour -ge 3 -and $utcHour -lt 5)) {
        return "Frankfurt"
    }
    if ($utcHour -ge 15 -and $utcHour -lt 22) {
        return "Atlanta"
    }
    if ($utcHour -ge 22 -or $utcHour -lt 3) {
        return "Dallas-Fort Worth"
    }
    return "Frankfurt"
}

function Get-GitWorktreeEvidence {
    param([string]$RepoRoot)
    $evidence = [ordered]@{
        Available = $false
        Branch = ""
        Commit = ""
        WorktreeDirty = ""
        StatusCount = ""
        StatusShort = ""
        Error = ""
    }
    $gitCommand = Get-Command git -ErrorAction SilentlyContinue
    if (-not $gitCommand) {
        $evidence.Error = "git command unavailable"
        return [pscustomobject]$evidence
    }
    Push-Location $RepoRoot
    try {
        $branch = @(& git rev-parse --abbrev-ref HEAD 2>$null | Select-Object -First 1)
        $commit = @(& git rev-parse HEAD 2>$null | Select-Object -First 1)
        $statusLines = @(& git status --porcelain=v1 2>$null)
        $evidence.Available = $true
        $evidence.Branch = if ($branch.Count -gt 0) { "$($branch[0])" } else { "" }
        $evidence.Commit = if ($commit.Count -gt 0) { "$($commit[0])" } else { "" }
        $evidence.WorktreeDirty = $statusLines.Count -gt 0
        $evidence.StatusCount = $statusLines.Count
        $evidence.StatusShort = (($statusLines | Select-Object -First 25) -join " | ") -replace "`r|`n", " "
        if ($statusLines.Count -gt 25) {
            $evidence.StatusShort = "$($evidence.StatusShort) | ...+$($statusLines.Count - 25) more"
        }
    } catch {
        $evidence.Error = $_.Exception.Message
    } finally {
        Pop-Location
    }
    return [pscustomobject]$evidence
}

function Join-BenchmarkEvidenceSnippet {
    param(
        [string[]]$Lines,
        [int]$MaxLines = 30,
        [int]$MaxChars = 1800
    )
    $snippet = @($Lines | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First $MaxLines | ForEach-Object {
        ($_ -replace "`r|`n", " ").Trim()
    }) -join " | "
    if ($snippet.Length -gt $MaxChars) {
        return $snippet.Substring(0, $MaxChars) + "...truncated"
    }
    return $snippet
}

function Get-LocalApkEvidence {
    param([string]$Path)
    $evidence = [ordered]@{
        Exists = $false
        Bytes = ""
        LastWriteUtc = ""
        Sha256 = ""
        Error = ""
    }
    try {
        if (-not (Test-Path -LiteralPath $Path)) {
            $evidence.Error = "missing"
            return [pscustomobject]$evidence
        }
        $file = Get-Item -LiteralPath $Path
        $hash = Get-FileHash -LiteralPath $Path -Algorithm SHA256
        $evidence.Exists = $true
        $evidence.Bytes = $file.Length
        $evidence.LastWriteUtc = $file.LastWriteTimeUtc.ToString("o")
        $evidence.Sha256 = $hash.Hash.ToLowerInvariant()
    } catch {
        $evidence.Error = $_.Exception.Message
    }
    return [pscustomobject]$evidence
}

function Find-LatestApkPath {
    param(
        [string]$RepoRoot,
        [string]$RelativeDirectory,
        [string]$NamePattern
    )
    $candidates = @(
        Join-Path $RepoRoot $RelativeDirectory
        Join-Path $RepoRoot ("app\" + $RelativeDirectory)
    )
    foreach ($dir in $candidates) {
        if (-not (Test-Path -LiteralPath $dir)) { continue }
        $file = Get-ChildItem -LiteralPath $dir -Filter $NamePattern -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($file) { return $file.FullName }
    }
    return Join-Path $RepoRoot ("app\" + $RelativeDirectory + "\" + $NamePattern)
}

function Get-BenchmarkDeviceEvidence {
    param(
        [string]$RepoRoot,
        [string]$PackageName
    )
    $debugApkPath = Find-LatestApkPath -RepoRoot $RepoRoot -RelativeDirectory "build\outputs\apk\debug" -NamePattern "*debug.apk"
    $testApkPath = Find-LatestApkPath -RepoRoot $RepoRoot -RelativeDirectory "build\outputs\apk\androidTest\debug" -NamePattern "*androidTest.apk"
    $debugApk = Get-LocalApkEvidence -Path $debugApkPath
    $testApk = Get-LocalApkEvidence -Path $testApkPath
    $evidence = [ordered]@{
        DebugApkExists = $debugApk.Exists
        DebugApkPath = $debugApkPath
        DebugApkBytes = $debugApk.Bytes
        DebugApkLastWriteUtc = $debugApk.LastWriteUtc
        DebugApkSha256 = $debugApk.Sha256
        DebugApkError = $debugApk.Error
        TestApkExists = $testApk.Exists
        TestApkPath = $testApkPath
        TestApkBytes = $testApk.Bytes
        TestApkLastWriteUtc = $testApk.LastWriteUtc
        TestApkSha256 = $testApk.Sha256
        TestApkError = $testApk.Error
        PackagePaths = ""
        PackageCompileEvidence = ""
        BatteryLevel = ""
        BatteryTempC = ""
        BatteryStatus = ""
        BatteryPlugged = ""
        ThermalStatus = ""
        ThermalEvidence = ""
        DisplayRefreshEvidence = ""
        DeviceBuildFingerprint = ""
        DeviceBuildVersion = ""
        DeviceBuildSdk = ""
        DeviceArtEvidence = ""
        Error = ""
    }
    try {
        $packagePaths = @(Invoke-Adb shell pm path $PackageName 2>$null)
        $evidence.PackagePaths = Join-BenchmarkEvidenceSnippet -Lines $packagePaths -MaxLines 8 -MaxChars 1200
    } catch {
        $evidence.Error = "pm path failed: $($_.Exception.Message)"
    }
    try {
        $packageDump = @(Invoke-Adb shell dumpsys package $PackageName 2>$null)
        $compileLines = @($packageDump | Where-Object { $_ -match "(?i)dexopt|compiler|compile|profile|speed|verify|oat|odex|status=|reason=" })
        $evidence.PackageCompileEvidence = Join-BenchmarkEvidenceSnippet -Lines $compileLines -MaxLines 40 -MaxChars 2200
    } catch {
        $evidence.PackageCompileEvidence = "unavailable: $($_.Exception.Message)"
    }
    try {
        $battery = @(Invoke-Adb shell dumpsys battery 2>$null)
        foreach ($line in $battery) {
            if ($line -match "^\s*level:\s*(\d+)") { $evidence.BatteryLevel = $Matches[1] }
            if ($line -match "^\s*temperature:\s*(\d+)") { $evidence.BatteryTempC = ("{0:N1}" -f ([double]$Matches[1] / 10.0)) }
            if ($line -match "^\s*status:\s*(\d+)") { $evidence.BatteryStatus = $Matches[1] }
            if ($line -match "^\s*plugged:\s*(\d+)") { $evidence.BatteryPlugged = $Matches[1] }
        }
    } catch {
        $evidence.BatteryStatus = "unavailable: $($_.Exception.Message)"
    }
    try {
        $thermal = @(Invoke-Adb shell dumpsys thermalservice 2>$null)
        foreach ($line in $thermal) {
            if ($line -match "(?i)thermal\s+status:\s*([A-Za-z0-9_ -]+)") {
                $evidence.ThermalStatus = $Matches[1].Trim()
                break
            }
        }
        $evidence.ThermalEvidence = Join-BenchmarkEvidenceSnippet -Lines $thermal -MaxLines 20 -MaxChars 1800
    } catch {
        $evidence.ThermalEvidence = "unavailable: $($_.Exception.Message)"
    }
    try {
        $display = @(Invoke-Adb shell dumpsys display 2>$null | Where-Object { $_ -match "(?i)refresh|fps|mode|DisplayDeviceInfo" })
        $evidence.DisplayRefreshEvidence = Join-BenchmarkEvidenceSnippet -Lines $display -MaxLines 35 -MaxChars 2200
    } catch {
        $evidence.DisplayRefreshEvidence = "unavailable: $($_.Exception.Message)"
    }
    try {
        $props = @(Invoke-Adb shell getprop 2>$null)
        foreach ($line in $props) {
            if ($line -match "^\[ro\.build\.fingerprint\]:\s*\[(.*)\]") { $evidence.DeviceBuildFingerprint = $Matches[1] }
            if ($line -match "^\[ro\.build\.version\.release\]:\s*\[(.*)\]") { $evidence.DeviceBuildVersion = $Matches[1] }
            if ($line -match "^\[ro\.build\.version\.sdk\]:\s*\[(.*)\]") { $evidence.DeviceBuildSdk = $Matches[1] }
        }
        $artLines = @($props | Where-Object { $_ -match "(?i)\[(dalvik\.vm|persist\.device_config\.runtime_native|pm\.dexopt|ro\.dalvik|dalvik\.vm\.usejit)" })
        $evidence.DeviceArtEvidence = Join-BenchmarkEvidenceSnippet -Lines $artLines -MaxLines 45 -MaxChars 2600
    } catch {
        $evidence.DeviceArtEvidence = "unavailable: $($_.Exception.Message)"
    }
    return [pscustomobject]$evidence
}

function Get-PackageDexoptState {
    param([string]$CompileEvidence)
    $match = [regex]::Match($CompileEvidence, "Dexopt state:.*?arm64:\s*\[status=([^\]]+)\]\s*\[reason=([^\]]+)\]", [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if (-not $match.Success) { return "" }
    return "$($match.Groups[1].Value)/$($match.Groups[2].Value)"
}

function Get-PackageDexoptEntries {
    param([string]$CompileEvidence)
    $matches = [regex]::Matches($CompileEvidence, "(?<isa>[A-Za-z0-9_.-]+):\s*\[status=(?<status>[^\]]+)\]\s*\[reason=(?<reason>[^\]]+)\]", [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    $entries = @()
    foreach ($match in $matches) {
        $entries += [pscustomobject]@{
            Isa = $match.Groups["isa"].Value
            State = "$($match.Groups["status"].Value)/$($match.Groups["reason"].Value)"
        }
    }
    return $entries
}

function Get-PackageDexoptFingerprint {
    param([string]$CompileEvidence)
    $entries = @(Get-PackageDexoptEntries -CompileEvidence $CompileEvidence)
    if ($entries.Count -eq 0) { return "" }
    return (@($entries | Sort-Object Isa, State | ForEach-Object { "$($_.Isa)=$($_.State)" }) -join ";")
}

function Test-PackageDexoptAllEntriesMatch {
    param(
        [string]$CompileEvidence,
        [string]$ExpectedState
    )
    $entries = @(Get-PackageDexoptEntries -CompileEvidence $CompileEvidence)
    if ($entries.Count -eq 0) { return $false }
    foreach ($entry in $entries) {
        if ($entry.State -ne $ExpectedState) { return $false }
    }
    return $true
}

function Get-ControlledExpectedDexoptState {
    param([string]$NormalizationMode)
    if ($NormalizationMode -eq "PostInstallResetV1") { return "verify/install" }
    return "verify/install-speg"
}

function Invoke-ControlledDexoptNormalization {
    param(
        [string]$Mode,
        [string]$PackageName,
        [string]$OutputPath
    )
    $result = [ordered]@{
        Mode = $Mode
        Command = ""
        ExitCode = ""
        Output = ""
        PackageCompileEvidence = ""
        PackageDexoptState = ""
        PackageDexoptFingerprint = ""
    }
    if ($Mode -eq "None") {
        $result.Command = "none; preserving post-install package dexopt state"
        $result.ExitCode = 0
    } elseif ($Mode -eq "PostInstallResetV1") {
        $commandArgs = @("shell", "cmd", "package", "compile", "--reset", $PackageName)
        $result.Command = "adb $($commandArgs -join ' ')"
        Write-Host "Applying controlled dexopt normalization $Mode with: $($result.Command)"
        $normalizationOutput = @(Invoke-Adb @commandArgs 2>&1)
        $result.ExitCode = $LASTEXITCODE
        $result.Output = Join-BenchmarkEvidenceSnippet -Lines $normalizationOutput -MaxLines 60 -MaxChars 2400
        if ($result.ExitCode -ne 0) {
            @(
                "mode=$($result.Mode)"
                "command=$($result.Command)"
                "exit_code=$($result.ExitCode)"
                "output=$($result.Output)"
            ) | Set-Content -Path $OutputPath
            throw "Controlled dexopt normalization failed with exit code $($result.ExitCode): $($result.Output)"
        }
    } else {
        throw "Unsupported controlled dexopt normalization mode: $Mode"
    }
    try {
        $packageDump = @(Invoke-Adb shell dumpsys package $PackageName 2>$null)
        $compileLines = @($packageDump | Where-Object { $_ -match "(?i)dexopt|compiler|compile|profile|speed|verify|oat|odex|status=|reason=" })
        $result.PackageCompileEvidence = Join-BenchmarkEvidenceSnippet -Lines $compileLines -MaxLines 80 -MaxChars 3600
        $result.PackageDexoptState = Get-PackageDexoptState -CompileEvidence $result.PackageCompileEvidence
        $result.PackageDexoptFingerprint = Get-PackageDexoptFingerprint -CompileEvidence $result.PackageCompileEvidence
    } catch {
        $result.PackageCompileEvidence = "unavailable: $($_.Exception.Message)"
    }
    @(
        "mode=$($result.Mode)"
        "command=$($result.Command)"
        "exit_code=$($result.ExitCode)"
        "output=$($result.Output)"
        "package_dexopt_state=$($result.PackageDexoptState)"
        "package_dexopt_fingerprint=$($result.PackageDexoptFingerprint)"
        "package_compile_evidence=$($result.PackageCompileEvidence)"
    ) | Set-Content -Path $OutputPath
    return [pscustomobject]$result
}

function Invoke-ControlledBenchmarkPreflight {
    param(
        [string]$RepoRoot,
        [string]$PackageName,
        [string]$OutputPath,
        [pscustomobject]$GitEvidence,
        [string]$ArtCompileMode,
        [string]$ExpectedDexoptState,
        [string]$NormalizationMode,
        [int]$TimeoutSeconds
    )
    $expectedDexopt = if ([string]::IsNullOrWhiteSpace($ExpectedDexoptState)) { Get-ControlledExpectedDexoptState -NormalizationMode $NormalizationMode } else { $ExpectedDexoptState }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $samples = @()
    $thermalZeroCount = 0
    $lastEvidence = $null
    while ((Get-Date) -lt $deadline) {
        $lastEvidence = Get-BenchmarkDeviceEvidence -RepoRoot $RepoRoot -PackageName $PackageName
        $dexoptState = Get-PackageDexoptState -CompileEvidence $lastEvidence.PackageCompileEvidence
        $dexoptFingerprint = Get-PackageDexoptFingerprint -CompileEvidence $lastEvidence.PackageCompileEvidence
        $thermalStatus = "$($lastEvidence.ThermalStatus)".Trim()
        if ($thermalStatus -eq "0") {
            $thermalZeroCount += 1
        } else {
            $thermalZeroCount = 0
        }
        $gitClean = ($GitEvidence.Available -eq $true -and "$($GitEvidence.WorktreeDirty)" -eq "False")
        $dexoptOk = Test-PackageDexoptAllEntriesMatch -CompileEvidence $lastEvidence.PackageCompileEvidence -ExpectedState $expectedDexopt
        $artOk = ($ArtCompileMode -eq "InstallDefault")
        $samples += "utc=$((Get-Date).ToUniversalTime().ToString("o")) thermal=$thermalStatus consecutiveThermalZero=$thermalZeroCount dexopt=$dexoptState fingerprint=$dexoptFingerprint expectedDexopt=$expectedDexopt normalizationMode=$NormalizationMode art=$ArtCompileMode gitClean=$gitClean"
        if ($gitClean -and $dexoptOk -and $artOk -and $thermalZeroCount -ge 2) {
            $summary = Join-BenchmarkEvidenceSnippet -Lines $samples -MaxLines 20 -MaxChars 1800
            @(
                "required=True"
                "passed=True"
                "expected_dexopt=$expectedDexopt"
                "normalization_mode=$NormalizationMode"
                "thermal_zero_samples=$thermalZeroCount"
                "package_dexopt_state=$dexoptState"
                "package_dexopt_fingerprint=$dexoptFingerprint"
                "git_clean=$gitClean"
                "art_compile_mode=$ArtCompileMode"
                "evidence=$summary"
            ) | Set-Content -Path $OutputPath
            return [pscustomobject]@{
                Passed = $true
                Evidence = $summary
                PackageDexoptState = $dexoptState
                PackageDexoptFingerprint = $dexoptFingerprint
                ExpectedDexoptState = $expectedDexopt
                NormalizationMode = $NormalizationMode
                ThermalZeroSamples = $thermalZeroCount
            }
        }
        Start-Sleep -Seconds ([Math]::Min(10, [Math]::Max(1, [int][Math]::Floor(($deadline - (Get-Date)).TotalSeconds))))
    }
    $lastDexopt = if ($lastEvidence) { Get-PackageDexoptState -CompileEvidence $lastEvidence.PackageCompileEvidence } else { "" }
    $lastFingerprint = if ($lastEvidence) { Get-PackageDexoptFingerprint -CompileEvidence $lastEvidence.PackageCompileEvidence } else { "" }
    $lastThermal = if ($lastEvidence) { "$($lastEvidence.ThermalStatus)".Trim() } else { "" }
    $finalSummary = Join-BenchmarkEvidenceSnippet -Lines $samples -MaxLines 30 -MaxChars 2400
    @(
        "required=True"
        "passed=False"
        "expected_dexopt=$expectedDexopt"
        "normalization_mode=$NormalizationMode"
        "last_thermal_status=$lastThermal"
        "last_package_dexopt_state=$lastDexopt"
        "last_package_dexopt_fingerprint=$lastFingerprint"
        "git_clean=$($GitEvidence.Available -eq $true -and "$($GitEvidence.WorktreeDirty)" -eq "False")"
        "art_compile_mode=$ArtCompileMode"
        "evidence=$finalSummary"
    ) | Set-Content -Path $OutputPath
    throw "Controlled preflight rejected before benchmark capture: thermal=$lastThermal dexopt=$lastDexopt expectedDexopt=$expectedDexopt. See $OutputPath."
}

function Assert-InlandCityArgument {
    if ([string]::IsNullOrWhiteSpace($City)) { return }
    $target = Get-LandSafeTarget -Name $City
    if (-not $target) {
        throw "City '$City' is not a land-safe harness target. Choose a current timetable-backed US/EU target such as Frankfurt, London, Amsterdam, Paris, Madrid, Dallas-Fort Worth, Atlanta, Phoenix, Las Vegas, Chicago, New York, or Los Angeles."
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
        $result.RejectReason = "target city is missing or not in the timetable-backed US/EU route-proof allowlist"
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
        $result.RejectReason = "$($result.OutOfEnvelopeSamples) focus samples left the timetable-backed US/EU route envelope for $($target.Name)"
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

function Get-FlightAlertFfmpegPath {
    $candidates = @(
        "C:\Tools\ffmpeg-btbn-latest\bin\ffmpeg.exe",
        "ffmpeg.exe"
    )
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) { return $candidate }
        $resolved = Get-Command $candidate -ErrorAction SilentlyContinue
        if ($resolved) { return $resolved.Source }
    }
    return ""
}

function New-RoadMotionVideoStrip {
    param(
        [string]$VideoPath,
        [string]$DestinationPath
    )
    if (-not (Test-Path $VideoPath)) { return "" }
    $ffmpeg = Get-FlightAlertFfmpegPath
    if ([string]::IsNullOrWhiteSpace($ffmpeg)) { return "" }
    $stdoutPath = [System.IO.Path]::GetTempFileName()
    $stderrPath = [System.IO.Path]::GetTempFileName()
    try {
        $arguments = @(
            "-y",
            "-hide_banner",
            "-loglevel", "error",
            "-i", $VideoPath,
            "-vf", "fps=8,scale=480:-1,tile=4x8",
            "-frames:v", "1",
            "-update", "1",
            $DestinationPath
        )
        $process = Start-Process -FilePath $ffmpeg -ArgumentList $arguments -Wait -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
        if ($process.ExitCode -ne 0 -or -not (Test-Path $DestinationPath)) { return "" }
    } finally {
        Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
    }
    return $DestinationPath
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
        "closeSatellitePanLabels" { return "closeSatellitePanLabels" }
        "closeSatellitePanLabelsPerf" { return "closeSatellitePanLabelsPerf" }
        "satellitePanZoomSanityPerf" { return "satellitePanZoomSanityPerf" }
        "satelliteBenchmarkPanZoomWorkloadPerf" { return "satelliteBenchmarkPanZoomWorkloadPerf" }
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

function Get-RemainingRunSeconds {
    param([DateTime]$Deadline)
    $remaining = [int][Math]::Ceiling(($Deadline - (Get-Date)).TotalSeconds)
    return [Math]::Max(1, $remaining)
}

function Stop-FlightAlertPerfRun {
    Invoke-Adb shell "pkill -2 screenrecord >/dev/null 2>&1 || true" | Out-Null
    Invoke-Adb shell "am force-stop $packageName >/dev/null 2>&1 || true" | Out-Null
    Invoke-Adb shell "am force-stop $testPackageName >/dev/null 2>&1 || true" | Out-Null
}

function Stop-GradleInstrumentationProcesses {
    param([string]$RepoRootForMatch)
    $normalizedRoot = $RepoRootForMatch.ToLowerInvariant()
    $processes = @(Get-CimInstance Win32_Process | Where-Object {
        $commandLine = $_.CommandLine
        if ([string]::IsNullOrWhiteSpace($commandLine)) { return $false }
        $lower = $commandLine.ToLowerInvariant()
        return $lower.Contains("connecteddebugandroidtest") -and $lower.Contains($normalizedRoot)
    })
    foreach ($process in $processes) {
        try {
            Stop-Process -Id $process.ProcessId -Force -ErrorAction Stop
            Write-Host "Stopped timed-out Gradle instrumentation process pid=$($process.ProcessId)."
        } catch {
            Write-Host "Could not stop timed-out Gradle instrumentation process pid=$($process.ProcessId): $($_.Exception.Message)"
        }
    }
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

function Invoke-GradleInstallForSplitInstrumentation {
    param(
        [string]$RepoRoot,
        [string]$GradlewPath,
        [string]$GradleInstallLogPath,
        [string]$DeviceSerial
    )
    Push-Location $RepoRoot
    $previousSerial = $env:ANDROID_SERIAL
    try {
        $installArgs = @("installDebug", "installDebugAndroidTest")
        if (-not [string]::IsNullOrWhiteSpace($DeviceSerial)) {
            $env:ANDROID_SERIAL = $DeviceSerial
            $installArgs += "-Pandroid.injected.device.serial=$DeviceSerial"
        }
        $installArgs += "--no-daemon"
        Write-Host "Installing debug and androidTest APKs before split instrumentation."
        & $GradlewPath @installArgs 2>&1 | Tee-Object -FilePath $GradleInstallLogPath | ForEach-Object {
            Write-Host $_
        }
        $exitCode = $LASTEXITCODE
        "FLIGHTALERT_GRADLE_INSTALL_EXIT_CODE=$exitCode" | Add-Content -Path $GradleInstallLogPath
        return $exitCode
    } finally {
        if ($null -eq $previousSerial) {
            Remove-Item Env:\ANDROID_SERIAL -ErrorAction SilentlyContinue
        } else {
            $env:ANDROID_SERIAL = $previousSerial
        }
        Pop-Location
    }
}

function Invoke-ArtCompileControl {
    param(
        [string]$Mode,
        [string]$PackageName,
        [string]$OutputPath
    )
    $result = [ordered]@{
        Mode = $Mode
        Command = ""
        ExitCode = ""
        Output = ""
        PackageCompileEvidence = ""
    }
    if ($Mode -eq "InstallDefault") {
        $result.Command = "none; using post-install package default"
        $result.ExitCode = 0
    } else {
        $compilerMode = if ($Mode -eq "Speed") { "speed" } else { "verify" }
        $commandArgs = @("shell", "cmd", "package", "compile", "-m", $compilerMode, "-f", $PackageName)
        $result.Command = "adb $($commandArgs -join ' ')"
        Write-Host "Applying ART compile mode $Mode with: $($result.Command)"
        $compileOutput = @(Invoke-Adb @commandArgs 2>&1)
        $result.ExitCode = $LASTEXITCODE
        $result.Output = Join-BenchmarkEvidenceSnippet -Lines $compileOutput -MaxLines 60 -MaxChars 2400
        if ($result.ExitCode -ne 0) {
            @(
                "mode=$($result.Mode)"
                "command=$($result.Command)"
                "exit_code=$($result.ExitCode)"
                "output=$($result.Output)"
            ) | Set-Content -Path $OutputPath
            throw "ART compile command failed with exit code $($result.ExitCode): $($result.Output)"
        }
    }
    try {
        $packageDump = @(Invoke-Adb shell dumpsys package $PackageName 2>$null)
        $compileLines = @($packageDump | Where-Object { $_ -match "(?i)dexopt|compiler|compile|profile|speed|verify|oat|odex|status=|reason=" })
        $result.PackageCompileEvidence = Join-BenchmarkEvidenceSnippet -Lines $compileLines -MaxLines 80 -MaxChars 3600
    } catch {
        $result.PackageCompileEvidence = "unavailable: $($_.Exception.Message)"
    }
    @(
        "mode=$($result.Mode)"
        "command=$($result.Command)"
        "exit_code=$($result.ExitCode)"
        "output=$($result.Output)"
        "package_compile_evidence=$($result.PackageCompileEvidence)"
    ) | Set-Content -Path $OutputPath
    if ($Mode -eq "Verify" -and $result.PackageCompileEvidence -notmatch "\[status=verify\]") {
        throw "ART compile proof rejected: requested Verify but package evidence did not report status=verify. See $OutputPath."
    }
    if ($Mode -eq "Speed" -and $result.PackageCompileEvidence -notmatch "\[status=speed\]") {
        throw "ART compile proof rejected: requested Speed but package evidence did not report status=speed. See $OutputPath."
    }
    return [pscustomobject]$result
}

function Start-AmInstrumentationJob {
    param(
        [string]$AdbPath,
        [string[]]$InstrumentArgs,
        [string]$InstrumentationComponent,
        [string]$InstrumentationLogPath,
        [string]$DeviceSerial
    )
    Start-Job -ScriptBlock {
        param($JobAdbPath, $JobInstrumentArgs, $JobInstrumentationComponent, $JobInstrumentationLogPath, $JobDeviceSerial)
        $adbArgs = @()
        if (-not [string]::IsNullOrWhiteSpace($JobDeviceSerial)) {
            $adbArgs += "-s"
            $adbArgs += $JobDeviceSerial
        }
        $adbArgs += "shell"
        $adbArgs += "am"
        $adbArgs += "instrument"
        $adbArgs += "-w"
        $adbArgs += "-r"
        $adbArgs += $JobInstrumentArgs
        $adbArgs += $JobInstrumentationComponent
        & $JobAdbPath @adbArgs 2>&1 | Tee-Object -FilePath $JobInstrumentationLogPath
        "FLIGHTALERT_GRADLE_EXIT_CODE=$LASTEXITCODE"
    } -ArgumentList $AdbPath, $InstrumentArgs, $InstrumentationComponent, $InstrumentationLogPath, $DeviceSerial
}

function Start-FlightAlertInstrumentationJob {
    param(
        [string]$Mode,
        [string]$RepoRoot,
        [string]$GradlewPath,
        [string[]]$GradleRunnerArgs,
        [string[]]$InstrumentArgs,
        [string]$InstrumentationComponent,
        [string]$InstrumentationLogPath,
        [string]$DeviceSerial
    )
    if ($Mode -eq "SplitInstall") {
        return Start-AmInstrumentationJob -AdbPath $adb -InstrumentArgs $InstrumentArgs -InstrumentationComponent $InstrumentationComponent -InstrumentationLogPath $InstrumentationLogPath -DeviceSerial $DeviceSerial
    }
    return Start-GradleInstrumentationJob -RepoRoot $RepoRoot -GradlewPath $GradlewPath -RunnerArgs $GradleRunnerArgs -GradleLogPath $InstrumentationLogPath -DeviceSerial $DeviceSerial
}

function Wait-GradleInstrumentationJob {
    param(
        [System.Management.Automation.Job]$Job,
        [int]$TimeoutSeconds
    )
    $exitCode = 1
    $timedOut = $false
    $completed = Wait-Job -Job $Job -Timeout $TimeoutSeconds
    if (-not $completed) {
        $timedOut = $true
        Write-Host "Instrumentation exceeded MaxRunSeconds; stopping screen recording, force-stopping Flight Alert, and discarding this run as evidence."
        Stop-FlightAlertPerfRun
        Stop-GradleInstrumentationProcesses -RepoRootForMatch $repoRoot
        Stop-Job -Job $Job -ErrorAction SilentlyContinue
    }
    $jobOutput = @(Receive-Job -Job $Job -ErrorAction SilentlyContinue)
    Remove-Job -Job $Job -Force -ErrorAction SilentlyContinue
    foreach ($entry in $jobOutput) {
        $line = "$entry"
        if ($line -match "^FLIGHTALERT_GRADLE_EXIT_CODE=(\d+)$") {
            $exitCode = [int]$Matches[1]
        } else {
            Write-Host $line
        }
    }
    if ($timedOut) {
        $exitCode = 124
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        TimedOut = $timedOut
    }
}

if ([string]::IsNullOrWhiteSpace($City)) {
    $City = Get-TimetableDefaultCity
    Write-Host "No -City supplied; selected timetable-backed target '$City' from current UTC hour $([DateTime]::UtcNow.ToString('HH:mm'))."
}

Assert-InlandCityArgument

if ([string]::IsNullOrWhiteSpace($ArtifactName)) {
    $ArtifactName = Get-DefaultArtifactName -Name $TestName
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if ([string]::IsNullOrWhiteSpace($OutputName)) {
    $OutputName = "instrumentation-$ArtifactName-$stamp"
}
$gitEvidence = Get-GitWorktreeEvidence -RepoRoot $repoRoot
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
$videoEvidenceHoldSeconds = 0
$gradleExit = 1
$gradleTimedOut = $false
$splitInstallLog = ""
$splitInstallExit = ""
$artCompilePath = ""
$artCompileCommand = ""
$artCompileExit = ""
$artCompileOutput = ""
$artCompilePackageEvidence = ""
$controlledPreflightPath = ""
$controlledPreflightExpectedDexopt = Get-ControlledExpectedDexoptState -NormalizationMode $ControlledDexoptNormalizationMode
$controlledPreflightPassed = $false
$controlledPreflightEvidence = ""
$controlledDexoptNormalizationPath = ""
$controlledDexoptNormalizationCommand = ""
$controlledDexoptNormalizationExit = ""
$controlledDexoptNormalizationOutput = ""
$controlledDexoptNormalizationPreState = ""
$controlledDexoptNormalizationPreFingerprint = ""
$controlledDexoptNormalizationPackageEvidence = ""
$controlledDexoptNormalizationPackageState = ""
$controlledDexoptNormalizationPackageFingerprint = ""
$instrumentationComponent = "$testPackageName/$instrumentationRunner"

Push-Location $repoRoot
$previousAndroidSerial = $env:ANDROID_SERIAL
try {
    $gradleLog = Join-Path $outDir "$OutputName-gradle.txt"
    $splitInstallLog = Join-Path $outDir "$OutputName-install.txt"
    $artCompilePath = Join-Path $outDir "$OutputName-art-compile.txt"
    $controlledDexoptNormalizationPath = Join-Path $outDir "$OutputName-controlled-dexopt-normalization.txt"
    $instrumentationArgs = [ordered]@{
        class = "$testClass#$TestName"
    }
    if (-not [string]::IsNullOrWhiteSpace($Device)) {
        $env:ANDROID_SERIAL = $Device
    }
    if (-not [string]::IsNullOrWhiteSpace($City)) {
        $instrumentationArgs.targetCity = $City
    }
    if ($MapRoads -ne "Current") {
        $mapRoadsValue = if ($MapRoads -eq "On") { "true" } else { "false" }
        $instrumentationArgs.mapRoads = $mapRoadsValue
    }
    if ($MapBorders -ne "Current") {
        $mapBordersValue = if ($MapBorders -eq "On") { "true" } else { "false" }
        $instrumentationArgs.mapBorders = $mapBordersValue
    }
    if ($SkipChrome) {
        $instrumentationArgs.skipChrome = "true"
    }
    if ($SkipTopStatus) {
        $instrumentationArgs.skipTopStatus = "true"
    }
    if ($SkipControls) {
        $instrumentationArgs.skipControls = "true"
    }
    if ($SkipTrafficPanel) {
        $instrumentationArgs.skipTrafficPanel = "true"
    }
    if ($SkipTraffic) {
        $instrumentationArgs.skipTraffic = "true"
    }
    if ($TrafficDetailTiming) {
        $instrumentationArgs.trafficDetailTiming = "true"
    }
    if ($MapDetailTiming) {
        $instrumentationArgs.mapDetailTiming = "true"
    }
    if ($RecordVideo) {
        $videoEvidenceHoldSeconds = [Math]::Min($VideoTimeLimitSeconds, [Math]::Max(6, $MaxRunSeconds - 45))
        $instrumentationArgs.videoEvidenceHoldSeconds = "$videoEvidenceHoldSeconds"
    }
    $runnerArgs = @()
    if (-not [string]::IsNullOrWhiteSpace($Device)) {
        $runnerArgs += "-Pandroid.injected.device.serial=$Device"
    }
    $amRunnerArgs = @()
    foreach ($entry in $instrumentationArgs.GetEnumerator()) {
        $runnerArgs += "-Pandroid.testInstrumentationRunnerArguments.$($entry.Key)=$($entry.Value)"
        $amRunnerArgs += "-e"
        $amRunnerArgs += "$($entry.Key)"
        $amRunnerArgs += "$($entry.Value)"
    }
    if ($HarnessExecutionMode -eq "SplitInstall") {
        $splitInstallExit = Invoke-GradleInstallForSplitInstrumentation -RepoRoot $repoRoot -GradlewPath $gradlew -GradleInstallLogPath $splitInstallLog -DeviceSerial $Device
        if ($splitInstallExit -ne 0) {
            throw "Split instrumentation install failed with exit code $splitInstallExit. See $splitInstallLog."
        }
        $compileResult = Invoke-ArtCompileControl -Mode $ArtCompileMode -PackageName $packageName -OutputPath $artCompilePath
        $artCompileCommand = $compileResult.Command
        $artCompileExit = $compileResult.ExitCode
        $artCompileOutput = $compileResult.Output
        $artCompilePackageEvidence = $compileResult.PackageCompileEvidence
        if ($RequireControlledPreflight) {
            $controlledDexoptNormalizationPreState = Get-PackageDexoptState -CompileEvidence $artCompilePackageEvidence
            $controlledDexoptNormalizationPreFingerprint = Get-PackageDexoptFingerprint -CompileEvidence $artCompilePackageEvidence
            Invoke-Adb shell "am force-stop $packageName >/dev/null 2>&1 || true" | Out-Null
            Invoke-Adb shell "am force-stop $testPackageName >/dev/null 2>&1 || true" | Out-Null
            $normalizationResult = Invoke-ControlledDexoptNormalization -Mode $ControlledDexoptNormalizationMode -PackageName $packageName -OutputPath $controlledDexoptNormalizationPath
            $controlledDexoptNormalizationCommand = $normalizationResult.Command
            $controlledDexoptNormalizationExit = $normalizationResult.ExitCode
            $controlledDexoptNormalizationOutput = $normalizationResult.Output
            $controlledDexoptNormalizationPackageEvidence = $normalizationResult.PackageCompileEvidence
            $controlledDexoptNormalizationPackageState = $normalizationResult.PackageDexoptState
            $controlledDexoptNormalizationPackageFingerprint = $normalizationResult.PackageDexoptFingerprint
            $controlledPreflightPath = Join-Path $outDir "$OutputName-controlled-preflight.txt"
            $preflightResult = Invoke-ControlledBenchmarkPreflight -RepoRoot $repoRoot -PackageName $packageName -OutputPath $controlledPreflightPath -GitEvidence $gitEvidence -ArtCompileMode $ArtCompileMode -ExpectedDexoptState $controlledPreflightExpectedDexopt -NormalizationMode $ControlledDexoptNormalizationMode -TimeoutSeconds $ControlledPreflightTimeoutSeconds
            $controlledPreflightPassed = $preflightResult.Passed
            $controlledPreflightEvidence = $preflightResult.Evidence
        }
        Invoke-Adb shell "am force-stop $packageName >/dev/null 2>&1 || true" | Out-Null
        Invoke-Adb shell "am force-stop $testPackageName >/dev/null 2>&1 || true" | Out-Null
    }
    $runDeadline = (Get-Date).AddSeconds($MaxRunSeconds)
    if ($RecordVideo) {
        $gradleJob = Start-FlightAlertInstrumentationJob -Mode $HarnessExecutionMode -RepoRoot $repoRoot -GradlewPath $gradlew -GradleRunnerArgs $runnerArgs -InstrumentArgs $amRunnerArgs -InstrumentationComponent $instrumentationComponent -InstrumentationLogPath $gradleLog -DeviceSerial $Device
        $foregroundTimeout = [Math]::Min(30, (Get-RemainingRunSeconds -Deadline $runDeadline))
        if (Wait-FlightAlertForeground -TimeoutSeconds $foregroundTimeout) {
            Start-Sleep -Milliseconds 1200
            if (-not (Wait-FlightAlertForeground -TimeoutSeconds ([Math]::Min(2, (Get-RemainingRunSeconds -Deadline $runDeadline))))) {
                $videoSkippedReason = "Flight Alert left foreground during the pre-video settle delay; video skipped to avoid launcher/home-screen evidence."
                Write-Host $videoSkippedReason
            } else {
            if ([string]::IsNullOrWhiteSpace($VideoDisplayId)) {
                $VideoDisplayId = Get-ActivePhysicalDisplayId
            }
            if ([string]::IsNullOrWhiteSpace($VideoDisplayId)) {
                Write-Host "Recording screen video to $remoteVideoPath for up to $VideoTimeLimitSeconds seconds after Flight Alert reached foreground. Max run seconds=$MaxRunSeconds."
            } else {
                Write-Host "Recording screen video to $remoteVideoPath for up to $VideoTimeLimitSeconds seconds on display $VideoDisplayId after Flight Alert reached foreground. Max run seconds=$MaxRunSeconds."
            }
            $videoJob = Start-ScreenRecord -RemotePath $remoteVideoPath -TimeLimitSeconds $VideoTimeLimitSeconds -DisplayId $VideoDisplayId
            $videoStartedAfterForeground = $true
            Start-Sleep -Milliseconds 300
            }
        } else {
            $videoSkippedReason = "Flight Alert did not reach foreground within $foregroundTimeout seconds; video skipped to avoid launcher/home-screen evidence."
            Write-Host $videoSkippedReason
        }
        $jobResult = Wait-GradleInstrumentationJob -Job $gradleJob -TimeoutSeconds (Get-RemainingRunSeconds -Deadline $runDeadline)
        $gradleExit = $jobResult.ExitCode
        $gradleTimedOut = $jobResult.TimedOut
    } else {
        $gradleJob = Start-FlightAlertInstrumentationJob -Mode $HarnessExecutionMode -RepoRoot $repoRoot -GradlewPath $gradlew -GradleRunnerArgs $runnerArgs -InstrumentArgs $amRunnerArgs -InstrumentationComponent $instrumentationComponent -InstrumentationLogPath $gradleLog -DeviceSerial $Device
        $jobResult = Wait-GradleInstrumentationJob -Job $gradleJob -TimeoutSeconds (Get-RemainingRunSeconds -Deadline $runDeadline)
        $gradleExit = $jobResult.ExitCode
        $gradleTimedOut = $jobResult.TimedOut
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
$displayArtifacts = @($pulledPaths | Where-Object { $_ -like "*-display.txt" })
$packageArtifacts = @($pulledPaths | Where-Object { $_ -like "*-package.txt" })
$routeFocusEvidence = Test-RouteFocusEvidence -LogcatPath $logcatPath -TargetArtifacts $targetArtifacts
$deviceEvidence = Get-BenchmarkDeviceEvidence -RepoRoot $repoRoot -PackageName $packageName
$contactSheetPath = ""
$roadMotionStripPath = ""
$roadMotionStripRequired = $RecordVideo -and $MapRoads -eq "On" -and $TestName.ToLowerInvariant().Contains("satellite")
if ($screenshots.Count -gt 0) {
    try {
        $contactSheetPath = New-PngContactSheet -PngPaths $screenshots -DestinationPath (Join-Path $outDir "$OutputName-contact-sheet.png")
    } catch {
        Write-Host "Contact sheet generation skipped: $($_.Exception.Message)"
    }
}
if ($roadMotionStripRequired -and $videos.Count -gt 0) {
    try {
        $roadMotionStripPath = New-RoadMotionVideoStrip -VideoPath $videos[0] -DestinationPath (Join-Path $outDir "$OutputName-road-motion-strip.jpg")
    } catch {
        Write-Host "Road motion strip generation skipped: $($_.Exception.Message)"
    }
}
$routeProofPath = Join-Path $outDir "$OutputName-route-proof.txt"
$routeProof = @()
$routeProof += "test_class=$testClass"
$routeProof += "test_name=$TestName"
$routeProof += "artifact_prefix=$artifactPrefix"
$routeProof += "device=$Device"
$routeProof += "benchmark_role=$BenchmarkRole"
$routeProof += "harness_execution_mode=$HarnessExecutionMode"
$routeProof += "instrumentation_component=$instrumentationComponent"
$routeProof += "split_install_log=$splitInstallLog"
$routeProof += "split_install_exit_code=$splitInstallExit"
$routeProof += "art_compile_mode=$ArtCompileMode"
$routeProof += "art_compile_command=$artCompileCommand"
$routeProof += "art_compile_exit_code=$artCompileExit"
$routeProof += "art_compile_output=$artCompileOutput"
$routeProof += "art_compile_package_evidence=$artCompilePackageEvidence"
$routeProof += "controlled_preflight_required=$($RequireControlledPreflight.IsPresent)"
$routeProof += "controlled_preflight_timeout_seconds=$ControlledPreflightTimeoutSeconds"
$routeProof += "controlled_preflight_expected_dexopt=$controlledPreflightExpectedDexopt"
$routeProof += "controlled_dexopt_normalization_mode=$ControlledDexoptNormalizationMode"
$routeProof += "controlled_preflight_passed=$controlledPreflightPassed"
$routeProof += "controlled_preflight_file=$controlledPreflightPath"
$routeProof += "controlled_preflight_evidence=$controlledPreflightEvidence"
$routeProof += "controlled_dexopt_normalization_file=$controlledDexoptNormalizationPath"
$routeProof += "controlled_dexopt_normalization_command=$controlledDexoptNormalizationCommand"
$routeProof += "controlled_dexopt_normalization_exit_code=$controlledDexoptNormalizationExit"
$routeProof += "controlled_dexopt_normalization_output=$controlledDexoptNormalizationOutput"
$routeProof += "controlled_dexopt_normalization_pre_state=$controlledDexoptNormalizationPreState"
$routeProof += "controlled_dexopt_normalization_pre_fingerprint=$controlledDexoptNormalizationPreFingerprint"
$routeProof += "controlled_dexopt_normalization_package_state=$controlledDexoptNormalizationPackageState"
$routeProof += "controlled_dexopt_normalization_package_fingerprint=$controlledDexoptNormalizationPackageFingerprint"
$routeProof += "controlled_dexopt_normalization_package_evidence=$controlledDexoptNormalizationPackageEvidence"
$routeProof += "git_metadata_available=$($gitEvidence.Available)"
$routeProof += "git_branch=$($gitEvidence.Branch)"
$routeProof += "git_commit=$($gitEvidence.Commit)"
$routeProof += "git_worktree_dirty=$($gitEvidence.WorktreeDirty)"
$routeProof += "git_status_count=$($gitEvidence.StatusCount)"
$routeProof += "git_status_short=$($gitEvidence.StatusShort)"
$routeProof += "git_error=$($gitEvidence.Error)"
$routeProof += "debug_apk_exists=$($deviceEvidence.DebugApkExists)"
$routeProof += "debug_apk_path=$($deviceEvidence.DebugApkPath)"
$routeProof += "debug_apk_bytes=$($deviceEvidence.DebugApkBytes)"
$routeProof += "debug_apk_last_write_utc=$($deviceEvidence.DebugApkLastWriteUtc)"
$routeProof += "debug_apk_sha256=$($deviceEvidence.DebugApkSha256)"
$routeProof += "debug_apk_error=$($deviceEvidence.DebugApkError)"
$routeProof += "test_apk_exists=$($deviceEvidence.TestApkExists)"
$routeProof += "test_apk_path=$($deviceEvidence.TestApkPath)"
$routeProof += "test_apk_bytes=$($deviceEvidence.TestApkBytes)"
$routeProof += "test_apk_last_write_utc=$($deviceEvidence.TestApkLastWriteUtc)"
$routeProof += "test_apk_sha256=$($deviceEvidence.TestApkSha256)"
$routeProof += "test_apk_error=$($deviceEvidence.TestApkError)"
$routeProof += "device_package_paths=$($deviceEvidence.PackagePaths)"
$routeProof += "post_run_package_compile_evidence=$($deviceEvidence.PackageCompileEvidence)"
$routeProof += "post_run_package_dexopt_state=$(Get-PackageDexoptState -CompileEvidence $deviceEvidence.PackageCompileEvidence)"
$routeProof += "post_run_package_dexopt_fingerprint=$(Get-PackageDexoptFingerprint -CompileEvidence $deviceEvidence.PackageCompileEvidence)"
$routeProof += "battery_level=$($deviceEvidence.BatteryLevel)"
$routeProof += "battery_temp_c=$($deviceEvidence.BatteryTempC)"
$routeProof += "battery_status=$($deviceEvidence.BatteryStatus)"
$routeProof += "battery_plugged=$($deviceEvidence.BatteryPlugged)"
$routeProof += "thermal_status=$($deviceEvidence.ThermalStatus)"
$routeProof += "thermal_evidence=$($deviceEvidence.ThermalEvidence)"
$routeProof += "post_run_display_refresh_evidence=$($deviceEvidence.DisplayRefreshEvidence)"
$routeProof += "device_build_fingerprint=$($deviceEvidence.DeviceBuildFingerprint)"
$routeProof += "device_build_version=$($deviceEvidence.DeviceBuildVersion)"
$routeProof += "device_build_sdk=$($deviceEvidence.DeviceBuildSdk)"
$routeProof += "device_art_evidence=$($deviceEvidence.DeviceArtEvidence)"
$routeProof += "device_evidence_error=$($deviceEvidence.Error)"
$routeProof += "city_argument=$City"
$routeProof += "map_roads_argument=$MapRoads"
$routeProof += "map_borders_argument=$MapBorders"
$routeProof += "skip_chrome=$SkipChrome"
$routeProof += "skip_top_status=$SkipTopStatus"
$routeProof += "skip_controls=$SkipControls"
$routeProof += "skip_traffic_panel=$SkipTrafficPanel"
$routeProof += "skip_traffic=$SkipTraffic"
$routeProof += "record_video=$RecordVideo"
$routeProof += "max_run_seconds=$MaxRunSeconds"
$routeProof += "video_time_limit_seconds=$VideoTimeLimitSeconds"
$routeProof += "video_evidence_hold_seconds=$videoEvidenceHoldSeconds"
$routeProof += "instrumentation_timed_out=$gradleTimedOut"
$routeProof += "video_started_after_flightalert_foreground=$videoStartedAfterForeground"
$routeProof += "video_skipped_reason=$videoSkippedReason"
$routeProof += "visible_evidence_land_safe=$VisibleEvidenceLandSafe"
$routeProof += "visible_evidence_reviewer=$VisibleEvidenceReviewer"
$routeProof += "visible_evidence_rule=Pass only after screenshots/video and focusLat/focusLon logs show active gesture focus over the timetable-backed US/EU land/traffic target. Incidental coastline/water in a continent-scale viewport is acceptable only when the focus stays on the requested target."
$routeProof += "screenshots_count=$($screenshots.Count)"
$routeProof += "videos_count=$($videos.Count)"
$routeProof += "in_run_display_artifacts=$($displayArtifacts.Count)"
if ($displayArtifacts.Count -gt 0) {
    $inRunDisplayLines = @()
    foreach ($displayArtifact in $displayArtifacts) {
        $inRunDisplayLines += @(Select-String -Path $displayArtifact -Pattern "DisplayDeviceInfo|mActiveModeId|mActiveSfDisplayMode|mActiveRenderFrameRate|mDisplayModeSpecs|preferred|refresh|fps=" -CaseSensitive:$false -ErrorAction SilentlyContinue | Select-Object -First 80 | ForEach-Object { $_.Line })
    }
    $routeProof += "in_run_display_refresh_evidence=$(Join-BenchmarkEvidenceSnippet -Lines $inRunDisplayLines -MaxLines 80 -MaxChars 3200)"
}
$routeProof += "in_run_package_artifacts=$($packageArtifacts.Count)"
if ($packageArtifacts.Count -gt 0) {
    $inRunPackageLines = @()
    foreach ($packageArtifact in $packageArtifacts) {
        $inRunPackageLines += @(Select-String -Path $packageArtifact -Pattern "dexopt|compiler|compile|profile|speed|verify|oat|odex|codePath|resourcePath|primaryCpuAbi|status=|reason=" -CaseSensitive:$false -ErrorAction SilentlyContinue | Select-Object -First 100 | ForEach-Object { $_.Line })
    }
    $routeProof += "in_run_package_compile_evidence=$(Join-BenchmarkEvidenceSnippet -Lines $inRunPackageLines -MaxLines 100 -MaxChars 3600)"
}
$routeProof += "contact_sheet=$contactSheetPath"
$routeProof += "road_motion_strip_required=$roadMotionStripRequired"
$routeProof += "road_motion_strip=$roadMotionStripPath"
$routeProof += "road_motion_temporal_rule=For satellite roads-on video evidence, inspect the MP4 and road-motion strip for frame-to-frame road opacity flicker, road LOD scale swaps, whole-road-layer blink, parent/exact overlay crossfades, and label/border/road mismatches. Still contact sheets alone are insufficient."
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
$roadMotionEvidenceReady = (-not $roadMotionStripRequired) -or -not [string]::IsNullOrWhiteSpace($roadMotionStripPath)
$acceptedEvidence = (
    $VisibleEvidenceLandSafe -eq "Pass" -and
    $routeFocusEvidence.Passed -and
    (-not $RecordVideo -or $videoStartedAfterForeground) -and
    $roadMotionEvidenceReady -and
    -not $gradleTimedOut
)
if (-not $acceptedEvidence) {
    $routeProof += ""
    $routeProof += "accepted_optimizer_evidence=false"
    $routeProof += "accepted_optimizer_evidence_reason=visible evidence is $VisibleEvidenceLandSafe; route_focus_passed=$($routeFocusEvidence.Passed); video_started_after_flightalert_foreground=$videoStartedAfterForeground; road_motion_evidence_ready=$roadMotionEvidenceReady; instrumentation_timed_out=$gradleTimedOut."
} else {
    $routeProof += ""
    $routeProof += "accepted_optimizer_evidence=true"
}
$routeProof | Set-Content -Path $routeProofPath
Write-Host "Saved route-proof manifest: $routeProofPath"

if ($gradleTimedOut) {
    throw "Instrumentation exceeded MaxRunSeconds=$MaxRunSeconds. Stopped screen recording/app and pulled available artifacts into $outDir; discard this run as evidence."
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

Write-Host "Saved artifacts: $outDir"
Write-Host "Saved summary CSV: $summaryCsv"
Write-Host ("Route focus evidence: passed={0}; city={1}; samples={2}; maxDistanceKm={3:N1}; rejectReason={4}" -f `
    $routeFocusEvidence.Passed, $routeFocusEvidence.ExpectedCity, $routeFocusEvidence.FocusSamples, [double]$routeFocusEvidence.MaxDistanceKm, $routeFocusEvidence.RejectReason)
if (-not [string]::IsNullOrWhiteSpace($contactSheetPath)) {
    Write-Host "Saved contact sheet: $contactSheetPath"
}
if (-not [string]::IsNullOrWhiteSpace($roadMotionStripPath)) {
    Write-Host "Saved road motion strip: $roadMotionStripPath"
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
