# Waterway Render-Lock Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resume the authenticated Experiment 8 whole-world waterway cook without repeating extraction/admission, eliminate the deterministic SQLite self-lock, and publish a source-honest package carrying exact recovery evidence.

**Architecture:** Renderer staging will authenticate source rows and write staged rows through one SQLite connection configured to the declared 64 MiB cache. A separate explicit recovery entry point will accept only the exact preserved incident state, atomically reset renderer-owned state while preserving admission state, bind a versioned recovery receipt, and render under the fixed code identity. The ordinary render path remains fail-closed on identity drift.

**Tech Stack:** Python 3.11.1, SQLite 3.39.4 in DELETE journal mode, `unittest`, canonical JSON/SHA-256 provenance, PowerShell host orchestration.

## Global Constraints

- Preserve the completed 5,433,355-root admission and its `fatalCount = 0` receipts exactly.
- Preserve an exact hashed backup of the failed 61,499,113,472-byte database and terminal logs before recovery mutation.
- Never relabel the failed render as complete; final evidence must bind old and new identities and the 1,200-feature reset checkpoint.
- Recovery accepts only the exact incident predecessor state and may reset renderer state once.
- Admission/source tables and receipts are immutable across recovery.
- Output publication remains atomic and no-replace.
- Render uses `journal_mode=DELETE`, `synchronous=FULL`, `temp_store=FILE`, `mmap_size=0`, and `cache_size=-65536`.
- The ordinary public exact-feature iterator remains read-only/query-only.
- The recovery path hashes bound closure/root inputs but does not reparse all 281,984,067 objects or recompute all admission decisions.
- No device operation occurs until final package and APK host validation completes.

---

### Task 1: Eliminate the renderer self-lock

**Files:**
- Modify: `tools/experiment8/osm_global_waterway_store.py`
- Test: `tools/experiment8/tests/test_osm_global_waterway_package.py`

**Interfaces:**
- Produces: `_iter_exact_waterway_features(connection: sqlite3.Connection, *, source_binding: WaterwaySourceBinding)` for internal connection-owned iteration.
- Produces: `_configure_render_connection(connection: sqlite3.Connection) -> None` applying the exact five render PRAGMAs.
- Preserves: `iter_exact_waterway_features(database_path: Path, *, source_binding: WaterwaySourceBinding)` as the public query-only wrapper.

- [ ] **Step 1: Add a focused failing self-lock regression**

Build a completed fixture admission with at least two exact features. Open a real DELETE-mode writer, deliberately set `cache_size=1` and `cache_spill=1`, and use the old separate read-only generator while staging a valid large BLOB row so the writer becomes exclusive. Assert the old topology raises `sqlite3.OperationalError` at the successor read; then call `_stage_renderer_records` and require it to reach the second feature and commit.

```python
def test_renderer_staging_uses_one_connection_across_dirty_cache_spill(self) -> None:
    # Arrange the real fixture database and force a one-page spilling writer.
    # The explicit old-topology probe must reproduce "database is locked".
    # The production staging call must finish and persist renderComplete=True.
```

- [ ] **Step 2: Run the focused test and retain the deliberate RED**

Run:

```powershell
py -3.11 -m unittest tools.experiment8.tests.test_osm_global_waterway_package.GlobalWaterwayPublicationTests.test_renderer_staging_uses_one_connection_across_dirty_cache_spill -v
```

Expected: failure in the production staging path because it still opens `iter_exact_waterway_features(database_path, ...)` on a second connection.

- [ ] **Step 3: Add a failing runtime-cache contract test**

Patch `sqlite3.connect` with a recording connection factory around one fixture render and assert the render writer reports:

```python
{
    "journal_mode": "delete",
    "synchronous": 2,
    "temp_store": 1,
    "mmap_size": 0,
    "cache_size": -65536,
}
```

Expected before implementation: `cache_size == -2000` on the render writer.

- [ ] **Step 4: Factor the internal iterator and configure the writer**

Implement one connection-owned generator containing the current authentication loop. Make the public wrapper open/query-only/close and `yield from` the internal generator. Change `_stage_renderer_records` to call the internal generator with its writer connection. Add:

```python
def _configure_render_connection(connection: sqlite3.Connection) -> None:
    connection.execute("PRAGMA journal_mode=DELETE")
    connection.execute("PRAGMA synchronous=FULL")
    connection.execute("PRAGMA temp_store=FILE")
    connection.execute("PRAGMA mmap_size=0")
    connection.execute("PRAGMA cache_size=-65536")
```

Use it immediately after the render writer opens.

- [ ] **Step 5: Run focused and full waterway tests**

Run:

```powershell
py -3.11 -m unittest tools.experiment8.tests.test_osm_global_waterway_package.GlobalWaterwayPublicationTests.test_renderer_staging_uses_one_connection_across_dirty_cache_spill -v
py -3.11 -m unittest tools.experiment8.tests.test_osm_global_waterway_package -v
```

Expected: deliberate old-topology probe reproduces the incident; fixed production path and full suite pass.

- [ ] **Step 6: Commit**

```powershell
git add -- tools/experiment8/osm_global_waterway_store.py tools/experiment8/tests/test_osm_global_waterway_package.py
git commit -m "Prevent waterway renderer SQLite self-lock"
```

---

### Task 2: Add the authenticated render-only recovery boundary

**Files:**
- Create: `tools/experiment8/osm_global_waterway_recovery.py`
- Modify: `tools/experiment8/osm_global_waterway_package.py`
- Modify: `tools/experiment8/osm_global_waterway_store.py`
- Test: `tools/experiment8/tests/test_osm_global_waterway_package.py`
- Test: `tools/experiment8/tests/test_run_osm_global_waterway_package.py`

**Interfaces:**
- Produces: `WaterwayRenderRecoveryAuthority` as a private, exact incident policy owned by the package boundary.
- Produces: `recover_global_waterway_package(*, extraction_directory: Path, output_directory: Path, work_directory: Path, package_id: str, failure_log: Path, backup_receipt: Path, checkpoint_features: int = 100) -> GlobalWaterwayBuildResult`.
- Produces CLI: `run_osm_global_waterway_package recover-render` with required `--extraction`, `--output`, `--work`, `--package-id`, `--failure-log`, and `--backup-receipt`.

- [ ] **Step 1: Add exact-incident policy and rejection tests**

Tests must construct a fixture DB whose stored identities are rewritten to an exact test predecessor and whose render checkpoint is incomplete. Pin rejection for every independent drift:

```python
for mutation in (
    "ingest-incomplete", "admission-incomplete", "fatal-nonzero",
    "old-run-identity", "old-render-identity", "checkpoint",
    "render-row-count", "source-hash", "failure-log-hash",
    "backup-receipt", "sqlite-sidecar", "output-exists", "partial-exists",
):
    with self.subTest(mutation=mutation):
        self.assert_recovery_rejected_before_mutation(mutation)
```

The test compares all admission/source table counts and receipt bytes before and after each rejection.

- [ ] **Step 2: Run rejection tests and retain RED**

Run the new recovery test class alone. Expected: import/API failure because the recovery module and CLI do not exist.

- [ ] **Step 3: Implement pure authority validation**

In `osm_global_waterway_recovery.py`, define immutable incident constants for the exact predecessor code/runtime/run identities, failure class/message, checkpoint `renderedFeatures = 1200`, and required database length. Parse only canonical JSON receipts. Return one immutable plan containing preserved identities, renderer reset counts, and the new render identity; perform no mutation in validation.

- [ ] **Step 4: Implement the atomic renderer reset**

Under one `BEGIN IMMEDIATE` transaction:

```sql
DELETE FROM records;
DELETE FROM rendered_features;
DELETE FROM feature_ids;
DELETE FROM variant_ids;
DELETE FROM geometry_ids;
DELETE FROM label_ids;
DELETE FROM sourced_ids;
DELETE FROM meta WHERE key IN (
  'renderRunIdentity','renderCheckpoint','partialDirectoryOwner','buildCheckpoint'
);
```

Reset only renderer peak fields, preserve ingest peak fields, insert canonical `renderRecoveryReceipt`, insert the new `renderRunIdentity` and zero checkpoint, then commit. Re-read and verify every preserved receipt and source/admission table count plus every reset renderer count.

- [ ] **Step 5: Implement render-only continuation and final receipt binding**

The recovery entry point must verify bound input file hashes, open the existing DB without calling ordinary `_open_database`/`_ingest`, validate the recovery authority, perform the one-time reset, call fixed `_stage_renderer_records`, and publish normally. If the recovery receipt already exists and matches the new identity, skip reset and continue from its current render checkpoint. Embed `renderRecoveryReceipt` under `receipt["build"]["recovery"]`.

- [ ] **Step 6: Add byte-equivalence and interrupted-resume tests**

Render the same fixture clean and through recovery. Remove only `build.recovery` before comparison and require byte-identical manifest, records, index, semantic stream, and all remaining receipt fields. Pause the recovered render, rerun recovery mode, prove the reset count remains one, and require final equality.

- [ ] **Step 7: Wire and test the explicit CLI**

Add only `recover-render`; ordinary `render` must still reject identity drift. Test exact argument routing, canonical stdout, canonical structured stderr, nonzero rejection, and no direct store authority minting.

- [ ] **Step 8: Run focused and dependent suites**

Run:

```powershell
py -3.11 -m unittest tools.experiment8.tests.test_osm_global_waterway_package -v
py -3.11 -m unittest tools.experiment8.tests.test_run_osm_global_waterway_package -v
py -3.11 -m unittest discover -s tools/experiment8/tests -p 'test_*.py' -v
```

Expected: all tests pass with no new ordinary-render compatibility escape.

- [ ] **Step 9: Commit**

```powershell
git add -- tools/experiment8/osm_global_waterway_recovery.py tools/experiment8/osm_global_waterway_package.py tools/experiment8/osm_global_waterway_store.py tools/experiment8/tests/test_osm_global_waterway_package.py tools/experiment8/tests/test_run_osm_global_waterway_package.py
git commit -m "Recover authenticated waterway render state"
```

---

### Task 3: Review and freeze the recovery implementation

**Files:**
- Review: all Task 1 and Task 2 files
- Evidence: `C:/FlightAlert-test-artifacts/experiment8/water-render-recovery/`

- [ ] **Step 1: Generate a review package from `9403b75` to recovery HEAD**

Include commit list, diff stat, full unified diff, exact test commands/results, and all changed-file SHA-256 identities.

- [ ] **Step 2: Dispatch independent spec-and-quality review**

Require two verdicts: exact design compliance and code quality. Critical/Important findings block execution and return through TDD/re-review.

- [ ] **Step 3: Run final verification**

Run full Experiment 8 Python tests, `py_compile` for changed modules, `git diff --check`, secret/private-path/generated-junk scans, and confirm the worktree is clean at a committed recovery HEAD.

---

### Task 4: Preserve evidence and execute the recovered render

**Files/Artifacts:**
- Source DB: `E:/FlightAlert-exp8-work/osm-global-waterway-260629-work-policy3-successor-202f7d0/waterway-state.sqlite`
- Backup root: `D:/FlightAlert-old-experiments-archive/water-render-lock-recovery-20260713/`
- Output: `E:/FlightAlert-exp8-work/osm-global-waterway-260629-v4`
- New logs: `E:/FlightAlert-exp8-work/osm-global-waterway-260629-v4-render-logs-r5/`

- [ ] **Step 1: Capture immutable pre-recovery authority**

Require no producer/monitor/Python process, no DB sidecars, no output/partial sibling, lease unheld, exact Git HEAD, and exact terminal log identities. Copy the DB and logs to the fresh backup root, hash source and backup, and require byte length/SHA-256 equality before mutation.

- [ ] **Step 2: Run recovery validation only**

Execute the recovery CLI's validation mode against the live paths. Require exact acceptance document and zero file/stat changes to the source DB.

- [ ] **Step 3: Launch the reviewed recovery renderer detached**

Bind PID, creation time, command line, committed recovery source HEAD, stdout/stderr paths, DB preidentity, and backup receipt. Confirm forward checkpoint progress without opening SQLite from a second watcher while the writer is active.

- [ ] **Step 4: Launch a new final-package monitor generation**

Create monitor `r4` from the accepted monitor template with only the reviewed producer PID/creation/command and evidence-prefix changes. Run preflight, independent delta review, then launch detached. Never reuse terminal `r3` paths.

- [ ] **Step 5: Authenticate atomic publication**

On renderer exit, require output publication, absent partial sibling, empty stderr, complete recovery receipt, fixed-code run identity, and exact four-file water package inventory. Then require monitor `r4` to advance into merge/finalize rather than reporting producer loss.
