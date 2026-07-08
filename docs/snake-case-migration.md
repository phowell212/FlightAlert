# Naming Methodology

Flight Alert uses Kotlin names that make app-owned behavior easy to scan while preserving required external names at framework and source boundaries.

## Current Rule

- Use lower_snake_case for app-owned functions, properties, parameters, and local variables.
- Keep Kotlin type names in PascalCase.
- Keep constants in UPPER_SNAKE.
- Keep Android framework override names and required override parameter names in their framework spelling.
- Keep manifest entry-point class names such as `MainActivity` and service/activity class names.
- Keep generated/build API names, Gradle DSL names, resource names, and Android/Java/Kotlin library calls unchanged.
- Keep external provider field names unchanged when they must match source data, headers, URL parameters, or page globals.

## Boundary Exceptions

Remaining camelCase hits in app source should come from framework, generated, library, or external-source boundaries, including:

- Android lifecycle/input overrides such as `onCreate`, `onDraw`, `onTouchEvent`, `onKeyDown`, and `performClick`.
- Android override parameters whose names match superclass signatures, such as `savedInstanceState`, `rootIntent`, `startId`, `outAttrs`, and `keyCode`.
- Android, Java, Kotlin, or JSON API calls such as `drawText`, `textSize`, `startsWith`, `currentTimeMillis`, `openConnection`, and `optJSONObject`.
- External source keys and globals, such as feed fields, FAA/ArcGIS field names, HTTP headers, and page globals.

## Verification Method

Use scans to find app-owned declarations that appear to violate the naming rule:

```powershell
rg -n "\b(?:val|var|fun)\s+[a-z][A-Za-z0-9]*[A-Z][A-Za-z0-9_]*\b" app\src\main\java\com\flightalert app\src\test\java\com\flightalert
```

The declaration scan should return only framework overrides or documented boundary exceptions.

After broad naming changes, run the normal verification commands:

```powershell
.\gradlew.bat assembleDebug --console=plain
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat lintDebug --warning-mode all --console=plain
```
