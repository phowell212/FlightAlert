# Flight Alert

Flight Alert is a native Kotlin Android app that gives drone pilots map-first awareness of nearby aircraft. It combines live public traffic feeds with maps, aircraft details, configurable filters, aviation layers, and proximity alerts.

Flight Alert is supplemental situational awareness only. It is not a certified detect-and-avoid system.

Current version: **1.10** &middot; Minimum Android version: **Android 10 (API 29)**

## Features

- Live aircraft from Airplanes.Live, with OpenSky fallback.
- Street, no-label, and satellite maps.
- Aircraft identity, altitude, motion, source freshness, route, trace, registry data, and photos when sources provide them.
- Search and filters for aircraft class, altitude, range, airborne state, report age, and alert-volume membership.
- Configurable horizontal and vertical alert volumes with extreme-priority notifications.
- FAA airspace, airport, and North Atlantic Track layers.
- Worldwide OpenStreetMap-derived labels, waterways, and administrative boundaries from the included reference dictionary.

Unavailable source data is shown as loading or unavailable; the app does not invent missing values.

## Repository layout

```text
app/            Android application source and resources
references/     Runtime reference dictionary data
AGENTS.md       Repository guidance for coding agents
README.md       Project and setup documentation
```

Gradle, Android Studio, and other local project configuration are intentionally not tracked. They remain in configured development checkouts but are not part of the public Git tree.

`references/world-reference-dictionary-v4/` holds the complete dictionary used by the app:

```text
class-catalog.bin
manifest.json
world-reference-records.part-0001-of-0195.bin ... part-0195-of-0195.bin
world-reference-tile-index.part-0001-of-0002.bin ... part-0002-of-0002.bin
```

The 197 binary parts are ordinary Git files no larger than 95 MiB. Flight Alert reads them directly as two logical files; no download or reassembly step is required after cloning. The directory's exact combined size is **19,495,730,581 bytes** (19.50 GB / 18.16 GiB). Allow additional space for Git's object database, the Android SDK, Gradle cache, and APK build output.

## Build and install

Requirements:

- Android Studio with its bundled JDK, or an equivalent Gradle-compatible JDK.
- Android SDK Platform 37 and Android SDK Platform Tools.
- An Android 10 or newer device with USB debugging enabled and at least 20 GB free for the dictionary and app.

Build from a configured local Android Studio project, or use its local Gradle wrapper:

```powershell
.\gradlew.bat assembleDebug
```

The APK is created at `build/outputs/apk/debug/Flight Alert-debug.apk`. Install it, then copy the included dictionary to the app's external-files directory:

```powershell
adb install -r ".\build\outputs\apk\debug\Flight Alert-debug.apk"

$deviceReferences = "/sdcard/Android/data/com.flightalert/files/references"
adb shell mkdir -p $deviceReferences
adb push ".\references\world-reference-dictionary-v4" "$deviceReferences/"
```

Launch Flight Alert and grant the requested permissions.

## Sources and attribution

- Aircraft traffic and metadata: Airplanes.Live and OpenSky.
- Flight traces: Airplanes.Live and ADSB.lol.
- Registry and aircraft photos: FAA Registry and PlaneSpotters where available.
- Maps: CARTO using OpenStreetMap data, and Esri World Imagery.
- Aviation layers: FAA public services.
- Reference dictionary: OpenStreetMap planet data dated 2026-06-29.

Map and reference data: [&copy; OpenStreetMap contributors](https://www.openstreetmap.org/copyright). The derived reference database is made available under the [Open Data Commons Open Database License 1.0](https://opendatacommons.org/licenses/odbl/1-0/).

Other providers retain their respective terms and attribution.

## Safety and permissions

- Public ADS-B and MLAT feeds can be delayed, incomplete, rate-limited, or missing aircraft. Small drones and non-transmitting aircraft generally cannot be detected.
- Device altitude accuracy varies. Vertical separation is unavailable when either altitude is missing.
- Map filters affect visible traffic; alert monitoring continues against the complete live feed.
- Environmental-impact figures are estimates, not measured fuel burn.
- **Location** centers the map and calculates alert separation.
- **Notifications** deliver extreme-priority aircraft alerts.
- **Foreground-service location** keeps monitoring active in the background.
- **Internet** loads traffic, maps, traces, metadata, photos, and aviation layers.
