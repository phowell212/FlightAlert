# Performance Tools

These scripts are for Android device performance checks. They assume PowerShell, ADB, and the local Android project layout.

## Common Commands

```powershell
.\tools\perf\RunFlightMapGesturePerf.ps1 -TestName quickZoomJumpsOverTrafficSatellite -RecordVideo
.\tools\perf\RunFlightMapPanMatrix.ps1 -Matrix Short -NoScreenshots
node .\tools\perf\BuildFlightAlertPerformanceWorkbook.mjs
```

## Scripts

- `RunFlightMapGesturePerf.ps1` - primary instrumented gesture runner with optional video evidence.
- `RunFlightMapPerf.ps1` - lower-level manual pan, soak, and zoom runner.
- `RunFlightMapPanMatrix.ps1` - repeatable pan scenario matrix.
- `SummarizeFrameStats.ps1` - converts Android frame stats into summary metrics.
- `AnalyzeFrameCorrelation.mjs` - joins Perfetto traces with Flight Alert render logs.
- `BuildFlightAlertPerformanceWorkbook.mjs` - rebuilds `docs/flightalert-performance-metrics.xlsx`.

Generated artifacts are written under `tools/perf/out/`, which is ignored and safe to delete after useful evidence has been copied into the workbook.
