# Full-Fidelity Visual-Evaluation Size Policy

## Purpose

Produce a complete, unpruned whole-world reference package for visual evaluation
before compression work resumes. The visual-evaluation authorization is not a
generic size bypass: it requires measured free space at the exact destination
and preserves every format, index, integer, resource, atomicity, hash, and
rollback bound.

## Modes

- `budgeted-release-v1` is the default and retains the existing strict
  requirement that a component remain below 38,500,000,000 bytes.
- `complete-uncompressed-visual-evaluation-v1` authorizes a complete package
  only from fresh filesystem authority obtained after the deterministic partial
  directory is owned. At each invocation, capacity is the current destination
  free bytes plus the exact bytes already present in that owned, non-redirected
  partial. Capacity is memory-only: neither SQLite nor a prior receipt can mint
  or preserve it across a resume. The final receipt binds the exact four-file
  package size and a fresh post-staging free-space measurement; publication
  requires at least the full 1,500,000,000-byte reserve at that boundary and an
  unchanged immediate recheck.

Unknown values, aliases, booleans, and generic ignore flags reject. Visual mode
does not authorize pruning features, classes, names, geometry, zoom levels, or
source rows.

## Evidence

The canonical policy document, its SHA-256, the policy-module byte identity,
and the exact selected mode are part of the renderer run identity. The build
receipt contains that binding, the fresh capacity decision, and actual
published-directory bytes. Recovered top-level projection, top-level decision,
and nested recovery decision must agree on those exact bytes and historical
23.5/25/38.5/40 GB threshold booleans, even when visual capacity authorizes an
over-budget package. The checkpoint database retains only immutable core
recovery evidence, never final publication bytes or reusable capacity.

## Recovery transition

The exact 2026-07-13 render-lock incident predates an identity-bound size
policy. Its pinned predecessor is treated only as the legacy implicit
`budgeted-release-v1` mode. Package-owned recovery authority binds that exact
predecessor to `complete-uncompressed-visual-evaluation-v1`; a CLI value cannot
mint a different transition. The recovery receipt and resume identity carry
the intended policy binding byte-for-byte.

## Read-only validation

`validate-recovery` opens the preserved SQLite database with URI `mode=ro` and
`PRAGMA query_only=ON`. It authenticates source files, external evidence,
database identity, receipts, table counts, output/sidecar absence, current
recovery code, the complete renderer code set (including the tile-key model),
the exact rerendered prefix and every renderer ID table, and the authorized size
transition. It accepts only the identity-owned partial on a publication-stage
resume, remeasures fresh destination free space, and derives reclaimable bytes
from a stable before/after exact partial inventory. It cannot reset, stage,
publish, or persist capacity. Prefix validation uses one read transaction;
execution holds a write reservation and rejects another connection's commit
between checkpoint reservations. Execution also repeats validation and, after
every potentially long input hash, makes a final fast sidecar/output/partial
check the last operation before the first renderer deletion inside the reserved
transaction.

## Acceptance

Slice A is accepted only with RED-to-GREEN policy, publication, recovery,
resume, destructive-boundary, calendar/timestamp, and CLI tests; full
Experiment 8 discovery; exact source identities; and independent spec and code
review. Live databases, cooks, and devices remain untouched until that freeze.
