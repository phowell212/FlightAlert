# Single Android Application Project Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the stale generated app identity and stale Android Studio launch identity while preserving Flight Alert's one authoritative root Android application project and all existing source work.

**Architecture:** The root Gradle project remains the sole Android application and continues to source `app/src/main`. Cleanup is limited to one obsolete ignored build tree and two obsolete ignored IDE records, followed by a clean root build and explicit single-project verification.

**Tech Stack:** PowerShell, Gradle Wrapper, Android Gradle Plugin, Android Studio XML workspace state, Git

## Global Constraints

- Keep `build.gradle.kts`, `settings.gradle.kts`, and `app/src/main` as the authoritative application project and source tree.
- Do not convert the repository to a `:app` subproject.
- Do not change package identity or application behavior.
- Do not touch the existing Experiment 7 modifications or untracked Experiment 7 files.
- Validate recursive deletion targets as absolute paths inside `C:\Users\Phineas\Documents\Flight Alert` before removal.
- Do not commit generated build output or Android Studio machine state.

---

## File Map

- Remove generated directory: `app/build/` — obsolete output from the stale `Flight_Alert.app` identity.
- Modify ignored local state: `.idea/workspace.xml` — remove only the `app` Android run configuration whose module is `Flight_Alert.app`.
- Modify ignored local state: `.idea/deploymentTargetSelector.xml` — remove only the `SelectionState` whose `runConfigName` is `app`.
- Preserve tracked source/configuration: `build.gradle.kts`, `settings.gradle.kts`, `app/src/main/**`.

### Task 1: Remove the stale generated and IDE app identities

**Files:**
- Remove: `app/build/`
- Modify: `.idea/workspace.xml:149`
- Modify: `.idea/deploymentTargetSelector.xml:9`

**Interfaces:**
- Consumes: the current root Gradle application identity `Flight_Alert` and source root `app/src/main`.
- Produces: local repository state with no obsolete `Flight_Alert.app` identity.

- [ ] **Step 1: Run the acceptance probe and verify it fails before cleanup**

```powershell
$failures = @()
if (Test-Path -LiteralPath 'app\build') { $failures += 'stale app/build exists' }
if (Select-String -LiteralPath '.idea\workspace.xml' -SimpleMatch 'Flight_Alert.app' -Quiet) { $failures += 'stale IDE module exists' }
if (Select-String -LiteralPath '.idea\deploymentTargetSelector.xml' -SimpleMatch 'runConfigName="app"' -Quiet) { $failures += 'stale deployment entry exists' }
if ($failures.Count -gt 0) { throw ($failures -join '; ') }
```

Expected: FAIL and name all three stale conditions.

- [ ] **Step 2: Remove only the obsolete run configuration from `.idea/workspace.xml`**

Delete the complete `<configuration name="app" ...>` element whose child is `<module name="Flight_Alert.app" />`. Preserve the preceding `Flight_Alert` configuration and every other workspace component.

- [ ] **Step 3: Remove only the obsolete deployment selection from `.idea/deploymentTargetSelector.xml`**

Delete exactly:

```xml
<SelectionState runConfigName="app">
  <option name="selectionMode" value="DROPDOWN" />
  <DialogSelection />
</SelectionState>
```

Preserve the `Flight_Alert` selection state.

- [ ] **Step 4: Validate and remove the stale generated directory**

```powershell
$workspace = [System.IO.Path]::GetFullPath((Get-Location).Path).TrimEnd('\')
$target = [System.IO.Path]::GetFullPath((Join-Path $workspace 'app\build')).TrimEnd('\')
$expected = [System.IO.Path]::GetFullPath('C:\Users\Phineas\Documents\Flight Alert\app\build').TrimEnd('\')
if ($target -ne $expected -or -not $target.StartsWith($workspace + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing unexpected removal target: $target"
}
if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
```

Expected: `app/build` is absent; `app/src` remains present.

- [ ] **Step 5: Re-run the acceptance probe**

Run the Step 1 PowerShell probe again.

Expected: PASS with no exception.

### Task 2: Rebuild and prove the repository has one application project

**Files:**
- Regenerate: `build/` — ignored output belonging to the sole root application project.
- Preserve: all tracked and untracked Experiment 7 work.

**Interfaces:**
- Consumes: the cleaned local state from Task 1.
- Produces: one successful root-project APK build plus documentary terminal evidence for every cleanup invariant.

- [ ] **Step 1: Clean the authoritative root project**

```powershell
.\gradlew.bat clean --no-daemon
```

Expected: `BUILD SUCCESSFUL`; root `build/` is removed by Gradle; `app/build/` remains absent.

- [ ] **Step 2: Rebuild the authoritative root project**

```powershell
.\gradlew.bat assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL` and `build/outputs/apk/debug/Flight Alert-debug.apk` exists.

- [ ] **Step 3: Verify the live Gradle project graph**

```powershell
.\gradlew.bat projects --no-daemon
```

Expected output contains `Root project 'Flight Alert'` and `No sub-projects`.

- [ ] **Step 4: Verify source and project cardinality**

```powershell
$projectFiles = Get-ChildItem -LiteralPath . -Recurse -Force -File -ErrorAction Stop |
    Where-Object {
        $_.FullName -notmatch '\\build\\|\\.git\\|\\.gradle\\' -and
        $_.Name -in @('settings.gradle', 'settings.gradle.kts', 'build.gradle', 'build.gradle.kts', 'AndroidManifest.xml')
    }
$settings = @($projectFiles | Where-Object { $_.Name -like 'settings.gradle*' })
$buildScripts = @($projectFiles | Where-Object { $_.Name -like 'build.gradle*' })
$manifests = @($projectFiles | Where-Object { $_.Name -eq 'AndroidManifest.xml' })
if ($settings.Count -ne 1 -or $buildScripts.Count -ne 1 -or $manifests.Count -ne 1) {
    throw "Unexpected project cardinality: settings=$($settings.Count), buildScripts=$($buildScripts.Count), manifests=$($manifests.Count)"
}
if (Test-Path -LiteralPath 'app\build') { throw 'app/build was recreated' }
```

Expected: PASS with counts `1, 1, 1` and no `app/build`.

- [ ] **Step 5: Verify IDE and source identities**

```powershell
if (-not (Select-String -LiteralPath '.idea\workspace.xml' -SimpleMatch '<module name="Flight_Alert" />' -Quiet)) { throw 'valid Flight_Alert run module missing' }
if (Select-String -LiteralPath '.idea\workspace.xml' -SimpleMatch 'Flight_Alert.app' -Quiet) { throw 'stale Flight_Alert.app module remains' }
if (Select-String -LiteralPath '.idea\deploymentTargetSelector.xml' -SimpleMatch 'runConfigName="app"' -Quiet) { throw 'stale app deployment entry remains' }
if (-not (Test-Path -LiteralPath 'app\src\main\AndroidManifest.xml')) { throw 'authoritative manifest missing' }
if (-not (Test-Path -LiteralPath 'app\src\main\java\com\flightalert\MainActivity.kt')) { throw 'authoritative MainActivity missing' }
```

Expected: PASS.

- [ ] **Step 6: Record the output hash and verify Git preservation**

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'build\outputs\apk\debug\Flight Alert-debug.apk'
git status --short --branch
```

Expected: one SHA-256 value; Git status still lists the pre-existing Experiment 7 changes and no generated output or IDE state.
