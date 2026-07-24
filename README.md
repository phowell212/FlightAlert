# Flight Alert

### Nearby and far away aircraft, rendered for drone pilots.

Flight Alert turns an Android phone or tablet into a map-first aircraft-awareness display. It combines live public traffic, aviation context, and a worldwide offline reference dictionary so a pilot can see what is nearby, what it is doing, and whether it is entering a configured alert volume.

Current release: **1.10** · Minimum Android version: **Android 10 (API 29)**

> Flight Alert is supplemental situational awareness. It is not a certified detect-and-avoid system.

## One screen, four jobs

**See traffic.** Aircraft from Airplanes.Live, with OpenSky fallback, are drawn over street, no-label, or satellite maps with source age and motion kept visible.

**Understand the aircraft.** Selection opens identity, altitude, route, trace, registry, and photo details when the underlying providers actually have them.

**Read the airspace.** FAA airspace, airports, North Atlantic Tracks, and the bundled OpenStreetMap-derived labels, waterways, and administrative boundaries share the same map.

**Know when it matters.** Horizontal and vertical alert volumes drive proximity warnings and extreme-priority notifications. Map filters change what is drawn; they do not silently remove traffic from alert monitoring.

## The two halves of this repository

`app/` is the native Kotlin Android application.

`world-reference-dictionary/` is the immutable runtime map dictionary shipped beside it. It contains:

```text
class-catalog.bin
manifest.json
world-reference-records.part-0001-of-0195.bin ... part-0195-of-0195.bin
world-reference-tile-index.part-0001-of-0002.bin ... part-0002-of-0002.bin
```

The 197 binary parts are ordinary Git files no larger than 95 MiB. Flight Alert reads them as two logical files without a download or reassembly step. Their exact combined size is **19,495,730,581 bytes** (19.50 GB / 18.16 GiB), so allow extra room for Git, the Android SDK, Gradle, and APK output.

Gradle and Android Studio configuration stay local to configured development checkouts; the public tree contains the application source and the data it consumes.

## Build and load it

You need Android Studio with its bundled JDK, Android SDK Platform 37, Platform Tools, and an Android 10 or newer device with USB debugging enabled.

From a configured local checkout:

```powershell
.\gradlew.bat assembleDebug
adb install -r ".\build\outputs\apk\debug\Flight Alert-debug.apk"

$deviceReferences = "/sdcard/Android/data/com.flightalert/files/references"
adb shell mkdir -p $deviceReferences
adb push ".\world-reference-dictionary" "$deviceReferences/"
```

Launch Flight Alert and grant Location, Notifications, Foreground-service location, and Internet access when Android asks for them.

## Missing data stays missing

Public ADS-B and MLAT feeds can be delayed, incomplete, or rate-limited. Small drones and non-transmitting aircraft generally cannot be detected. Device altitude can also be inaccurate.

Flight Alert therefore shows `Loading` or `Unavailable` instead of inventing identity, position, altitude, route, trace, ownership, or safety claims. Vertical separation is unavailable when either altitude is missing, and environmental-impact values remain estimates rather than measured fuel burn.

## Data and attribution

- Live traffic and metadata: Airplanes.Live and OpenSky
- Flight traces: Airplanes.Live and ADSB.lol
- Registry and aircraft photos: FAA Registry and PlaneSpotters
- Base maps: CARTO/OpenStreetMap and Esri World Imagery
- Aviation layers: FAA public services
- Offline reference dictionary: OpenStreetMap planet data dated 2026-06-29

Map and reference data: [© OpenStreetMap contributors](https://www.openstreetmap.org/copyright). The derived reference database is available under the [Open Data Commons Open Database License 1.0](https://opendatacommons.org/licenses/odbl/1-0/). Other providers retain their respective terms and attribution.
