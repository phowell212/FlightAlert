# Reference Label Layout Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make prominent sourced labels such as Chester River reliably appear at phone-appropriate size while preserving source geometry, prominence order, feature-specific styling, and zero visual degeneracy.

**Architecture:** Add one pure Kotlin layout selector between path planning and Android drawing. The selector groups alternate placements by typed source occurrence, removes duplicate tile memberships before ranking, and performs exact box/polyline collision checks; the renderer retains every ranked planner placement and draws only the selected placement. The installed whole-world r15 package and its hash-bound presentation tables remain unchanged for this pass.

**Tech Stack:** Kotlin/JVM, Android Canvas/Path, JUnit 4 repository tests, Gradle Android application build, physical Samsung phone video/screenshot evidence.

## Global Constraints

- Use the installed whole-world r15 package; do not restart a cook or create another active package root.
- Preserve exact source text and geometry. Never invent, translate, or move a name without admitted source data.
- A structural or performance win with missing, crushed, fragmented, overlapping, or unstable text is a failure.
- Preserve prominence ordering and distinct styles for rivers, streams, cities, islands, regions, and other feature classes.
- Coastline outlines remain disabled by default.
- Non-Latin primary text remains above sourced English at the shared 76% italic secondary size; Latin-script names such as Spanish remain single-line.
- The first phone-size trial uses the existing minimum text scale `1.0`, replacing the current phone's oversized `1.35` test state only for the coordinated evidence run.
- Keep debugging and measurement code outside the production app.
- Do not mutate the phone without the atomic device lease; preserve the reference package, user preferences other than the intentional temporary text-scale test, running state, and USB stay-awake bit.

---

### Task 1: Pure Collision-Safe Layout Selection

**Files:**
- Create: `app/src/main/java/com/flightalert/map/ReferenceLabelLayoutSelector.kt`
- Create: `tools/experiment8/kotlin-test/java/com/flightalert/map/ReferenceLabelLayoutSelectorTest.kt`

**Interfaces:**
- Consumes: `ReferenceScreenRect` and `ReferencePathLabelPoint`.
- Produces: `ReferenceLabelOccurrenceId`, `ReferenceLabelCollisionShape`, `ReferenceLabelLayoutCandidate`, and `ReferenceLabelLayoutSelector.select(...)`.

- [x] **Step 1: Write three failing behavioral tests**

Create candidates through a tiny test data class implementing `ReferenceLabelLayoutCandidate`. Pin these exact cases:

```kotlin
@Test fun alternatePathPlacementIsTriedAfterTheFirstCollides() {
    val blocker = box(token = 1, occurrence = occurrence(1uL), priority = 1,
        rect = rect(40.0, 40.0, 80.0, 80.0))
    val first = path(token = 2, occurrence = occurrence(2uL), priority = 2,
        rank = 0, points = listOf(point(20.0, 60.0), point(100.0, 60.0)), radius = 5.0)
    val second = path(token = 3, occurrence = occurrence(2uL), priority = 2,
        rank = 1, points = listOf(point(20.0, 110.0), point(100.0, 110.0)), radius = 5.0)
    assertEquals(listOf(1, 3), select(blocker, first, second).map { it.token })
}

@Test fun duplicateTileMembershipConsumesOneBudgetSlot() {
    val duplicateA = box(token = 1, occurrence = occurrence(10uL), priority = 1,
        rect = rect(10.0, 10.0, 30.0, 30.0))
    val duplicateB = duplicateA.copy(token = 2)
    val distinct = box(token = 3, occurrence = occurrence(11uL), priority = 2,
        rect = rect(60.0, 10.0, 80.0, 30.0))
    assertEquals(listOf(1, 3), select(duplicateA, duplicateB, distinct, budget = 2).map { it.token })
}

@Test fun curvedPathIsNotRejectedByItsBroadBoundingBox() {
    val obstacle = rect(40.0, 40.0, 60.0, 60.0)
    val aroundObstacle = path(token = 1, occurrence = occurrence(20uL), priority = 1,
        rank = 0,
        points = listOf(point(0.0, 0.0), point(0.0, 100.0), point(100.0, 100.0)),
        radius = 5.0)
    assertEquals(listOf(1), select(aroundObstacle, avoid = listOf(obstacle)).map { it.token })
}
```

- [x] **Step 2: Run the focused test and verify RED**

Run:

```powershell
.\gradlew.bat testDebugUnitTest -PflightAlertRepositoryTests=true --tests com.flightalert.map.ReferenceLabelLayoutSelectorTest --console=plain
```

Expected: compilation fails because the four selector types do not yet exist.

- [x] **Step 3: Implement the minimal pure selector**

Use these exact public-internal shapes:

```kotlin
internal data class ReferenceLabelOccurrenceId(val candidateId: ULong, val repeatOrdinal: Long)

internal sealed interface ReferenceLabelCollisionShape {
    val bounds: ReferenceScreenRect
    data class Box(val rect: ReferenceScreenRect) : ReferenceLabelCollisionShape {
        override val bounds: ReferenceScreenRect get() = rect
    }
    data class Path(
        val points: List<ReferencePathLabelPoint>,
        val radiusPx: Double,
        override val bounds: ReferenceScreenRect,
    ) : ReferenceLabelCollisionShape
}

internal interface ReferenceLabelLayoutCandidate {
    val occurrenceId: ReferenceLabelOccurrenceId
    val featureId: ULong
    val priority: Int
    val placementRank: Int
    val protectedArea: Boolean
    val waterLine: Boolean
    val anchor: ReferencePathLabelPoint
    val collisionShape: ReferenceLabelCollisionShape
}

internal object ReferenceLabelLayoutSelector {
    fun <T : ReferenceLabelLayoutCandidate> select(
        candidates: List<T>,
        viewport: ReferenceScreenRect,
        staticAvoidRects: List<ReferenceScreenRect>,
        labelBudget: Int,
        protectedAreaBudget: Int,
        waterRepeatDistancePx: Double,
    ): List<T>
}
```

Implementation rules:

1. Group by `occurrenceId` before sorting or budget accounting.
2. Within a group, retain the first candidate for each exact `collisionShape`, then sort by `placementRank`.
3. Sort groups by the first candidate's `priority`, `featureId`, and occurrence `repeatOrdinal`.
4. Try each placement in rank order; accept the first whose exact collision shape clears static obstacles and all accepted shapes.
5. `Box`/`Box` uses strict rectangle overlap; `Path`/`Box` uses minimum polyline-to-rectangle distance versus `radiusPx`; `Path`/`Path` uses minimum segment distance versus the sum of radii.
6. An accepted occurrence contributes exactly one budget slot.
7. Preserve the existing nearby-repeat rule for water lines by `featureId`, anchor distance, and `waterRepeatDistancePx`.
8. Validate finite geometry, positive budgets, paths with at least two points, and nonnegative radii fail closed.

- [x] **Step 4: Run focused and adjacent tests and verify GREEN**

```powershell
.\gradlew.bat testDebugUnitTest -PflightAlertRepositoryTests=true `
  --tests com.flightalert.map.ReferenceLabelLayoutSelectorTest `
  --tests com.flightalert.map.ReferencePathLabelPlannerTest `
  --tests com.flightalert.map.ReferenceLabelAvoidanceTest `
  --console=plain
```

Expected: all selected tests pass with no failures.

### Task 2: Preserve Every Planner Placement in the Android Renderer

**Files:**
- Create: `app/src/main/java/com/flightalert/map/ReferenceLineLabelPlacementAdapter.kt`
- Create: `tools/experiment8/kotlin-test/java/com/flightalert/map/ReferenceLineLabelPlacementAdapterTest.kt`
- Modify: `app/src/main/java/com/flightalert/map/ReferenceDictionaryOverlayRenderer.kt`
- Test: `tools/experiment8/kotlin-test/java/com/flightalert/map/ReferenceLabelLayoutSelectorTest.kt`

**Interfaces:**
- Consumes: the Task 1 selector and all ranked `ReferencePathLabelPlanner.plan(...)` results.
- Produces: one accepted `DictionaryLabelCandidate` per typed occurrence, with its exact selected path drawn by Android Canvas.

- [x] **Step 1: Write the failing planner-to-layout adapter test**

Create `ReferenceLineLabelPlacementAdapterTest`. Obtain a real multi-placement list from `ReferencePathLabelPlanner.plan(...)`, pass it to the wished-for `ReferenceLineLabelPlacementAdapter.fromPlanner(...)`, and assert:

```kotlin
assertEquals(planned.size, adapted.size)
assertEquals(planned, adapted.map { it.placement })
adapted.forEachIndexed { index, item ->
    assertEquals(index, item.placementRank)
    assertEquals(
        ReferenceLabelOccurrenceId(item.placement.candidateId, item.placement.repeatOrdinal),
        item.occurrenceId,
    )
}
```

Run the focused test and confirm compilation fails because the adapter is absent. Then create the Android-free adapter with exactly these shapes:

```kotlin
internal data class ReferenceLineLabelPlacementOption(
    val placement: ReferencePathLabelPlacement,
    val occurrenceId: ReferenceLabelOccurrenceId,
    val placementRank: Int,
)

internal object ReferenceLineLabelPlacementAdapter {
    fun fromPlanner(
        placements: List<ReferencePathLabelPlacement>,
    ): List<ReferenceLineLabelPlacementOption> = placements.mapIndexed { index, placement ->
        ReferenceLineLabelPlacementOption(
            placement = placement,
            occurrenceId = ReferenceLabelOccurrenceId(
                placement.candidateId,
                placement.repeatOrdinal,
            ),
            placementRank = index,
        )
    }
}
```

This adapter is the compile-time seam that prevents the Android renderer from returning to `.firstOrNull()`.

- [x] **Step 2: Replace single-placement construction**

Change `line_label_candidate(...)` to `line_label_candidates(...) : List<DictionaryLabelCandidate>`. Compute the typed `candidateId` once, pass the complete planner result to `ReferenceLineLabelPlacementAdapter.fromPlanner(...)`, and assign for each adapted option:

```kotlin
occurrenceId = option.occurrenceId
featureId = candidateId
placementRank = option.placementRank
collisionShape = ReferenceLabelCollisionShape.Path(
    points = option.placement.presentationPath,
    radiusPx = collisionRadius.toDouble(),
    bounds = pathBounds(option.placement.presentationPath, collisionRadius.toDouble()),
)
```

For point labels use the source `candidate_id` when present, otherwise the existing deterministic fallback hash, `repeatOrdinal = 0`, and a padded `Box` collision shape.

- [x] **Step 3: Delegate acceptance to the pure selector**

Replace the renderer's `occupied_label_bounds` AABB loop with:

```kotlin
accepted_labels += ReferenceLabelLayoutSelector.select(
    candidates = label_candidates,
    viewport = ReferenceScreenRect(0.0, 0.0, viewport.width.toDouble(), viewport.height.toDouble()),
    staticAvoidRects = label_avoid_rects,
    labelBudget = label_budget(viewport),
    protectedAreaBudget = protected_area_label_budget(viewport),
    waterRepeatDistancePx = WATER_LINE_LABEL_REPEAT_DISTANCE_PX.toDouble(),
)
```

Have `DictionaryLabelCandidate` implement `ReferenceLabelLayoutCandidate`. Store planner points rather than allocating an Android `Path` per alternate placement; build the reusable `line_label_path` only inside `draw_label_candidate` for the accepted candidate.

- [x] **Step 4: Verify the repository test suite and Android build**

```powershell
.\gradlew.bat testDebugUnitTest -PflightAlertRepositoryTests=true --console=plain
.\gradlew.bat assembleDebug --console=plain
.\gradlew.bat lintDebug --console=plain
```

Expected: every repository test passes; assemble and lint complete without new errors.

### Task 3: Physical Phone Size and Motion Gate

**Files:**
- Modify only after evidence supports it: `app/src/main/java/com/flightalert/ui/FlightAlertSettings.kt`
- Test only after evidence supports it: add or extend a repository settings test for `DEFAULT_MAP_LABEL_TEXT_SCALE`.
- Update: `C:\Users\Phineas\Documents\FlightAlert-test-artifacts\experiment8\zeus-current-goal-and-next-steps.md`
- Evidence output: `C:\Users\Phineas\Documents\FlightAlert-test-artifacts\experiment8\visual-handoff\`

**Interfaces:**
- Consumes: Task 2 APK and the already installed r15 package.
- Produces: phone video/screenshots and an evidence-backed text-scale default.

- [x] **Step 1: Build and acquire the atomic device lease**

Use `C:\Users\Phineas\Documents\FlightAlert-test-artifacts\coordination\device-lease.ps1`. Capture the lease-start APK hash, `flight_alert` preference bytes, running state, reference-root path/inventory, and `stay_on_while_plugged_in`; install only the same-signature Task 2 APK. Do not clear app data or caches.

- [x] **Step 2: Select the intended visual-test state**

Set SATELLITE, vector reference labels on, place/water/region labels on, borders on, coastline outline off, and map label text scale `1.0`. Do not add a separate settings-readback step; let the visible result expose a wrong setup.

- [ ] **Step 3: Capture physical evidence**

At the current Kent County/Chester River view, capture a still plus a 20-second screen recording while panning far enough to force label reselection. Then capture representative views at the same scale in Europe and one non-Latin region. Reject immediately if Chester River is absent despite the admitted record, any creek crowds out a major river, text overlaps/crushes/fragments, labels jump to unrelated geometry, or non-Latin/English hierarchy is wrong.

- [x] **Step 4: Establish the text-size default with TDD**

If `1.0` is visibly readable and restrained on the 904 x 2316 phone, first add a failing test asserting `FlightAlertSettings.DEFAULT_MAP_LABEL_TEXT_SCALE == 1.0f`, watch it fail at `1.35f`, then change only that constant and rerun the focused test/build. If video shows `1.0` too small, repeat the evidence at the next existing 0.05 step and bind the test to the smallest readable value.

- [x] **Step 5: Restore or intentionally retain state and release**

Keep the accepted text scale only if it is the chosen new user-facing state; otherwise restore the exact lease-start preferences. Always preserve package/reference roots and USB stay-awake, remove any temporary recording from the phone after pulling it, release the token, and record the final state/evidence paths in the operational progress file.

### Current Physical Checkpoint — 2026-07-17

- Source APK: `3FD72DA3AD8EDBBBFD382459531A8EF6397662CFF022C9B9FB13C35732785E51` (14,372,007 bytes).
- Installed/running with the existing whole-world V4 reference package unchanged at 16,571,587,751 bytes.
- Native label scale `1.0`; coastline filter default remains off.
- Exact production-renderer Kent replay accepts and draws Chester River using the native 21.65625 px text metrics and 23.165781 px collision radius.
- Full SATELLITE before/pan/after gate shows Chester River in the starting view and after reselection, without crushed glyphs or a detached baseline.
- Full gate evidence: `C:\Users\Phineas\Documents\FlightAlert-test-artifacts\experiment8\visual-handoff\20260717T0917Z-native-size-chester-full-app`.
- Renderer replay evidence: `C:\Users\Phineas\Documents\FlightAlert-test-artifacts\experiment8\visual-handoff\20260717T0914Z-native-size-chester`.
- Device lease released (`held:false`); candidate intentionally retained for user visual judgment.
- Task 3 Step 3 remains open for Europe and non-Latin-script visual sampling after the user judges this phone presentation.
