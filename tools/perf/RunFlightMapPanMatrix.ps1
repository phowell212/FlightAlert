param(
    [string]$Device = "RFCX40KPN3B",
    [ValidateSet("Short", "Full")]
    [string]$Matrix = "Short",
    [string]$IterationLabel = "",
    [switch]$NoScreenshots,
    [switch]$IncludeCadenceSoak
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false

$runner = Join-Path $PSScriptRoot "RunFlightMapPerf.ps1"
if (-not (Test-Path $runner)) {
    throw "RunFlightMapPerf.ps1 was not found next to this matrix script."
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
}

$shortCases = @(
    New-Case "wide-dfw-street-off-fast" "Dallas-Fort Worth" 3.6 "Street" "Off" "Horizontal" "Steady" "Long" 1300
    New-Case "wide-dfw-satellite-off-fast" "Dallas-Fort Worth" 3.6 "Satellite" "Off" "Horizontal" "Steady" "Long" 1300
    New-Case "country-toronto-street-off-pulse" "Toronto" 5.4 "Street" "Off" "Horizontal" "Pulse" "Medium" 1700
    New-Case "country-toronto-satellite-off-pulse" "Toronto" 5.4 "Satellite" "Off" "Horizontal" "Pulse" "Medium" 1700
    New-Case "transition-chicago-street-off-diagonal" "Chicago" 8.4 "Street" "Off" "DiagonalDown" "EaseIn" "Medium" 1500
    New-Case "transition-chicago-satellite-off-diagonal" "Chicago" 8.4 "Satellite" "Off" "DiagonalUp" "EaseOut" "Medium" 1500
    New-Case "close-nyc-street-off-vertical" "New York City" 12.0 "Street" "Off" "Vertical" "Steady" "Medium" 1000
    New-Case "close-nyc-satellite-off-vertical" "New York City" 12.0 "Satellite" "Off" "Vertical" "Steady" "Medium" 1000
    New-Case "country-toronto-street-on-diagonal" "Toronto" 5.4 "Street" "On" "DiagonalDown" "Steady" "Long" 1300
    New-Case "country-toronto-satellite-on-diagonal" "Toronto" 5.4 "Satellite" "On" "DiagonalUp" "Steady" "Long" 1300
)

$fullCases = @(
    New-Case "wide-dfw-street-off-fast" "Dallas-Fort Worth" 3.6 "Street" "Off" "Horizontal" "Steady" "Long" 1200
    New-Case "wide-dfw-satellite-on-diagonal" "Dallas-Fort Worth" 3.6 "Satellite" "On" "DiagonalDown" "Steady" "Long" 1200
    New-Case "country-toronto-street-off-pulse" "Toronto" 5.4 "Street" "Off" "Horizontal" "Pulse" "Medium" 1600
    New-Case "country-toronto-satellite-off-pulse" "Toronto" 5.4 "Satellite" "Off" "Horizontal" "Pulse" "Medium" 1600
    New-Case "country-toronto-street-on-diagup" "Toronto" 5.4 "Street" "On" "DiagonalUp" "Steady" "Long" 1100
    New-Case "country-toronto-satellite-on-diagdown" "Toronto" 5.4 "Satellite" "On" "DiagonalDown" "Steady" "Long" 1100
    New-Case "country-toronto-street-off-fastshort" "Toronto" 5.4 "Street" "Off" "Horizontal" "Steady" "Short" 650
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
foreach ($case in $cases) {
    Invoke-PanCase -Case $case -Index $index -Total $cases.Count
    $index++
}

if ($IncludeCadenceSoak) {
    $outputName = "{0}-{1:00}-cadence-soak-country-toronto-street-off" -f $IterationLabel, $index
    Write-Host ("[{0}/{1}] Cadence soak: Toronto z5.4 Street/Off 28s" -f $index, ($cases.Count + 1))
    $args = @(
        "-Device", $Device,
        "-Mode", "soak",
        "-SoakSeconds", "28",
        "-CityName", "Toronto",
        "-Zoom", "5.4",
        "-MapSource", "Street",
        "-MapLabels", "On",
        "-RestrictedAirspaces", "Off",
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
}
