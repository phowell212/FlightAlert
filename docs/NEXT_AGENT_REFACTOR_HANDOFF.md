# FlightAlert Refactor Handoff

Delete this file once the refactor is complete and the follow-up agent no longer needs it.

## Current Goal

Make `FlightMapView` behave like a controller: it should coordinate map state, selection, draw order, and user input while feature objects own their own logic. The readability target is roughly 2,000 lines per Kotlin file, with about 3,000 lines as the hard maximum unless there is a strong reason.

## What Was Done

- Added code-organization rules in `docs/code-organization.md`.
- Kept Android Studio compatibility: normal Gradle project shape, original app module, no custom wrapper changes.
- Split aircraft symbol drawing into `ui.map.render.AircraftSymbolRenderer`.
- Split impact text and row presentation into `ui.map.impact.AircraftImpactPresenter`.
- Split route/detail route text into `ui.map.route.AircraftRoutePresenter`.
- Preserved current external data methods and route validation behavior.
- Verified `assembleDebug` succeeds after the latest extraction.

## Current Build Command

The contained no-admin environment used here was:

```powershell
$tmp='C:\Users\phowell\AppData\Local\Temp\codex-flightalert-gradle'
New-Item -ItemType Directory -Force $tmp | Out-Null
$env:JAVA_HOME='C:\Users\phowell\AppData\Local\FlightAlertDev\AndroidSmokeEnv\.gradle-flightalert\jdks\eclipse_adoptium-21-amd64-windows.2'
$env:GRADLE_USER_HOME=$tmp
$env:GRADLE_OPTS="-Djava.io.tmpdir=$tmp"
& 'C:\Users\phowell\AppData\Local\FlightAlertDev\AndroidSmokeEnv\.gradle-flightalert\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' --no-daemon --console=plain assembleDebug
```

## Known State

- `FlightMapView.kt` is still too large, around 6.4k lines after the latest pass.
- This is improved but not finished. Treat it as a working checkpoint, not architectural completion.
- The app built successfully after the route presenter extraction.
- Route fields should still prefer validated current-flight route data and otherwise show `Unavailable`, not stale origin/destination pairs.

## Recommended Next Extractions

1. Move settings/filter/priority panel bounds into a dedicated layout object.
2. Move details panel drawing into a renderer/presenter pair.
3. Move map tile drawing/cache request logic into a tile coordinator.
4. Move aviation layer drawing into an aviation-layer renderer.
5. Move path drawing and selected-flight path state into a path controller.

## Testing Directives

- Keep each extraction behavior-preserving and build after each meaningful move.
- Do not push if `assembleDebug` fails.
- Runtime-check several live aircraft types: at least one airliner, one regional/commuter aircraft, and one GA/business-jet target.
- For GA/business jets, `Route`, `Origin`, and `Destination` may legitimately be `Unavailable`.
- For airline route data, reject stale mismatched route pairs rather than filling the details panel with the last known route.

## Caution

Avoid spending time on global tool installs or admin prompts. Keep Android/Gradle work inside the contained AppData environment unless the user explicitly changes that direction.
