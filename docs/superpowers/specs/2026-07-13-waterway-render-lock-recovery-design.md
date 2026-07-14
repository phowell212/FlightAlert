# Experiment 8 Waterway Render-Lock Recovery Design

## Purpose

Recover the completed, authenticated whole-world waterway admission after the
renderer failed with `sqlite3.OperationalError: database is locked`, without
restarting extraction, weakening source identity, or presenting the failed
render as successful evidence.

The preserved database contains a complete admission for 5,433,355 selected
roots with `fatalCount = 0` and an incomplete renderer checkpoint containing
1,200 committed features. No output or partial-output directory exists.

## Root Cause

The renderer opens a DELETE-journal writer and a second long-lived read-only
feature connection. The writer does not restore the intended 64 MiB cache and
commits only every 100 rendered features. A dirty-page spill can therefore
promote the writer to an EXCLUSIVE lock. The same thread then resumes the
read-only connection and waits on its own writer until Python's five-second
SQLite timeout raises. The monitor never opens the database, and post-failure
forensics found no remaining external lock holder.

## Chosen Recovery

Use an explicit render-only recovery mode in the authoritative pipeline.

1. Preserve an exact hashed backup of the failed database and terminal logs on
   `D:` before mutation.
2. Require the exact known failed ingest/render identities, completed ingest
   and admission receipts, zero fatal roots, the exact incomplete render
   checkpoint, absent output/partial directories, and no SQLite sidecars.
3. Stream-verify the bound root-ID and closure-OPL byte lengths and SHA-256
   identities. Do not reparse all 281,984,067 source objects or recompute all
   5,433,355 admission decisions.
4. In one atomic SQLite transaction, record a recovery receipt and reset only
   renderer-owned tables and renderer metadata. Admission/source tables and
   their receipts are immutable across the transaction.
5. Render under the current fixed code identity. Exact feature authentication
   reads and renderer writes use the same SQLite connection, and the render
   connection applies the declared 64 MiB cache configuration.
6. Include the recovery receipt in the final build receipt. The output must
   identify the original admission identity, failed renderer identity,
   recovery implementation identity, reset checkpoint, and new renderer
   identity. It must never claim that the failed renderer produced output.
7. Support checkpointed continuation under the new recovery identity if a
   later interruption occurs. The one-time reset may not run twice.

## Rejected Alternatives

- **Unchanged restart:** preserves the old identity but repeats hours of full
  prefix authentication and deterministically recreates the same lock.
- **Ad-hoc database PRAGMA or metadata edit:** may make this run proceed but is
  not source-bound, reproducible, or honest.
- **Clean recook:** is technically simple but discards six hours of validated
  admission work and does not improve correctness.

## Components

### Single-connection exact-feature iterator

Factor the exact-feature implementation so the public read-only iterator still
opens its own query-only connection, while renderer staging consumes the same
writer connection it uses for staged rows. No cursor may remain live across a
yield.

### Render connection configuration

Apply `journal_mode=DELETE`, `synchronous=FULL`, `temp_store=FILE`,
`mmap_size=0`, and `cache_size=-65536` to the render connection. The build
receipt's existing 64 MiB cache claim must match runtime behavior.

### Recovery authority

Recovery is explicit and fail-closed. It accepts only the exact incident
predecessor identities and state. Any source, receipt, row-count, checkpoint,
sidecar, output-path, or code-identity difference rejects before mutation.

### Recovery receipt

The canonical receipt records:

- schema and recovery-policy identity;
- failed stderr identity and failure class;
- pre-recovery database identity and backup identity;
- preserved ingest/admission receipt identities;
- old render-run identity and 1,200-feature checkpoint;
- exact renderer row counts removed;
- new renderer-run and recovery-code identities;
- transaction completion and timestamp.

The final package build receipt embeds this document.

## Failure Handling

- Failure before the recovery transaction changes nothing.
- Failure during the transaction rolls back all renderer reset operations.
- Failure after the transaction resumes only through the new recovery mode and
  new identity; it cannot repeat the reset.
- Output publication remains no-replace and atomic.
- A new final-package monitor generation is required because monitor `r3` is
  terminal evidence and cannot be reused.

## Tests

1. Reproduce the real self-lock with DELETE journaling, a tiny writer cache,
   cache spilling, real staged BLOB rows, and the old two-connection topology.
2. Prove same-connection staging reaches the next feature and checkpoint.
3. Prove the render connection uses the exact 64 MiB cache target.
4. Prove recovery accepts only the exact incident identities and completed
   zero-fatal admission.
5. Prove recovery rejects altered source, receipt, checkpoint, output,
   sidecars, renderer rows, and recovery-code identity before mutation.
6. Prove the atomic reset preserves all admission/source rows and receipts,
   removes only renderer state, and records exact removed counts.
7. Prove a recovered fixture package is byte-identical to a clean fixed-code
   package aside from the explicitly embedded recovery evidence.
8. Prove interruption after recovery resumes without a second reset.
9. Run the full waterway suite and dependent Experiment 8 package tests.

## Acceptance

The recovery is accepted only after focused RED-to-GREEN tests, full dependent
tests, independent spec/code review, an immutable backup hash match, a clean
recovered render publication, and a new monitor generation that merges the
water package without weakening the final package or phone-install gates.
