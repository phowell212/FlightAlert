# Experiment 8 OSM Named-Waterway Supplement Plan

> **Status:** Pilot authorization only. This plan does not authorize the dated
> planet download, a full-world bake, Android integration, phone changes,
> redistribution claims, or Experiment 8 success.

**Goal:** Add a source-honest, logically separable OpenStreetMap named-waterway
lane that can label prominent rivers such as the western Chester River without
copying a name onto Esri geometry, while keeping the complete Experiment 8
on-phone reference footprint inside the preferred `23,500,000,000`-byte package
gate and preferred `25,000,000,000`-byte device target. The user-authorized
fallback remains a package below `38,500,000,000` bytes and a complete device
below `40,000,000,000` bytes; it is not a reason to spend bytes without a
documented coverage or visual-fidelity benefit.

**Architecture:** The Esri and OSM sources remain different authorities. Exact
OSM root objects, relation membership, signed E7 coordinates, tags, versions,
and source hashes remain in detached audit evidence. A new typed OSM occurrence
and exact geodetic renderer geometry feed the common renderer stream under a
versioned `FLIGHT_ALERT_OSM_NAMED_WATERWAY` presentation policy. The phone
artifact is a separately identifiable ODbL database/lane. No name, geometry,
identity, empty-state, or dedupe fact crosses between Esri and OSM.

**Tooling:** One dated 212,933,228-byte Maryland OSM PBF pilot, isolated
hash-pinned `osmium-tool`, a project-owned independent selector/verifier,
pinned Python, `unittest`, the Experiment 8 semantic model/codecs, and external
evidence on the C/E/D drives. Any planet or multi-region work requires a later
separately reviewed and explicitly authorized plan.

---

## Non-negotiable boundaries

- A name is rendered only from an OSM way that itself owns the exact name and
  path, or from a named `type=waterway` relation over its explicitly referenced
  member geometry. Equal text, proximity, visual continuity, parent/child map
  tiles, approximate endpoints, or Esri geometry never establish ownership.
- Root selections and objects included only to satisfy references stay
  distinct. A referenced node, way, or relation cannot become a label
  candidate unless it independently satisfies the frozen root predicate.
- Relation paths use provider member order, roles, and exact shared node IDs.
  Never sort or stitch by coordinate proximity. Disconnected, branching, or
  ambiguous membership remains separate exact parts and is audited.
- The OSM lane has its own generation, source state, ID domains, coverage,
  semantic-empty proof, attribution, and integrity roots. Missing or corrupt
  OSM data is `Unavailable` for this lane and cannot change an Esri state.
- Whole words are shaped and placed atomically. No per-character semantic or
  package records are permitted. The current phone screenshot's oversized,
  crushed, fragmented, overlapping labels remain a hard rejection condition.
- The OSM supplement counts inside the same Experiment 8 package and device
  ceilings. It receives no additional storage allowance.
- `latest` aliases are forbidden. This plan cannot acquire a full planet file
  or a regional matrix.

## Current source finding

The official API specimen
`relation/12152277/full` is 140,387 bytes with SHA-256
`beea8b394d26fa86e3c372b678420a5fb84af801be7378a681f2f2976f35e99d`.
It contains relation version 2, timestamp `2024-11-20T18:48:42Z`,
`type=waterway`, `name=Chester River`, Wikidata `Q5093696`, eight explicitly
named `waterway=river` member ways, and 705 referenced nodes. Five member-way
bounds intersect the source-derived western phone corridor. This proves a
candidate source lane; it is not the final dated-planet authority.

An isolated `osmium-tool 1.11.1` fixture pipeline converted the XML, performed
sequential waterway-then-name selection, restored references with `getid -r`,
and passed `check-refs` with zero missing nodes. The selected PBF is byte-for-byte
identical to the converted source PBF at 7,743 bytes, SHA-256
`d9f424d1690c76c8246a167a1e7cab002e79044c2106716a76ee4e1b128d6778`.
This is preliminary evidence to reproduce from committed tooling.

---

### Task 0: Freeze the pilot runtime, drives, and immutable inputs

**Create:**

- `tools/experiment8/osm_hydro_source.py`
- `tools/experiment8/tests/test_osm_hydro_source.py`
- `docs/experiment8-osm-waterway-source-contract.md`

- [ ] Record the C, D, and E volume identities, media, exact free bytes, and
  storage watermarks. Use C only for the small isolated runtime, E for the fast
  Maryland pilot transaction, and D for durable evidence.
- [ ] Lock the isolated tool packages and extracted runtime:
  `osmium-tool_1.11.1-1build2_amd64.deb` SHA-256
  `d8e791ac3558aaafa95d3f6ac7329b15df2fb502bd6babff881e62830e49f906`
  and `libboost-program-options1.71.0_1.71.0-6ubuntu6_amd64.deb` SHA-256
  `389095c7167251ee73667031a4c0f45083a31347cc95faddbdf5b7d22ac4c774`.
  Record `osmium 1.11.1`, `libosmium 2.15.4`, host/WSL identity, locale,
  command lines, binary/library hashes, and dependency inventory.
- [ ] Hash-pin the Chester API XML and the immutable regional pilot at
  `https://download.geofabrik.de/north-america/us/maryland-260710.osm.pbf`.
  Require exactly 212,933,228 bytes and provider MD5
  `2642fa017680941a2fab4f96c23d9c03`; preserve the MD5 sidecar, headers, URL,
  acquisition UTC, PBF header/source timestamp, and an independently computed
  SHA-256. Never substitute `maryland-latest.osm.pbf`.
- [ ] Capture and hash the OSM copyright page, ODbL 1.0 legal text, OSMF
  attribution guidance, licence FAQ, and horizontal-layer guidance used for
  the architecture decision.
- [ ] Make every mismatch, redirect outside the locked provider set, short
  read, checksum failure, unexpected PBF header, stale partial, or insufficient
  transaction watermark fatal and quarantined.

Do not download a planet or any non-Maryland regional PBF in Task 0.

### Task 1: Specify exact root selection and reference closure

- [ ] Write RED tests for direct named ways, named waterway relations, unnamed
  waterways, named non-water objects, excluded waterway values, lifecycle
  tags, multilingual names, blank names, same-name disconnected paths,
  nested/branching/cyclic relations, missing references, duplicate IDs,
  antimeridian geometry, and invalid E7 coordinates.
- [ ] Freeze direct-way values separately from relation eligibility. Initial
  admitted line values are `river`, `stream`, `canal`, `tidal_channel`, and
  `wadi`. `dam`, `weir`, `lock_gate`, `dock`, `riverbank`, `ditch`, `drain`,
  areas, unknown values, and lifecycle-only waterways are excluded unless a
  later versioned policy explicitly admits them.
- [ ] Freeze retained display fields and numeric field IDs for exact `name`,
  `int_name`, `name:en`, `official_name`, and supported `name:<language>`
  values. Preserve each exact source string; do not transliterate, concatenate,
  or fabricate a fallback. Blank strings are not names.
- [ ] Implement a true conjunctive pipeline: first emit only candidate
  `waterway` ways and `type=waterway` relations without references; then apply
  the name predicate to that candidate stream; finally run `osmium getid -r`
  against the original locked PBF using only the selected root IDs.
- [ ] Keep the production selector provenance-incapable: its only public
  selection operation accepts immutable candidate XML bytes and returns
  in-memory canonical generic root-ID and selection-material bytes. It performs
  no final writes and cannot name Maryland, a runtime, or a source PBF. Require
  the live-verified provenance wrapper to invoke that exact imported callable,
  revalidate and reconcile both returned byte strings, bind their hashes and
  lengths to the candidate/source/runtime/command/policy identities, then own
  the only transactional final publication path.
- [ ] Implement an independent project-owned rescan that applies the predicates
  without importing the production selector, reconciles every root ID and
  object version, distinguishes selected roots from reference-only objects,
  proves recursive closure, and fails on additions, omissions, invalid objects,
  or unexplained missing refs.
- [ ] Emit separately hashed all-root selection, per-root closure-audit, and
  admitted-root manifests. The exact Maryland regional profile may mark only
  independently proven clipped relation roots as
  `Unavailable(source_incomplete)`; it may not reclassify them empty or silently
  drop them. Every selected direct way must close.
- [ ] For the Maryland profile, require one global closure probe, exact direct
  member attribution, independent singleton probes for predicted-incomplete
  relations, dependency propagation, singleton-union equality, one zero-missing
  predicted-complete relation union, then an admitted way-plus-relation
  `getid -r` exit 0 and `check-refs` success. Require exact counts,
  deterministic manifests, and identical cold/warm semantic results.
- [ ] Keep the future planet gate strictly stronger:
  `selected == complete == admitted`; any missing reference aborts the build and
  no regional quarantine rule is available.

### Task 2: Extend the typed renderer contract for exact OSM geometry

- [ ] Add a source type and ID domains that cannot collide with Esri. An OSM
  source feature identity includes dated source generation, object type, object
  ID, version, selected root or reference status, exact retained tags, exact
  ordered refs/members, and duplicate occurrence where applicable.
- [ ] Preserve source geometry as signed integer longitude/latitude in OSM E7
  units. Do not pretend the Esri tile-local rational source geometry is an OSM
  source representation.
- [ ] Add `OSM_WGS84_E7` as a language-neutral renderer coordinate/projection
  mode. Canonical renderer bytes retain exact E7 coordinates. Web-Mercator
  projection is presentation work and cannot alter source/variant/candidate
  identity.
- [ ] Define fixed Web-Mercator latitude limits, antimeridian unwrap, overflow
  checks, whole-path part ordering, and a deterministic device projection. For
  retrieval memberships, use independently verified conservative bounds with
  a documented numeric error margin; false-positive memberships are allowed,
  false negatives are fatal.
- [ ] Prove A/B codecs reconstruct identical E7 renderer records and ordering.
  Runtime clipping, projection, viewport wrapping, or placement must not mutate
  canonical geometry or label candidate hashes.
- [ ] Keep the full exact source occurrence detached from the phone hot record,
  while manifests bind the selected-root, closure, normalized-stream, and
  source-audit hashes.

This task may extend the Task 1 Experiment 8 renderer contract only after its
existing Esri semantics are independently accepted. It must not weaken or
reinterpret those semantics.

### Task 3: Define relation assembly and presentation policy

- [ ] A named relation owns only explicitly referenced member geometry. Keep
  provider member order and roles. Join adjacent members only when their exact
  node IDs meet in the stated order; preserve every discontinuity as a new part.
  Retain point members such as `source` and `mouth` in exact source/closure audit
  but never turn them into line geometry or line text. Reject unresolved nested
  cycles and audit branches rather than guessing.
- [ ] Direct named ways and named relations remain separate source occurrences.
  Freeze deterministic relation-versus-way placement priority, while retaining
  both in audit evidence and allowing whole-label collision to select at most
  one visible result.
- [ ] Compile the versioned
  `FLIGHT_ALERT_OSM_NAMED_WATERWAY` policy with OSM provenance. Use an explicit
  zoom/priority policy based only on admitted source type, exact geometry length,
  relation membership, and source tags; never claim Esri styling as OSM styling.
- [ ] The initial presentation target reuses the accepted visual intent—not the
  provenance—of the locked water-line rule: modest text size, intact shaping,
  `0.07` em letter spacing, 30-degree maximum bend, 1000-pixel repeat distance,
  avoid edges, keep upright, deterministic global phase, atomic whole-run
  acceptance, and viewport candidate dedupe.
- [ ] Store the complete source-owned river run and policy, never one baked
  midpoint/anchor/rotation. At each current fractional zoom and viewport, derive
  an eligible visible screen-space subpath from the full run, shape the word
  once, and adapt placement as the displayed river shape changes. Preserve
  stable pan/zoom continuity with retained-run validation and whole-label
  fade/handoff; hide the whole label when no legible run exists.
- [ ] Require the exact western Chester source path to produce applicable,
  intact `Chester River` candidates following the visible river at a frozen
  multi-zoom/fractional-zoom/pan matrix around the screenshot corridor. Require the
  unrelated eastern Esri and western OSM candidates to retain different IDs and
  provenance, with no cross-source semantic dedupe or geometry join.

### Task 4: Prove the Chester fixture end to end

- [ ] Reproduce the XML-to-PBF/filter/closure pipeline from committed tooling
  and manifests; independently reparse the XML and selected PBF.
- [ ] Reconcile relation metadata, all eight member IDs/versions, 705 node refs,
  exact tags, exact member order, exact E7 coordinates, exact geographic bounds,
  and the five western-corridor intersections.
- [ ] Generate the OSM source occurrence, exact relation parts, renderer
  candidates, both codec micro-packages, and independent package/source reports.
- [ ] Require semantic non-emptiness, source/path/text ownership, intact whole
  text, A/B renderer-stream equality, zero missing references, deterministic
  rebuild hashes, bounded decode scratch, and no per-character records.
- [ ] Add mutations for every selection, identity, relation, coordinate,
  attribution, and package-integrity field and prove fail-closed behavior.

### Task 5: Run only the dated Maryland pilot

- [ ] Acquire and verify the dated Maryland PBF only after Tasks 0-1 pass.
  Reprove Chester object versions/membership from that source; API XML is a
  fixture, not final authority.
- [ ] Record Maryland source bytes, selected roots by class, reference
  expansion, relation fan-out/parts, exact coordinate counts, selected PBF,
  normalized/spool bytes, unique strings, A/B package bytes, postings,
  placement memberships, page touches, block distributions, decode memory,
  elapsed time, and peak host memory/scratch.
- [ ] Independently account for every Maryland-selected root and reconcile the
  admitted complete subset and package. Any unaccounted missing reference,
  missing prominent in-coverage sourced name, inappropriate admitted type,
  corrupted relation, degenerate whole-label record, or unexplained
  source/package mismatch blocks the pilot.
- [ ] Report Maryland ratios only as bounded pilot measurements. Do not call
  them a global projection and do not extrapolate them into planet authority.

### Task 6: Freeze ODbL and source-honesty behavior

- [ ] Treat the filtered/indexed OSM lane conservatively as an ODbL derivative
  database. Keep it logically and physically separable from Esri records.
- [ ] Include `OpenStreetMap contributors` attribution, the OSM copyright URL,
  the ODbL URI/text, source snapshot, build-method/source-offer information, and
  database notices in the artifact and third-party notices.
- [ ] When OSM records are displayed, the app must visibly and honestly expose
  OSM attribution. It must not claim that Esri supplied OSM-only names.
- [ ] Preserve or offer the corresponding machine-readable OSM-derived database
  and reproducible transformation method. Document that app-store, EULA, and DRM
  terms cannot impose additional restrictions on recipients' ODbL rights.
- [ ] Obtain an explicit classification of the Esri+OSM use before any public
  release claim. Personal Experiment 8 success is not proof of Esri
  redistribution rights; `exportTilesAllowed` is not permission.

### Future work note: planet and diverse-region work is not authorized here

The current candidate immutable source is
`https://planet.openstreetmap.org/pbf/planet-260629.osm.pbf`, currently
93,653,630,756 bytes with official MD5
`ea988fc161cd54bf8f35eeb3ea8390dc`. These are non-authorizing planning facts.
No command in this plan may fetch that object, create a multi-region matrix, or
normalize a complete world OSM lane. Those actions require a later plan that is
reviewed after the Maryland results, includes diverse bounded samples and a
conservative projection, recomputes the full transaction watermark, and obtains
a fresh user-confirmed world-acquisition checkpoint.

---

## Maryland pilot completion gate

The Maryland pilot is complete only when all of the following are independently
proven:

- exact immutable Maryland/runtime/license locks and deterministic rebuilds;
- true conjunctive root selection plus independent full reconciliation;
- exact selection/closure/admission accounting, zero incomplete direct ways,
  zero unexplained missing references, and zero missing references in the final
  admitted closure;
- exact E7 geometry/projection semantics and cross-codec equality;
- relation assembly without inferred name/geometry transfer;
- Chester data-level and renderer-level success with no degenerate labels;
- complete Maryland measurements with no global extrapolation claim; and
- logically separable ODbL database/attribution/source-offer behavior.

Passing this gate does not authorize another regional file, a planet download,
world normalization, phone changes, public redistribution, release, or an
Experiment 8 success claim. It supplies evidence for a later separately reviewed
and user-confirmed world-acquisition plan.
