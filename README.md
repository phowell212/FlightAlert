# Flight Alert

Flight Alert is a Kotlin Android app for drone pilots who want live, map-first awareness of nearby aircraft and conservative proximity notifications.

The core design rule is **no pretending**: if a value cannot be obtained from a live or documented source, the UI says it is unavailable instead of inventing or pre-generating it.

## Version

Current app version: **1.3**

## What It Does

- Shows real map tiles with OpenStreetMap street tiles or Esri World Imagery satellite tiles.
- Lets street-map town, city, road, and other base-map labels be switched off from a nested Settings panel.
- Groups Settings into Display, Map, Safety, and Reference sections so controls are easier to scan on portrait, landscape, and folding layouts.
- Requests device location and centers the map around the pilot.
- Supports pan, pinch zoom, scroll-wheel zoom, and keyboard zoom for emulator/hardware input.
- Frames the first map load so the clear map area covers at least 1.25x the DJI Mavic 3 maximum horizontal range when no saved zoom exists.
- Displays live ADS-B/aircraft-feed traffic when available.
- Filters visible map traffic by live search, aircraft class, altitude band, distance band, airborne/ground state, report freshness, or supported alert-volume membership.
- Smoothly estimates aircraft sprite motion between feed updates from last reported speed, heading, and position.
- Uses distinct symbols for general aviation, airliners, rotorcraft, gliders, UAVs, and surface traffic when feed metadata supports it.
- Geometrically morphs markers between compact dense-map dots and type-aware aircraft silhouettes as zoom and density change.
- Keeps map imagery untinted so street and satellite colors stay true to the source tiles.
- Lets you select an aircraft, view its actual reported path when a real trace exists, and open a detailed aircraft sheet.
- Adds a History button in selected-path mode when real trace data includes completed previous flights for that aircraft.
- Adds a trace-derived aircraft usage view from the details sheet, with current-week flight/hour totals and a seven-day graph when real trace segments support it.
- Adds an aircraft environmental-impact page from the details sheet, with a class-based carbon score, trace-derived CO2 estimates when real trace time exists, and benchmark comparisons to common aircraft classes.
- Provides an Impact methodology page in Settings with source links and the exact score formula used by the app.
- Extends a displayed selected path from the latest trace point to the current live sprite position when the live report is fresh.
- Shows only the selected aircraft plus extreme-priority traffic while a selected path is displayed.
- Sends Android notifications when aircraft enter or leave the configured alert volume around you.
- Provides a priority tracker page for the live queue, alert range controls, and an optional map ring that reflects the alert range.

## Alert Model

Alerts use a 3D volume around the device:

- **Horizontal range** in feet/meters.
- **Vertical range** in feet/meters.

An aircraft enters the alert volume only when it is inside both limits. The service sends one notification when an aircraft enters and one notification when it leaves. It does not spam the closest aircraft every polling interval.

Vertical separation requires both aircraft altitude and device altitude. If either altitude is unavailable, Flight Alert does not claim the aircraft is inside the alert volume.

If the aircraft feed is unavailable, Flight Alert keeps its last known hazard state instead of claiming the area is clear. Priority-list notifications are cleared when the live feed is unavailable so stale aircraft are not displayed as current.

## Priority Tracker

The priority tracker is a broader live queue around your position. Its internal tracking range is intentionally not exposed as a normal user-facing tuning control. The visible controls are:

- Queue on/off.
- Alert ring on/off.
- Alert horizontal range.
- Alert vertical range.

The optional map ring always reflects the alert horizontal range, not the hidden broader queue range. While the priority tracker page is open, the ring is shown as a preview so the user can adjust it without getting stuck unable to see what changed.

An aircraft is promoted to the extreme-priority list only when alerts are enabled and it is inside the configured horizontal and vertical alert volume. While any extreme-priority aircraft exist, the persistent notification lists their registration/callsign and altitude. The notification updates on each service poll and disappears automatically when the list is empty.

Flight Alert increases monitoring cadence when relevant aircraft are present. Priority-range aircraft are refreshed as their contact age approaches 10 seconds, and extreme-priority aircraft are refreshed as their contact age approaches 3 seconds.

## Data Sources

- Live traffic: Airplanes.Live first when its query plan fully covers the current viewport. On wide views where Airplanes.Live point circles would only cover patches of the map, OpenSky's rectangular query is used for complete visible-area coverage and Airplanes.Live enriches overlapping aircraft.
- Flight paths: ADSB.lol tar1090 trace files. The app selects the current moving leg, respects source-marked new-leg flags, avoids connecting stale/disconnected traces, and hides the path button when a usable real trace cannot be retrieved.
- Aircraft usage: selected-aircraft usage graphs are derived only from ADSB.lol trace segments already retrieved for that aircraft. The totals are trace-window/current-week totals, not all-time utilization claims.
- Aircraft metadata: ADSBdb aircraft records and HexDB general metadata, with FAA Registry used for U.S. N-number aircraft. When a U.S. registration is missing from the live feed, the app derives the N-number from the Mode S hex allocation only to query real aircraft-record sources.
- Declared route metadata: ADSBdb callsign route lookup first, then HexDB route lookup when ADSBdb has no match.
- Military origin classification: only shown for aircraft tagged military. The selected aircraft's real track origin is checked against nearby OpenStreetMap aerodrome data through Overpass and labeled military only when the matched aerodrome tags or name support it.
- Exact aircraft photos: ADSBdb image URLs, JetPhotos only when the returned registration matches exactly, PlaneSpotters by ICAO hex/registration, then HexDB image lookup.
- Representative and search photos: Wikimedia Commons, Openverse, and Wikipedia page images after exact-photo sources fail. Representative photos are labeled as not the exact aircraft. Search-engine fallback photos open a proof view with source buttons and available evidence.
- Map imagery: OpenStreetMap street tiles, CARTO no-label tiles using OpenStreetMap data when street labels are disabled, or Esri World Imagery satellite tiles.
- Environmental impact: live aircraft type metadata is mapped to broad benchmark classes only. CO2 uses EIA per-gallon factors for jet fuel and aviation gasoline. Trace totals use real trace duration and never app-session accumulated positions.

The FAA Registry page states its aircraft registration inquiry is updated each federal working day at midnight. DJI publishes the Mavic 3 maximum flight distance as 30 km.

## Version 1.3 Verification And Feature Audit

For this release pass:

- `assembleDebug` passed.
- `lintDebug` passed.
- `testDebugUnitTest` passed with no local unit-test sources present.
- Static security scan found no WebView/JavaScript bridge use, no cleartext app traffic, no external-storage permissions, no hardcoded bearer/basic credentials, and no API keys committed in source.
- Settings were reorganized into Display, Map, Safety, and Reference groups, with hit rectangles kept aligned to every visible button.
- Tower Glass control and panel highlights now share one inset geometry so glass accents line up with the real button surfaces.
- No Android target was attached during this pass, so runtime screenshots could not be captured for 1.3 on this machine.

Feature audit ratings after the pass:

| Area | Rating | Notes |
| --- | ---: | --- |
| No-pretending data policy | 10/10 | Missing live/source data remains unavailable instead of generated. |
| Map tiles and map controls | 9/10 | HTTPS public tiles, street/satellite toggle, label controls, pan/pinch/scroll/key zoom. Public tile availability remains external. |
| Live traffic coverage | 9/10 | Uses viewport-aware feed selection and source status. Public ADS-B/MLAT coverage remains external. |
| Aircraft motion and sprites | 9/10 | Frame-driven interpolation and density/zoom morphing are preserved. |
| Selected and previous flight paths | 9/10 | Current paths and previous-flight History appear only when real trace segments are available. Trace-source gaps remain external. |
| Alert and priority notifications | 9/10 | Entry/exit alerts, vertical separation gating, persistent extreme-priority notification, and cadence escalation are implemented. Device altitude/feed quality remain limiting inputs. |
| Aircraft details and photos | 9/10 | Exact, representative, and investigable search-photo paths are separated and labeled. Photo coverage remains source-limited. |
| Settings and responsive UI | 9/10 | Grouped settings layout and compact landscape arrangement are clearer, with non-overlapping controls. |
| Security posture | 9/10 | Backups and cleartext disabled, service non-exported, HTTPS enforced in fetch helpers. Public network feeds still require normal internet exposure. |

## Known Limits

- This is not a certified detect-and-avoid system.
- ADS-B/MLAT feeds can be delayed, rate-limited, incomplete, or missing aircraft that are not transmitting.
- Device altitude quality varies. If ownship altitude is unavailable, vertical separation cannot be fully trusted.
- Military aircraft may be missing or intentionally limited in public feeds.
- Military-base origin is shown as unavailable or civilian/other unless real track-origin and aerodrome data support a military-base label.
- Exact aircraft photos depend on third-party photo coverage. If exact photos are unavailable, the app falls back to clearly labeled representative, verified search, or investigable search images when a source can be found.
- Small-aircraft photo search is improved with common general-aviation aliases, but rare or oddly represented aircraft can still lack usable imagery.
- Filters only change what is displayed in the map UI; alert and priority monitoring continue to use the full live feed so safety notifications are not hidden by a visual filter.
- Aircraft usage graphs are limited to the trace source window. If the selected aircraft lacks usable trace segments, the app shows Unavailable rather than estimating history.
- Environmental-impact values are estimates by aircraft class, not measured fuel burn. If the selected aircraft's type or fuel class is still loading, the UI says Loading; if it cannot be supported, it says Unavailable.

## Build

Open this folder directly in Android Studio. It is a single Android application project with the source set under `app/src/main`.

From PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is written to:

```text
build/outputs/apk/debug/Flight Alert-debug.apk
```

## Permissions

Flight Alert requests:

- Location, for map centering and alert separation calculations.
- Notifications, for range entry/exit alerts.
- Foreground service location, so monitoring can continue while another app is in front.
- Internet, for map tiles, traffic feeds, tracks, metadata, and photos.

## Security And Privacy

- Android app backup is disabled so local settings and cached app data are not copied through platform backup.
- Cleartext network traffic is disabled at the manifest level; image downloads are rejected unless they use HTTPS.
- Lock-screen notification surfaces use a generic public version instead of exposing nearby aircraft details before unlock.
- `local.properties`, build outputs, and root-level screenshots are ignored because they are local machine/test artifacts rather than project source.

## Safety Note

Use Flight Alert as supplemental situational awareness only. Drone pilots remain responsible for visual line of sight, regulatory compliance, and yielding to crewed aircraft.
