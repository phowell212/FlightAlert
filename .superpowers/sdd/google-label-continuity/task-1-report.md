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
