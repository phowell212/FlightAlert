# Flight Alert

Flight Alert is a Kotlin Android app for drone pilots who want live, map-first awareness of nearby aircraft and conservative proximity notifications.

The core design rule is **no pretending**: if a value cannot be obtained from a live or documented source, the UI says it is unavailable instead of inventing or pre-generating it.

## Version

Current app version: **1.2**

## What It Does

- Shows real map tiles with OpenStreetMap street tiles or Esri World Imagery satellite tiles.
- Requests device location and centers the map around the pilot.
- Supports pan, pinch zoom, scroll-wheel zoom, and keyboard zoom for emulator/hardware input.
- Frames the first map load so the clear map area covers at least 1.25x the DJI Mavic 3 maximum horizontal range when no saved zoom exists.
- Displays live ADS-B/aircraft-feed traffic when available.
- Smoothly estimates aircraft sprite motion between feed updates from last reported speed, heading, and position.
- Uses distinct symbols for general aviation, airliners, rotorcraft, gliders, UAVs, and surface traffic when feed metadata supports it.
- Geometrically morphs markers between compact dense-map dots and type-aware aircraft silhouettes as zoom and density change.
- Keeps map imagery untinted so street and satellite colors stay true to the source tiles.
- Lets you select an aircraft, view its actual reported path when a real trace exists, and open a detailed aircraft sheet.
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
- Aircraft metadata: FAA Registry for U.S. N-number aircraft when a registration is available, with HexDB as a general aircraft metadata source.
- Declared route metadata: ADSBdb callsign route lookup first, then HexDB route lookup when ADSBdb has no match.
- Military origin classification: only shown for aircraft tagged military. The selected aircraft's real track origin is checked against nearby OpenStreetMap aerodrome data through Overpass and labeled military only when the matched aerodrome tags or name support it.
- Exact aircraft photos: ADSBdb image URLs, JetPhotos only when the returned registration matches exactly, PlaneSpotters by ICAO hex/registration, then HexDB image lookup.
- Representative and search photos: Wikimedia Commons, Openverse, and Wikipedia page images after exact-photo sources fail. Representative photos are labeled as not the exact aircraft. Search-engine fallback photos open a proof view with source buttons and available evidence.
- Map imagery: OpenStreetMap street tiles or Esri World Imagery satellite tiles.

The FAA Registry page states its aircraft registration inquiry is updated each federal working day at midnight. DJI publishes the Mavic 3 maximum flight distance as 30 km.

## Version 1.2 Verification

For this release pass:

- `assembleDebug` passed.
- `lintDebug` passed.
- Runtime screenshots were checked on a connected Samsung Android device.
- Settings, priority tracker, details, alert-ring, and selected-path states were visually checked.
- FAA Registry parsing was verified against a live N-number result and now rejects FAA page navigation text.
- Selected path rendering was checked against live ADS-B data: the selected `N802FG` trace ended within about 0.9 km of both Airplanes.Live and ADSB.lol live positions, with the app extending the visible trail to the current sprite.

## Known Limits

- This is not a certified detect-and-avoid system.
- ADS-B/MLAT feeds can be delayed, rate-limited, incomplete, or missing aircraft that are not transmitting.
- Device altitude quality varies. If ownship altitude is unavailable, vertical separation cannot be fully trusted.
- Military aircraft may be missing or intentionally limited in public feeds.
- Military-base origin is shown as unavailable or civilian/other unless real track-origin and aerodrome data support a military-base label.
- Exact aircraft photos depend on third-party photo coverage. If exact photos are unavailable, the app falls back to clearly labeled representative, verified search, or investigable search images when a source can be found.
- Small-aircraft photo search is improved with common general-aviation aliases, but rare or oddly represented aircraft can still lack usable imagery.

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