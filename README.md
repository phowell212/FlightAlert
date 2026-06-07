# Flight Alert

Flight Alert is a Kotlin Android prototype for drone pilots who want a live, map-first view of nearby aircraft and conservative proximity notifications.

The app follows a strict no-pretending rule: if a value cannot be obtained from a live or documented source, the UI says it is unavailable rather than inventing it.

## Version

Current app version: **1.1**

## What It Does

- Shows real map tiles with a street or satellite map option.
- Requests device location and centers the map around the pilot.
- Displays live ADS-B/aircraft-feed traffic when available.
- Smoothly estimates aircraft sprite motion between feed updates from last reported speed, heading, and position.
- Lets you select an aircraft, view its reported flight path when available, and open a details panel.
- Shows only the selected aircraft plus extreme-priority traffic while a selected flight path is displayed.
- Sends Android notifications when aircraft enter or leave the configured alert volume around you.
- Tracks aircraft in a configurable priority range and can show a persistent extreme-priority notification for aircraft below the selected altitude.
- Keeps a low-importance foreground monitoring notification active while hazard alerts or priority tracking are enabled.

## Alert Model

Alerts are controlled by two settings:

- **Horizontal range** in feet/meters.
- **Vertical range** in feet/meters.

An aircraft enters the alert volume only when it is within both configured limits. The service emits one notification when an aircraft enters and one when it leaves. It does not repeatedly notify for the same aircraft on every polling interval.

Vertical separation requires both aircraft altitude and device altitude. If either altitude is unavailable, Flight Alert does not claim the aircraft is inside the alert volume.

If the aircraft feed is unavailable, Flight Alert retains its last known hazard state instead of claiming the area is clear. Priority-list notifications are cleared when the live feed is unavailable so stale aircraft are not displayed as current.

## Priority Tracker

The priority tracker is configured separately from the hazard alert volume:

- **Tracking range** controls the circle drawn around your position and the aircraft included on the priority page.
- **Priority below** controls which aircraft are promoted to the extreme-priority list.

When priority tracking is active, any aircraft inside the tracking range and below the configured altitude appears in a persistent notification with its altitude and registration when available. The notification updates on each service poll and disappears when the list is empty or tracking is turned off.

## Data Sources

- Live traffic: Airplanes.Live first, OpenSky as a fallback where useful.
- Flight tracks: OpenSky track endpoint with ADSB.lol tar1090 trace files as a fallback when OpenSky is rate-limited or unavailable.
- Aircraft metadata: FAA Registry for U.S. N-number aircraft when a registration is available, with HexDB as a general aircraft metadata source.
- Declared route metadata: ADSBdb callsign route lookup first, then HexDB route lookup when ADSBdb has no match.
- Military origin classification: the selected aircraft's real flight-track origin is checked against nearby OpenStreetMap aerodrome data through Overpass. It is labeled as military only when the matched aerodrome tags or name support that classification.
- Exact aircraft photos: ADSBdb image URLs, JetPhotos results only when the returned registration matches exactly, PlaneSpotters by ICAO hex/registration, then HexDB image lookup.
- Representative photos: Wikimedia Commons, Openverse, and Wikipedia page images, only after exact-photo sources fail, and always labeled as not the exact aircraft.
- Map imagery: OpenStreetMap street tiles or Esri World Imagery satellite tiles.

The FAA Registry page states its aircraft registration inquiry is updated each federal working day at midnight. DJI publishes the Mavic 3 max flight distance as 30 km; Flight Alert uses 1.25x that distance for first-load map framing when no saved zoom exists.

## Known Limits

- This is not a certified detect-and-avoid system.
- ADS-B/MLAT feeds can be delayed, rate-limited, incomplete, or missing aircraft that are not transmitting.
- Device altitude quality varies. If ownship altitude is unavailable, vertical separation cannot be fully trusted.
- Military aircraft may be missing or intentionally limited in public feeds.
- Military-base origin is shown as unavailable or civilian/other unless real track-origin and aerodrome data support a military-base label.
- Exact aircraft photos depend on third-party photo coverage. If exact photos are unavailable, the app falls back to a clearly labeled representative make/model image.

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

## Safety Note

Use Flight Alert as supplemental situational awareness only. Drone pilots remain responsible for visual line of sight, regulatory compliance, and yielding to crewed aircraft.
