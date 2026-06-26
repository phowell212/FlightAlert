# Snake Case Migration Status

Status date: 2026-06-26

Branch scanned: `master`

The app-owned Kotlin function, property, parameter, and local-variable migration is substantially complete.

## Current Rule

- Use lower_snake_case for app-owned functions, properties, parameters, and local variables.
- Keep Kotlin type names in PascalCase.
- Keep constants in UPPER_SNAKE.
- Keep Android framework override names and required override parameter names in their framework spelling.
- Keep manifest entry-point class names such as `MainActivity` and `AircraftAlertService`.
- Keep generated/build API names, Gradle DSL names, resource names, and Android/Java/Kotlin library calls unchanged.
- Keep external provider field names unchanged when they must match source data, headers, URL parameters, or page globals.

## Verified Exceptions

Remaining camelCase hits in source should be one of these categories:

- Android lifecycle/input overrides: `onCreate`, `onDraw`, `onTouchEvent`, `onKeyDown`, `performClick`, and related framework methods.
- Android override parameters whose names match superclass signatures: `savedInstanceState`, `rootIntent`, `startId`, `outAttrs`, `newCursorPosition`, `beforeLength`, `afterLength`, `actionCode`, and `keyCode`.
- Android, Java, Kotlin, or JSON API calls: `drawText`, `textSize`, `startsWith`, `currentTimeMillis`, `openConnection`, `optJSONObject`, and similar library members.
- External source keys and globals: for example `ownOpCode`, `globe.firstFetchDone`, `pendingFetches`, FAA/ArcGIS field names, and HTTP headers.

## Verification Commands

Useful scans:

```powershell
rg -n "\b(?:val|var|fun)\s+[a-z][A-Za-z0-9]*[A-Z][A-Za-z0-9_]*\b" app\src\main\java\com\flightalert app\src\test\java\com\flightalert
```

The declaration scan should return only framework overrides or documented boundary exceptions.

Run after naming changes:

```powershell
.\gradlew.bat assembleDebug --console=plain
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat lintDebug --warning-mode all --console=plain
```
