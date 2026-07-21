# Flight Alert

Flight Alert is a Kotlin Android app for drone pilots who want live, map-first awareness of nearby aircraft. It is supplemental situational awareness only, not a certified detect-and-avoid system.

Current version: **1.10**

## What It Does

- Shows nearby live aircraft on a map using real public traffic feeds.
- Supports street, no-label, and satellite map styles from real map providers.
- Displays aircraft identity, altitude, motion, source freshness, and details when source data supports them.
- Lets you filter visible traffic by search, aircraft class, altitude, range, airborne/ground state, report age, and alert-volume membership.
- Provides selected-aircraft details such as registry, route, trace, usage, photos, and environmental-impact estimates when real sources provide enough information.
- Supports priority alert settings with unit-aware horizontal and vertical separation controls.
- Sends notifications only while aircraft qualify for the configured extreme-priority alert volume.
- Includes optional aviation layers from real public sources.

## Data Honesty

Flight Alert follows a no-pretending rule: if a value cannot be obtained from a live or documented source, the app says **Loading** or **Unavailable** instead of inventing it.

That applies to aircraft, maps, routes, photos, alerts, altitude, location, military status, ownership, and source freshness.

## Data Sources

- Aircraft traffic: Airplanes.Live and OpenSky-style viewport queries.
- Flight traces: tar1090-compatible trace sources such as ADSB.lol.
- Aircraft metadata, routes, and photos: documented aviation metadata and photo sources, with uncertainty labeled when a result is representative or investigable rather than exact.
- Map tiles: OpenStreetMap, CARTO no-label tiles, and Esri World Imagery.
- Aviation layers: FAA public services for airspace, airports, and NAT track data.
- Environmental impact: trace time/distance when available, broad aircraft-class fuel-burn ranges, and published per-gallon CO2 factors.

## Safety Limits

- Public ADS-B/MLAT feeds can be delayed, incomplete, rate-limited, or missing aircraft.
- Small drones and non-transmitting aircraft generally cannot be detected from public aircraft feeds.
- Device altitude quality varies; vertical separation is unavailable if either altitude is missing.
- Photos, route metadata, traces, maps, and aviation layers depend on third-party source availability.
- Filters only change visible map traffic; alert monitoring continues against the full live feed.
- Environmental-impact values are estimates, not measured fuel burn.

## Permissions

- Location: map centering and alert separation.
- Notifications: extreme-priority aircraft alerts only while qualifying aircraft are present.
- Foreground service location: background monitoring.
- Internet: maps, aircraft feeds, traces, metadata, photos, and aviation layers.

## Build From Source

Clone the repository, open its root in Android Studio, or build from PowerShell:

```powershell
git clone https://github.com/phowell212/FlightAlert.git
Set-Location FlightAlert
.\gradlew.bat assembleDebug
```

The debug APK is written under:

```text
build/outputs/apk/debug/Flight Alert-debug.apk
```

## Whole-world reference preview

Flight Alert can use a downloadable whole-world reference preview containing
source-backed OpenStreetMap labels, waterways, and administrative boundaries.
This global places and named waterways preview now also renders administrative
boundaries from the same source-bound package.
It is intentionally classified as
`full-fidelity-visual-evaluation`: it is useful for worldwide visual testing,
but it does **not** claim a complete all-feature world dictionary. The current
six-file package is 19,495,741,202 bytes (about 19.50 GB / 18.16 GiB), so choose
a destination with adequate free space.

Use the exact manifest URL from a pinned Flight Alert GitHub release, never a
`latest` shortcut:

```powershell
$manifestUrl = 'https://github.com/phowell212/FlightAlert/releases/download/experiment8-world-reference-v4-r16/world-experiment8-binary-v4.release-manifest.json'
$referenceRoot = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'FlightAlert\Reference'
$null = New-Item -ItemType Directory -Force $referenceRoot
$packageRoot = Join-Path $referenceRoot 'world-experiment8-binary-v4'
$authorityRoot = Join-Path $referenceRoot 'flightalert-reference-authority'

.\tools\download-reference-dictionary-experiment8.ps1 `
  -ManifestUrl $manifestUrl `
  -Output $packageRoot

py -3.11 -m tools.experiment8.reference_release_assets materialize `
  --manifest-url $manifestUrl `
  --package $packageRoot `
  --output $authorityRoot

.\tools\install-reference-dictionary-experiment8.ps1 `
  -PackageRoot $packageRoot `
  -ApkPath "$authorityRoot\FlightAlert-reference-preview.apk" `
  -FinalResult "$authorityRoot\final-package-result.json" `
  -InstallPolicy full-fidelity-visual-evaluation `
  -ValidateOnly
```

The fetch step atomically publishes exactly one six-file package root. The
materialization step downloads the manifest-bound APK and source result, then
creates a local result whose only changes are the current package/APK paths and
the documented installPolicy; it revalidates all identities before publication.
Building, fetching,
materializing, and `-ValidateOnly` do not require a device lease helper.
Transactional phone modes require an explicit `-LeaseHelper`, `-ThreadId`, and
external `-EvidenceDirectory`; there is no machine-specific default.

The 19.50 GB database is attached to the pinned GitHub release in transport
chunks because GitHub cannot store it as one Git object. The downloader verifies
and reconstructs the exact original files. The APK remains one ordinary APK,
and compiling the app does not download or embed the database.

OpenStreetMap attribution, the ODbL 1.0 license, pinned source identity, build
method, and machine-readable database offer are documented in
[THIRD_PARTY_REFERENCE_DATA.md](THIRD_PARTY_REFERENCE_DATA.md).

## Repository Notes

The Git tree contains source code, durable documentation, and small project
tooling. The large reference database is published as release assets;
screenshots, videos, traces, diagnostics, caches, and temporary run output stay
outside the Git history.

Agent and contributor methodology lives in [AGENTS.md](AGENTS.md).
