# Deterministic Parallel Waterway Render Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render all 5,426,707 admitted whole-world waterway features with bounded multiprocessing while preserving serial runtime bytes, semantic evidence, collisions, checkpoints, and recovery honesty.

**Architecture:** The parent remains the only SQLite owner and canonical feature enumerator. Spawned workers perform only adaptive geometry/label/envelope construction in contiguous checkpoint-aligned microbatches and publish authenticated run-owned spools; the parent verifies and reduces every feature frame strictly by ordinal before the unchanged 100-feature checkpoint commit. Production first pauses after 300 committed features so the full cook proceeds from a measured ETA.

**Tech Stack:** Python 3.11 standard library (`concurrent.futures`, spawn multiprocessing, `dataclasses`, `hashlib`, `struct`, `pathlib`), SQLite DELETE/FULL mode, existing Experiment 8 codecs, `unittest`.

## Global Constraints

- Do not prune features, zooms, labels, geometries, source fields, prominence classes, or runtime records.
- Keep one live SQLite connection; worker code must not import or call `sqlite3.connect`.
- Keep `checkpointFeatures=100`, contiguous ordinal commits, DELETE journaling, `synchronous=FULL`, and `PRAGMA data_version` protection.
- Worker order/count is execution scheduling only and must not change runtime files or semantic evidence.
- Production uses at most 10 workers on this 12-core/24-thread, 42,854,248,448-byte host.
- Bound submitted jobs, total in-flight points, serialized input bytes, reserved spool bytes, and worker output bytes; one over-budget feature runs alone under the hard output ceiling.
- Initial production limits are 20 jobs, 12,000,000 aggregate points, 1,000,000 points per job, 2 GiB aggregate canonical input, 128 MiB canonical input per job, 20 GiB aggregate reserved spool, and 1 GiB per-job spool quota. The 300-feature probe may lower these limits, but may not raise them without another bounded resource test.
- Spools are exact-run-owned, same-volume, atomically published, and non-authoritative.
- ProcessPool submissions carry one canonical byte job only; workers decode it. The in-flight input bound is the exact byte length of those payloads, not an estimate of Python object/pickle size.
- Include the spool reservation in capacity checks and retain the 1,500,000,000-byte destination reserve.
- No worker SQLite, WAL/SHM, wildcard cleanup, unowned deletion, GPU rewrite, or competing producer.
- Add the parallel module unconditionally to `_render_code_identities`; a changed store/parallel code identity requires fresh query-only live validation before reset.

---

### Task 1: Authenticated Worker Batch And Recording Registry

**Files:**
- Create: `tools/experiment8/waterway_parallel_render.py`
- Create: `tools/experiment8/tests/test_waterway_parallel_render.py`

**Interfaces:**
- Consumes: `ExactWaterwayFeature`, `build_adaptive_waterway_feature`, `HotIdRegistry`, existing renderer/sourced-text codecs.
- Produces: `FeatureRenderFrame`, `FeatureRenderBatchJob`, `RegistryClaim`, `SpoolDescriptor`, `encode_feature_batch_job()`, `decode_feature_batch_job()`, `render_feature_batch_job()`, `read_feature_batch()`.

- [ ] **Step 1: Write failing registry and batch-authentication tests**

Add this replay test plus wrong-hash, wrong-ordinal, wrong-run, trailing-byte, symlink/reparse, and out-of-root cases:

```python
def test_recording_registry_replays_exact_events_in_call_order(self):
    worker = RecordingHotIdRegistry()
    first = worker.register(b"FAE8OSMID1\0", b"one")
    second = worker.register(b"FAE8OSMID1\0", b"two")
    parent = HotIdRegistry()
    replay_registry_claims(parent, worker.claims)
    self.assertEqual(first, parent.register(b"FAE8OSMID1\0", b"one"))
    self.assertEqual(second, parent.register(b"FAE8OSMID1\0", b"two"))
```

- [ ] **Step 2: Run RED**

```powershell
py -3.11 -m unittest tools.experiment8.tests.test_waterway_parallel_render -v
```

Expected: missing new module/interfaces.

- [ ] **Step 3: Implement immutable transport types**

```python
@dataclass(frozen=True, slots=True)
class FeatureRenderBatchJob:
    start_ordinal: int
    features: tuple[ExactWaterwayFeature, ...]
    source_generation_sha256: str
    classifier_sha256: str
    zooms: tuple[int, ...]
    render_run_identity_sha256: str
    spool_directory: str
    spool_byte_quota: int

@dataclass(frozen=True, slots=True)
class RegistryClaim:
    domain: bytes
    canonical_bytes: bytes
    full_sha256: bytes
    hot_id: int

@dataclass(frozen=True, slots=True)
class SpoolDescriptor:
    start_ordinal: int
    end_ordinal_exclusive: int
    file_name: str
    byte_count: int
    sha256: str
    source_range_sha256: str
    point_count: int
```

`RecordingHotIdRegistry.register()` must call `super().register()`, append the exact domain/canonical/fingerprint event, and return the unchanged fingerprint.

- [ ] **Step 4: Implement a bounded binary codec and worker**

Use versioned length-prefixed binary job and spool codecs. Range-check every integer and count before allocation; require EOF after the final field. The parent encodes one complete immutable `FeatureRenderBatchJob` into bytes and submits only that byte string to `ProcessPoolExecutor`; the worker decodes it before rendering. One output spool binds a contiguous range and `source_range_sha256`, the canonical SHA-256 over the ordered complete input feature frames. It cannot cross the next checkpoint or requested pause target and contains ordered per-feature output frames. Every output frame contains ordinal, exact source descriptor, rendered-feature row, ordered registry events, five ID-table row sets, ordered record SQL rows with encoded envelopes, and posting-byte peak.

`render_feature_batch_job(job_bytes)` must decode the canonical job, use a fresh recording registry for every feature, call the unchanged renderer, encode each frame through a quota-enforcing writer, write `<start>-<end>.batch.tmp-<pid>`, flush/fsync, and atomically replace `<start>-<end>.batch`. Return only `SpoolDescriptor`.

- [ ] **Step 5: Verify and commit Task 1**

```powershell
py -3.11 -m unittest tools.experiment8.tests.test_waterway_parallel_render -v
py -3.11 -m py_compile tools\experiment8\waterway_parallel_render.py
git diff --check
git add tools/experiment8/waterway_parallel_render.py tools/experiment8/tests/test_waterway_parallel_render.py
git commit -m "Add authenticated waterway render batches"
```

---

### Task 2: Bounded Ordinal Scheduler And Spool Ownership

**Files:**
- Modify: `tools/experiment8/waterway_parallel_render.py`
- Modify: `tools/experiment8/tests/test_waterway_parallel_render.py`

**Interfaces:**
- Consumes: Task 1 job/descriptor/batch functions.
- Produces: `ParallelRenderLimits`, `ParallelFeatureRenderer`, `prepare_spool_directory()`, `finish_spool_directory()`.

- [ ] **Step 1: Write failing reverse-completion and ownership tests**

Use a fake executor that completes checkpoint/pause-aligned ranges in reverse. Assert `next_batch()` yields contiguous ranges and frames `0,1,2...`, never crosses a checkpoint or pause target, splits at point/input bounds, never exceeds job/point/exact-canonical-input/reserved-spool bounds, and never yields range 1 before range 0. Reject incorrect source-range SHA, owner, unknown child, link/reparse, out-of-root path, stale temp name, and excessive actual or reserved spool bytes.

- [ ] **Step 2: Implement hard limits**

```python
@dataclass(frozen=True, slots=True)
class ParallelRenderLimits:
    workers: int
    max_in_flight_jobs: int
    max_in_flight_points: int
    max_points_per_job: int
    max_in_flight_input_bytes: int
    max_input_bytes_per_job: int
    max_spool_bytes: int
    max_spool_bytes_per_job: int

    def __post_init__(self) -> None:
        if not 1 <= self.workers <= 64:
            raise GlobalWaterwayPackageError("parallel render worker count is invalid")
        if not self.workers <= self.max_in_flight_jobs <= self.workers * 2:
            raise GlobalWaterwayPackageError("parallel render in-flight job bound is invalid")
        if (
            self.max_in_flight_points <= 0
            or self.max_points_per_job <= 0
            or self.max_in_flight_input_bytes <= 0
            or self.max_input_bytes_per_job <= 0
            or self.max_spool_bytes <= 0
            or self.max_spool_bytes_per_job <= 0
        ):
            raise GlobalWaterwayPackageError("parallel render resource bound is invalid")
```

The owner schema is `flightalert.experiment8.waterway-parallel-spool.v1` and binds package ID, complete canonical render-run SHA, source-document SHA, and batch schema. Publish `owner.json` by flush/fsync/atomic rename. A matching resume may remove only exact `^[0-9]{12}-[0-9]{12}\.batch(?:\.tmp-[0-9]+)?$` names under that owned directory.

- [ ] **Step 3: Implement bounded spawn scheduling**

Use `multiprocessing.get_context("spawn")` and `ProcessPoolExecutor`. Build consecutive microbatches of at most the remaining features before the next checkpoint or requested pause target, splitting earlier at `max_points_per_job` and the exact encoded job-byte `max_input_bytes_per_job`. Submit only the canonical job bytes. Reserve each job's points, exact bytes, and complete spool quota before submission and check free space while retaining the 1.5GB destination reserve. Maintain future-to-reservation and range-to-descriptor maps; verify completed descriptors and source-range SHA immediately but yield only `next_ordinal`. A feature larger than a batching bound runs alone under the output ceiling. On error cancel futures, wait for shutdown, and preserve owned spools.

- [ ] **Step 4: Verify and commit Task 2**

```powershell
py -3.11 -m unittest tools.experiment8.tests.test_waterway_parallel_render -v
py -3.11 -m py_compile tools\experiment8\waterway_parallel_render.py
git diff --check
git add tools/experiment8/waterway_parallel_render.py tools/experiment8/tests/test_waterway_parallel_render.py
git commit -m "Schedule bounded ordinal waterway workers"
```

---

### Task 3: Parent-Only SQLite Reduction And Checkpoint Parity

**Files:**
- Modify: `tools/experiment8/osm_global_waterway_store.py`
- Modify: `tools/experiment8/waterway_parallel_render.py`
- Modify: `tools/experiment8/tests/test_osm_global_waterway_package.py`
- Modify: `tools/experiment8/tests/test_waterway_parallel_render.py`

**Interfaces:**
- Consumes: `ParallelFeatureRenderer.next_batch()`.
- Produces: `_stage_feature_render_frame()` and parallel arguments on `_stage_renderer_records()`.

- [ ] **Step 1: Write failing serial/parallel parity tests**

Render the same fixture with workers 1, 2, and forced reverse microbatch completion. Require equal runtime-file bytes, renderer semantic SHA, logical records/rendered rows, and all five identity tables. Patch `sqlite3.connect` and prove exactly one connection and no worker SQLite import/call. Test that changing the parallel module bytes changes the canonical render-run identity and is rejected by recovery validation.

- [ ] **Step 2: Write collision/failure/resume tests**

Cover hidden registry collisions and `feature_ids`, `geometry_ids`, `label_ids`, `variant_ids`, `sourced_ids`, including a collision at an interior frame. Finish a later range before the first, fail the final feature before a checkpoint, and require no rows from that checkpoint. Corrupt a spool, require rollback to the prior committed prefix, resume, and require clean serial runtime bytes.

- [ ] **Step 3: Extract one batch reducer**

```python
def _stage_feature_render_frame(
    connection: sqlite3.Connection,
    *,
    ordinal: int,
    exact: ExactWaterwayFeature,
    frame: FeatureRenderFrame,
    registry: HotIdRegistry,
    peaks: dict[str, int],
) -> None:
    validate_frame_against_exact_source(ordinal, exact, frame)
    replay_registry_claims(registry, frame.registry_claims)
    for table, hot_id, full_sha256 in frame.identity_rows:
        _insert_hot_identity(connection, table, hot_id, full_sha256)
    for row in validate_and_decode_record_rows(exact, frame.record_rows):
        connection.execute(_INSERT_RECORD_SQL, row)
    connection.execute(_INSERT_RENDERED_FEATURE_SQL, frame.rendered_feature_row)
    peaks["featurePostingBytes"] = max(
        peaks["featurePostingBytes"], frame.posting_bytes
    )
```

Both workers=1 and workers>1 must use the same frame encoder/reducer. Parent validation must retain the exact `ExactWaterwayFeature`, verify envelope/tile/feature/sourced/variant/geometry/label relationships before SQL, and preserve current duplicate/collision error text.

- [ ] **Step 4: Integrate without changing commit semantics**

Extend `_stage_renderer_records` with keyword-only `parallel_limits` and `spool_directory`. After each next-range verified spool, call the per-feature reducer in order and run the existing checkpoint/pause block unchanged. Retain every consumed spool until its checkpoint commits, then delete it. Remove the empty owned spool directory only after render-complete commits.

- [ ] **Step 5: Verify and commit Task 3**

```powershell
py -3.11 -m unittest tools.experiment8.tests.test_waterway_parallel_render -v
py -3.11 -m unittest tools.experiment8.tests.test_osm_global_waterway_package.GlobalWaterwayStoreTests -v
py -3.11 -m unittest tools.experiment8.tests.test_osm_global_waterway_package.GlobalWaterwayRenderRecoveryTests -v
git diff --check
git add tools/experiment8/osm_global_waterway_store.py tools/experiment8/waterway_parallel_render.py tools/experiment8/tests/test_osm_global_waterway_package.py tools/experiment8/tests/test_waterway_parallel_render.py
git commit -m "Parallelize deterministic waterway feature staging"
```

---

### Task 4: Recovery Controls, Progress Evidence, And Probe Pause

**Files:**
- Modify: `tools/experiment8/osm_global_waterway_package.py`
- Modify: `tools/experiment8/osm_global_waterway_recovery.py`
- Modify: `tools/experiment8/osm_global_waterway_store.py`
- Modify: `tools/experiment8/waterway_parallel_render.py`
- Modify: both waterway test files from Tasks 1-3.

**Interfaces:**
- Adds CLI flags `--render-workers`, `--pause-after-features`, `--progress-file`.
- Adds external schema `flightalert.experiment8.waterway-render-progress.v1`.

- [ ] **Step 1: Write failing CLI/progress tests**

Require workers 1..64, positive pause count, and a fresh real progress parent. Reject existing unknown progress, link/reparse, or output-contained progress. After a checkpoint require schema, render-run SHA, checkpoint count 100, committed count, total admitted count, worker count, UTC/monotonic elapsed, throughput, and hard bounds. Cover a crash after SQLite commit but before progress replacement: authenticated same-run progress behind the checkpoint advances; progress ahead or bound to another run fails.

- [ ] **Step 2: Thread execution-only controls**

Pass CLI -> `recover_global_waterway_package` -> `_recover_bound_global_waterway_package` -> `_stage_renderer_records`. Do not add worker count or pause target to semantic/run identity. Defaults remain workers=1, no pause, no progress; the controlled production command explicitly uses 10.

- [ ] **Step 3: Publish atomic progress after committed checkpoints**

Write `<progress>.tmp-<pid>`, flush/fsync, and atomically replace only after `_commit_render_checkpoint` succeeds. On resume accept an authenticated same-run document at or behind the SQLite checkpoint and atomically advance it; reject one ahead or bound to another run. Watchers read this file/process state only, never SQLite.

- [ ] **Step 4: Verify pause/resume and commit Task 4**

Run a fixture with pause=300, then resume using a different worker count. Require byte equality with uninterrupted output and exact progress transitions. Run all focused waterway tests and commit with message `Add controlled parallel waterway progress`.

---

### Task 5: Full Verification, Review, And Source Freeze

**Files:**
- Review all Task 1-4 source/tests.
- Update ignored `.superpowers/sdd/progress.md` only with evidence.

**Interfaces:**
- Produces one clean reviewed commit suitable for live recovery validation.

- [ ] **Step 1: Run all Experiment 8 tests and compilation**

```powershell
py -3.11 -m unittest discover -s tools\experiment8\tests -p "test_*.py"
py -3.11 -m py_compile tools\experiment8\waterway_parallel_render.py tools\experiment8\osm_global_waterway_store.py tools\experiment8\osm_global_waterway_recovery.py tools\experiment8\osm_global_waterway_package.py
git diff --check
git status --short
```

Expected: every test passes; tracked worktree clean after commits.

- [ ] **Step 2: Run a realistic bounded workers-1 vs workers-10 benchmark**

Use retained small/medium/maximum-admitted point fixtures. Require exact runtime/index/semantic parity, bounded combined RSS/spool bytes, and faster mixed-fixture elapsed time with workers 10.

- [ ] **Step 3: Obtain two independent read-only reviews**

Freeze exact hashes. One reviewer checks the spec/determinism/recovery contract; one checks code quality, process cleanup, ownership, and test gaps. Any Critical/Important result returns to RED.

- [ ] **Step 4: Commit accepted implementation and record evidence**

Commit only scoped files/docs. Record the exact clean commit/hashes/tests in the ignored progress ledger.

---

### Task 6: Live Validation, 300-Feature Probe, And Background Cook

**Artifacts:**
- DB: `E:\FlightAlert-exp8-work\osm-global-waterway-260629-work-policy3-successor-202f7d0\waterway-state.sqlite`
- Prior accepted validation: `E:\FlightAlert-exp8-work\osm-global-waterway-260629-v4-validation-r6`
- New validation: `E:\FlightAlert-exp8-work\osm-global-waterway-260629-v4-parallel-validation-r1`
- New producer evidence: `E:\FlightAlert-exp8-work\osm-global-waterway-260629-v4-parallel-render-r1`
- Output: `E:\FlightAlert-exp8-work\osm-global-waterway-260629-v4`

**Interfaces:**
- Consumes Task 5 clean source and exact incident/backup evidence.
- Produces accepted validation, measured ETA, then complete water package.

- [ ] **Step 1: Verify clean process/output/lease boundary**

Require no water producer, output, partial, SQLite sidecar, or held device lease. Verify the exact DB identity and prior attestation. No watcher opens SQLite after producer start.

- [ ] **Step 2: Run a fresh detached query-only validation**

Persist native stdout/stderr/exit and before/after stat/boundary evidence. Require accepted nested validation, exact DB SHA, zero stderr, and no mutation under the Task 5 source.

- [ ] **Step 3: Run the 300-feature production probe**

```powershell
py -3.11 -m tools.experiment8.run_osm_global_waterway_package recover-render `
  --extraction E:\FlightAlert-exp8-work\osm-global-waterway-260629-extraction `
  --output E:\FlightAlert-exp8-work\osm-global-waterway-260629-v4 `
  --work E:\FlightAlert-exp8-work\osm-global-waterway-260629-work-policy3-successor-202f7d0 `
  --package-id world-osm-named-waterways-260629-v4 `
  --failure-log E:\FlightAlert-exp8-work\osm-global-waterway-260629-v4-render-logs-r4\stderr.log `
  --backup-receipt D:\FlightAlert-old-experiments-archive\water-render-lock-recovery-20260713\render-recovery-backup-receipt.json `
  --checkpoint-features 100 `
  --size-policy complete-uncompressed-visual-evaluation-v1 `
  --render-workers 10 `
  --pause-after-features 300 `
  --progress-file E:\FlightAlert-exp8-work\osm-global-waterway-260629-v4-parallel-render-r1\progress.json
```

The detached wrapper survives usage resets and reads only process/progress/file stats.

- [ ] **Step 4: Compute ETA and continue from evidence**

Compute `(5_426_707 - 300) / committed_features_per_second` from three checkpoints. If acceptable under the user's standing autonomy instruction, resume the same run without pause. Otherwise preserve the authenticated 300-feature checkpoint and optimize the measured hot path.

- [ ] **Step 5: Monitor without querying SQLite**

Track producer PID, progress, spool inventory, partial/output stats, CPU, memory, and E free space. Do not duplicate/restart a healthy process.

- [ ] **Step 6: Verify completion and enter final merge/install**

Require exit 0, state complete, no sidecars/spools/partials, exact build/runtime hashes, source/admission/recovery/size evidence, and honest incomplete-world claim. Then use the already reviewed v2 merger/finalizer, source-bound APK, transactional pending install, and stop for user visual judgment.
