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
- Map Labels controls for street labels, borders, raster/vector reference mode, and label text scale.
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
- The docs and tools folders include concise folder-level READMEs, and generated perf/map artifacts stay ignored.
