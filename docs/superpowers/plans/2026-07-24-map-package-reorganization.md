# Map Package Reorganization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reorganize the flat Flight Alert map package into the approved capability packages, consolidate the approved small concepts, and split the reference overlay renderer without changing behavior or performance.

**Architecture:** Keep `com.flightalert.map` as the shared facade and primitive package. Move tile rendering and aviation layers into concrete subpackages, organize reference work beneath `map.reference`, and make `ReferenceDictionaryOverlayRenderer` a top-down coordinator over concrete dictionary, border, label, and retained collaborators.

**Tech Stack:** Kotlin, Android Canvas, Android executors and synchronization primitives, Gradle/AGP, PowerShell, ADB screen recording and frame statistics.

## Global Constraints

- The baseline application source is commit `d489200`; the implementation branch starts from
  the planning branch tip and must have no `app/` changes relative to `d489200`.
- No rendering algorithm, threshold, ordering, style value, timing value, cache size, package format, executor priority, or settings behavior may change.
- No new dependency, interface layer, factory, dependency-injection framework, or speculative abstraction.
- No new per-frame allocation in drawing, panning, zooming, label admission, boundary raster, or retained-frame hot paths.
- No try-catch block in rendering or interaction hot paths.
- No agent instructions, workflow notes, or process reminders in production Kotlin comments.
- `aviation_layers` is the required package name.
- Do not create a generic `policy` directory.
- Retained-reference files stay directly under `map.reference` as a contextual decision for this package.
- Motion validation uses video, not screenshots.
- Use the physical phone when available and an emulator only when the phone is unavailable.
- Exercise dense land, including Morocco, across the zoom bands where the reference dictionary is active.
- Keep temporary probes, recordings, and performance artifacts outside the application repository.

---

### Task 1: Establish the isolated worktree and baseline evidence

**Files:**
- Read: `C:\Users\h\AndroidStudioProjects\FlightAlert-add-v5-split\docs\superpowers\specs\2026-07-24-map-package-reorganization-design.md`
- Create worktree: `C:\Users\h\AndroidStudioProjects\FlightAlert-map-reorganization`
- External evidence: `C:\Users\h\AndroidStudioProjects\FlightAlert-test-artifacts\map-reorganization\baseline\`

**Interfaces:**
- Consumes: Git commit `d489200` and the existing ignored Gradle scaffold in `C:\Users\h\AndroidStudioProjects\FlightAlert`.
- Produces: an isolated implementation branch, a successful baseline build, a baseline motion video, and baseline frame statistics.

- [ ] **Step 1: Create the isolated worktree through the required skill**

Invoke `superpowers:using-git-worktrees`, then create:

```powershell
$base = git rev-parse agent/add-v5-split
git diff --quiet d489200 $base -- app
if ($LASTEXITCODE -ne 0) { throw 'Planning changed the application baseline' }
git worktree add `
  -b agent/map-package-reorganization `
  C:\Users\h\AndroidStudioProjects\FlightAlert-map-reorganization `
  $base
```

- [ ] **Step 2: Supply the ignored local build scaffold without tracking it**

Copy these existing local files into the new worktree:

```powershell
$source = 'C:\Users\h\AndroidStudioProjects\FlightAlert'
$target = 'C:\Users\h\AndroidStudioProjects\FlightAlert-map-reorganization'
@(
  'build.gradle.kts',
  'settings.gradle.kts',
  'gradle.properties',
  'local.properties',
  'gradlew.bat'
) | ForEach-Object {
  Copy-Item -LiteralPath (Join-Path $source $_) -Destination (Join-Path $target $_)
}
Copy-Item -LiteralPath (Join-Path $source 'gradle') `
  -Destination (Join-Path $target 'gradle') -Recurse
```

Verify that `git status --short` does not show these ignored files.

- [ ] **Step 3: Run the baseline build**

Run:

```powershell
.\gradlew.bat assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL` and
`build\outputs\apk\debug\Flight Alert-debug.apk`.

- [ ] **Step 4: Capture intentional baseline motion evidence**

Create the external evidence directory:

```powershell
$evidence = 'C:\Users\h\AndroidStudioProjects\FlightAlert-test-artifacts\map-reorganization\baseline'
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
adb devices -l
```

Choose a physical-device serial when one is available; otherwise choose the emulator serial.
Install and launch the baseline build, position the map over Morocco, enable borders and
labels, and start:

```powershell
$deviceLines = adb devices -l |
  Select-Object -Skip 1 |
  Where-Object { $_ -match '\sdevice(\s|$)' }
$selectedLine = $deviceLines |
  Where-Object { $_ -notmatch '^emulator-' } |
  Select-Object -First 1
if (!$selectedLine) { $selectedLine = $deviceLines | Select-Object -First 1 }
if (!$selectedLine) { throw 'No physical phone or emulator is available' }
$serial = ($selectedLine -split '\s+')[0]
adb -s $serial install -r 'build\outputs\apk\debug\Flight Alert-debug.apk'
adb -s $serial shell am force-stop com.flightalert
adb -s $serial shell monkey -p com.flightalert 1
adb -s $serial shell dumpsys gfxinfo com.flightalert reset
$recording = Start-Process -FilePath adb -ArgumentList @(
  '-s', $serial,
  'shell', 'screenrecord',
  '--bit-rate', '12000000',
  '--time-limit', '180',
  '/sdcard/flightalert-map-reorganization-baseline.mp4'
) -WindowStyle Hidden -PassThru
```

During recording, perform the same repeatable sequence later used for final validation:

1. Hold at maximum zoom-out long enough for zoom-zero-through-three presentation to settle.
2. Pan west-east and north-south across Morocco and neighboring land.
3. Pinch through dictionary source zoom 4.
4. Continue through source zoom bands 5, 6, 7, 8, 9, 10, and 11.
5. Reverse the sequence back to maximum zoom-out.
6. Repeat a shorter pan with borders and labels disabled.

Pull the video and frame data:

```powershell
Wait-Process -Id $recording.Id
adb -s $serial pull /sdcard/flightalert-map-reorganization-baseline.mp4 `
  (Join-Path $evidence 'flightalert-map-reorganization-baseline.mp4')
adb -s $serial shell dumpsys gfxinfo com.flightalert framestats |
  Set-Content -LiteralPath (Join-Path $evidence 'gfxinfo-framestats.txt')
```

Expected: the video visibly exercises meaningful reference content at every selected band;
the OFF segment contains neither borders nor labels.

- [ ] **Step 5: Record the baseline commit without changing source**

Run:

```powershell
git status --short
git rev-parse HEAD
```

Expected: no tracked changes and no `app/` diff from `d489200`.

---

### Task 2: Move the root encoding and tile-rendering package

**Files:**
- Rename: `app/src/main/java/com/flightalert/map/ReferencePolicyEncoding.kt`
  → `app/src/main/java/com/flightalert/map/MapPresentationEncoding.kt`
- Move: `app/src/main/java/com/flightalert/map/MapLayerPanCompositor.kt`
  → `app/src/main/java/com/flightalert/map/tiles/MapLayerPanCompositor.kt`
- Move: `app/src/main/java/com/flightalert/map/MapTileDiskCache.kt`
  → `app/src/main/java/com/flightalert/map/tiles/MapTileDiskCache.kt`
- Move: `app/src/main/java/com/flightalert/map/SatelliteBaseTileRenderer.kt`
  → `app/src/main/java/com/flightalert/map/tiles/SatelliteBaseTileRenderer.kt`
- Move: `app/src/main/java/com/flightalert/map/SatelliteMapTileRenderer.kt`
  → `app/src/main/java/com/flightalert/map/tiles/SatelliteMapTileRenderer.kt`
- Move: `app/src/main/java/com/flightalert/map/StreetMapTileRenderer.kt`
  → `app/src/main/java/com/flightalert/map/tiles/StreetMapTileRenderer.kt`
- Consolidate:
  `SatelliteBaseTilePolicy.kt` + `SatelliteBaseTileScheduler.kt`
  → `app/src/main/java/com/flightalert/map/tiles/SatelliteTileScheduling.kt`
- Modify: `app/src/main/java/com/flightalert/map/MapTileRenderer.kt`
- Modify imports in current callers.

**Interfaces:**
- Consumes: root `Viewport`, `TileSource`, `MapTileRenderState`, `MapTileRenderStyle`,
  `MapProjection`, and shared map constants.
- Produces: package `com.flightalert.map.tiles`; root `MapTileRenderer` remains the facade
  consumed by `FlightMapView`.

- [ ] **Step 1: Run the structural check and verify that it initially fails**

```powershell
$expected = @(
  'app/src/main/java/com/flightalert/map/tiles/MapTileDiskCache.kt',
  'app/src/main/java/com/flightalert/map/tiles/SatelliteTileScheduling.kt'
)
$missing = $expected | Where-Object { !(Test-Path -LiteralPath $_) }
if ($missing.Count -eq 0) { throw 'Expected the pre-move structural check to fail' }
$missing
```

Expected: both paths are reported missing.

- [ ] **Step 2: Move the files with Git history**

```powershell
New-Item -ItemType Directory `
  -Path app/src/main/java/com/flightalert/map/tiles -Force | Out-Null
git mv app/src/main/java/com/flightalert/map/ReferencePolicyEncoding.kt `
  app/src/main/java/com/flightalert/map/MapPresentationEncoding.kt
git mv app/src/main/java/com/flightalert/map/MapLayerPanCompositor.kt `
  app/src/main/java/com/flightalert/map/tiles/MapLayerPanCompositor.kt
git mv app/src/main/java/com/flightalert/map/MapTileDiskCache.kt `
  app/src/main/java/com/flightalert/map/tiles/MapTileDiskCache.kt
git mv app/src/main/java/com/flightalert/map/SatelliteBaseTileRenderer.kt `
  app/src/main/java/com/flightalert/map/tiles/SatelliteBaseTileRenderer.kt
git mv app/src/main/java/com/flightalert/map/SatelliteMapTileRenderer.kt `
  app/src/main/java/com/flightalert/map/tiles/SatelliteMapTileRenderer.kt
git mv app/src/main/java/com/flightalert/map/StreetMapTileRenderer.kt `
  app/src/main/java/com/flightalert/map/tiles/StreetMapTileRenderer.kt
```

- [ ] **Step 3: Consolidate satellite request ordering without changing logic**

Create `tiles/SatelliteTileScheduling.kt` with:

```kotlin
package com.flightalert.map.tiles

internal enum class SatelliteBaseRequestKind {
    VISIBLE_PARENT,
    VISIBLE_EXACT,
    UPPER_PREFETCH,
    BUFFER_EXACT,
    BUFFER_PARENT
}

internal data class SatelliteBaseTileRequestOrder(
    val generation: Long,
    val kind: SatelliteBaseRequestKind,
    val parentDepth: Int = 0,
    val sequence: Long
) {
    val priority: Int = SatelliteBaseTilePolicy.priority(kind, parentDepth)
}
```

Append the existing `SatelliteBaseTilePolicy` and `SatelliteBaseTileScheduler` bodies
unchanged beneath these declarations, then remove their old files with `git rm`.

- [ ] **Step 4: Update package declarations and imports**

Set every moved tile file to:

```kotlin
package com.flightalert.map.tiles
```

Import required shared types from `com.flightalert.map`. Update `MapTileRenderer.kt` to
import the three renderer classes from `com.flightalert.map.tiles`. Keep
`MapPresentationEncoding.kt` in `package com.flightalert.map`. Move the existing
`policy_require` function from `ReferencePresentationPolicy.kt` into
`MapPresentationEncoding.kt` so sourced text and reference presentation both depend on the
root primitive rather than making the root package depend on `map.reference`.

- [ ] **Step 5: Run the focused structural and build checks**

```powershell
$tileFiles = rg --files app/src/main/java/com/flightalert/map/tiles
if ($tileFiles.Count -ne 6) { throw "Expected 6 tile files, found $($tileFiles.Count)" }
rg -n '^package com\.flightalert\.map$' app/src/main/java/com/flightalert/map/tiles
.\gradlew.bat assembleDebug --no-daemon
```

Expected: six tile files, no flat-package declaration under `tiles`, and `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```powershell
git add -- app/src/main/java/com/flightalert/map
git diff --cached --check
git commit -m 'Organize map tile rendering'
```

---

### Task 3: Move aviation layers into their package

**Files:**
- Move all eight approved aviation files from `map/` to `map/aviation_layers/`.
- Modify: `app/src/main/java/com/flightalert/FlightMapView.kt`
- Modify any non-map caller importing an aviation type.

**Interfaces:**
- Consumes: root map geometry and sourced-text primitives.
- Produces: `com.flightalert.map.aviation_layers` with the existing controller, client,
  source, renderer, inspector, selection, model, and text-adapter APIs.

- [ ] **Step 1: Verify the target package does not exist**

```powershell
if (Test-Path app/src/main/java/com/flightalert/map/aviation_layers) {
  throw 'aviation_layers unexpectedly exists before the move'
}
```

- [ ] **Step 2: Move the eight files**

```powershell
New-Item -ItemType Directory `
  -Path app/src/main/java/com/flightalert/map/aviation_layers -Force | Out-Null
@(
  'AviationLayerClient.kt',
  'AviationLayerController.kt',
  'AviationLayerInspector.kt',
  'AviationLayerModels.kt',
  'AviationLayerRenderer.kt',
  'AviationLayerSelection.kt',
  'AviationLayerSources.kt',
  'AviationMapTextAdapter.kt'
) | ForEach-Object {
  git mv "app/src/main/java/com/flightalert/map/$_" `
    "app/src/main/java/com/flightalert/map/aviation_layers/$_"
}
```

- [ ] **Step 3: Update the package and import boundary**

Set every moved file to:

```kotlin
package com.flightalert.map.aviation_layers
```

Import root geometry and sourced-text types explicitly. Replace caller imports from
the old root imports for types whose names begin with `Aviation` with imports from
`com.flightalert.map.aviation_layers`.
Do not introduce an aviation facade.

- [ ] **Step 4: Verify package membership and compile**

```powershell
$files = rg --files app/src/main/java/com/flightalert/map/aviation_layers
if ($files.Count -ne 8) { throw "Expected 8 aviation-layer files, found $($files.Count)" }
rg -n '^package com\.flightalert\.map$' app/src/main/java/com/flightalert/map/aviation_layers
rg -n 'import com\.flightalert\.map\.Aviation' app/src/main/java
.\gradlew.bat assembleDebug --no-daemon
```

Expected: eight files, no old package declarations or old imports, and `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```powershell
git add -- app/src/main/java/com/flightalert
git diff --cached --check
git commit -m 'Organize aviation map layers'
```

---

### Task 4: Move existing reference files and perform approved consolidations

**Files:**
- Move shared presentation and retained files into `map/reference/`.
- Move nine existing dictionary files into `map/reference/dictionary/`.
- Move three existing border files into `map/reference/borders/`.
- Move eleven existing label files into `map/reference/labels/`.
- Consolidate the two line-label files as `ReferenceLineLabelPlacement.kt`.
- Modify all callers and imports.

**Interfaces:**
- Consumes: root map geometry, text, and encoding primitives.
- Produces: the approved reference package tree while
  `ReferenceDictionaryOverlayRenderer` still contains the existing algorithms.

- [ ] **Step 1: Run the pre-move structural failure check**

```powershell
$expected = @(
  'app/src/main/java/com/flightalert/map/reference/dictionary/ReferenceDictionaryPackage.kt',
  'app/src/main/java/com/flightalert/map/reference/borders/ReferenceBoundaryRasterPolicy.kt',
  'app/src/main/java/com/flightalert/map/reference/labels/ReferencePathLabelPlanner.kt'
)
$missing = $expected | Where-Object { !(Test-Path -LiteralPath $_) }
if ($missing.Count -ne 3) { throw 'Unexpected reference package baseline' }
```

- [ ] **Step 2: Create the approved directories**

```powershell
@(
  'app/src/main/java/com/flightalert/map/reference',
  'app/src/main/java/com/flightalert/map/reference/dictionary',
  'app/src/main/java/com/flightalert/map/reference/borders',
  'app/src/main/java/com/flightalert/map/reference/labels'
) | ForEach-Object { New-Item -ItemType Directory -Path $_ -Force | Out-Null }
```

- [ ] **Step 3: Move shared reference and retained files**

Move these files into `map/reference/` with `git mv`:

```text
ReferenceDictionaryOverlayRenderer.kt
ReferencePolicyCanonicalJson.kt
ReferencePresentationPolicy.kt
ReferencePresentationTables.kt
ReferenceRetainedBitmapCoordinator.kt
ReferenceRetainedCoverage.kt
ReferenceRetainedLabelTransition.kt
ReferenceRetainedLabelVisibility.kt
ReferenceRetainedSceneHistory.kt
```

Set their package to `com.flightalert.map.reference`.

- [ ] **Step 4: Move dictionary files**

Move these files into `map/reference/dictionary/`:

```text
ReferenceClassCatalog.kt
ReferenceDictionaryBinaryPresentation.kt
ReferenceDictionaryBinaryTile.kt
ReferenceDictionaryIndexReader.kt
ReferenceDictionaryLodPolicy.kt
ReferenceDictionaryPackage.kt
ReferenceDictionaryRenderTile.kt
ReferenceDictionarySegmentedFile.kt
ReferenceDictionaryTileMemoryCache.kt
```

Set their package to `com.flightalert.map.reference.dictionary`.

- [ ] **Step 5: Move border files**

Move these files into `map/reference/borders/`:

```text
ReferenceBoundaryOccurrenceSelector.kt
ReferenceBoundaryRasterDiskPolicy.kt
ReferenceBoundaryRasterPolicy.kt
```

Set their package to `com.flightalert.map.reference.borders`.

- [ ] **Step 6: Move label files and consolidate line-label placement**

Move these files into `map/reference/labels/`:

```text
ReferenceLabelAdmissionPolicy.kt
ReferenceLabelAvoidance.kt
ReferenceLabelLayoutSelector.kt
ReferenceLabelRuntimePresentationPolicy.kt
ReferenceLowZoomCountryPolicy.kt
ReferencePathLabelPlanner.kt
ReferenceProminencePolicy.kt
ReferenceScreenSegmentAabbIndex.kt
ReferenceVisiblePathProjector.kt
```

Create `ReferenceLineLabelPlacement.kt` in the same package by placing the existing
`ReferenceLineLabelFitPolicy`, `ReferenceLineLabelPlacementOption`, and
`ReferenceLineLabelPlacementAdapter` declarations together without changing their bodies.
Remove the two old files.

- [ ] **Step 7: Update imports mechanically by ownership**

Use these package prefixes:

```text
com.flightalert.map.reference
com.flightalert.map.reference.dictionary
com.flightalert.map.reference.borders
com.flightalert.map.reference.labels
```

Update `FlightMapView`, UI reference-filter files, tile renderers, and reference files.
Keep `MapGeometry`, sourced text, and `MapPresentationEncoding` imports at the root.

- [ ] **Step 8: Verify the tree and compile**

```powershell
rg -n '^package com\.flightalert\.map$' app/src/main/java/com/flightalert/map/reference
if (Test-Path app/src/main/java/com/flightalert/map/reference/policy) {
  throw 'A policy directory must not exist'
}
if (Test-Path app/src/main/java/com/flightalert/map/reference/retained) {
  throw 'Retained files must remain directly under reference'
}
.\gradlew.bat assembleDebug --no-daemon
```

Expected: no stale flat package declaration, no rejected folders, and `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```powershell
git add -- app/src/main/java/com/flightalert
git diff --cached --check
git commit -m 'Organize reference map components'
```

---

### Task 5: Extract dictionary content, decoding, and repository ownership

**Files:**
- Create: `app/src/main/java/com/flightalert/map/reference/dictionary/ReferenceDictionaryContent.kt`
- Create: `app/src/main/java/com/flightalert/map/reference/dictionary/ReferenceDictionaryTileDecoder.kt`
- Create: `app/src/main/java/com/flightalert/map/reference/dictionary/ReferenceTileRepository.kt`
- Modify: `app/src/main/java/com/flightalert/map/reference/ReferenceDictionaryOverlayRenderer.kt`

**Interfaces:**
- Consumes: existing package store, binary presentation, binary tile, render-tile, memory
  cache, and LOD types.
- Produces:
  - `ReferenceDictionaryContent` declarations for decoded line, label, geometry, style, and
    tile records.
  - `ReferenceDictionaryTileDecoder.decode(
    payload: ByteArray,
    outlineVisibilityCentizoom: Int,
    requestIsRelevant: () -> Boolean,
    ): ParsedDictionaryTile?`.
  - `ReferenceTileRepository.prepare()`, `package_check_pending()`, `class_catalog()`,
    `content_revision()`, `clear()`, and `close()`.
  - `ReferenceTileRepository.request_tile_if_needed(
    tile: VisibleDictionaryTile,
    generation: Long,
    additionallyRelevant: (() -> Boolean)? = null,
    ): Int`.

- [ ] **Step 1: Establish a failing size/ownership check**

```powershell
$overlay = 'app/src/main/java/com/flightalert/map/reference/ReferenceDictionaryOverlayRenderer.kt'
$lines = (Get-Content -LiteralPath $overlay).Count
if ($lines -lt 7000) { throw "Expected the unsplit overlay, found $lines lines" }
@(
  'ReferenceDictionaryContent.kt',
  'ReferenceDictionaryTileDecoder.kt',
  'ReferenceTileRepository.kt'
) | ForEach-Object {
  if (Test-Path "app/src/main/java/com/flightalert/map/reference/dictionary/$_") {
    throw "$_ unexpectedly exists before extraction"
  }
}
```

- [ ] **Step 2: Extract decoded content declarations**

Move the existing private declarations for parsed tiles, line records, label records,
geometry, rings, bounds, points, screen points, line styles, label styles, label templates,
layer groups, and draw references into `ReferenceDictionaryContent.kt`.

Use `internal` visibility only where a border, label, retained, or repository collaborator
must consume a declaration. Keep all other declarations private to that file.

- [ ] **Step 3: Extract tile decoding**

Move the existing payload functions beginning at `parse_tile_payload` through the final
legacy parsing and style-resolution helpers into `ReferenceDictionaryTileDecoder`.
The public extraction boundary is:

```kotlin
internal class ReferenceDictionaryTileDecoder {
    fun decode(
        payload: ByteArray,
        outlineVisibilityCentizoom: Int,
        requestIsRelevant: () -> Boolean,
    ): ParsedDictionaryTile?
}
```

The method must dispatch to the existing binary or legacy decoder exactly as before. Move
existing constants with the decoder; do not rewrite parsing loops.

- [ ] **Step 4: Extract repository state and lifecycle**

Move these existing fields and their methods together into `ReferenceTileRepository`:

```text
package_store
package_snapshot
package_retry_after_ms
package_probe_pending
package_generation
package_lifecycle_lock
content_revision
request_generation
tile_cache
requested_tiles
desired_tile_keys
next_desired_tile_keys
zoom_ahead_desired_tile_keys
next_zoom_ahead_desired_tile_keys
tile_executor
worker_id used by tile workers
```

Move package probing, tile request queuing, desired-key tracking, visibility selection, cache
publication, and repository clear/close methods unchanged. Inject the existing decoder and
`request_redraw` callback through the constructor.

- [ ] **Step 5: Reconnect the coordinator without changing draw order**

Construct one repository in `ReferenceDictionaryOverlayRenderer`. Replace direct field
access with repository calls. Keep the coordinator's `draw`, `prepare`,
`package_check_pending`, `reference_class_catalog`, `content_revision`, `clear`, and `close`
entry points unchanged for `SatelliteMapTileRenderer`.

- [ ] **Step 6: Run focused checks and build**

```powershell
rg -n 'parse_tile_payload|probe_package|request_tile_if_needed' `
  app/src/main/java/com/flightalert/map/reference/ReferenceDictionaryOverlayRenderer.kt
.\gradlew.bat assembleDebug --no-daemon
```

Expected: those responsibilities are absent from the coordinator and the build succeeds.

- [ ] **Step 7: Commit**

```powershell
git add -- app/src/main/java/com/flightalert/map/reference
git diff --cached --check
git commit -m 'Extract reference dictionary loading'
```

---

### Task 6: Extract boundary rendering

**Files:**
- Create: `app/src/main/java/com/flightalert/map/reference/borders/ReferenceBoundaryRenderer.kt`
- Modify: `app/src/main/java/com/flightalert/map/reference/ReferenceDictionaryOverlayRenderer.kt`
- Modify shared content visibility only where required.

**Interfaces:**
- Consumes: decoded dictionary line content, viewport, layer groups, filter mask, tile
  repository generation, map tile disk cache, paints, density, and redraw callback.
- Produces:

```kotlin
internal data class BoundaryRasterDrawResult(
    val boundaries: Int,
    val ready: Boolean,
    val requested: Int,
)

internal class ReferenceBoundaryRenderer {
    fun draw(
        canvas: Canvas,
        viewport: Viewport,
        source_zoom: Int,
        groups: ReferenceDictionaryLayerGroups,
        filter_mask: ReferenceFilterMask,
        interaction_active: Boolean,
        tile_request_generation: Long,
        now_ms: Long,
        freeze_active_band: Boolean = false,
    ): ReferenceBoundaryDrawResult

    fun clear()
    fun close()
}
```

- [ ] **Step 1: Verify the boundary implementation is still in the coordinator**

```powershell
rg -n 'draw_boundary_raster_layer|render_boundary_raster|draw_boundary_records' `
  app/src/main/java/com/flightalert/map/reference/ReferenceDictionaryOverlayRenderer.kt
```

Expected: all three functions are present.

- [ ] **Step 2: Move boundary fields and private types as one unit**

Move boundary paths, occurrence buffers, raster cells, raster workspaces, executor, completed
queue, request sets, disk namespaces, raster caches, active/pending bands, safety scenes,
fade state, paint, and all `Boundary*` private declarations into
`ReferenceBoundaryRenderer`.

- [ ] **Step 3: Move boundary methods unchanged**

Move the boundary-raster band selection, cell building, safety request and promotion, disk
read/write, raster rendering, completion publication, cache trimming, fallback drawing,
boundary batching, paint setup, and path drawing methods. Preserve executor size, thread
priority, cache limits, PNG encoding, namespace calculation, paint passes, and sort order.

- [ ] **Step 4: Delegate from the coordinator**

Replace each boundary branch in `draw` with the exact `ReferenceBoundaryRenderer.draw`
result. Keep the same decision points and return statistics.

- [ ] **Step 5: Build and exercise borders**

```powershell
rg -n 'boundary_raster_executor|completed_boundary_rasters|BoundaryRasterWorkspace' `
  app/src/main/java/com/flightalert/map/reference/ReferenceDictionaryOverlayRenderer.kt
.\gradlew.bat assembleDebug --no-daemon
```

Expected: boundary ownership is absent from the coordinator and the build succeeds. Run the
app over Morocco with labels temporarily disabled through existing layer controls where
possible; record a short pan/zoom video and verify border continuity before proceeding.

- [ ] **Step 6: Commit**

```powershell
git add -- app/src/main/java/com/flightalert/map/reference
git diff --cached --check
git commit -m 'Extract reference boundary rendering'
```

---

### Task 7: Extract label rendering

**Files:**
- Create: `app/src/main/java/com/flightalert/map/reference/labels/ReferenceLabelRenderer.kt`
- Modify: `app/src/main/java/com/flightalert/map/reference/ReferenceDictionaryOverlayRenderer.kt`
- Modify dictionary content visibility only where required.

**Interfaces:**
- Consumes: decoded label content, viewport, label options, filter mask, avoid rectangles,
  paints, path workspaces, density functions, and retained label workspace.
- Produces:

```kotlin
internal class ReferenceLabelRenderer {
    fun draw_labels(
        canvas: Canvas,
        viewport: Viewport,
        tiles: List<DictionaryTileDrawRef>,
        label_text_scale: Float,
        groups: ReferenceDictionaryLayerGroups,
        filter_mask: ReferenceFilterMask,
        label_avoid_rects: List<ReferenceScreenRect>,
    ): Int

    fun plan_labels(
        workspace: LabelPlanningWorkspace,
        viewport: Viewport,
        tiles: List<DictionaryTileDrawRef>,
        label_text_scale: Float,
        groups: ReferenceDictionaryLayerGroups,
        filter_mask: ReferenceFilterMask,
        label_avoid_rects: List<ReferenceScreenRect>,
        include_buffer_tiles: Boolean = false,
        fixed_candidates: List<DictionaryLabelCandidate> = emptyList(),
        preferred_occurrences: Set<ReferenceLabelOccurrenceId> = emptySet(),
        preferred_candidate_keys: Set<ReferenceLabelCandidateKey> = emptySet(),
        excluded_rect: ReferenceScreenRect? = null,
        label_budget_override: Int? = null,
        protected_area_budget_override: Int? = null,
        keep_planning: () -> Boolean = ALWAYS_KEEP_LABEL_PLAN,
    ): Boolean

    fun draw_planned_labels(
        canvas: Canvas,
        workspace: LabelPlanningWorkspace,
    )
}
```

- [ ] **Step 1: Verify label ownership begins in the coordinator**

```powershell
rg -n 'plan_labels|line_label_candidates|point_label_candidate|draw_label_candidate' `
  app/src/main/java/com/flightalert/map/reference/ReferenceDictionaryOverlayRenderer.kt
```

Expected: all label functions are present.

- [ ] **Step 2: Move label workspaces and private types**

Move path, path measure, bounds, position buffer, UI label workspace, retained label
workspace, label-planning workspace, label record refs, label candidate, candidate key, label
style, template, and draw-count declarations into `ReferenceLabelRenderer` or
`ReferenceDictionaryContent` according to whether they represent behavior or decoded data.

- [ ] **Step 3: Move label methods without rewriting loops**

Move label record collection, candidate generation, line placement, point placement,
admission, budgets, measurement, text-on-path drawing, halo drawing, paint configuration,
anchors, projection, visibility checks, priority, hierarchy, and style resolution.

Keep existing mutable workspaces as fields. Do not replace them with temporary lists or
sequence operations.

- [ ] **Step 4: Delegate planning and drawing**

Replace coordinator label branches with `ReferenceLabelRenderer.plan_labels` and
`ReferenceLabelRenderer.draw_planned_labels`, preserving the current call order relative to
boundary and retained rendering.

- [ ] **Step 5: Build and exercise labels**

```powershell
rg -n 'line_label_path|ui_label_workspace|plan_labels|draw_label_candidate' `
  app/src/main/java/com/flightalert/map/reference/ReferenceDictionaryOverlayRenderer.kt
.\gradlew.bat assembleDebug --no-daemon
```

Expected: label ownership is absent from the coordinator and the build succeeds. Record a
short Morocco video through low, regional, and detailed zoom bands; reject labels that move
between geographic positions, change hierarchy, or appear in a different order.

- [ ] **Step 6: Commit**

```powershell
git add -- app/src/main/java/com/flightalert/map/reference
git diff --cached --check
git commit -m 'Extract reference label rendering'
```

---

### Task 8: Extract retained rendering and finish the coordinator

**Files:**
- Create: `app/src/main/java/com/flightalert/map/reference/ReferenceRetainedRenderer.kt`
- Modify: `app/src/main/java/com/flightalert/map/reference/ReferenceDictionaryOverlayRenderer.kt`
- Modify retained helper visibility only where required.

**Interfaces:**
- Consumes: boundary and label renderers, tile repository, retained helper functions,
  viewport, options, avoid rectangles, paints, density, and redraw callback.
- Produces:

```kotlin
internal class ReferenceRetainedRenderer {
    fun finish_completed_retained_label_frame(
        viewport: Viewport,
        target_tile_zoom: Int,
        fallback_tile_zoom: Int?,
        options: RetainedReferenceOptions,
        label_avoid_rects: List<ReferenceScreenRect>,
        interaction_active: Boolean,
    )

    fun retained_frame_for_viewport(
        canvas: Canvas,
        viewport: Viewport,
        options: RetainedReferenceOptions,
        target_tile_zoom: Int,
        fallback_tile_zoom: Int?,
        destination: ReferenceRetainedDestination,
    ): RetainedReferenceFrame?

    fun draw_retained_frame_with_transition(
        canvas: Canvas,
        viewport: Viewport,
        frame: RetainedReferenceFrame,
        now_ms: Long,
        destination: ReferenceRetainedDestination? = null,
    )

    fun stage_zoom_ahead_labels(
        viewport: Viewport,
        source_zoom: Int,
        labels_enabled: Boolean,
        label_text_scale: Float,
        place_labels_enabled: Boolean,
        water_labels_enabled: Boolean,
        region_labels_enabled: Boolean,
        public_lands_enabled: Boolean,
        filter_mask: ReferenceFilterMask,
        label_avoid_rects: List<ReferenceScreenRect>,
        options: RetainedReferenceOptions,
    ): Int

    fun reset()
    fun clear()
    fun close()
}
```

These methods carry the existing values directly; no new aggregate object may be allocated
per frame.

- [ ] **Step 1: Verify retained ownership begins in the coordinator**

```powershell
rg -n 'retained_bitmap_executor|retained_label_executor|retained_frame_history|stage_zoom_ahead_labels' `
  app/src/main/java/com/flightalert/map/reference/ReferenceDictionaryOverlayRenderer.kt
```

Expected: retained state and methods are present.

- [ ] **Step 2: Move retained fields and private types together**

Move retained executors, coordinators, retry state, destinations, target/fallback/partial
frames, fade, displayed frame, interaction state, history, retained options, frame content,
request keys, requests, prepared results, and fade declarations into
`ReferenceRetainedRenderer`.

- [ ] **Step 3: Move retained methods without changing timing**

Move label-frame request and completion, boundary bitmap preparation, retained-frame
creation, fallback selection, eligibility checks, destination calculation, zoom-ahead
staging, frame replacement, promotion, fade creation, partial-frame creation, drawing, reset,
clear, and close behavior.

Preserve executor priorities, cancellation ordering, fade timing, history limit, retry
timing, frame identity, and bitmap lifetime.

- [ ] **Step 4: Reduce the overlay to top-down coordination**

Keep in `ReferenceDictionaryOverlayRenderer`:

```text
public draw entry point
enabled-layer early return
package availability and zoom selection
high-level interaction and fallback decisions
delegation order
draw-stat aggregation
prepare/package-check/catalog/content-revision facade methods
clear/reset/close facade methods
```

The coordinator must own one repository, boundary renderer, label renderer, and retained
renderer. It must not own their executors, caches, workspaces, or record models.

- [ ] **Step 5: Run ownership, size, and build checks**

```powershell
$overlay = 'app/src/main/java/com/flightalert/map/reference/ReferenceDictionaryOverlayRenderer.kt'
$lines = (Get-Content -LiteralPath $overlay).Count
if ($lines -ge 2500) { throw "Coordinator remains too large at $lines lines" }
rg -n 'Executors\.|ConcurrentLinkedQueue|ThreadLocal<' $overlay
.\gradlew.bat assembleDebug --no-daemon
```

Expected: coordinator under 2,500 lines, no worker ownership, and `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```powershell
git add -- app/src/main/java/com/flightalert/map/reference
git diff --cached --check
git commit -m 'Extract retained reference rendering'
```

---

### Task 9: Final structural, build, runtime, and publication verification

**Files:**
- Verify all files under `app/src/main/java/com/flightalert/map/`.
- External evidence:
  `C:\Users\h\AndroidStudioProjects\FlightAlert-test-artifacts\map-reorganization\final\`

**Interfaces:**
- Consumes: all prior task commits and the baseline evidence.
- Produces: a clean, built, visually verified branch ready for `origin/master`.

- [ ] **Step 1: Run the final structural check**

```powershell
$required = @(
  'app/src/main/java/com/flightalert/map/tiles/SatelliteTileScheduling.kt',
  'app/src/main/java/com/flightalert/map/aviation_layers/AviationLayerController.kt',
  'app/src/main/java/com/flightalert/map/reference/ReferenceRetainedRenderer.kt',
  'app/src/main/java/com/flightalert/map/reference/dictionary/ReferenceTileRepository.kt',
  'app/src/main/java/com/flightalert/map/reference/borders/ReferenceBoundaryRenderer.kt',
  'app/src/main/java/com/flightalert/map/reference/labels/ReferenceLabelRenderer.kt'
)
$missing = $required | Where-Object { !(Test-Path -LiteralPath $_) }
if ($missing) { throw "Missing required paths: $($missing -join ', ')" }
if (Test-Path app/src/main/java/com/flightalert/map/reference/policy) {
  throw 'Rejected policy directory exists'
}
if (Test-Path app/src/main/java/com/flightalert/map/reference/retained) {
  throw 'Rejected retained directory exists'
}
```

- [ ] **Step 2: Verify package-path agreement and production comments**

```powershell
rg -n '^package com\.flightalert\.map$' `
  app/src/main/java/com/flightalert/map/tiles `
  app/src/main/java/com/flightalert/map/aviation_layers `
  app/src/main/java/com/flightalert/map/reference
rg -n -i 'agent|codex|workflow note|implementation plan|ponytail:' `
  app/src/main/java/com/flightalert/map
```

Expected: no stale package declarations and no agent/process comments. Review any domain
identifier match rather than deleting valid application terminology blindly.

- [ ] **Step 3: Run the exact final build**

```powershell
.\gradlew.bat clean assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Capture final motion and frame evidence**

Use the same device, application settings, Morocco viewport, gesture order, recording length,
and zoom-band sequence from Task 1:

```powershell
$evidence = 'C:\Users\h\AndroidStudioProjects\FlightAlert-test-artifacts\map-reorganization\final'
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
$deviceLines = adb devices -l |
  Select-Object -Skip 1 |
  Where-Object { $_ -match '\sdevice(\s|$)' }
$selectedLine = $deviceLines |
  Where-Object { $_ -notmatch '^emulator-' } |
  Select-Object -First 1
if (!$selectedLine) { $selectedLine = $deviceLines | Select-Object -First 1 }
if (!$selectedLine) { throw 'No physical phone or emulator is available' }
$serial = ($selectedLine -split '\s+')[0]
adb -s $serial install -r 'build\outputs\apk\debug\Flight Alert-debug.apk'
adb -s $serial shell am force-stop com.flightalert
adb -s $serial shell monkey -p com.flightalert 1
adb -s $serial shell dumpsys gfxinfo com.flightalert reset
$recording = Start-Process -FilePath adb -ArgumentList @(
  '-s', $serial,
  'shell', 'screenrecord',
  '--bit-rate', '12000000',
  '--time-limit', '180',
  '/sdcard/flightalert-map-reorganization-final.mp4'
) -WindowStyle Hidden -PassThru
```

Perform the six-step gesture and toggle sequence from Task 1, then finish:

```powershell
Wait-Process -Id $recording.Id
adb -s $serial pull /sdcard/flightalert-map-reorganization-final.mp4 `
  (Join-Path $evidence 'flightalert-map-reorganization-final.mp4')
adb -s $serial shell dumpsys gfxinfo com.flightalert framestats |
  Set-Content -LiteralPath (Join-Path $evidence 'gfxinfo-framestats.txt')
```

- [ ] **Step 5: Compare against the real goal**

Review both videos as motion, not as isolated frames. Verify:

```text
borders are present at maximum zoom-out
labels use the same hierarchy and geographic anchors
no regional blank section appears during pan or zoom
no new pause precedes border or label loading
retained labels do not jump between locations
frame pacing is not visibly or numerically worse
OFF shows neither borders nor labels
ON restores both layers
```

If a difference appears, identify the owning extracted component, restore the previous
ownership or state ordering, rebuild, and repeat only the affected zoom bands plus one
neighboring band.

- [ ] **Step 6: Verify the final diff and commit any validation-driven correction**

```powershell
git status --short
git diff --check
git log --oneline --decorate -12
```

Do not commit external videos, frame data, ignored Gradle scaffolding, or build output.

- [ ] **Step 7: Publish only the verified branch**

Fetch and confirm `origin/master` is still an ancestor:

```powershell
git fetch origin master
git merge-base --is-ancestor origin/master HEAD
if ($LASTEXITCODE -ne 0) { throw 'origin/master changed incompatibly; rebase before pushing' }
git push origin HEAD:refs/heads/master
git ls-remote origin refs/heads/master
git rev-parse HEAD
```

Expected: the remote and local commit IDs match.
