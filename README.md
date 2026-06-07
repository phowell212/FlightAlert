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
- Sends Android notifications when aircraft enter or leave the configured alert volume around you.
- Keeps a low-importance foreground monitoring notification active while alerts are enabled.

## Alert Model

Alerts are controlled by two settings:

- **Horizontal range** in feet/meters.
- **Vertical range** in feet/meters.

An aircraft enters the alert volume only when it is within both configured limits. The service emits one notification when an aircraft enters and one when it leaves. It does not repeatedly notify for the same aircraft on every polling interval.

If the aircraft feed is unavailable, Flight Alert retains its last known alert state instead of claiming the area is clear.

## Data Sources

- Live traffic: Airplanes.Live first, OpenSky as a fallback where useful.
- Flight tracks: OpenSky track endpoint.
- Aircraft metadata: FAA Registry for U.S. N-number aircraft when a registration is available, with HexDB as a general aircraft/route metadata source.
- Exact aircraft photos: PlaneSpotters by ICAO hex/registration, then HexDB image lookup.
- Representative photos: Wikimedia Commons only after exact-photo sources fail, and always labeled as not the exact aircraft.
- Map imagery: OpenStreetMap street tiles or Esri World Imagery satellite tiles.

The FAA Registry page states its aircraft registration inquiry is updated each federal working day at midnight. DJI publishes the Mavic 3 max flight distance as 30 km; Flight Alert uses 1.25x that distance for first-load map framing when no saved zoom exists.

## Known Limits

- This is not a certified detect-and-avoid system.
- ADS-B/MLAT feeds can be delayed, rate-limited, incomplete, or missing aircraft that are not transmitting.
- Device altitude quality varies. If ownship altitude is unavailable, vertical separation cannot be fully trusted.
- Military aircraft may be missing or intentionally limited in public feeds.
- Military-base origin is shown as unavailable unless a real source provides it.
- The path conflict filter only activates when verified path data exists for both the selected aircraft and candidate aircraft.

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
