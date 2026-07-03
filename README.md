# Flight Alert

Flight Alert is a Kotlin Android app for drone pilots who want live, map-first awareness of nearby aircraft. It is supplemental situational awareness only, not a certified detect-and-avoid system.

Current version: **1.9**

## Highlights

- Live aircraft map with real street, no-label, or satellite tiles; fresh installs default to satellite with borders and street labels off.
- Smooth retained tile handoff for satellite imagery and street-map zoom transitions.
- ADS-B/MLAT traffic from live public feeds, with source status shown when data is missing.
- Traffic/details source modes for API-only, Airplanes.Live web-only, or hybrid enrichment.
- Smooth aircraft motion, zoom/density marker morphing, and type-aware symbols.
- Aircraft filters for search, class, altitude, range, airborne/ground state, report age, and alert-volume membership.
- Selectable aircraft details with registry, route, trace, usage, photo, and environmental-impact views when real source data supports them.
- Long-press an aircraft photo to open a source-labeled gallery; source-marked photos include proof and browser buttons.
- Registry country flag in aircraft details and the traffic card.
- Settings skins/themes, with Cockpit retained as an option.
- Priority alert settings with unit-aware range controls.
- Nested Settings panels for Map Labels, aviation layers, display, and alert controls.
- Map Labels controls for street labels, borders, and label text scale.
- Optional aviation layers from real public sources:
  - FAA ATC/FIR/OCA boundary airspace.
  - FAA special-use/restricted airspace.
  - FAA operational airport labels.
  - FAA/NAT oceanic tracks.
- Notifications only while an aircraft is on the extreme-priority list, meaning it is inside the configured horizontal and vertical alert volume.
- Modern adaptive launcher icon.

## Data Policy

The app follows a no-pretending rule: if a value cannot be obtained from a live or documented source, the UI says **Loading** or **Unavailable** instead of inventing it.

## Data Sources

- Aircraft traffic: Airplanes.Live and OpenSky-style viewport queries.
- Flight traces: ADSB.lol tar1090 traces.
- Aircraft metadata/routes/photos: Airplanes.Live API and Globe web pane, ADSBdb, HexDB, FAA Registry, PlaneSpotters, JetPhotos, Wikimedia/Openverse/Wikipedia fallbacks.
- Map tiles: OpenStreetMap, CARTO no-label tiles, and Esri World Imagery.
- Aviation layers: FAA ArcGIS services for airspace/airports and FAA NAT track JSON.
- Environmental impact: trace time/distance when available, broad aircraft-class fuel-burn ranges, and EIA per-gallon CO2 factors.

## Reference Dictionary Bake Work

Flight Alert is exploring a whole-world baked reference dictionary so satellite mode can keep Esri-level border, place, water, road, and label detail while rendering it with stable retained phone behavior. This work is split into two phases:

- Phase 1 is an external Esri-derived bake/schema/package task. It should first lock onto the same Esri reference source/path used by Flight Alert's accepted satellite/reference baseline, then produce a globally scalable dictionary schema, LOD hierarchy, storage/shard plan, proof slice, and validation reports before phone renderer changes begin.
- Phase 2 is the phone implementation. It should consume the baked dictionary with stable retained pan/zoom behavior, hierarchy-aware borders, professional label sizing/fading, correct layer toggles, physical-device video validation, and less reference-layer work than the accepted raster/reference baseline. The dictionary must replace superseded reference-rendering work as an optimization, not add another layer.

Experiment 3 starts from the current raster-only app state: there is no user-facing or code-level vector reference mode for borders or labels. Experiment 3 must not silently substitute a non-Esri structured map provider for the Esri source. Such sources may be used only for non-authoritative comparison or validation if explicitly requested. If the Esri source cannot be accessed as structured data, Phase 1 should design and prove an Esri-tile analysis/extraction path rather than switching providers.

Keep bake code, raw downloads, caches, generated packages, validation reports, videos, and traces outside this repo. Active whole-world Phase 1 Experiment 3 bake work belongs under `D:\FlightAlert-test-artifacts\experiment 3`. Treat `D:\FlightAlert-test-artifacts\experiment 1` as the first historical cook/archive and `D:\FlightAlert-test-artifacts\experiment 2` as a stopped/dud follow-up reference, not as implementation input. The old folders `C:\Users\Phineas\Documents\FlightAlert-test-artifacts` and `E:\FlightAlert-test-artifacts` may be actively deleted or recycled; do not work inside them. If fast E-side scratch is needed, create a separate temporary sibling such as `E:\FlightAlert-experiment3-scratch`, then verify and drain outputs back to the D Experiment 3 root. If small C-side Phase 2 probe scripts are needed, use a separate sibling such as `C:\Users\Phineas\Documents\FlightAlert-experiment3-probes`, and write durable outputs back to D. Only the final phone-consumable package/artifact should be copied out of the Experiment 3 D-root after it is validated. The app repo should contain only source, docs, and small manifest-style guidance.

Large external bake jobs should scale builder and shard concurrency to what the CPU, disk, and source pipeline can sustain. Do not target a fixed builder count; adapt parallelism until sustained average CPU utilization is above 85% when there is enough work available, while avoiding disk stalls, memory pressure, provider throttling, and low-space conditions. Report Phase 1 percent complete at least every 5 minutes while running, including active builder count and each builder's own percent complete. Experiment 3 must maintain its own workbook under `D:\FlightAlert-test-artifacts\experiment 3`, save those periodic metrics there, and update its graphs as the run progresses. Write shard outputs atomically, and validate representative parallel output against serial output before trusting it. Gradle/Kotlin work remains sequential.

Experiment 3 agents should keep one explicit active goal and continue working until that goal is complete: complete Phase 0 source recovery/design reset, Phase 1 Esri-derived dictionary construction/validation, and Phase 2 phone implementation/validation. A broad context pass, partial report, proof slice, or build is not completion. The goal is complete only when all three phases are validated or genuinely blocked with a concrete blocker.

## Build

Open `C:\Users\h\AndroidStudioProjects\FlightAlert` directly in Android Studio.

From PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

Debug APK:

```text
build/outputs/apk/debug/Flight Alert-debug.apk
```

## Permissions

- Location: map centering and alert separation.
- Notifications: extreme-priority aircraft alerts only while qualifying aircraft are present.
- Foreground service location: background monitoring.
- Internet: maps, aircraft feeds, traces, metadata, photos, and aviation layers.

## Safety Limits

- Public ADS-B/MLAT feeds can be delayed, incomplete, rate-limited, or missing aircraft.
- Small drones and non-transmitting aircraft generally cannot be detected from these public feeds.
- Device altitude quality varies; vertical separation is unavailable if either altitude is missing.
- Photos, route metadata, traces, and aviation layers depend on third-party source availability.
- Filters only change visible map traffic; alert monitoring continues against the full live feed.
- CO2/impact values are estimates, not measured fuel burn.

## 1.9 Verification

- `assembleDebug` passes on the v1.9 tree.
- `testDebugUnitTest` passes; there are currently no debug unit test sources.
- Map/settings UI hit targets now live with the UI layout/panel code while `FlightMapView` remains the coordinator for app state changes.
- The debug build keeps version metadata at `versionCode = 10` and `versionName = "1.9"`.

## 1.8 Verification

- `assembleDebug` passed on the v1.8 tree.
- Fresh installs default to satellite view with borders enabled and street labels disabled; saved user preferences still win.
- Fresh installs default map-label text scaling to 1.35x; saved user preferences still win.
- Flight-path and traffic hot paths reuse cached aircraft keys/labels instead of re-normalizing strings during rendering.
- The app repo keeps build/source tooling only; tests, perf scripts, and generated evidence live in `../FlightAlert-test-artifacts/`.
