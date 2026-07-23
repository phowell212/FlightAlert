# Google-Style Label Continuity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prefer a still-valid previously displayed label occurrence during background retained-frame layout, without changing semantic priority or adding draw/gesture hot-path work.

**Architecture:** Extend the pure label selector with an optional occurrence preference and feed it only from the retained-label worker. Capture the prior immutable label list when a new retained request starts, then build the identity set off the UI thread and thread it through existing planning functions.

**Tech Stack:** Kotlin, Android Canvas renderer, JUnit 4, Gradle injected JVM tests, ADB real-device video and gfxinfo validation.

## Global Constraints

- No try/catch in hot code.
- Keep current label validity, collision, water-repeat, protected-area, and fixed-core rules authoritative.
- Use the full `ReferenceLabelOccurrenceId` identity.
- Do not traverse prior labels or build a set in draw or gesture code.
- Preserve visuals at every reference zoom band; test meaningful dense geography.
- Use real-phone video, not screenshots, for temporal validation.

---

### Task 1: Stable Prior-Occurrence Admission

**Files:**
- Modify: `.superpowers/sdd/reference-v5/tests/ReferenceLabelLayoutSeedTest.kt`
- Modify: `app/src/main/java/com/flightalert/map/ReferenceLabelLayoutSelector.kt`
- Modify: `app/src/main/java/com/flightalert/map/ReferenceDictionaryOverlayRenderer.kt`

**Interfaces:**
- Consumes: `ReferenceLabelOccurrenceId` and the immutable labels of `displayed_retained_frame`.
- Produces: `ReferenceLabelLayoutSelector.select(..., preferredOccurrences: Set<ReferenceLabelOccurrenceId> = emptySet())`.

- [ ] **Step 1: Write the failing selector test**

Add a test that creates two fully overlapping equal-priority labels where the previous occurrence has the larger deterministic feature ID. Pass that identity as preferred and require it to win. In the same rule test, make the challenger semantically stronger and require the stronger label to win. Add a `priority` argument to the existing test candidate helper.

```kotlin
@Test
fun preferredOccurrenceWinsOnlyAnEqualPriorityCollision() {
    val challenger = candidate(id = 1u, feature = 10u, left = 40.0, right = 60.0)
    val previous = candidate(id = 2u, feature = 20u, left = 40.0, right = 60.0)

    assertEquals(
        listOf(previous),
        selectPreferred(previous.occurrenceId, challenger, previous),
    )

    val stronger = candidate(
        id = 3u,
        feature = 30u,
        left = 40.0,
        right = 60.0,
        priority = 0,
    )
    assertEquals(
        listOf(stronger),
        selectPreferred(previous.occurrenceId, stronger, previous),
    )
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
.\gradlew.bat -I .superpowers\sdd\reference-v5\tests.init.gradle testDebugUnitTest --tests com.flightalert.map.ReferenceLabelLayoutSeedTest
```

Expected: compilation fails because `preferredOccurrences` is not yet part of the selector API, proving the test demands the new behavior.

- [ ] **Step 3: Add the selector preference**

Add the defaulted set after `fixedCandidates`. Use the existing comparator unchanged for the empty case. For a nonempty set, insert this key after priority and before feature ID:

```kotlin
.thenBy { it.first.occurrenceId !in preferredOccurrences }
```

Do not change candidate validation or admission checks.

- [ ] **Step 4: Feed preference from retained background planning**

After `retained_label_coordinator.start(key)` accepts a new request, capture `displayed_retained_frame.labels` only when options and package generation match. Store the immutable list reference on `RetainedLabelPlanRequest`.

On `retained_label_executor`, build:

```kotlin
val preferred_occurrences = HashSet<ReferenceLabelOccurrenceId>(request.preferredLabels.size)
for (label in request.preferredLabels) {
    preferred_occurrences += label.occurrenceId
}
```

Thread `preferred_occurrences` through `create_retained_frame`, `draw_retained_scene_content`, `plan_labels`, and `accept_label_candidates`. Leave all defaults empty so direct UI planning is unchanged.

- [ ] **Step 5: Run the focused test and verify GREEN**

Run the Step 2 command.

Expected: `ReferenceLabelLayoutSeedTest` passes.

- [ ] **Step 6: Run source verification**

Run:

```powershell
.\gradlew.bat -I .superpowers\sdd\reference-v5\tests.init.gradle testDebugUnitTest
.\gradlew.bat assembleDebug lintDebug
```

Expected: all injected unit tests, assembly, and lint pass with no new warnings or errors.

- [ ] **Step 7: Commit the implementation**

Stage only the design, plan, selector, renderer, and focused test. Commit with:

```powershell
git commit -m "Stabilize reference label placement"
```

### Task 2: Real-Phone Temporal Acceptance

**Files:**
- Create outside Git: `artifact-work/readable-rebase/google-polish/*.mp4`

**Interfaces:**
- Consumes: debug APK and real Fold serial `RFCX40KPN3B`.
- Produces: meaningful Morocco z7-z9 and z10-z12 action videos plus action-window frame timing.

- [ ] **Step 1: Install the exact built APK and establish dense Morocco views**

Use ADB against `RFCX40KPN3B`. Confirm the actual viewport zoom band through app/runtime state before each sequence; do not infer it from appearance alone.

- [ ] **Step 2: Record slow pan and pinch sequences**

At z7-z9 and z10-z12, record video containing a stationary lead-in, slow pan, pinch across the band, reverse pan, and stationary tail. Keep Morocco/Spain/Algeria land and visible labels in frame; do not test over ocean.

- [ ] **Step 3: Inspect the video as a temporal sequence**

Require stable geographic attachment, no post-settle swap, no blank section, visible expected borders, and no reference-specific hard pop. Distinguish satellite imagery tile refinement from the reference overlay.

- [ ] **Step 4: Correlate action windows with frame timing**

Report p50, p95, p99, maximum, and over-33-ms counts for the actual gesture intervals. Compare against the previously accepted same-device reference run and investigate any material regression before proceeding.

- [ ] **Step 5: Publish only after acceptance**

Verify the final diff and remote target, push the exact commit to `origin/master`, then confirm the local and remote master hashes match.
