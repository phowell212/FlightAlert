param(
    [string]$Device = "RFCX40KPN3B",
    [ValidateSet("Short", "Full")]
    [string]$Matrix = "Short",
    [string]$IterationLabel = "",
    [switch]$NoScreenshots,
    [switch]$IncludeCadenceSoak,
    [double]$TargetHz = 120.0
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false

$runner = Join-Path $PSScriptRoot "RunFlightMapPerf.ps1"
$summarizer = Join-Path $PSScriptRoot "SummarizeFrameStats.ps1"
$outRoot = Join-Path $PSScriptRoot "out"
if (-not (Test-Path $runner)) {
    throw "RunFlightMapPerf.ps1 was not found next to this matrix script."
}
if (-not (Test-Path $summarizer)) {
    throw "SummarizeFrameStats.ps1 was not found next to this matrix script."
}

if ([string]::IsNullOrWhiteSpace($IterationLabel)) {
    $IterationLabel = "matrix-" + (Get-Date -Format "yyyyMMdd-HHmmss")
}

function New-Case {
    param(
        [string]$Name,
        [string]$City,
        [double]$Zoom,
        [ValidateSet("Street", "Satellite")] [string]$Map,
        [ValidateSet("Off", "On")] [string]$Restricted,
        [ValidateSet("Horizontal", "Vertical", "DiagonalDown", "DiagonalUp")] [string]$Direction,
        [ValidateSet("Steady", "EaseIn", "EaseOut", "Pulse")] [string]$Profile,
        [ValidateSet("Short", "Medium", "Long")] [string]$Length,
        [int]$DurationMs
    )
    [ordered]@{
        Name = $Name
        City = $City
        Zoom = $Zoom
        Map = $Map
        Restricted = $Restricted
        Direction = $Direction
        Profile = $Profile
        Length = $Length
        DurationMs = $DurationMs
    }
}

function Invoke-PanCase {
    param(
        [hashtable]$Case,
        [int]$Index,
        [int]$Total
    )
    $outputName = "{0}-{1:00}-{2}" -f $IterationLabel, $Index, $Case.Name
    Write-Host ("[{0}/{1}] {2}: {3} z{4} {5}/{6} {7} {8} {9}ms" -f `
        $Index, $Total, $Case.City, $Case.Map, $Case.Zoom, $Case.Restricted, `
        $Case.Direction, $Case.Profile, $Case.Length, $Case.DurationMs)
    $args = @(
        "-Device", $Device,
        "-Mode", "longpan",
        "-CityName", $Case.City,
        "-Zoom", $Case.Zoom,
        "-MapSource", $Case.Map,
        "-MapLabels", "On",
        "-RestrictedAirspaces", $Case.Restricted,
        "-PanDirection", $Case.Direction,
        "-PanProfile", $Case.Profile,
        "-PanLength", $Case.Length,
        "-PanDurationMs", $Case.DurationMs,
        "-TargetHz", $TargetHz,
        "-ClearSelection",
        "-OutputName", $outputName
    )
    if ($NoScreenshots) {
        $args += "-NoScreenshots"
    }
    & $runner @args
    if ($LASTEXITCODE -ne 0) {
        throw "Pan matrix case failed: $($Case.Name)"
    }
    $statsPath = Join-Path $outRoot "$outputName-framestats.txt"
    if (-not (Test-Path $statsPath)) {
        throw "Expected framestats file was not created for $($Case.Name): $statsPath"
    }
    return [pscustomobject]@{
        OutputName = $outputName
        Gesture = "Pan"
        Case = $Case
        StatsPath = $statsPath
    }
}

function Get-ScaleBand {
    param([hashtable]$Case)
    if ($Case.Name -match "^wide") { return "wide" }
    if ($Case.Name -match "^country") { return "country" }
    if ($Case.Name -match "^transition") { return "transition" }
    if ($Case.Name -match "^close") { return "close" }
    if ([double]$Case.Zoom -lt 4.5) { return "wide" }
    if ([double]$Case.Zoom -lt 7.0) { return "country" }
    if ([double]$Case.Zoom -lt 10.0) { return "transition" }
    return "close"
}

function Export-MatrixSummary {
    param([array]$Results)
    if ($Results.Count -eq 0) { return }
    $statsPaths = @($Results | ForEach-Object { $_.StatsPath })
    $summaryLines = & $summarizer -Path $statsPaths -TargetHz $TargetHz -Csv
    $summaries = $summaryLines | ConvertFrom-Csv
    $summaryByFile = @{}
    foreach ($summary in $summaries) {
        $summaryByFile[$summary.File] = $summary
    }
    $rows = foreach ($result in $Results) {
        $case = $result.Case
        $statsFile = Split-Path -Path $result.StatsPath -Leaf
        $summary = $summaryByFile[$statsFile]
        if (-not $summary) { throw "No summary row was produced for $statsFile" }
        [pscustomobject]@{
            Matrix = $Matrix
            IterationLabel = $IterationLabel
            Scenario = $case.Name
            ScaleBand = Get-ScaleBand -Case $case
            Gesture = $result.Gesture
            City = $case.City
            Zoom = $case.Zoom
            Map = $case.Map
            Restricted = $case.Restricted
            Direction = $case.Direction
            Profile = $case.Profile
            Length = $case.Length
            DurationMs = $case.DurationMs
            OutputName = $result.OutputName
            File = $summary.File
            Frames = $summary.Frames
            RawTimelineFrames = $summary.RawTimelineFrames
            PresentIntervals = $summary.PresentIntervals
            TargetHz = $summary.TargetHz
            TargetBudgetMs = $summary.TargetBudgetMs
            OverTarget = $summary.OverTarget
            LatencyMiss120Pct = $summary.LatencyMiss120Pct
            Over90Pct = $summary.Over90Pct
            Over60Pct = $summary.Over60Pct
            PresentDrop120Pct = $summary.PresentDrop120Pct
            PresentOver60Pct = $summary.PresentOver60Pct
            P50Ms = $summary.P50Ms
            P90Ms = $summary.P90Ms
            P95Ms = $summary.P95Ms
            P99Ms = $summary.P99Ms
            PresentP50Ms = $summary.PresentP50Ms
            PresentP95Ms = $summary.PresentP95Ms
            PresentP99Ms = $summary.PresentP99Ms
            MaxMs = $summary.MaxMs
            P50EquivalentFps = $summary.P50EquivalentFps
            P95EquivalentFps = $summary.P95EquivalentFps
            AndroidJank = $summary.AndroidJank
            AndroidLegacyJank = $summary.AndroidLegacyJank
        }
    }
    New-Item -ItemType Directory -Force -Path $outRoot | Out-Null
    $summaryPath = Join-Path $outRoot "$IterationLabel-matrix-summary-120hz.csv"
    $rows | Export-Csv -Path $summaryPath -NoTypeInformation
    Write-Host "Matrix summary CSV: $summaryPath"
    Write-Host "Worst matrix scenarios by 120Hz present drops:"
    $rows |
        Sort-Object { [double]$_.PresentDrop120Pct } -Descending |
        Format-Table Scenario,ScaleBand,City,Zoom,Map,Restricted,Direction,Profile,Length,PresentDrop120Pct,PresentP50Ms,PresentP95Ms,LatencyMiss120Pct,P95Ms,AndroidJank -AutoSize
}

$shortCases = @(
    New-Case "wide-dfw-street-off-fast" "Dallas-Fort Worth" 3.6 "Street" "Off" "Horizontal" "Steady" "Long" 1300
    New-Case "wide-dfw-satellite-off-fast" "Dallas-Fort Worth" 3.6 "Satellite" "Off" "Horizontal" "Steady" "Long" 1300
    New-Case "country-london-street-off-pulse" "London" 5.4 "Street" "Off" "Horizontal" "Pulse" "Medium" 1700
    New-Case "country-london-satellite-off-pulse" "London" 5.4 "Satellite" "Off" "Horizontal" "Pulse" "Medium" 1700
    New-Case "transition-chicago-street-off-diagonal" "Chicago" 8.4 "Street" "Off" "DiagonalDown" "EaseIn" "Medium" 1500
    New-Case "transition-chicago-satellite-off-diagonal" "Chicago" 8.4 "Satellite" "Off" "DiagonalUp" "EaseOut" "Medium" 1500
    New-Case "close-nyc-street-off-vertical" "New York City" 12.0 "Street" "Off" "Vertical" "Steady" "Medium" 1000
    New-Case "close-nyc-satellite-off-vertical" "New York City" 12.0 "Satellite" "Off" "Vertical" "Steady" "Medium" 1000
    New-Case "country-chicago-street-on-diagonal" "Chicago" 5.4 "Street" "On" "DiagonalDown" "Steady" "Long" 1300
    New-Case "country-chicago-satellite-on-diagonal" "Chicago" 5.4 "Satellite" "On" "DiagonalUp" "Steady" "Long" 1300
)

$fullCases = @(
    New-Case "wide-dfw-street-off-fast" "Dallas-Fort Worth" 3.6 "Street" "Off" "Horizontal" "Steady" "Long" 1200
    New-Case "wide-dfw-satellite-on-diagonal" "Dallas-Fort Worth" 3.6 "Satellite" "On" "DiagonalDown" "Steady" "Long" 1200
    New-Case "country-london-street-off-pulse" "London" 5.4 "Street" "Off" "Horizontal" "Pulse" "Medium" 1600
    New-Case "country-london-satellite-off-pulse" "London" 5.4 "Satellite" "Off" "Horizontal" "Pulse" "Medium" 1600
    New-Case "country-chicago-street-on-diagup" "Chicago" 5.4 "Street" "On" "DiagonalUp" "Steady" "Long" 1100
    New-Case "country-chicago-satellite-on-diagdown" "Chicago" 5.4 "Satellite" "On" "DiagonalDown" "Steady" "Long" 1100
    New-Case "country-newyork-street-off-fastshort" "New York City" 5.4 "Street" "Off" "Horizontal" "Steady" "Short" 650
    New-Case "transition-chicago-street-off-ease" "Chicago" 8.4 "Street" "Off" "DiagonalDown" "EaseIn" "Medium" 1400
    New-Case "transition-chicago-satellite-on-pulse" "Chicago" 8.4 "Satellite" "On" "DiagonalUp" "Pulse" "Medium" 1400
    New-Case "transition-chicago-street-on-vertical" "Chicago" 8.4 "Street" "On" "Vertical" "Steady" "Medium" 1000
    New-Case "close-nyc-street-off-vertical" "New York City" 12.0 "Street" "Off" "Vertical" "Steady" "Medium" 900
    New-Case "close-nyc-satellite-on-vertical" "New York City" 12.0 "Satellite" "On" "Vertical" "Steady" "Medium" 900
)

$cases = if ($Matrix -eq "Full") { $fullCases } else { $shortCases }

Write-Host ("Running {0} pan matrix with {1} cases. Label={2}. Screenshots={3}" -f `
    $Matrix, $cases.Count, $IterationLabel, (-not $NoScreenshots))

$index = 1
$results = @()
foreach ($case in $cases) {
    $results += Invoke-PanCase -Case $case -Index $index -Total $cases.Count
    $index++
}

if ($IncludeCadenceSoak) {
    $outputName = "{0}-{1:00}-cadence-soak-country-london-street-off" -f $IterationLabel, $index
    Write-Host ("[{0}/{1}] Cadence soak: London z5.4 Street/Off 28s" -f $index, ($cases.Count + 1))
    $args = @(
        "-Device", $Device,
        "-Mode", "soak",
        "-SoakSeconds", "28",
        "-CityName", "London",
        "-Zoom", "5.4",
        "-MapSource", "Street",
        "-MapLabels", "On",
        "-RestrictedAirspaces", "Off",
        "-TargetHz", $TargetHz,
        "-ClearSelection",
        "-OutputName", $outputName
    )
    if ($NoScreenshots) {
        $args += "-NoScreenshots"
    }
    & $runner @args
    if ($LASTEXITCODE -ne 0) {
        throw "Cadence soak failed."
    }
    $soakStatsPath = Join-Path $outRoot "$outputName-framestats.txt"
    if (-not (Test-Path $soakStatsPath)) {
        throw "Expected framestats file was not created for cadence soak: $soakStatsPath"
    }
    $results += [pscustomobject]@{
        OutputName = $outputName
        Gesture = "Soak"
        Case = [ordered]@{
            Name = "cadence-soak-country-london-street-off"
            City = "London"
            Zoom = 5.4
            Map = "Street"
            Restricted = "Off"
            Direction = "None"
            Profile = "Soak"
            Length = "28s"
            DurationMs = 28000
        }
        StatsPath = $soakStatsPath
    }
}

Export-MatrixSummary -Results $results
