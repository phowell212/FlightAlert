# Experiment 8 Final Install and Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transactionally install the finalized whole-world V3 reference package and the source-matched Flight Alert APK, then retain documentary physical-device evidence that the resulting app is usable, source-honest, visually non-degenerate, and within the complete phone footprint and performance budgets.

**Architecture:** A host-side Python installer validates the immutable final-monitor result, exact six-file package inventory, canonical manifest/receipts, source APK, byte ceilings, and device preconditions before it can issue any ADB mutation. It acquires the shared atomic phone lease, stages the package under a tokenized sibling directory while Flight Alert is stopped, verifies every staged file on-device, atomically swaps the package directory, installs the APK, and always records or restores exact state. A separate acceptance runner drives explicit map/settings scenarios and writes raw screenshots, videos, storage, process-memory, frame, and source-status evidence outside the repository.

**Tech Stack:** Python 3.11 standard library, Android Debug Bridge, PowerShell atomic device-lease helper, Android debug APK, Experiment 8 V3 package and finalization receipts, `unittest`.

## Global Constraints

- The preferred complete required on-phone footprint is strictly below `25,000,000,000` bytes; the hard fallback ceiling is strictly below `40,000,000,000` bytes.
- Count the greater of logical and allocated bytes for the installed APK, the single active package, indexes/catalogs/manifests/integrity files, and mandatory caches after acceptance.
- Accepted phone state contains one active reference package and no inactive package, staging sibling, backup, source PBF, trace, or duplicate package copy.
- Host JSON is permitted; the Android draw/load hot path remains typed binary and does not parse a world JSON dictionary.
- Coastline outlines default off, but an explicit existing user choice is not silently overwritten.
- No phone operation begins without the atomic lease; temporary state is restored and the lease is released on success, failure, or interruption.
- Set each scenario's required settings directly and let its assertions expose a wrong prerequisite; do not add redundant pre-verification loops.
- Thermal status 1/2/3 is allowed while all reported temperatures remain below `60 C`; stop at any reported temperature greater than or equal to `60 C`.
- Generated packages, evidence, screenshots, videos, and run logs stay outside the repository.

---

### Task 1: Immutable host install contract

**Files:**
- Create: `tools/experiment8/reference_package_install.py`
- Test: `tools/experiment8/tests/test_reference_package_install.py`

**Interfaces:**
- Consumes: final package directory `E:\FlightAlert-exp8-work\world-experiment8-binary-v3`, final-monitor result `E:\FlightAlert-exp8-work\final-package-monitor-v3-r2.result.json`, and `build\outputs\apk\debug\Flight Alert-debug.apk`.
- Produces: `HostInstallPlan.validate(...)`, an immutable plan containing exact package ID, six regular file names/sizes/SHA-256 values, APK size/SHA-256, logical package bytes, complete projected bytes, and preferred/hard gate results.

- [ ] **Step 1: Write failing tests for the exact final inventory and identity.**

  Create fixtures with `manifest.json`, `records.fadictpack`, `tile-index.bin`, `merge-receipt.json`, `class-catalog.bin`, and `class-catalog-finalization-receipt.json`. Assert rejection of a seventh file, directory/reparse surrogate, package-ID mismatch, noncanonical manifest/receipt JSON, mismatched receipt hashes, changed APK, changed package file between hash and final stat, and a final-monitor result that does not bind the same package/APK paths and byte counts.

- [ ] **Step 2: Run the focused test and verify RED.**

  Run: `py -3.11 -m unittest tools.experiment8.tests.test_reference_package_install -v`

  Expected: import failure for `tools.experiment8.reference_package_install`.

- [ ] **Step 3: Implement the smallest immutable validator.**

  Use strict UTF-8 JSON decoding with duplicate-key rejection, exact type checks (`type(value) is int/bool/str`), canonical `json.dumps(..., sort_keys=True, separators=(",", ":"), ensure_ascii=False) + b"\n"` comparison for repository-owned receipts, `os.lstat` before and after streaming SHA-256, and exact ordinal filename comparison. Reject a package at or above `38,500,000,000` bytes or a complete projected footprint at or above `40,000,000,000` bytes; retain the preferred result separately.

- [ ] **Step 4: Run focused and full Experiment 8 host tests.**

  Run:

  ```powershell
  py -3.11 -m unittest tools.experiment8.tests.test_reference_package_install -v
  py -3.11 -m unittest discover -s tools/experiment8/tests -t . -v
  ```

  Expected: all tests pass with no warning or generated file under the repository.

### Task 2: Transactional device installer

**Files:**
- Modify: `tools/experiment8/reference_package_install.py`
- Create: `tools/install-reference-dictionary-experiment8.ps1`
- Modify: `tools/experiment8/tests/test_reference_package_install.py`

**Interfaces:**
- Consumes: `HostInstallPlan`, an `AdbClient` command runner, and the JSON output of `device-lease.ps1`.
- Produces: one canonical external evidence receipt and a device with the new package at `/storage/emulated/0/Android/data/com.flightalert/files/reference/world-experiment8-binary-v3` or exact prestate restored.

- [ ] **Step 1: Write failing tests using a stateful fake ADB executable.**

  Cover: lease already held; more or fewer than one authorized device; insufficient storage; package/app not stopped; push failure; staged size/hash mismatch; rename failure; APK install failure; cleanup failure; interruption; an existing accepted package; explicit coastline preference preservation; first-run coastline default off; and success with no staging/backup/old-package residue.

- [ ] **Step 2: Verify each new test fails for the missing transaction.**

  Run: `py -3.11 -m unittest tools.experiment8.tests.test_reference_package_install.DeviceTransactionTest -v`

  Expected: assertions fail because no device transaction exists.

- [ ] **Step 3: Implement the transaction.**

  Acquire the lease with owner `Zeus/Experiment8`. Capture `adb devices -l`, installed APK pull/hash/size, exact `flight_alert` preference bytes, running/focused state, reference-root inventory, available/allocated storage, and notification-listener approval/binding. Stop Flight Alert under the previously accepted disallow-listener then force-stop order. Push the six files into a tokenized same-parent stage, with `manifest.json` last. Verify exact file size and SHA-256 on-device, rename any existing final package to a tokenized backup, rename stage to final, install the source-matched APK, and verify package open/status. On any failure, restore the old package/APK/preferences/listener/running state and retain a fail-closed recovery journal. On successful acceptance finalization, remove every old package, backup, and stage so only the active package remains.

- [ ] **Step 4: Keep the PowerShell wrapper mechanical.**

  `tools/install-reference-dictionary-experiment8.ps1` resolves Python 3.11 and forwards exact package, APK, result, lease-helper, evidence-root, and `--execute` arguments. It contains no second package policy and never prompts interactively.

- [ ] **Step 5: Run fake-device tests and validate-only against the real final package.**

  Run:

  ```powershell
  py -3.11 -m unittest tools.experiment8.tests.test_reference_package_install -v
  .\tools\install-reference-dictionary-experiment8.ps1 -PackageRoot 'E:\FlightAlert-exp8-work\world-experiment8-binary-v3' -ApkPath '.\build\outputs\apk\debug\Flight Alert-debug.apk' -FinalResult 'E:\FlightAlert-exp8-work\final-package-monitor-v3-r2.result.json' -ValidateOnly
  ```

  Expected: tests pass and validation prints one canonical plan without touching ADB.

### Task 3: Physical installation and global acceptance

**Files:**
- Modify only if a test exposes a product defect: the smallest owning Kotlin/tool file plus its focused test.
- Generated evidence: `C:\Users\Phineas\Documents\FlightAlert-test-artifacts\experiment8\final-device-acceptance\<UTC-run-id>\`

**Interfaces:**
- Consumes: the accepted installer, finalized package, source-matched APK, and explicit viewport/settings matrix.
- Produces: installed polished app, raw evidence, a canonical acceptance summary, and exact steady-state footprint ledger.

- [ ] **Step 1: Acquire the phone only when installation is ready.**

  Announce/acquire the atomic lease immediately before ADB. Retain it only while actively installing, driving, capturing, or restoring.

- [ ] **Step 2: Install transactionally and verify one active package.**

  Run the installer with `--execute`; require exact source/package hashes, no stage/backup/old package, app catalog available, and coastline outline default off unless the captured preferences contained an explicit coastline choice.

- [ ] **Step 3: Drive the source-honesty and visual matrix with direct settings.**

  Capture multiple zooms and pan/pinch sequences at Kent/Chester River; London/continental Europe; Tokyo; Cairo; Mumbai; São Paulo; Cape Town; Sydney; Fiji/dateline; Greenland/high latitude; and one sparse ocean/coastline viewport. Require sourced Chester River placement, creek visibility only at closer zoom, feature-size-driven waterway thresholds, distinct island/river/creek/city/border styling, prominence hierarchy, stable collision/path shaping, coastline default off and toggleable, filters by semantic feature/border category, no missing-source invention, and source script primary with exact provider English smaller/italic below only for non-Latin scripts.

- [ ] **Step 4: Retain performance and footprint evidence.**

  For cold and warm launch plus dense/sparse pan/pinch lanes, retain raw screen recording, `dumpsys gfxinfo ... framestats`, `dumpsys meminfo`, thermal service, process samples, package/cache `stat`/`du` logical and allocated bytes, and screenshots at fixed assertion points. Reject any temperature at or above `60 C`, immense lag, repeated frame stalls, package reader runaway, or complete footprint at/above `40,000,000,000` bytes.

- [ ] **Step 5: Fix observed failures test-first and repeat only affected plus regression lanes.**

  Every product failure first becomes a focused failing host/instrumented test. Implement the smallest owning fix, rebuild, rerun the focused gate, then rerun Kent, one Europe lane, one non-Latin lane, and the affected scenario. A structurally valid package never overrides a visible rejection.

- [ ] **Step 6: Final verification and handoff.**

  Run repository JVM tests, full Experiment 8 Python tests, `assembleDebug`, and `lintDebug`; verify Git scope and one Gradle project; record exact source commit/APK/package/evidence hashes; release the phone lease; and present the running accepted app.
