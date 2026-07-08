# Flight Alert

Flight Alert is a Kotlin Android app for drone pilots who want live, map-first awareness of nearby aircraft. It is supplemental situational awareness only, not a certified detect-and-avoid system.

Current version: **1.9**

## What It Does

- Shows nearby live aircraft on a map using real public traffic feeds.
- Supports street, no-label, and satellite map styles from real map providers.
- Displays aircraft identity, altitude, motion, source freshness, and details when source data supports them.
- Lets you filter visible traffic by search, aircraft class, altitude, range, airborne/ground state, report age, and alert-volume membership.
- Provides selected-aircraft details such as registry, route, trace, usage, photos, and environmental-impact estimates when real sources provide enough information.
- Supports priority alert settings with unit-aware horizontal and vertical separation controls.
- Sends notifications only while aircraft qualify for the configured extreme-priority alert volume.
- Includes optional aviation layers from real public sources.

## Data Honesty

Flight Alert follows a no-pretending rule: if a value cannot be obtained from a live or documented source, the app says **Loading** or **Unavailable** instead of inventing it.

That applies to aircraft, maps, routes, photos, alerts, altitude, location, military status, ownership, and source freshness.

## Data Sources

- Aircraft traffic: Airplanes.Live and OpenSky-style viewport queries.
- Flight traces: tar1090-compatible trace sources such as ADSB.lol.
- Aircraft metadata, routes, and photos: documented aviation metadata and photo sources, with uncertainty labeled when a result is representative or investigable rather than exact.
- Map tiles: OpenStreetMap, CARTO no-label tiles, and Esri World Imagery.
- Aviation layers: FAA public services for airspace, airports, and NAT track data.
- Environmental impact: trace time/distance when available, broad aircraft-class fuel-burn ranges, and published per-gallon CO2 factors.

## Safety Limits

- Public ADS-B/MLAT feeds can be delayed, incomplete, rate-limited, or missing aircraft.
- Small drones and non-transmitting aircraft generally cannot be detected from public aircraft feeds.
- Device altitude quality varies; vertical separation is unavailable if either altitude is missing.
- Photos, route metadata, traces, maps, and aviation layers depend on third-party source availability.
- Filters only change visible map traffic; alert monitoring continues against the full live feed.
- Environmental-impact values are estimates, not measured fuel burn.

## Permissions

- Location: map centering and alert separation.
- Notifications: extreme-priority aircraft alerts only while qualifying aircraft are present.
- Foreground service location: background monitoring.
- Internet: maps, aircraft feeds, traces, metadata, photos, and aviation layers.

## Build From Source

Open the project root in Android Studio, or build from PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is written under:

```text
app/build/outputs/apk/debug/
```

## Repository Notes

The app repo is for source code, durable documentation, and small project tooling. Generated data products, screenshots, videos, traces, large diagnostics, caches, and temporary run output should live outside the repo.

Agent and contributor methodology lives in [AGENTS.md](AGENTS.md).
