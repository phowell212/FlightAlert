# Flight Alert

### Nearby and far away aircraft, rendered for drone pilots.

Flight Alert turns an Android phone or tablet into an aircraft-awareness display. It combines live public traffic, with user specified settings to ensure you always know when a risky aircraft is in your area.

Current release: **1.10** · Minimum Android version: **Android 10 (API 29)**

> Flight Alert is supplemental situational awareness. It is not a certified detect-and-avoid system.

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

## Data and attribution

- Live traffic and metadata: Airplanes.Live and OpenSky
- Flight traces: Airplanes.Live and ADSB.lol
- Registry and aircraft photos: FAA Registry and PlaneSpotters
- Base maps: CARTO/OpenStreetMap and Esri World Imagery
- Aviation layers: FAA public services
- Offline reference dictionary: OpenStreetMap planet data dated 2026-06-29
