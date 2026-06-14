# Flight Alert

Flight Alert is a Kotlin Android app for drone pilots who want live, map-first awareness of nearby aircraft. It is supplemental situational awareness only, not a certified detect-and-avoid system.

Current version: **1.5**

## Highlights

- Live aircraft map with real street, no-label, or satellite tiles.
- ADS-B/MLAT traffic from live public feeds, with source status shown when data is missing.
- Traffic/details source modes for API-only, Airplanes.Live web-only, or hybrid enrichment.
- Smooth aircraft motion, zoom/density marker morphing, and type-aware symbols.
- Aircraft filters for search, class, altitude, range, airborne/ground state, report age, and alert-volume membership.
- Selectable aircraft details with registry, route, trace, usage, photo, and environmental-impact views when real source data supports them.
- Long-press an aircraft photo to open a source-labeled gallery; source-marked photos include proof and browser buttons.
- Registry country flag in aircraft details and the traffic card.
- Settings skins/themes, with Cockpit retained as an option.
- Nested Settings panels for street-map labels and aviation layers.
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

## 1.5 Verification

- `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, and `lintDebug` pass on the release-prep tree.
- Emulator visual/layout check covered the main street-map surface with real tiles, aircraft overlays, readable aircraft dots/outlines, and the current panel/theme treatment.
- Physical phone `RFCX40KPN3B` passed `PriorityNotificationContractInstrumentedTest`, covering non-extreme contacts, extreme contacts, altitude text updates, and automatic notification clearing when the extreme-priority queue is empty.
- Physical phone `RFCX40KPN3B` v1.5 low-zoom street/off shell-pan over New York City rendered 487 frames with 11 janky frames (2.26%), p50 7 ms, p95 10 ms, and p99 14 ms; same-run motion capture preserved real map imagery and aircraft dot outlines.
- This pass kept the post-Optimizer/Artist map behavior intact while adding only light service/readability humanization and deterministic notification coverage.
