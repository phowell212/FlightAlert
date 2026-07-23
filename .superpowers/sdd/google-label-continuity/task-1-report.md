# Task 1 Report: Stable Prior-Occurrence Admission

## RED

Command (with process-local `ANDROID_HOME=C:\\Users\\h\\AppData\\Local\\Android\\Sdk` because this isolated worktree has no `local.properties`):

```powershell
.\gradlew.bat -I .superpowers\sdd\reference-v5\tests.init.gradle testDebugUnitTest --tests com.flightalert.map.ReferenceLabelLayoutSeedTest
```

Result: failed at `:compileDebugUnitTestKotlin` as intended:

```text
No parameter with name 'preferredOccurrences' found.
```

The first invocation without that process-local SDK path stopped before compilation with `SDK location not found`; no source change was made for that environment issue.

## GREEN

Focused command:

```powershell
.\gradlew.bat -I .superpowers\sdd\reference-v5\tests.init.gradle testDebugUnitTest --tests com.flightalert.map.ReferenceLabelLayoutSeedTest
```

Result: `BUILD SUCCESSFUL in 5s`; `:testDebugUnitTest` passed.

Source verification:

```powershell
.\gradlew.bat -I .superpowers\sdd\reference-v5\tests.init.gradle testDebugUnitTest
.\gradlew.bat assembleDebug lintDebug
```

Results: `BUILD SUCCESSFUL in 2s` and `BUILD SUCCESSFUL in 48s`, respectively. The injected unit suite, debug assembly, and lint all passed.

## Files Changed

- `.superpowers/sdd/reference-v5/tests/ReferenceLabelLayoutSeedTest.kt`
- `app/src/main/java/com/flightalert/map/ReferenceLabelLayoutSelector.kt`
- `app/src/main/java/com/flightalert/map/ReferenceDictionaryOverlayRenderer.kt`
- `.superpowers/sdd/google-label-continuity/task-1-report.md`

## Self-Review

- The focused test proves a compatible prior occurrence wins only a fully overlapping equal-priority collision, while a stronger current occurrence still wins.
- The selector preference follows priority and precedes deterministic feature ordering; validation and all admission checks are unchanged.
- Compatible displayed labels are captured only after the retained coordinator accepts the request. The background executor alone builds the occurrence set, and direct UI planning retains empty defaults.
- `git diff --check` passed. No `try/catch`, device testing, or unrelated source changes were introduced.

## Concerns

No known implementation concerns. Physical-device temporal validation is intentionally out of scope for Task 1.

## Empty-Preference Regression Fix

### RED

Command (with process-local `ANDROID_HOME=C:\\Users\\h\\AppData\\Local\\Android\\Sdk`):

```powershell
.\gradlew.bat -I .superpowers\sdd\reference-v5\tests.init.gradle testDebugUnitTest --tests com.flightalert.map.ReferenceLabelLayoutSeedTest
```

Result: failed as intended. `emptyPreferredOccurrencesDoNotQueryMembership` threw
`IllegalStateException` from its `contains` implementation, proving the empty set was queried.

### GREEN

Command:

```powershell
.\gradlew.bat -I .superpowers\sdd\reference-v5\tests.init.gradle testDebugUnitTest --tests com.flightalert.map.ReferenceLabelLayoutSeedTest
```

Result: `BUILD SUCCESSFUL in 3s`; all four focused tests passed.

### Corrected Committed-File Scope

The original Task 1 commit also includes these task files:

- `.superpowers/sdd/google-label-continuity/task-1-brief.md`
- `docs/superpowers/plans/2026-07-22-google-style-label-continuity.md`
- `docs/superpowers/specs/2026-07-22-google-style-label-continuity-design.md`

This regression fix changes the focused test, selector, and this report only.

## Dense-Scene Preferred-Frontier Fix

### RED

Command (with process-local `ANDROID_HOME=C:\\Users\\h\\AppData\\Local\\Android\\Sdk`):

```powershell
.\gradlew.bat -I .superpowers\sdd\reference-v5\tests.init.gradle testDebugUnitTest --tests com.flightalert.map.ReferenceLabelLayoutSeedTest.preferredFrontierReselectsOnlyEqualPriorityRecords
```

Result: failed at `:compileDebugUnitTestKotlin` as intended:

```text
Unresolved reference 'preferredRecordComparator'.
Unresolved reference 'shouldContinuePreferredFrontier'.
```

This proved the planner/admission policy had no preferred record ordering or bounded
same-priority frontier.

### GREEN

Focused command:

```powershell
.\gradlew.bat -I .superpowers\sdd\reference-v5\tests.init.gradle testDebugUnitTest --tests com.flightalert.map.ReferenceLabelLayoutSeedTest.preferredFrontierReselectsOnlyEqualPriorityRecords
```

Result: `BUILD SUCCESSFUL in 3s`; the focused dense-scene regression passed.

Full verification:

```powershell
.\gradlew.bat -I .superpowers\sdd\reference-v5\tests.init.gradle testDebugUnitTest
.\gradlew.bat assembleDebug lintDebug
git diff --check
```

Results: `BUILD SUCCESSFUL in 1s`, `BUILD SUCCESSFUL in 1m 4s`, and
`git diff --check` exited 0 with no output. The full injected suite, debug assembly,
and lint passed.

### Files Changed

- `.superpowers/sdd/reference-v5/tests/ReferenceLabelLayoutSeedTest.kt`
- `app/src/main/java/com/flightalert/map/ReferenceLabelAdmissionPolicy.kt`
- `app/src/main/java/com/flightalert/map/ReferenceDictionaryOverlayRenderer.kt`
- `.superpowers/sdd/google-label-continuity/task-1-report.md`
- `.superpowers/sdd/google-label-continuity/task-1-brief.md` removed from Git only;
  the ignored local workflow copy remains.

### Self-Review

- Preferred candidate keys are derived on the retained-label executor from candidate ID
  plus rendered world copy; full occurrence identities remain authoritative in selection.
- Nonempty retained planning orders preferred records after effective priority and before
  feature ID. Empty planning uses the original record comparator and immediate budget stop.
- A full budget advances only through preferred records at the same effective priority,
  forces a final reselect when candidates were added below the doubling threshold, and does
  not advance to a weaker preferred priority.
- Current geometry, collision, validity, and admission rules remain unchanged. No stale
  candidate is fixed in place, and no device testing or push was performed.

## Exact-Preseed Replacement

This section supersedes the rejected coarse record-order/frontier implementation above.
The coarse comparator, record flag, continuation policy, and regression were removed.

### RED

Command (with process-local `ANDROID_HOME=C:\\Users\\h\\AppData\\Local\\Android\\Sdk`):

```powershell
.\gradlew.bat -I .superpowers\sdd\reference-v5\tests.init.gradle testDebugUnitTest --tests com.flightalert.map.ReferenceLabelLayoutSeedTest.preferredSeedsRequireExactOccurrenceAndActivePriority
```

Result: failed at `:compileDebugUnitTestKotlin` as intended:

```text
Unresolved reference 'retainPreferredSeeds'.
Unresolved reference 'appendActivePreferredSeeds'.
```

The focused scenario required full occurrence matching, repeat/world-copy rejection,
same-feature filtering, semantic-priority preservation, and frontier gating.

### GREEN

Focused command:

```powershell
.\gradlew.bat -I .superpowers\sdd\reference-v5\tests.init.gradle testDebugUnitTest --tests com.flightalert.map.ReferenceLabelLayoutSeedTest.preferredSeedsRequireExactOccurrenceAndActivePriority
```

Results: initial GREEN was `BUILD SUCCESSFUL in 4s`; the final focused scenario rerun
was `BUILD SUCCESSFUL in 2s`.

Full verification:

```powershell
.\gradlew.bat -I .superpowers\sdd\reference-v5\tests.init.gradle testDebugUnitTest
.\gradlew.bat assembleDebug lintDebug
git diff --check
```

Results: the full injected suite was `BUILD SUCCESSFUL in 2s`, its final post-review
rerun was `BUILD SUCCESSFUL in 4s`, `assembleDebug lintDebug` was
`BUILD SUCCESSFUL in 51s`, and `git diff --check` exited 0 with no output.

### Files Changed

- `.superpowers/sdd/reference-v5/tests/ReferenceLabelLayoutSeedTest.kt`
- `app/src/main/java/com/flightalert/map/ReferenceLabelAdmissionPolicy.kt`
- `app/src/main/java/com/flightalert/map/ReferenceDictionaryOverlayRenderer.kt`
- `.superpowers/sdd/google-label-continuity/task-1-report.md`

### Self-Review

- The original record comparator, feature block, candidate thresholds, candidate counts,
  and immediate full-budget stop are restored.
- The retained-label executor still builds both full occurrence IDs and coarse
  candidate/world-copy keys. Only nonempty background preference planning allocates seed
  and selector scratch lists.
- The prepass scans original-sorted visible records, generates only matching coarse keys
  through the same current-geometry helper as normal planning, deduplicates postings
  locally, and clears that dedupe set before normal planning.
- Only exact full occurrence matches become separate seeds. Excluded padding geometry is
  filtered by the shared generator. Seeds never enter normal candidate counts or thresholds.
- Each selector call activates only seeds whose current effective priority is no weaker
  than the processed priority frontier. Seeds remain ordinary candidates subject to all
  existing admission checks; stale repeats and world copies seed nothing.
- No push or device testing was performed.
