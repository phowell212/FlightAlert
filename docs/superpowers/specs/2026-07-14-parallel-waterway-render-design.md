# Deterministic Parallel Waterway Render Design

## Purpose

Experiment 8 must produce the complete admitted whole-world waterway reference
for visual evaluation without changing its source truth, label semantics,
runtime records, or recovery guarantees. The current renderer processes
5,426,707 admitted features in one CPython loop. Existing evidence cannot
support an overnight ETA and shows a material multi-day risk, so that serial
implementation is not an acceptable use of this PC.

This design parallelizes only deterministic CPU work. It does not prune
features, reduce zooms, weaken prominence rules, change label content, use a
different codec, or claim coverage that the source does not prove.

## Selected Approach

Use a bounded Windows `ProcessPoolExecutor` for feature geometry, label,
presentation, and record-envelope construction. Keep one coordinator process
as the sole owner of the live SQLite connection, canonical source iterator,
global identity registry, SQL writes, checkpoints, and final package files.

The coordinator enumerates authenticated `ExactWaterwayFeature` values in the
existing canonical order. Each worker renders one feature using the unchanged
`build_adaptive_waterway_feature` implementation and writes one authenticated,
run-owned spool batch containing only pickle-safe primitives. Results may
finish out of order, but the coordinator consumes and stages them strictly by
global feature ordinal.

GPU rendering is not selected. The workload is dominated by branch-heavy OSM
relation traversal, zoom-dependent geometry simplification/intersection,
semantic label construction, canonical hashing, and DEFLATE preparation. A GPU
rewrite would have a larger semantic surface and would not preserve the current
Python/Android codec contract quickly enough for this visual-evaluation build.

## Architecture

### Coordinator

The existing `_stage_renderer_records` path remains the only database writer.
It retains:

- the existing `BEGIN IMMEDIATE` reservation and `PRAGMA data_version` guard;
- `_validated_renderer_prefix_stream` and its canonical feature order;
- the prefix-global `HotIdRegistry`;
- every insert into `records`, `rendered_features`, and the five stored ID
  tables;
- contiguous `checkpointFeatures=100` commits;
- pause, rollback, and resume semantics; and
- final tile traversal, hashes, manifest, and build receipt.

The coordinator submits at most a bounded number of uncommitted jobs and never
opens a second SQLite connection. It drains only the next required ordinal.
Ahead-of-gap results remain non-authoritative spool files.

### Worker

A worker receives an immutable job containing:

- global ordinal;
- one `ExactWaterwayFeature`;
- planet source SHA-256;
- classifier SHA-256; and
- the exact zoom tuple.

It uses a worker-local recording `HotIdRegistry`, calls the unchanged adaptive
renderer, converts the result into the same ordered SQL row values and record
envelopes used by `_stage_rendered_feature`, and records every registry call in
call order. It does not import, open, query, or write SQLite.

The worker writes a canonical binary batch to an exact run-owned path. The
batch has a versioned header, ordinal and exact source descriptor, payload byte
length, SHA-256, ordered registry events, ordered ID-table claims, ordered
record rows, rendered-feature row, and posting-byte peak. It publishes with an
atomic same-directory rename and returns only the descriptor, byte count, and
SHA-256 to the coordinator.

### Ordinal Reducer

For each next ordinal, the coordinator:

1. verifies the descriptor, batch size, SHA-256, schema, source descriptor,
   ordinal, and run identity;
2. replays every registry event into the prefix-global `HotIdRegistry` and
   requires identical fingerprints;
3. applies the existing five ID-table collision checks;
4. inserts the exact record rows and rendered-feature row in current traversal
   order;
5. advances only the contiguous checkpoint prefix; and
6. removes only that verified run-owned batch after its data is represented by
   the current transaction.

This preserves fatal cross-worker collision behavior and prevents an early
worker failure from allowing later ordinals into an authoritative checkpoint.

## Spool Ownership And Capacity

Spools live under one exact directory derived from the render-run identity in
the existing work directory. A canonical owner document binds the directory to
the package ID, render-run identity SHA-256, source binding, and spool schema.

On first use, the directory must be absent. On resume, the coordinator may
discard only exact batch/temp names under a directory whose owner document is
byte-valid and matches the active render identity. Unknown files, links,
reparse points, an incorrect owner, or paths outside that directory fail
closed. Spools are never accepted as recovery authority.

The number of workers, submitted jobs, total serialized input bytes, and total
spool bytes are bounded. Capacity checks include the maximum spool reservation
and retain the full-fidelity policy's 1,500,000,000-byte destination reserve.
The worker count is execution scheduling only; changing it cannot change the
render identity or package bytes.

## Determinism And Provenance

The serial and parallel schedulers must produce identical:

- logical rows in all renderer and ID tables;
- `records.fadictpack` and `tile-index.bin` bytes;
- renderer semantic SHA-256;
- manifest and build-receipt semantic/source fields; and
- collision, pause, checkpoint, and resume outcomes.

The store and parallel module code identities remain part of the new render-run
identity. The authenticated incident prefix belongs to the old renderer. The
new implementation therefore requires another mutation-free live
`validate-recovery` pass against the exact 61,499,113,472-byte database before
the reset. It must not be hot-swapped into a running or partially reset cook.

## Progress And ETA

The producer writes an external, atomically replaced progress document after
each committed 100-feature checkpoint. It contains only committed feature
count, elapsed monotonic duration, worker/in-flight/spool bounds, and observed
throughput. It never opens SQLite from a watcher and is not package authority.

After at least three production checkpoints, ETA is computed from committed
features and elapsed render time. The background producer continues through
Codex usage resets. Worker-count changes are permitted only at a clean
checkpoint/resume boundary.

## Failure Handling

- Worker exceptions, corrupt batches, wrong ordinals, wrong source descriptors,
  invalid hashes, or registry mismatches cancel outstanding work and fail the
  producer.
- SQLite rolls back uncommitted rows; only the last contiguous committed prefix
  is authoritative.
- Run-owned spool evidence is preserved on failure and cleaned only after exact
  owner verification on a later resume.
- No wildcard deletion, shared-file merge, WAL mode, worker SQLite connection,
  or competing producer is allowed.
- The accepted recovery validation and its backup/failure evidence remain
  immutable.

## Verification Gates

1. Fixture parity with workers `1` and `N`, including forced reverse/random
   completion, must produce byte-identical runtime files and semantic evidence.
2. Hidden registry collisions and all five stored ID-table collision domains
   must fail at the same ordinal before checkpoint publication.
3. Worker failure, corrupt spool, wrong descriptor, and interrupted higher
   ordinal completion must resume to the clean serial bytes.
4. Connection instrumentation must prove one SQLite connection, DELETE journal,
   no WAL/SHM, and no worker database access.
5. Slow-low-ordinal tests must prove the job, byte, spool, and memory bounds.
6. The complete Experiment 8 suite, Python compilation, and independent review
   must pass.
7. A new live query-only recovery validation must authenticate the exact
   database and new code identity before destructive reset.
8. Production progress must yield a measured ETA after three checkpoints. If
   the measured ETA remains impractical, stop only at a committed checkpoint
   and redesign rather than silently running for years.

## Release Boundary

Parallel rendering changes only the offline Experiment 8 producer. It adds no
runtime debug code or dependency to the Android app. After the water package is
complete, the already reviewed final merger, class-catalog finalizer,
full-fidelity pending installer, source-bound APK build, and phone visual gate
continue unchanged.
