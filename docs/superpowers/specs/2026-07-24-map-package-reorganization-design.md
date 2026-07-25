# Map Package Reorganization Design

## Goal

Reorganize `com.flightalert.map` so that its directory and package structure exposes the
major map concepts directly, related code is consolidated where that improves reading, and
the 7,306-line reference overlay renderer is divided into complete responsibilities.

This is an organizational change. Rendering behavior, visual output, loading latency, frame
pacing, cache behavior, threading, and settings behavior must remain unchanged.

## Readability Direction

Flight Alert is large enough to require structure based on its own dependency graph rather
than imitating a smaller project. The applicable readability principles are:

- Put top-level coordination before implementation detail.
- Give files and packages names that identify complete ideas.
- Keep descriptive names instead of introducing shorthand.
- Consolidate files only when their contents form one concept.
- Split files when independent responsibilities have become interleaved.
- Apply folder decisions contextually rather than through a universal file-count rule.

Production Kotlin comments may document domain behavior, performance constraints, or
non-obvious invariants. They must not contain instructions, workflow notes, or reminders for
agents.

## Package Structure

Every directory below is a real Kotlin package. File paths and package declarations must
agree.

```text
com/flightalert/map/
  DeviceHeadingProvider.kt
  MapGeometry.kt
  MapPresentationEncoding.kt
  MapTileRenderer.kt
  SourcedMapText.kt
  SourcedMapTextPolicy.kt
  SourcedTextUnicode17Profile.kt

  tiles/
    MapLayerPanCompositor.kt
    MapTileDiskCache.kt
    SatelliteBaseTileRenderer.kt
    SatelliteMapTileRenderer.kt
    SatelliteTileScheduling.kt
    StreetMapTileRenderer.kt

  aviation_layers/
    AviationLayerClient.kt
    AviationLayerController.kt
    AviationLayerInspector.kt
    AviationLayerModels.kt
    AviationLayerRenderer.kt
    AviationLayerSelection.kt
    AviationLayerSources.kt
    AviationMapTextAdapter.kt

  reference/
    ReferenceDictionaryOverlayRenderer.kt
    ReferencePolicyCanonicalJson.kt
    ReferencePresentationPolicy.kt
    ReferencePresentationTables.kt
    ReferenceRetainedBitmapCoordinator.kt
    ReferenceRetainedCoverage.kt
    ReferenceRetainedLabelTransition.kt
    ReferenceRetainedLabelVisibility.kt
    ReferenceRetainedRenderer.kt
    ReferenceRetainedSceneHistory.kt

    dictionary/
      ReferenceClassCatalog.kt
      ReferenceDictionaryBinaryPresentation.kt
      ReferenceDictionaryBinaryTile.kt
      ReferenceDictionaryContent.kt
      ReferenceDictionaryIndexReader.kt
      ReferenceDictionaryLodPolicy.kt
      ReferenceDictionaryPackage.kt
      ReferenceDictionaryRenderTile.kt
      ReferenceDictionarySegmentedFile.kt
      ReferenceDictionaryTileDecoder.kt
      ReferenceDictionaryTileMemoryCache.kt
      ReferenceTileRepository.kt

    borders/
      ReferenceBoundaryOccurrenceSelector.kt
      ReferenceBoundaryRasterDiskPolicy.kt
      ReferenceBoundaryRasterPolicy.kt
      ReferenceBoundaryRenderer.kt

    labels/
      ReferenceLabelAdmissionPolicy.kt
      ReferenceLabelAvoidance.kt
      ReferenceLabelLayoutSelector.kt
      ReferenceLabelRenderer.kt
      ReferenceLabelRuntimePresentationPolicy.kt
      ReferenceLineLabelPlacement.kt
      ReferenceLowZoomCountryPolicy.kt
      ReferencePathLabelPlanner.kt
      ReferenceProminencePolicy.kt
      ReferenceScreenSegmentAabbIndex.kt
      ReferenceVisiblePathProjector.kt
```

Retained-reference files remain directly under `reference/` because that arrangement makes
this package easier to scan. This is a decision for this package, not a general folder rule.

## Existing File Moves

### Root map package

- Keep `DeviceHeadingProvider.kt`, `MapGeometry.kt`, `MapTileRenderer.kt`,
  `SourcedMapText.kt`, `SourcedMapTextPolicy.kt`, and
  `SourcedTextUnicode17Profile.kt` at the map root.
- Rename `ReferencePolicyEncoding.kt` to `MapPresentationEncoding.kt` because its byte and
  hash primitives are shared by sourced map text and reference presentation code.

### Tile rendering

- Move `MapLayerPanCompositor.kt`, `MapTileDiskCache.kt`,
  `SatelliteBaseTileRenderer.kt`, `SatelliteMapTileRenderer.kt`, and
  `StreetMapTileRenderer.kt` into `map.tiles`.
- Combine `SatelliteBaseTilePolicy.kt` and `SatelliteBaseTileScheduler.kt` as
  `tiles/SatelliteTileScheduling.kt`. Request kinds, priority calculation, request-order
  values, and comparison remain together.

### Aviation layers

- Move every `AviationLayer*.kt` file and `AviationMapTextAdapter.kt` into
  `map.aviation_layers`.
- Preserve the existing controller-client-source-renderer flow. The package move must not
  introduce a second facade or an interface layer.

### Reference dictionary

- Move package discovery, package reading, binary presentation, binary tile decoding,
  segmented-file access, index reading, class catalog, render-tile models, LOD selection,
  and the memory cache into `map.reference.dictionary`.
- Extract the overlay renderer's package lifecycle, tile request queue, desired-key tracking,
  and tile memory-cache ownership into `ReferenceTileRepository`.
- Extract binary and legacy payload parsing into `ReferenceDictionaryTileDecoder`.
- Extract the decoded boundary, label, geometry, style, and tile content records into
  `ReferenceDictionaryContent`.

### Reference borders

- Move boundary occurrence selection and boundary raster rules into
  `map.reference.borders`.
- Extract boundary batching, raster requests, disk-raster access, safety scenes, continuity,
  fades, cache trimming, and boundary drawing into `ReferenceBoundaryRenderer`.
- Preserve the current raster executor, cache limits, disk namespace, draw order, paint
  treatment, and fallback behavior.

### Reference labels

- Move label planning, layout selection, runtime presentation, prominence, path projection,
  screen-segment indexing, admission, avoidance, and low-zoom country selection into
  `map.reference.labels`.
- Combine `ReferenceLineLabelFitPolicy.kt` and
  `ReferenceLineLabelPlacementAdapter.kt` as
  `labels/ReferenceLineLabelPlacement.kt`.
- Extract candidate generation, line and point placement, admission, budgeting, text
  measurement, halo drawing, and label drawing into `ReferenceLabelRenderer`.
- Preserve label identity, stable anchors, priority, collision behavior, world-copy
  selection, typography, and draw order.

### Retained reference rendering

- Keep retained coverage, label visibility, label transition, scene history, and bitmap
  coordination as files directly under `map.reference`.
- Extract retained frame creation, bitmap preparation, label-frame preparation, target and
  fallback selection, zoom-ahead staging, transitions, history, and retained drawing into
  `ReferenceRetainedRenderer`.
- Preserve the retained bitmap and retained label executors, current cancellation semantics,
  frame reuse, fade timing, and interaction behavior.

### Shared reference presentation

- Keep cross-cutting presentation definitions, tables, and canonical JSON directly under
  `map.reference`.
- Border-only rules live under `borders`; label-only rules live under `labels`; dictionary
  storage and LOD rules live under `dictionary`.
- Do not create a generic policy directory.

## Overlay Coordinator

`ReferenceDictionaryOverlayRenderer` remains the single object owned by
`SatelliteMapTileRenderer`. It becomes a top-down coordinator responsible for:

1. Receiving the viewport and enabled-layer state.
2. Asking `ReferenceTileRepository` for the visible decoded content.
3. Delegating boundary work to `ReferenceBoundaryRenderer`.
4. Delegating label work to `ReferenceLabelRenderer`.
5. Delegating retained-frame preparation and presentation to
   `ReferenceRetainedRenderer`.
6. Combining the existing draw statistics.
7. Coordinating clear, reset, close, package preparation, and content revision.

The collaborators are concrete internal classes. No interfaces, factories, dependency
injection framework, or speculative extension points are introduced.

## Dependency Direction

```text
FlightMapView
  -> map.MapTileRenderer
    -> map.tiles
      -> map.tiles.SatelliteMapTileRenderer
        -> map.reference.ReferenceDictionaryOverlayRenderer
          -> map.reference.dictionary
          -> map.reference.borders
          -> map.reference.labels
          -> map.reference retained components

FlightMapView
  -> map.aviation_layers
```

The root map package contains the shared geometry, text provenance, encoding primitives, and
map renderer facade. Domain packages may depend on those root primitives. Domain packages
must not depend on `FlightMapView`, and lower-level reference packages must not depend on the
overlay coordinator.

## Performance and Behavior Constraints

- Do not change rendering algorithms, thresholds, ordering, style values, timing constants,
  cache capacities, or package formats.
- Do not add allocations to per-frame drawing, panning, zooming, label admission, or boundary
  raster loops.
- Move existing reusable buffers, caches, locks, generations, and executor ownership into
  the collaborator that performs the corresponding work.
- Do not replace cached mutable workspaces with temporary collections.
- Do not add try-catch blocks to rendering or interaction hot paths.
- Preserve package-probe, tile-request, raster-request, retained-frame, cancellation, clear,
  and shutdown behavior.
- Preserve the borders-and-labels switch: OFF performs no reference package probing, catalog
  access, loading, or rendering and clears active dictionary work; ON restores both layers.
- Do not change the visual hierarchy or loading behavior at any zoom level.

## Implementation Order

1. Establish a baseline build and real-device motion recording from the current master.
2. Move shared root encoding and the tile package; compile.
3. Move the aviation layer package; compile.
4. Move reference presentation and dictionary files; compile.
5. Move border and label helper files and perform the two approved consolidations; compile.
6. Extract dictionary content, decoding, and repository ownership from the overlay
   coordinator; compile.
7. Extract boundary rendering; compile and exercise borders.
8. Extract label rendering; compile and exercise labels.
9. Extract retained rendering; compile and exercise pan and zoom continuity.
10. Run final build, structural checks, and real-device validation.

Each stage must remain reviewable and buildable. Package moves and behavioral extraction must
not be mixed with rendering optimizations.

## Validation

### Structural checks

- All Kotlin file paths match their package declarations.
- No obsolete Kotlin files remain in the old flat `map` directory.
- The map root contains only the approved shared entry and primitive files.
- There is no `policy` directory.
- Retained files are directly under `reference`.
- No production Kotlin comment contains agent or workflow instructions.

### Build checks

- Run `assembleDebug` after each independently moved or extracted subsystem.
- Run the final build from the exact branch intended for publication.
- Treat successful compilation as necessary but not sufficient for rendering acceptance.

### Runtime checks

- Use the physical phone when available; use the emulator only when the phone is unavailable.
- Record video rather than screenshots for all motion validation.
- Use dense land coverage, including Morocco, rather than ocean or empty zoom states.
- Exercise every zoom band in which the reference dictionary is used, including the
  zoom-zero-through-three presentation that uses zoom-four data.
- Repeat representative pan, pinch-in, and pinch-out sequences with the reference switch ON.
- Verify borders, label text, stable label positions, retained continuity, draw order, and
  absence of sudden jumps or blank regional sections.
- Repeat with the reference switch OFF and verify that neither borders nor labels appear and
  that reference dictionary work remains inactive.
- Compare cold loading latency, warmed loading latency, and frame pacing with the baseline.
- Reject or revise any extraction that causes visual degradation, additional loading delay,
  lower frame pacing, or zoom-band-specific regressions.

## Completion Criteria

The work is complete when the approved package tree is present, the overlay responsibilities
are separated as described, the approved consolidations are complete, the exact publication
branch builds, and real-device video plus performance observations show no visual, latency,
or frame-pacing regression across the reference dictionary's active zoom range.
