# Flight Alert

### Nearby and far away aircraft, rendered for drone pilots.

Flight Alert turns an Android phone or tablet into an aircraft-awareness display. It combines live public traffic, with user specified settings to ensure you always know when a risky aircraft is in your area.

Current release: **1.12** · Minimum Android version: **Android 10 (API 29)**

Version 1.12 makes the rebuilt map and controls the standard Flight Alert app, with live ADSB.lol traffic, flight traces, aircraft details, alerts, themes, and offline reference layers.

> Flight Alert is supplemental situational awareness. It is not a certified detect-and-avoid system.

Gradle and Android Studio configuration stay local to configured development checkouts; the public tree contains the application source and the data it consumes.

## Build and load it

You need Android Studio with its bundled JDK, Android SDK Platform 37, Platform Tools, and an Android 10 or newer device with USB debugging enabled.

From a configured local checkout:

```powershell
.\gradlew.bat assembleDebug
adb install -r ".\build\outputs\apk\debug\Flight Alert-debug.apk"
```

When the offline reference dictionary is missing, Flight Alert automatically downloads the approximately 12.52 GiB package from GitHub. Download progress and Pause are available in the notification.

## Data and attribution

- Live traffic and metadata: ADSB.lol, Airplanes.Live, and OpenSky
- Flight traces: Airplanes.Live and ADSB.lol
- Registry and aircraft photos: FAA Registry and PlaneSpotters
- Base map: Esri World Imagery
- Aviation layers: FAA public services
- Offline reference dictionary: OpenStreetMap planet data dated 2026-06-29
