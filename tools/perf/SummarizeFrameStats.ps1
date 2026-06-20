param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string[]]$Path,
    [double]$TargetHz = 120.0,
    [switch]$Csv
)

$ErrorActionPreference = "Stop"

function Get-PercentileValue {
    param(
        [double[]]$Values,
        [double]$Percentile
    )
    if ($Values.Count -eq 0) { return [double]::NaN }
    $sorted = $Values | Sort-Object
    $index = [int][Math]::Ceiling(($Percentile / 100.0) * $sorted.Count) - 1
    $index = [Math]::Max(0, [Math]::Min($sorted.Count - 1, $index))
    return [double]$sorted[$index]
}

function Read-AndroidSummaryValue {
    param(
        [string[]]$Lines,
        [string]$Pattern
    )
    $match = $Lines | Select-String -Pattern $Pattern | Select-Object -First 1
    if (-not $match) { return "" }
    return $match.Line.Trim()
}

function Read-FrameTimelineDurations {
    param([string]$StatsPath)

    $lines = Get-Content -Path $StatsPath
    $headerIndex = -1
    for ($i = $lines.Count - 1; $i -ge 0; $i--) {
        if ($lines[$i] -like "Flags,FrameTimelineVsyncId,*") {
            $headerIndex = $i
            break
        }
    }
    if ($headerIndex -lt 0) {
        return [pscustomobject]@{
            Lines = $lines
            DurationsMs = [double[]]@()
            IntendedSeconds = [double[]]@()
            PresentSeconds = [double[]]@()
        }
    }

    $headers = $lines[$headerIndex].Split(",", [StringSplitOptions]::RemoveEmptyEntries)
    $intendedIndex = [Array]::IndexOf($headers, "IntendedVsync")
    $completedIndex = [Array]::IndexOf($headers, "FrameCompleted")
    $presentIndex = [Array]::IndexOf($headers, "DisplayPresentTime")
    if ($intendedIndex -lt 0 -or $completedIndex -lt 0) {
        throw "FrameTimeline data in $StatsPath is missing IntendedVsync or FrameCompleted."
    }

    $durations = New-Object System.Collections.Generic.List[double]
    $intendedTimes = New-Object System.Collections.Generic.List[double]
    $presentTimes = New-Object System.Collections.Generic.List[double]
    for ($i = $headerIndex + 1; $i -lt $lines.Count; $i++) {
        $line = $lines[$i].Trim()
        if ([string]::IsNullOrWhiteSpace($line) -or $line -notmatch "^[\-\d]") { continue }
        $parts = $line.Split(",", [StringSplitOptions]::None)
        if ($parts.Count -le [Math]::Max($intendedIndex, $completedIndex)) { continue }
        $intended = 0L
        $completed = 0L
        if (-not [long]::TryParse($parts[$intendedIndex], [ref]$intended)) { continue }
        if (-not [long]::TryParse($parts[$completedIndex], [ref]$completed)) { continue }
        if ($intended -le 0 -or $completed -le $intended) { continue }
        $durationMs = ($completed - $intended) / 1000000.0
        if ($durationMs -le 0.0 -or $durationMs -gt 5000.0) { continue }
        $durations.Add($durationMs)
        $intendedTimes.Add($intended / 1000000000.0)
        if ($presentIndex -ge 0 -and $parts.Count -gt $presentIndex) {
            $present = 0L
            if ([long]::TryParse($parts[$presentIndex], [ref]$present) -and $present -gt 0) {
                $presentTimes.Add($present / 1000000000.0)
            }
        }
    }

    return [pscustomobject]@{
        Lines = $lines
        DurationsMs = [double[]]$durations.ToArray()
        IntendedSeconds = [double[]]$intendedTimes.ToArray()
        PresentSeconds = [double[]]$presentTimes.ToArray()
    }
}

function Read-HistogramDurations {
    param([string[]]$Lines)

    $histogramLine = $Lines | Where-Object { $_ -like "HISTOGRAM:*" } | Select-Object -First 1
    if (-not $histogramLine) { return [double[]]@() }

    $durations = New-Object System.Collections.Generic.List[double]
    $matches = [regex]::Matches($histogramLine, "(\d+)ms=(\d+)")
    foreach ($match in $matches) {
        $duration = [double]$match.Groups[1].Value
        $count = [int]$match.Groups[2].Value
        for ($i = 0; $i -lt $count; $i++) {
            $durations.Add($duration)
        }
    }
    return [double[]]$durations.ToArray()
}

function Get-PresentIntervals {
    param([double[]]$PresentSeconds)

    if ($PresentSeconds.Count -lt 2) { return [double[]]@() }
    $sorted = $PresentSeconds | Sort-Object -Unique
    $intervals = New-Object System.Collections.Generic.List[double]
    for ($i = 1; $i -lt $sorted.Count; $i++) {
        $deltaMs = ($sorted[$i] - $sorted[$i - 1]) * 1000.0
        if ($deltaMs -gt 0.0 -and $deltaMs -lt 200.0) {
            $intervals.Add($deltaMs)
        }
    }
    return [double[]]$intervals.ToArray()
}

function New-FrameStatsSummary {
    param(
        [string]$StatsPath,
        [double]$TargetHz
    )

    $data = Read-FrameTimelineDurations -StatsPath $StatsPath
    $histogramDurations = Read-HistogramDurations -Lines $data.Lines
    $durations = if ($histogramDurations.Count -gt 0) { $histogramDurations } else { $data.DurationsMs }
    $budgetMs = 1000.0 / $TargetHz
    $presentationDropMs = $budgetMs * 1.5
    $budget90Ms = 1000.0 / 90.0
    $budget60Ms = 1000.0 / 60.0
    $count = $durations.Count
    $overTarget = @($durations | Where-Object { $_ -gt $budgetMs }).Count
    $over90 = @($durations | Where-Object { $_ -gt $budget90Ms }).Count
    $over60 = @($durations | Where-Object { $_ -gt $budget60Ms }).Count
    $sampleSeconds = 0.0
    if ($data.IntendedSeconds.Count -gt 1) {
        $sampleSeconds = $data.IntendedSeconds[$data.IntendedSeconds.Count - 1] - $data.IntendedSeconds[0]
    }
    $producedFps = if ($sampleSeconds -gt 0.0) { $count / $sampleSeconds } else { [double]::NaN }
    $mean = if ($count -gt 0) { ($durations | Measure-Object -Average).Average } else { [double]::NaN }
    $p50 = Get-PercentileValue -Values $durations -Percentile 50
    $p90 = Get-PercentileValue -Values $durations -Percentile 90
    $p95 = Get-PercentileValue -Values $durations -Percentile 95
    $p99 = Get-PercentileValue -Values $durations -Percentile 99
    $presentIntervals = Get-PresentIntervals -PresentSeconds $data.PresentSeconds
    $presentCount = $presentIntervals.Count
    $presentDrops = @($presentIntervals | Where-Object { $_ -gt $presentationDropMs }).Count
    $presentOver60 = @($presentIntervals | Where-Object { $_ -gt 16.667 }).Count
    $presentP50 = Get-PercentileValue -Values $presentIntervals -Percentile 50
    $presentP95 = Get-PercentileValue -Values $presentIntervals -Percentile 95
    $presentP99 = Get-PercentileValue -Values $presentIntervals -Percentile 99
    $presentMean = if ($presentCount -gt 0) { ($presentIntervals | Measure-Object -Average).Average } else { [double]::NaN }

    return [pscustomobject]@{
        File = Split-Path -Path $StatsPath -Leaf
        Frames = $count
        RawTimelineFrames = $data.DurationsMs.Count
        PresentIntervals = $presentCount
        TargetHz = [Math]::Round($TargetHz, 1)
        TargetBudgetMs = [Math]::Round($budgetMs, 2)
        SampleSeconds = [Math]::Round($sampleSeconds, 3)
        ProducedFps = [Math]::Round($producedFps, 1)
        MeanMs = [Math]::Round($mean, 2)
        MeanEquivalentFps = if ($mean -gt 0.0) { [Math]::Round(1000.0 / $mean, 1) } else { [double]::NaN }
        OverTarget = $overTarget
        LatencyMiss120Pct = if ($count -gt 0) { [Math]::Round(($overTarget * 100.0) / $count, 2) } else { [double]::NaN }
        Over90Pct = if ($count -gt 0) { [Math]::Round(($over90 * 100.0) / $count, 2) } else { [double]::NaN }
        Over60Pct = if ($count -gt 0) { [Math]::Round(($over60 * 100.0) / $count, 2) } else { [double]::NaN }
        PresentDrop120Pct = if ($presentCount -gt 0) { [Math]::Round(($presentDrops * 100.0) / $presentCount, 2) } else { [double]::NaN }
        PresentOver60Pct = if ($presentCount -gt 0) { [Math]::Round(($presentOver60 * 100.0) / $presentCount, 2) } else { [double]::NaN }
        P50Ms = [Math]::Round($p50, 2)
        P90Ms = [Math]::Round($p90, 2)
        P95Ms = [Math]::Round($p95, 2)
        P99Ms = [Math]::Round($p99, 2)
        PresentP50Ms = [Math]::Round($presentP50, 2)
        PresentMeanMs = [Math]::Round($presentMean, 2)
        PresentMeanFps = if ($presentMean -gt 0.0) { [Math]::Round(1000.0 / $presentMean, 1) } else { [double]::NaN }
        PresentP95Ms = [Math]::Round($presentP95, 2)
        PresentP99Ms = [Math]::Round($presentP99, 2)
        MaxMs = if ($count -gt 0) { [Math]::Round(($durations | Measure-Object -Maximum).Maximum, 2) } else { [double]::NaN }
        P50EquivalentFps = if ($p50 -gt 0.0) { [Math]::Round(1000.0 / $p50, 1) } else { [double]::NaN }
        P95EquivalentFps = if ($p95 -gt 0.0) { [Math]::Round(1000.0 / $p95, 1) } else { [double]::NaN }
        AndroidJank = Read-AndroidSummaryValue -Lines $data.Lines -Pattern "^Janky frames:"
        AndroidLegacyJank = Read-AndroidSummaryValue -Lines $data.Lines -Pattern "^Janky frames \(legacy\):"
    }
}

$summaries = foreach ($entry in $Path) {
    $resolved = Resolve-Path -Path $entry
    foreach ($statsPath in $resolved) {
        New-FrameStatsSummary -StatsPath $statsPath.Path -TargetHz $TargetHz
    }
}

if ($Csv) {
    $summaries | ConvertTo-Csv -NoTypeInformation
} else {
    $summaries |
        Format-Table File, Frames, ProducedFps, PresentMeanFps, PresentP50Ms, PresentP95Ms, PresentP99Ms, MeanMs, P50Ms, P95Ms, P95EquivalentFps -AutoSize
}
