# Reference Scene Handoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep borders and labels geographically anchored and continuously represented while a complete destination scene is prepared.

**Architecture:** Reuse the current desired-tile ledger, boundary raster cache, retained target/fallback scenes, and stable occurrence transitions. Preserve work already queued before a gesture and prepare the existing lower-LOD label cover while idle; add no gesture-time decode, raster, or label planning.

**Tech Stack:** Kotlin, Android Canvas, existing reference package workers/caches, PowerShell source guard, Gradle/JUnit, ADB real-phone video.

## Global Constraints

- No try/catch in hot code.
- No new dependency, executor, cache manager, per-frame collection, or production diagnostic.
- A label remains attached to its geographic anchor during motion.
- Final temporal proof uses dense-land real-phone video across meaningful reference zoom bands.

---

### Task 1: Preserve Prepared Work and Build the Existing Fallback Scene

**Files:**
- Create outside Git: `.superpowers/sdd/reference-handoff/verify-reference-handoff.ps1`
- Modify: `app/src/main/java/com/flightalert/map/ReferenceDictionaryOverlayRenderer.kt:274-316`
- Modify: `app/src/main/java/com/flightalert/map/ReferenceDictionaryOverlayRenderer.kt:626-652`
- Modify: `app/src/main/java/com/flightalert/map/ReferenceDictionaryOverlayRenderer.kt:1088-1115`

The external guard remains at the path above with SHA-256
`A87E2F269BD6035AD8BFAC1CB08ADC0234B21E0FBAE965F46831959338535B86`.
The injected reference suite remains under `.superpowers/sdd/reference-v5/tests/`;
its `tests.init.gradle` entrypoint has SHA-256
`0421D481EA38BD989996A18315DF45AD35E261C5381FCB0A7D5C0E082B22AC8F`.

**Interfaces:**
- Consumes: `request_generation`, `relevant_boundary_raster_keys`, `fallback_visible_tiles`, `fallback_draw_tiles`, and `retained_label_coordinator`.
- Produces: preserved pre-gesture tile/raster work and an idle-prepared `RetainedFrameRole.FALLBACK` scene.

- [x] **Step 1: Write the failing source guard**

Create a PowerShell check that reads `ReferenceDictionaryOverlayRenderer.kt` and
requires these three snippets:

```powershell
Assert-Contains 'val generation = request_generation.get()'
Assert-Contains 'HashSet<ReferenceBoundaryRasterKey>(relevant_boundary_raster_keys)'
Assert-Contains 'fallback_ready && !retained_label_coordinator.hasCurrentRequest()'
```

It must also reject `update_desired_tile_keys(include_target_tiles = false)`.

- [x] **Step 2: Run the guard and verify RED**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .superpowers\sdd\reference-handoff\verify-reference-handoff.ps1
```

Expected: failure because the defer branch still clears desired tiles and the idle
fallback request does not exist.

- [x] **Step 3: Preserve queued tile and raster work**

In the retained defer branch, replace:

```kotlin
val generation = update_desired_tile_keys(include_target_tiles = false)
```

with:

```kotlin
val generation = request_generation.get()
```

When raster work is suspended, seed the new relevance set from the previous one:

```kotlin
val relevant_keys = if (raster_work_suspended) {
    HashSet<ReferenceBoundaryRasterKey>(relevant_boundary_raster_keys)
} else {
    HashSet(boundary_raster_cells.size + boundary_raster_draw_cells.size)
}
```

This permits already-started workers to finish without scheduling newly exposed
raster cells during the gesture.

- [x] **Step 4: Prepare the lower-LOD label cover while idle**

Immediately before returning an exact retained target frame, request the ready
fallback frame only when no label request is active:

```kotlin
if (fallback_needs_build && fallback_ready &&
    !retained_label_coordinator.hasCurrentRequest()
) {
    request_retained_label_frame(
        role = RetainedFrameRole.FALLBACK,
        viewport = viewport,
        frame_viewport = requireNotNull(fallback_viewport),
        tile_zoom = requireNotNull(fallback_zoom),
        tiles = fallback_draw_tiles,
        labels_enabled = labels_enabled,
        label_text_scale = label_text_scale,
        place_labels_enabled = place_labels_enabled,
        water_labels_enabled = water_labels_enabled,
        region_labels_enabled = region_labels_enabled,
        public_lands_enabled = public_lands_enabled,
        filter_mask = filter_mask,
        label_avoid_rects = label_avoid_rects,
        options = options,
        package_generation_snapshot = package_generation.get(),
    )
}
```

The existing preferred-occurrence planner and atomic publication remain unchanged.

- [x] **Step 5: Run the guard and focused reference suite**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .superpowers\sdd\reference-handoff\verify-reference-handoff.ps1
.\gradlew.bat -I .superpowers\sdd\reference-v5\tests.init.gradle testDebugUnitTest
```

Expected: source guard passes and all injected reference tests pass.

- [x] **Step 6: Run build verification**

Run:

```powershell
.\gradlew.bat assembleDebug lintDebug
```

Expected: build and lint complete successfully.

### Task 2: Real-Phone Temporal Acceptance

**Files:**
- Create outside Git: `C:\Users\h\OneDrive\Documents\Flight Alert\artifact-work\readable-rebase\reference-handoff\*.mp4`

**Interfaces:**
- Consumes: exact debug APK, physical Fold, Morocco dense-land fixtures, timed gesture harness.
- Produces: continuous-motion evidence for z2.0, z5.8, z7.5, and z10.5.

- [x] **Step 1: Install the exact APK and preserve device preferences**

Back up and hash the current app preferences, install the APK, set the Morocco
test location, and verify each exact zoom readback before recording.

- [x] **Step 2: Record meaningful motion**

Record pan, pinch, reversal, and settled tail at every band. At z5.8 use the
existing large-pan harness so newly exposed coverage exceeds retained padding.

- [x] **Step 3: Inspect the MP4 sequences**

Require borders and labels to remain attached to geography, continuing labels not
to relocate, no blank reference strip, no wholesale post-gesture swap, and no
material frame-pacing regression. Screenshots are not evidence.

- [x] **Step 4: Conditional border-band fix not needed**

If borders alone outrun the existing one-cell ring, add one idle-prepared parent
boundary band through the existing cache/history and repeat only the failed band
before the full final run. Do not enable raster allocation during gesture motion.
The 660 px z5.8 land-pan video retained borders and labels without a blank strip,
so that conditional production change was not made.

- [x] **Step 5: Restore the device and review the exact diff**

Restore preference hashes, location providers, authorization, and temporary files.
Run `git diff --check`, inspect the scoped diff, and commit only the renderer plus
approved documentation.
