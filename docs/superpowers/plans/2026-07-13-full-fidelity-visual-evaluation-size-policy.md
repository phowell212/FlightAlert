# Full-Fidelity Visual-Evaluation Size Policy Plan

**Goal:** Build and evaluate the complete whole-world reference without
compressing or pruning it, while keeping release budgets truthful and every
non-size safety gate intact.

## Slice A: bind policy before recovered water rendering

1. Add a closed two-mode policy module with strict default behavior and a
   1,500,000,000-byte visual destination reserve. Establish exact partial
   ownership before measuring capacity; on every invocation derive capacity
   from fresh destination free bytes plus exact owned-partial bytes, keep that
   authority out of SQLite, and bind the fresh post-staging reserve measurement
   into the final receipt.
2. Bind the policy document/hash/module/mode into renderer identity, build
   receipt, recovery transition, and resume validation.
3. Preserve the historical 23.5/25/38.5/40 GB values and compute their status
   booleans from actual package bytes regardless of authorization mode.
4. Add mutation-free `validate-recovery` API/CLI using a read-only/query-only
   SQLite connection and one stable read snapshot, including exact prefix/
   ID-table authentication and fresh capacity checks for identity-owned
   publication-stage resumes. Execution holds a SQLite write reservation and
   rejects external commits between checkpoint reservations.
5. Revalidate the complete renderer code identity (including the tile-key
   model) and bound root/closure identities after long hashing and immediately
   before the one-time renderer reset.
6. Reject impossible or noncanonical UTC recovery timestamps.
7. Run focused and full Experiment 8 tests, freeze exact identities, and obtain
   independent spec and quality approval before touching live cook state.

## Slice B: final package and install path

1. Carry the same policy binding and final capacity evidence through package
   merge, rebind, install, and release receipts.
2. Keep full-fidelity data intact for the user's visual judgment.
3. Restore strict release mode for later compressed release candidates unless
   a newer explicit instruction changes that policy.

## Global constraints

- No size-driven content pruning.
- No weakening of format, index, integer, resource, provenance, atomicity,
  rollback, or source-honesty gates.
- Default and unspecified mode remains `budgeted-release-v1`.
- A successful host package is intermediate evidence; the installed real app
  is the visible acceptance target.
