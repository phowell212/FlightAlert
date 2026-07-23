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
