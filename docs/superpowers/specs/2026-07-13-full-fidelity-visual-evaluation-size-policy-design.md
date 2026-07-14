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
  only when destination free space captured before its first owned staging
  write is at least the exact required package bytes plus 1,500,000,000 bytes.
  That first measurement is persisted in the checkpoint database and reused
  across an owned partial resume, so already-staged bytes are never counted
  twice. The final receipt also binds a post-staging free-space measurement;
  publication requires at least the full 1,500,000,000-byte reserve at that
  boundary and an unchanged immediate recheck.

Unknown values, aliases, booleans, and generic ignore flags reject. Visual mode
does not authorize pruning features, classes, names, geometry, zoom levels, or
source rows.

## Evidence

The canonical policy document, its SHA-256, the policy-module byte identity,
and the exact selected mode are part of the renderer run identity. The build
receipt contains that binding and the final capacity decision. Historical
23.5/25/38.5/40 GB thresholds and their booleans remain mathematically truthful
even when visual capacity authorizes an over-budget package.

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
recovery code, current renderer code, and the authorized size transition. It
accepts only the identity-owned partial on a publication-stage resume and
independently reconstructs its published size decision. It cannot reset,
stage, or publish. Execution repeats validation and, immediately
before renderer deletion, rehashes the root-ID/closure inputs and the complete
renderer code set inside the reserved transaction.

## Acceptance

Slice A is accepted only with RED-to-GREEN policy, publication, recovery,
resume, destructive-boundary, calendar/timestamp, and CLI tests; full
Experiment 8 discovery; exact source identities; and independent spec and code
review. Live databases, cooks, and devices remain untouched until that freeze.
