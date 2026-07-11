# Experiment 8 Semantic and Codec Pilot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans task-by-task. Every implementation task starts RED, receives a specification review, then receives a code-quality review before the next task owns its outputs.

**Goal:** Implement one deterministic, source-honest semantic normalizer and two independently readable phone-package candidates over identical typed renderer inputs, then prove both candidates on the real source-honest smoke corpus.

**Architecture:** Verified Esri PBFs and the pinned root style are converted into a common canonical renderer stream before either package writer runs. Full source occurrences remain detached hashed evidence; immutable renderer variants, hot tile postings/ownership, and Flight Alert presentation tokens are separately encoded for the phone. Format A stores independent per-tile renderer records. Format B content-addresses byte-identical canonical variant payloads and stores adaptive metatile postings whose `feature_id` is the sole occurrence-specific hot renderer field. Both reconstruct the identical tile-specific renderer stream. Both formats share only immutable normalized inputs, numeric/string/style table definitions, source-present and semantic-empty proofs, and codec settings. A separate reader implementation verifies both formats from language-neutral contracts without importing writer serialization routines. This plan does not edit or integrate the Android app.

**Tech Stack:** isolated Python 3.10.11 environment, strict in-project MVT protobuf/command reader, `mapbox-vector-tile==2.2.0` for fixture encoding and non-authoritative cross-checks only, `zstandard==0.25.0`, pinned transitive dependency lock, `unittest`, raw DEFLATE level 9, Zstandard level 9, Esri Mapbox Style v8 JSON/PBF

## Global Constraints

- The C-drive working tree is authoritative.
- Preserve the user's existing Experiment 7 changes in `ReferenceDictionaryPackage.kt`, `ReferenceDictionaryOverlayRenderer.kt`, its documentation, and installer.
- Do not edit Kotlin or touch the phone in this plan. Android adapters and physical-device acceptance begin only after a package winner exists.
- Keep the completed/in-flight Plan 1 acquisition and immutable documentary archive under `D:\FlightAlert-test-artifacts\experiment 8`.
- Use the faster NVMe workspace `C:\FlightAlert-exp8-work` for normalized spools, package cooks, readback, and benchmarks. Before deleting any working artifact, hash-verify and mirror the accepted reports/manifests needed for documentary proof back to the D-drive archive.
- Before normalization and every cook, record free bytes and require a conservative watermark covering inputs, outputs, transactional duplicate space, and 5 GB reserve. Stop cleanly rather than deleting old experiments when the watermark fails; deletion requires a separately inventoried decision even though the user permits it if genuinely needed.
- Every `python` command below must execute `C:\FlightAlert-exp8-work\.venv\Scripts\python.exe` explicitly (or through a verified shell variable bound to that exact path); the unprovisioned global `python` is forbidden for Plan 2 evidence.
- Consume only acquisition rows whose source generation, wire/PBF hashes, and sidecars pass the Plan 1 contract.
- A source-population-present tile is `Ready(empty)` only after a valid PBF was fully normalized and a distinct hashed semantic-empty proof was emitted. Presence in source coverage plus absence from a block index is not enough.
- A coordinate absent from the pinned population is `KnownEmpty`. A malformed, missing, or mismatched present tile remains `Unavailable`/failed.
- Do not bake the full world. Plan 2 may use the 154-row smoke input, a bounded source-verified Chester River/current-phone visual-QA fixture, and, only after Plan 1 completes, the bounded Stage A input.
- Do not fetch Stage B or authorize a world bake from this plan.
- Never silently ignore an unsupported relevant style expression, ambiguous semantic classification, ID collision, oversized block, malformed geometry, or missing string/style reference.
- Determinism is required across input order, worker counts, serial/parallel normalization, and repeated cooks.
- GPU utilization is not a goal. CPU/I/O implementations win unless a bounded real normalization or compression stage is demonstrably faster end-to-end on the GPU.
- Degenerate label/reference visuals are a fail-fast Experiment 8 rejection, not deferred polish. Structural validity, speed, or size can never compensate for an applicable prominent sourced name being lost; oversized text; crushed glyph spacing; fragmented/overlapping words; unstable path rotation; or unreadable collision placement. A line name is applicable only when one source occurrence supplies both exact text and exact placement path (or a documented nonzero provider-stable ID joins them), its style/policy display interval contains the zoom, its unmodified rational path intersects the viewport safety region with a continuous segment long enough for the whole shaped run, and deterministic whole-label collision accepts it. The screenshot alone is not evidence that a particular segment owns a name. Equal text, proximity, ancestor tiles, apparent water continuity, similar geometry, or approximate endpoint matches never transfer a name.
- Keep every memory-mapped index segment below `268,435,456` bytes, every positional-read pack shard at or below `1,610,612,736` bytes (1.5 GiB), and every independently inflated feature/posting/tile block at or below `4,194,304` bytes.
- Package authorization ceiling is `23,500,000,000` bytes; design target is `22,000,000,000` bytes. Plan 2 records measurements but does not make the statistical world-size decision.

## Authoritative Inputs

- Verified source lock SHA-256: `9f9d8c4333feefc1a7dd4824d2d2a895c353a9bbbff7fcc407f4c5db18468252`
- Population SHA-256: `ef3a0ab58d422add4c50a85525ef578fcb9106570f99ce9d529d2c7626cf85a3`
- Style SHA-256: `92cec535724bebd560ce18ba47f5ddbc803e9bef61d8450bd24098f941276c5b`
- Metadata SHA-256: `29586b422c8a5a9baa942551f9d1af634dcaea0c95e04aae47f571f48ef48136`
- Source generation ID: `df14184ba52be529b3eff37cae5b881f5670ebe1011a38eacee83a5cddcabecb`
- Source-honest smoke SHA-256: `23f83daf76be44c51143e7dfe70ddf5ff3ab8d458d1fc9707cafa3ea8e7eb056`
- Stage A sample SHA-256: `5ec8f0f508ce2b5793b82e9c04a458b9c32a586b247af2ef360624f7bb3c00a3`
- Current-phone negative visual baseline: `C:\Users\Phineas\Documents\FlightAlert-test-artifacts\coordination\flightalert-current-for-zeus.png`, 2,998,896 bytes, SHA-256 `3d035b3df9cb00a8c4f939e25dcc4adab071b604a60a85acee777372d5426add`
- Typed specimen: `D:\FlightAlert-test-artifacts\experiment 3\phase2\phone-package-pull-codex\dc-baltimore-z10-z11-phone-v1`
- Engineering reference only: `D:\FlightAlert-test-artifacts\experiment 4\tools\build_esri_reference_package.py`

The retained Experiment 3 package proves typed binary random access, strings, styles, and real nonempty label/boundary/transportation/water/context records. Its content and missing builder are not reused. Experiment 4's FAD2 code informs failure tests and sparse indexing, but its simplified heuristics and incomplete world artifact are not the new semantic contract.

The locked smoke corpus contains 23,227 decoded features across 113 source layers, and every decoded PBF feature ID is `0`; content-derived identity is mandatory. Observed `(z, extent)` pairs include `(3, 33,554,432)`, `(5, 8,388,608)`, `(8, 1,048,576)`, `(11, 131,072)`, `(13, 32,768)`, and `(16, 4,096)`, preserving roughly constant global precision. Coordinates include provider buffers outside tile bounds. The normalizer therefore preserves each declared extent and signed coordinate exactly; it never assumes, rounds, repairs, drops, or clamps geometry to 4,096.

## Plan Series

This is Plan 2. Plan 1 acquired and verified the source corpus. Plan 2 implements and smoke-proves the common normalizer and both codecs. Plan 3 runs Stage A/Stage B comparisons, statistical projections, physical-phone package microbenchmarks, and the winner decision. A later integration plan adapts only the winning typed source to the app.

---

### Task 0: Freeze the Plan 2 runtime and storage preflight

**Files:**
- Create: `tools/experiment8/requirements-plan2.lock.txt`

- [ ] Record the exact Python/runtime contract: Python 3.10.11, Windows amd64, zlib compile/runtime 1.2.13, `mapbox-vector-tile==2.2.0`, `numpy==2.2.6`, `protobuf==6.33.6`, `pyclipper==1.4.0`, `shapely==2.1.2`, and `zstandard==0.25.0`.
- [ ] Use only `C:\FlightAlert-exp8-work\.venv`; do not mutate global Python. Preserve exact wheel/distribution hashes and `pip freeze --all` output in the external environment evidence.
- [ ] Record C/D drive identity, total/free bytes, and the free-space watermark before each real normalization/cook. Active work must fit on C with transactional headroom and 5 GB reserve; accepted evidence is hash-verified onto D.
- [ ] Run the pre-Plan-2 suite in the isolated runtime. Baseline observed on 2026-07-11: 104 tests passed in 34.421 seconds.
- [ ] Commit the dependency lock only after exact isolated-runtime readback; generated venv/wheels/evidence remain outside the repo.

```powershell
& 'C:\FlightAlert-exp8-work\.venv\Scripts\python.exe' -m unittest discover -s tools/experiment8/tests -t . -q
git add -- tools/experiment8/requirements-plan2.lock.txt
git diff --cached --check
git commit -m "Pin Experiment 8 pilot runtime"
```

Do not begin Task 1 until an independent specification reviewer confirms the lock/runtime/storage identities and an independent code-quality reviewer confirms the clean 104-test baseline.

---

### Task 1: Freeze the typed renderer and identity contract

**Files:**
- Create: `tools/experiment8/semantic_model.py`
- Create: `tools/experiment8/tests/test_semantic_model.py`
- Create: `docs/experiment8-renderer-contract.md`

**Interfaces:**
- Produces: numeric enums, exact `SourceOccurrence`, `CanonicalVariant`, and `TilePosting` encodings, reconstructed renderer records, canonical semantic byte encoding, full SHA-256 fingerprints, collision-checked 64-bit IDs, one renderer-return order, and renderer-contract hashes.
- Consumes: `TileKey` from Plan 1.

- [ ] **Step 1: Write failing model and canonicalization tests**

Create complete tests for:

- exact enum values for layer groups `PLACES`, `WATER`, `REGIONS`, `PUBLIC_LANDS`, `TRANSPORTATION`, and `CONTEXT`;
- feature kinds `LABEL`, `LINE`, and `POLYGON_OUTLINE`, plus point/path/polygon geometry kinds;
- strict signed integer geometry, part offsets, bounds, point-count ceilings, and exact EOF;
- exact declared extents and source-local coordinates with no rounding, checked signed-64-bit reduced-rational world identities, x-wrap identity without draw-coordinate wrapping, and antimeridian/cross-LOD golden cases;
- fractional zoom/style values represented as fixed-point integers, never runtime Python floats in canonical bytes;
- exact UTF-8 visible text without lossy truncation or implicit Unicode rewriting;
- `DisputeID=0` producing a false disputed flag;
- different source generation/layer/geometry/style/text producing different full fingerprints;
- identical renderer records from different input orders producing identical canonical bytes;
- fatal rejection of any 64-bit ID collision between unequal full SHA-256 digests, using an injectable digest collision;
- renderer-contract hash independence from package format and compression codec.
- a tile-specific source occurrence never becoming the Format B dedupe unit;
- byte-identical canonical variants being the only reusable Format B unit while tile postings reconstruct the exact Format A renderer multiset and order;
- exact required fields for numeric draw order, geometry ID, source-style IDs, render-token IDs, placement/collision, land evidence/status, and semantic subtype.
- exact line-label candidate identity/evidence, source-tile edge domain, source zoom, display band, projection mode, spacing/max-angle/avoid-edge tokens, and viewport-level candidate deduplication;
- descendant retrieval intersecting the same exact canonical source path without clipping/mutating its identity, while a non-intersecting sibling receives no candidate;
- multiple retrieval memberships yielding one whole-word viewport candidate, with equal-text disconnected paths remaining distinct.

Run and verify RED:

```powershell
& 'C:\FlightAlert-exp8-work\.venv\Scripts\python.exe' -m unittest tools.experiment8.tests.test_semantic_model -v
```

- [ ] **Step 2: Implement immutable semantic records**

The canonical model separates exact source provenance, immutable renderer payload, and tile ownership so Format B can reuse only complete byte-identical payloads without losing a source occurrence or changing the renderer result:

```python
@dataclass(frozen=True, slots=True)
class SourceGeometry:
    kind: GeometryKind
    tile_key: TileKey
    source_zoom: int
    declared_extent: int
    parts: tuple[int, ...]
    source_local_coordinates: tuple[int, ...]
    bounds: tuple[int, int, int, int]

@dataclass(frozen=True, slots=True)
class RendererGeometry:
    kind: GeometryKind
    parts: tuple[int, ...]
    world_denominator: int
    world_coordinate_numerators: tuple[int, ...]
    bounds_numerators: tuple[int, int, int, int]

@dataclass(frozen=True, slots=True)
class SourceOccurrence:
    tile_key: TileKey
    source_feature_ordinal: int
    feature_id: int
    dedupe_id: int
    source_layer: str
    source_geometry: SourceGeometry
    source_audit_sha256: str

@dataclass(frozen=True, slots=True)
class CanonicalVariant:
    canonical_variant_id: int
    dedupe_id: int
    geometry_id: int
    source_layer_id: int
    source_scale_band_id: int
    layer_group: LayerGroup
    feature_kind: FeatureKind
    semantic_subtype: int
    source_style_layer_ids: tuple[int, ...]
    render_style_token_ids: tuple[int, ...]
    text: str | None
    geometry: RendererGeometry
    min_zoom_centi: int
    max_zoom_centi: int
    fade_in_centi: int
    fade_out_centi: int
    draw_order: int
    priority: int
    placement: NormalizedPlacement
    land_evidence: LandEvidence
    protected_status: ProtectedStatus
    flags: int

@dataclass(frozen=True, slots=True)
class TilePosting:
    requested_tile: TileKey
    feature_id: int
    canonical_variant_id: int
    owner_tile: TileKey
    world_wrap: int
```

`NormalizedPlacement` and line-label variants explicitly carry `label_candidate_id`, `text_evidence_kind`, numeric `text_source_field_id`, `placement_source_feature_id`, `placement_geometry_id`, source tile/zoom/edge domain, `projection_mode`, exact display-centizoom interval, spacing, maximum bend angle, collision group, priority, avoid-edge/keep-upright flags, and an active-band limit. Define byte-exact `canonical_line_label_candidate_bytes` over every meaning/eligibility field: source-feature and placement-geometry full digests; evidence kind/source-field ID; exact text; style/policy digest; source tile/zoom/edge domain; projection mode; display interval; spacing/max angle/collision/priority; placement flags; and active-band data. `label_candidate_id = SHA256("FAE8LLB1\\0" || canonical_line_label_candidate_bytes)`. Exclude only documented transport membership fields: requested retrieval tile/metatile, feature page/local ordinal, deterministic owner, and world-wrap copy. PBF ID `0` can never satisfy a join.

Cross-LOD support projects the same occurrence and its complete canonical path into intersecting descendant retrieval/metatile memberships. It never copies a name onto child geometry. The canonical path and candidate ID remain unchanged; clipping is temporary placement work only. Viewport assembly unions and deduplicates memberships by candidate ID before shaping/placement, so retrieval-tile edges cannot split a word. A style-driven `ParentLabelBand` stores one occurrence with source zoom, exact display interval, source/style IDs, and a hard band limit; child tiles are derived arithmetically and never materialized as one posting per descendant.

`SourceOccurrence` is evidence-only and is never joined or required by a phone package reader. The hot renderer record is exactly `TilePosting + CanonicalVariant`; `feature_id` is the complete and sole occurrence-specific field in its package bytes, while `dedupe_id` comes from the variant. The hot record excludes source ordinal, source-layer string, source geometry, the 32-byte audit digest, and raw source properties. Package manifests bind the normalized/source-audit stream hashes, and the independent verifier cross-checks each hot `feature_id` against the external occurrence evidence. This keeps provenance complete without multiplying documentary bytes across the phone package.

`SourceGeometry` preserves declared extent `E` and every signed source-local coordinate exactly. `RendererGeometry` is a lossless canonical world-rational encoding: checked signed-64-bit numerators `(tile*E + coordinate)` over denominator `(2^z*E)`, reduced by the greatest common divisor shared by the denominator and all geometry numerators. It preserves part/ring order, winding, closure, and provider buffers. X reduces modulo one world only for point seam identity/ownership; renderer geometry stays unwrapped and `TilePosting.world_wrap` reconstructs an allowed wrapped point reuse. A fixed common grid is allowed only after proving conversion lossless for every admitted extent. Include x=0/x=max antimeridian and cross-LOD exact-equality goldens. Do not clamp, stitch, round, repair, drop, or merge merely similar geometries.

PBF feature ID `0` is unavailable, not a stable global identifier. Derive:

- tile-specific `feature_id = SHA256("exp8-feature-v1\\0" || generation || tile key || source layer || canonical typed properties || exact typed geometry || duplicate occurrence)`;
- `dedupe_id` for point labels from exact group/kind/NFC display text/class plus exact global anchor; for lines/polygons without a proven provider identity, use `feature_id` and claim no cross-tile merge;
- `canonical_variant_id = SHA256("FAE8VAR1\\0" || canonical_variant_bytes)`, where the canonical bytes explicitly serialize `dedupe_id`, numeric `source_layer_id`, numeric `source_scale_band_id`, exact rational geometry identity, semantic fields, placement fields, text, and ordered source-style/presentation-token IDs. The numeric/full digest ID itself is excluded from its preimage.

Domain-separate every digest and retain every full SHA-256 fingerprint in detached evidence. Hot IDs are the first eight digest bytes interpreted unsigned big-endian and stored little-endian. Any 64-bit collision between unequal canonical bytes/full digests is fatal; never rehash around it or merge records unless their complete canonical bytes agree. Assign string and style IDs by lexicographic canonical bytes, never encounter order.

Every ID preimage excludes its own numeric/full-digest field and includes an explicit domain/version prefix; the renderer contract documents each preimage byte-for-byte so no identifier is self-referential.

Tests mutate every serialized variant field one at a time and require a different full/hot variant ID. A separate test mutates only provenance fields in `SourceOccurrence` and requires identical canonical variant bytes/ID. Independent package readers recompute every variant ID from package bytes alone without consulting detached occurrence evidence.

Line-label tests additionally prove exact rational path/tile intersection, source-edge rather than descendant-edge avoidance, antimeridian bands, stable global placement phase, whole-run atomic acceptance, 1000 px repeat spacing, 30-degree maximum bend, and viewport deduplication. Mutate every identity field and require a different full/hot candidate ID; mutate only transport membership fields and require the same ID; reject forced 64-bit collisions. Same text on disconnected/similar paths, approximate endpoints, different LODs, and zero provider IDs remain distinct. Runtime clipping cannot alter canonical geometry/candidate hashes, and no semantic or posting stream contains per-character records.

Canonical records use the `N8T1` version/magic and integer/enumerated/NFC-string fields only. A reconstructed hot renderer record is exactly one tile posting joined to its referenced variant; detached `SourceOccurrence` bytes are not part of the phone record. Every adapter returns each requested tile in one order: `(draw_order, priority, layer_group, feature_kind, canonical_variant_id, feature_id, canonical_renderer_bytes)`. The global semantic stream prefixes packed tile key and uses that same order. Its hash is `SHA256("flight-alert-exp8-semantic-v1\\0" || repeated(u32le(record_length) || record_bytes))`; the independent verifier reimplements this serialization from the document rather than importing the writer.

- [ ] **Step 3: Document the language-neutral renderer contract**

`docs/experiment8-renderer-contract.md` defines all three encodings, their exact join into renderer records, state semantics, centizoom units, enums, the one renderer order, geometry/rational math, collision ownership, placement/collision fields, land evidence, style interpolation, and heap-weight calculation. It defines `N8T1` canonical semantic records and explicit domain/version identifiers for geometry, dedupe, variants, features, source audit, and the whole semantic stream. It preserves the approved Kotlin-facing `ReferenceTileSource`/`ReferenceTileLoad` contract without editing Kotlin.

- [ ] **Step 4: Verify and commit Task 1**

```powershell
& 'C:\FlightAlert-exp8-work\.venv\Scripts\python.exe' -m unittest tools.experiment8.tests.test_semantic_model -v
git add -- tools/experiment8/semantic_model.py tools/experiment8/tests/test_semantic_model.py docs/experiment8-renderer-contract.md
git diff --cached --check
git commit -m "Define Experiment 8 renderer semantics"
```

Expected: tests pass; specification and code-quality reviewers approve exact record semantics before source selection begins.

---

### Task 2: Compile the pinned style and explicit semantic policy

**Files:**
- Create: `tools/experiment8/style_contract.py`
- Create: `tools/experiment8/semantic_policy.py`
- Create: `tools/experiment8/tests/test_style_contract.py`

**Interfaces:**
- Produces: `compile_style_contract()`, explicit source-layer/group policy, canonical style tokens, compiled feature filters, text resolution rules, and a complete include/exclude audit over all pinned style layers.
- Consumes: Task 1 fixed-point style and enum types.

- [ ] **Step 1: Write failing style/policy tests**

Tests must cover:

- exact raw style SHA-256 verification before parse;
- scalar and stop-based numeric/color/dash/text style properties;
- feature-property functions and categorical/interval/exponential stops;
- filters `==`, `in`, `!in`, and nested `all` used by the pinned style;
- exact matched `text-field` extraction (`_name` or `_name_global` in required rules), UTF-8 NFC, end trimming only, and no retention of the 41 unused translation fields;
- line casing plus inner stroke ordering;
- label min/max zoom, opacity/fade, font, halo, placement, priority, repeat distance, and collision group;
- the locked `Water line/label/Default` rule compiling as one whole-text line label with source values `text-size=10`, `text-letter-spacing=0.07`, `text-max-angle=30`, `symbol-spacing=1000`, `text-max-width=8`, `symbol-avoid-edges=true`, and no per-glyph text records;
- direct styled line-label candidates retaining their own exact source path; same-name disconnected paths and PBF ID `0` never join;
- `Water line large scale` retaining real `_name_en` semantic evidence without automatically inheriting the provider's `Water line/label/Default` style;
- no production policy branch keyed to the literal text `Chester River`;
- direct z12 style provenance emitting `PINNED_STYLE_LINE_LABEL` from `_name_global`;
- explicitly enabled fallback provenance emitting `FLIGHT_ALERT_POLICY` from its own `_name_en` occurrence/path with a distinct policy digest/candidate ID;
- disabled fallback emitting no label from z8 named geometry, and enabled fallback never borrowing another tile/LOD geometry;
- boundary hierarchy/disputed/coastline/water flags, with boolean values interpreted by value rather than presence;
- explicit mapping of every relevant label/line/public-land outline source layer to one numeric group;
- explicit audited exclusion of satellite-base-owned fill/context layers and icon-only rules;
- public-land evidence that permits only source-explicit public/protected evidence into `PUBLIC_LANDS`, while name-derived or ambiguous forest/park/openspace/farming records are excluded or assigned neutral `CONTEXT` tokens;
- half-open style zoom intervals combined with provider decizoom `_minzoom`/`_maxzoom` into exact centizoom integers parsed through `Decimal`, never binary float;
- a hard failure for an unsupported expression on an included rule;
- a complete audit whose included plus excluded style-layer IDs equal all `916` pinned layers with no duplicate ownership.

Run and verify RED:

```powershell
& 'C:\FlightAlert-exp8-work\.venv\Scripts\python.exe' -m unittest tools.experiment8.tests.test_style_contract -v
```

- [ ] **Step 2: Implement a fail-closed Style v8 compiler**

The current style contains 916 layers: 778 symbol, 74 line, and 64 fill layers; 767 symbol layers are text-bearing. Compile the explicit Flight Alert source-layer allowlist plus locked-style selectors for required region/boundary/coastline, place, water, public-land-context, and transportation records. Preserve transportation even if the current interim renderer suppresses it; Plan 3 decides package viability over the approved common contract. Do not retain unrelated building, generic landcover/fill, bathymetry, urban, vegetation, or commercial-POI families merely because they are style-visible.

A feature is admitted only when its exact source layer is policy-allowlisted and an accepted locked-style rule matches, or the policy names that exact source layer as an audited fallback. Unknown operators, required style fields, or property types fail normalization. Missing properties are not coerced to zero/false. Preserve ordered `source_style_layer_ids` separately from versioned Flight Alert `render_style_token_ids`; source classification never inherits the light-basemap presentation colors.

The typed semantic record retains exact raw selector inputs needed by later placement and presentation, including `_symbol`, `_label_class`, `SelectionPriority`, `Viz`, `DisputeID`, `_minzoom`, `_maxzoom`, `_len`, `DirTravel`, `DisplayID`, `Alt_ID`, matched style order, and token IDs. Text transform remains a style token rather than a destructive string rewrite. A matched label with absent/blank display text emits no fabricated label and increments an audited no-text counter.

The current-phone screenshot is a negative acceptance baseline, not a style source. Its oversized, compressed, fragmented, overlapping path text must never be reproduced. The locked Esri water-line label rule supplies the source style evidence above; the renderer contract preserves each name as one shaped text run plus its complete line path/collision inputs. Later Android integration must prove appropriate smaller on-device sizing, intact glyph spacing/word shaping, stable baseline direction, and collision readability at this exact class of view before success.

Compile `Water line/label/Default` as direct `PINNED_STYLE_LINE_LABEL` provenance. A separately authorized named-geometry fallback must use its own versioned Flight Alert policy/token and `FLIGHT_ALERT_POLICY` provenance, may render only that occurrence's exact path, and cannot name another tile/LOD geometry. Prefer direct styled candidates through deterministic placement priority, never semantic merging.

Land context uses an explicit `land_evidence` enum and `protected_status` (`SOURCE_EXPLICIT`, `NAME_DERIVED`, `AMBIGUOUS`, or `NOT_APPLICABLE`). Only `SOURCE_EXPLICIT` evidence may enter `PUBLIC_LANDS` or receive protected/public presentation. `Park or farming`, `forest or park`, and `openspace or forest` do not prove ownership; name-derived/ambiguous records are either excluded or assigned neutral `CONTEXT`, and may never inherit public-land colors, labels, attribution, or claims. Tests assert final renderer group and render-token identity, not merely an internal boolean.

Classification tables are exact, not name heuristics. Boundary `_symbol` values 0..5 map to admin levels 0..5; 6..11 map to the disputed form of level `_symbol-6` only when `DisputeID != 0`; locked-style `Viz=3` is excluded. Watershed boundary is a water-boundary kind, never an admin boundary. Road `_symbol` 0..10 maps to freeway, highway, freeway/highway ramp, major, major ramp, minor, minor ramp, local, service, pedestrian, and 4WD respectively; tunnel is orthogonal and shields/one-way come from exact matched style IDs. Water-line `_symbol` 0, 1, and 4 map to stream/river, canal/ditch, and intermittent. Water-area `_symbol` 7, 6, 3, 1, and 2 map to lake/river/bay, intermittent, swamp/marsh, playa, and ice. Transportation-place kind comes from exact style identity, never display-name text.

Every style layer receives exactly one audit outcome:

- included with source layer, group, kind, rule/token IDs, and supported operators;
- excluded with a stable policy reason such as `satellite_base_owned_fill`, `icon_only`, or `not_renderer_contract`;
- extraction failure.

Do not use free-form source-layer substring heuristics after compilation. The tracked policy owns the mapping. Runtime package records contain numeric group/style IDs only.

- [ ] **Step 3: Bind the real pinned style**

Run the compiler twice against the authoritative style. Write byte-identical audit/catalog evidence under:

Write active outputs under `C:\FlightAlert-exp8-work\pilot\style-contract\`, then hash-verify and mirror the accepted audit/catalog/manifest evidence under `D:\FlightAlert-test-artifacts\experiment 8\pilot\style-contract\`.

Require exact input/output hashes, 916-layer reconciliation, nonzero included rules for labels, boundaries, water, public lands, and transportation, and zero unsupported included expressions.

- [ ] **Step 4: Verify and commit Task 2**

```powershell
& 'C:\FlightAlert-exp8-work\.venv\Scripts\python.exe' -m unittest tools.experiment8.tests.test_style_contract -v
& 'C:\FlightAlert-exp8-work\.venv\Scripts\python.exe' -m unittest discover -s tools/experiment8/tests -t . -q
git add -- tools/experiment8/style_contract.py tools/experiment8/semantic_policy.py tools/experiment8/tests/test_style_contract.py
git diff --cached --check
git commit -m "Compile Experiment 8 semantic styles"
```

Do not begin Task 3 until an independent specification reviewer approves the compiled policy/style semantics and an independent code-quality reviewer approves implementation/tests.

---

### Task 3: Normalize verified source tiles into a canonical spool

**Files:**
- Create: `tools/experiment8/mvt_reader.py`
- Create: `tools/experiment8/normalize.py`
- Create: `tools/experiment8/normalize_sample.py`
- Create: `tools/experiment8/tests/test_mvt_reader.py`
- Create: `tools/experiment8/tests/test_normalize.py`

**Interfaces:**
- Produces: strict typed MVT source events, hash-addressed `.fanorm` source-occurrence/variant/posting files, `normalized-inventory.jsonl`, `normalized-summary.json`, and an explicit coverage-state inventory.
- Consumes: verified acquisition inventory/cache, Task 1 semantic model, Task 2 style contract.

- [ ] **Step 1: Write failing normalizer tests**

Use hand-authored raw protobuf/geometry-command goldens plus independently encoded real MVT fixtures to prove:

- raw protobuf varints/fixed32/fixed64/length fields, UTF-8, signed/unsigned value domains, unknown-field skipping, exact EOF, and byte/field ceilings;
- MVT tag pairs, key/value indexes, duplicate property keys, exact value wire type and float/double bit pattern, feature type, and command/count streams are preserved or rejected by the documented rule;
- MoveTo/LineTo/ClosePath cardinality, truncated/overflowed deltas, repeated close, missing close, invalid geometry-type command sequence, degenerate ring/line, and zero-area ring are rejected rather than repaired or silently dropped;
- y-down coordinates and every observed source extent (4,096, 32,768, 131,072, 1,048,576, 8,388,608, and 33,554,432), negative/over-extent provider buffers, asymmetric geometry, exact source-local coordinates, reduced-rational cross-LOD equality, and antimeridian wrap identity are honored without rounding/clamping;
- exact source PBF/sidecar/generation/hash validation occurs before normalize;
- point, multiline, polygon-outline, and buffered/out-of-tile coordinates globalize correctly;
- only style-matched records are emitted and every omission has an audit reason;
- label text, line placement path, anchor, rank, collision fields, and zoom/fade intervals survive;
- `DisputeID=0` is false and nonzero is true;
- PBF ID `0` never becomes a trusted source global ID;
- exact point-label seam duplicates with the same text/class/global anchor receive the same dedupe ID;
- similar but nonidentical clipped geometry never merges;
- a source-present semantic-empty tile emits `Ready(empty)` coverage state;
- a source-present tile without either a valid normalized block or an independently hashed semantic-empty proof is `Unavailable`;
- a source-proven absent coordinate emits `KnownEmpty` without opening a PBF;
- malformed geometry, unknown style token, excessive points/records, and unsupported included rules fail;
- worker counts 1 and 8 and shuffled input order produce byte-identical normalized inventory/summary/tile bytes;
- injected publication failure restores the previous complete output set.

The authoritative reader is the strict project-owned raw reader. `mapbox-vector-tile` may encode fixtures and cross-check valid decoded content, but its decoder is never a source-truth or verification boundary because it can repair polygon closure, drop zero-area rings, collapse value wire types, and overwrite duplicate keys.

Run and verify RED:

```powershell
& 'C:\FlightAlert-exp8-work\.venv\Scripts\python.exe' -m unittest tools.experiment8.tests.test_mvt_reader tools.experiment8.tests.test_normalize -v
```

- [ ] **Step 2: Implement bounded resumable normalization**

`normalize_sample` accepts the hash-pinned acquisition inventory, source-honest sample, verified lock, style contract, cache root, workers `1..16`, and output directory. It:

1. rehashes each PBF and sidecar and checks the acquisition inventory;
2. decodes protobuf and MVT geometry commands with the strict raw reader, preserving exact typed values/float bits and rejecting malformed or duplicate-key features rather than repairing them;
3. applies compiled feature filters and style rules;
4. writes canonical per-tile normalized bytes through same-directory temporary files;
5. records full fingerprints, numeric IDs, counts, source audit hashes, and coverage state;
6. reuses a tile only when every input/output identity revalidates;
7. keeps at most twice the worker count in flight;
8. publishes inventory/summary atomically and deterministically.

The normalized evidence set contains `normalization-policy.json`, `style-catalog.json`, `normalized-inventory.jsonl`, per-tile `.fanorm` files, `normalized-summary.json`, `source-audit.jsonl`, `unsupported.jsonl`, `semantic-hashes.json`, and `independent-verification.json`. Source audit hashes bind the exact PBF SHA, generation, z/x/y, layer, feature ordinal, canonical typed source properties, and decoded geometry. A valid source-present tile with zero accepted records receives a distinct hashed empty proof; failed decode/style/geometry work never does.

Use process workers only if measured faster for real MVT decode/normalization; otherwise use bounded threads/serial work. Do not add GPU work.

- [ ] **Step 3: Normalize and audit the real smoke corpus**

Output:

Active output: `C:\FlightAlert-exp8-work\pilot\smoke\normalized\`. Accepted policy, style catalog, summary, source audit, unsupported inventory, semantic hashes, and independent-verification evidence are hash-verified and mirrored to `D:\FlightAlert-test-artifacts\experiment 8\pilot\smoke\normalized\`.

Expected: 154 input states reconcile to 127 acquired present tiles plus 27 known-empty; zero source/style/geometry failures; nonzero applicable records in every required group; serial and parallel normalized hashes/bytes match; known-empty coordinates have no source file or normalized feature block.

Also acquire and hash-pin a minimal current-phone visual-QA source fixture through the hardened Plan 1 cache after the Stage A writer releases its lock. Strictly reverify the complete relevant LOD chain and viewport halo. Normalization must retain every verified occurrence's exact name, full geometry/path, source-tile edge domain, zoom interval, and whole-label style/placement fields; omission is a hard failure where that exact path is applicable. Require `Chester River` in an eastern viewport intersecting its verified sourced path, and require honest absence in the western Radcliffe/Island/Dam viewport unless another verified occurrence supplies a western named path. Never borrow the eastern name through visual continuity. If the user requires the western corridor labeled and Esri supplies no named path at any relevant LOD, add and independently lock a real hydrography source with exact named geometry before claiming success. The fixture also asserts that normalization never emits per-character fragments or oversized presentation tokens inconsistent with locked style evidence.

- [ ] **Step 4: Verify and commit Task 3**

```powershell
& 'C:\FlightAlert-exp8-work\.venv\Scripts\python.exe' -m unittest tools.experiment8.tests.test_mvt_reader tools.experiment8.tests.test_normalize -v
& 'C:\FlightAlert-exp8-work\.venv\Scripts\python.exe' -m unittest discover -s tools/experiment8/tests -t . -q
git add -- tools/experiment8/mvt_reader.py tools/experiment8/normalize.py tools/experiment8/normalize_sample.py tools/experiment8/tests/test_mvt_reader.py tools/experiment8/tests/test_normalize.py
git diff --cached --check
git commit -m "Normalize Experiment 8 semantic tiles"
```

Do not begin Task 4 until an independent specification reviewer approves strict-source parity and normalized encodings and an independent code-quality reviewer approves implementation/tests.

---

### Task 4: Implement common binary tables, coverage, and deterministic codecs

**Files:**
- Create: `tools/experiment8/binary_contract.py`
- Create: `tools/experiment8/codecs.py`
- Create: `tools/experiment8/tests/test_binary_contract.py`
- Create: `docs/experiment8-package-contract.md`

**Interfaces:**
- Produces: unsigned/signed varints, delta geometry, canonical strings/styles, separate sparse source-present/semantic-empty proof files, fixed index entries, block integrity entries, raw DEFLATE9/Zstandard9 codecs, and exact bounded readers used only by writer-side tests.

- [ ] **Step 1: Write failing binary/codec tests**

Test golden bytes and rejection for:

- unsigned varint and ZigZag signed boundaries, overflow, overlong encodings, and exact EOF;
- delta-coded multipart geometry and bounds;
- UTF-8 string table sorted by raw bytes with ID 0 reserved for absent;
- style table sorted by canonical style bytes with no float fields;
- fatal full-fingerprint-to-64-bit collision rejection;
- source coverage `zNN.cov` and semantic-empty proof `zNN.empty` files containing sorted unique packed keys, with every empty key required to be source-present;
- source-present plus indexed block resolving `Ready(nonempty)`;
- source-present plus semantic-empty proof and no block resolving `Ready(empty)`;
- source-present with neither block nor semantic-empty proof resolving `Unavailable`;
- population-absent resolving `KnownEmpty`, and invalid/out-of-LOD z/x/y resolving `OutsideCoverage`;
- raw DEFLATE level 9 (`wbits=-15`) and Zstandard level 9 deterministic round trips;
- exact compressor finish/strategy/runtime identities and canonical manifest JSON bytes;
- codec mismatch, truncated stream, trailing bytes, checksum/hash mismatch, zip bomb, and raw block over 4 MiB;
- exact 72-byte little-endian index entries and rejection of nonzero reserved bits, overlap, overflow, inconsistent lengths/counts, or wrong block digest;
- mapped index segmentation before 256 MiB and positional pack rollover at 1.5 GiB;
- bounded `ParentLabelBand` descriptors, exact source-path/metatile membership encoding, deterministic owner keys, and a frozen per-query placement-block/page-touch ceiling;
- sequential reusable-buffer decoding with aggregate scratch ceilings of 6 MiB for Format A and 10 MiB for Format B, plus a 32 MiB exact-weight reconstructed-renderer heap ceiling;
- repeated serial/parallel table builds producing byte-identical files.

- [ ] **Step 2: Implement the common contract**

Source coverage is a sparse sorted `uint64` key file per zoom. Across all 2,802,117 present coordinates its raw key payload is only about 22.4 MB and avoids a dense z16 bitmap. A separate sorted semantic-empty proof set contains only source-present coordinates whose valid PBF normalization produced zero admitted records. The state machine is exact:

- indexed block + source-present -> `Ready(nonempty)`;
- semantic-empty proof + source-present + no block -> `Ready(empty)`;
- source-present with neither proof -> `Unavailable`;
- population absent within z0..16 -> `KnownEmpty`;
- invalid coordinate or request outside declared source/display mapping -> `OutsideCoverage`.

Requests above source z16 may map/clamp to the z16 source LOD under the documented display mapping, but the package never claims that z17+ source data exists.

Every block index entry is exactly 72 bytes little-endian:

```text
primary_key:u64, pack_id:u32, codec:u8, flags:u8, reserved:u16,
offset:u64, compressed_length:u32, raw_length:u32,
crc32:u32, record_count:u32, sha256:bytes[32]
```

The package contract defines the `primary_key` per index family: packed requested tile for Format A tile blocks; exact `feature_page_id` for direct Format B page lookup (postings supply page ID/local ordinal and the page verifies the expected canonical variant ID); and packed metatile coordinate `(metatile_x << 29) | metatile_y` for direct/placement postings. Placement files live under `placement/zNN/bBBBB/sSS/`, so their path/header binds display zoom, exact parent-band ID, and adaptive metatile size; the 58-bit metatile coordinate is therefore unambiguous. Index files are segmented below 256 MiB and may be memory-mapped. A headerless 72-byte segment may contain at most 3,728,270 entries (268,435,440 bytes). Pack shards roll at or below 1.5 GiB and are read by bounded positional reads; they are not whole-file memory maps.

Pin codec settings:

```python
zlib.compressobj(
    level=9,
    method=zlib.DEFLATED,
    wbits=-15,
    memLevel=9,
    strategy=zlib.Z_DEFAULT_STRATEGY,
)
zstandard.ZstdCompressor(
    level=9,
    threads=0,
    write_content_size=True,
    write_checksum=True,
    write_dict_id=False,
)
```

Raw DEFLATE additionally pins `strategy=zlib.Z_DEFAULT_STRATEGY` and uses exactly one ordered input stream followed by `flush(zlib.Z_FINISH)`. Manifests record Python, package, `zlib.ZLIB_VERSION`, and `zlib.ZLIB_RUNTIME_VERSION`; any different runtime is a distinct package identity requiring byte-identity reproof. Bounded decompression supplies at most `raw_length+1` output space, requires exactly `raw_length`, `eof=True`, no `unused_data`/`unconsumed_tail`, and no trailing frame/bytes. SHA-256 covers the exact compressed block bytes named by the index; CRC32 covers the exact raw block bytes. Both are checked before a decoded record is trusted.

`docs/experiment8-package-contract.md` is the language-neutral authority for both independent readers. It freezes file/header magics and versions, integer endianness, enum/codec/flag IDs, canonical manifest JSON (`UTF-8`, sorted keys, compact separators, one final LF, no timestamps/absolute paths), string/style/coverage/empty/integrity encodings, per-index primary-key meaning, 72-byte entries, block framing, compressed-vs-raw hash targets, segmentation/sharding, exact EOF, parent-label bands, placement membership/ownership/page-touch limits, and renderer/display-query reconstruction order. Format-specific modules may implement it but cannot be its only specification.

Readers decode sequentially through reusable bounded buffers. Format A peak scratch is one at-most-4-MiB direct/placement block plus at most 2 MiB codec state (`6,291,456` bytes total). Format B peak scratch is one at-most-4-MiB placement/posting block plus one at-most-4-MiB feature page plus at most 2 MiB codec state (`10,485,760` bytes total); touched pages are never inflated simultaneously. Exact documented heap weights cap reconstructed renderer output at `33,554,432` bytes per display query. Cook, verification, and read fail closed when aggregate scratch, page-touch, or reconstructed-heap ceilings would be exceeded.

Every block has explicit codec, raw/compressed length, full SHA-256, CRC32, record ceilings, and exact EOF checks. `integrity.bin` retains full file/table hashes; no 32-bit hash is a trust boundary. The writer retains full 256-bit IDs in evidence and fails the entire cook on any unequal canonical-byte collision under a hot 64-bit ID.

- [ ] **Step 3: Verify and commit Task 4**

```powershell
& 'C:\FlightAlert-exp8-work\.venv\Scripts\python.exe' -m unittest tools.experiment8.tests.test_binary_contract -v
git add -- tools/experiment8/binary_contract.py tools/experiment8/codecs.py tools/experiment8/tests/test_binary_contract.py docs/experiment8-package-contract.md
git diff --cached --check
git commit -m "Add Experiment 8 binary primitives"
```

Do not begin Task 5 until an independent specification reviewer approves the language-neutral package contract and an independent code-quality reviewer approves implementation/tests.

---

### Task 5: Implement Format A typed per-tile packages

**Files:**
- Create: `tools/experiment8/format_a.py`
- Create: `tools/experiment8/cook_format_a.py`
- Create: `tools/experiment8/tests/test_format_a.py`

**Interfaces:**
- Produces the approved Format A layout for either codec and a writer-side random-access adapter that returns Task 1 renderer records.
- Consumes the immutable normalized spool and Task 4 tables/coverage/codecs.

- [ ] **Step 1: Write failing Format A tests**

Tests require:

- exact manifest/source/style/policy/normalized/codec identities;
- sorted sparse 64-bit tile index using exact 72-byte entries, mapped-index segmentation below 256 MiB, 1.5-GiB positional pack rollover, and offsets bounded to their owning pack;
- one independent compressed block per nonempty normalized tile;
- present nonempty, proved semantic-empty, incomplete/unavailable, known-empty, and outside-coverage behavior;
- random access inflating one direct tile block plus at most one placement block per active label band, under a frozen active-band limit;
- complete typed record/count/hash readback and exact EOF;
- corruption in manifest/index/coverage/string/style/integrity/pack failing closed, never returning empty;
- no absolute paths or timestamps in deterministic files;
- worker counts 1 and 8 and repeated cooks producing byte-identical packages;
- transactional publication rollback.

- [ ] **Step 2: Implement and smoke-cook both codecs**

Write:

```text
manifest.json
strings.bin
styles.bin
coverage/zNN.cov
empty/zNN.empty
index/zNN/iPPPP.idx
blocks/zNN/bPPPP.pack
placement/zNN/bBBBB/sSS/pPPPP.idx
placement/zNN/bBBBB/sSS/pPPPP.pack
integrity.bin
```

Index only nonempty semantic tiles; every omitted source-present tile must appear in the independently hashed semantic-empty proof set or resolve `Unavailable`. Format A inlines complete hot label records in bounded placement-metatile blocks; it does not expand one record into every child tile. Every reader returns the single Task 1 order `(draw_order, priority, layer_group, feature_kind, canonical_variant_id, feature_id, canonical_renderer_bytes)`. Record exact logical bytes, filesystem bytes, direct/placement block/raw/record distributions, active bands, memberships, and decode scratch/heap estimates in a separate deterministic summary.

- [ ] **Step 3: Verify and commit Task 5**

```powershell
& 'C:\FlightAlert-exp8-work\.venv\Scripts\python.exe' -m unittest tools.experiment8.tests.test_format_a -v
git add -- tools/experiment8/format_a.py tools/experiment8/cook_format_a.py tools/experiment8/tests/test_format_a.py
git diff --cached --check
git commit -m "Cook Experiment 8 Format A packages"
```

Do not begin Task 6 until an independent specification reviewer proves Format A reconstruction/state semantics against the contracts and an independent code-quality reviewer approves implementation/tests.

---

### Task 6: Implement Format B canonical variants and adaptive postings

**Files:**
- Create: `tools/experiment8/format_b.py`
- Create: `tools/experiment8/cook_format_b.py`
- Create: `tools/experiment8/tests/test_format_b.py`

**Interfaces:**
- Produces the approved Format B layout for either codec and a writer-side adapter returning the same Task 1 renderer records.

- [ ] **Step 1: Write failing Format B tests**

Tests require:

- exact canonical-byte equality before dedupe; any forced 64-bit collision between unequal full digests is fatal;
- exact manifest/source/style/policy/normalized/codec identities, canonical JSON without timestamps/absolute paths, mapped-index/pack/block bounds, and transactional publication rollback;
- PBF ID `0` and matching names alone never merge features;
- exact point-label seam duplicates with identical canonical bytes may reuse one canonical variant;
- clipped/nonidentical geometry or different LOD/style/text remains distinct;
- every source occurrence remains in detached audit evidence; every tile posting has exactly one owner, resolves to one complete canonical variant, carries `feature_id` as the sole occurrence-specific hot field, cross-checks to detached provenance, and reconstructs the same renderer multiset/order as Format A;
- 8x8 metatiles by default, deterministic 8x8 to 4x4 to 2x2 subdivision when uncompressed postings exceed 4 MiB;
- a 2x2 posting block still over 4 MiB failing the format rather than silently oversizing;
- feature blocks content-addressed, sorted, and capped at 4 MiB raw;
- sorted delta-coded feature IDs/postings and deterministic, explicitly evidenced placement ownership;
- one deterministic owner metatile from the exact anchor plus packed-key tie-break, at most four exact path/label-envelope membership references, and no child-tile expansion;
- spatially local feature pages with a frozen page-touch limit per placement query;
- present semantic-empty/known-empty/outside-coverage truth matching Format A;
- random access touching only coverage/index, one direct postings block plus at most one placement block per active band, and the frozen maximum number of bounded spatial feature pages;
- corruption, dangling postings, duplicate ownership, unknown IDs, trailing bytes, and wrong hashes fail closed;
- worker counts 1 and 8 produce byte-identical packages.

- [ ] **Step 2: Implement safe exact deduplication**

Write:

```text
manifest.json
strings.bin
styles.bin
coverage/zNN.cov
empty/zNN.empty
features/fPPPP.idx
features/fPPPP.pack
postings/zNN/mPPPP.idx
postings/zNN/mPPPP.pack
placement/zNN/bBBBB/sSS/pPPPP.idx
placement/zNN/bBBBB/sSS/pPPPP.pack
integrity.bin
```

The mainline candidate performs exact content-addressed dedupe only. Point/label ownership uses a stable exact global anchor. Lines and polygons without provider identity retain per-source-tile identity and receive no cross-tile/cross-LOD dedupe credit; any later exact variant ownership must use a documented stable anchor and independent proof. Label memberships may repeat one candidate ID for retrieval, but removing duplicate transport references is not semantic dedupe or projection credit. Store Format B variant pages by geographic owner locality rather than global random ID order; postings reference `feature_page_id + local_ordinal + canonical_variant_id`. Do not topologically stitch or infer global identity from names, class fields, zero/missing source IDs, parent tiles, or approximate endpoints. If an experimental stitching algorithm appears equally promising, preserve it as a separate artifact/branch for later testing; it cannot replace the exact candidate without independent semantic proof.

Size projections report both owner-charged exact-dedupe bytes and a conservative no-cross-tile-dedupe bound. Use the larger bound for the under-25-GB decision unless ownership and projection credit are independently proven.

- [ ] **Step 3: Verify and commit Task 6**

```powershell
& 'C:\FlightAlert-exp8-work\.venv\Scripts\python.exe' -m unittest tools.experiment8.tests.test_format_b -v
git add -- tools/experiment8/format_b.py tools/experiment8/cook_format_b.py tools/experiment8/tests/test_format_b.py
git diff --cached --check
git commit -m "Cook Experiment 8 Format B packages"
```

Do not begin Task 7 until an independent specification reviewer proves Format B reconstructs Format A's exact tile-specific renderer stream and an independent code-quality reviewer approves implementation/tests.

---

### Task 7: Implement an independent package/source verifier and benchmark harness

**Files:**
- Create: `tools/experiment8/independent_verify.py`
- Create: `tools/experiment8/verify_packages.py`
- Create: `tools/experiment8/benchmark_codecs.py`
- Create: `tools/experiment8/tests/test_independent_verify.py`

**Interfaces:**
- Produces independent full-readback, A/B semantic parity, source parity, corruption, and codec benchmark reports.
- Must not import writer serializers, Format A/B reader adapters, or their private parsing helpers.

- [ ] **Step 1: Write failing independence and corruption tests**

Tests enforce the import boundary with AST/module inspection and prove the verifier independently:

- hashes every deterministic package file;
- validates all index/coverage/pack/integrity bounds and exact EOF;
- decodes every string/style/geometry/record/posting;
- checks every posting resolves and ownership is unique;
- compares each decoded tile to canonical normalized bytes;
- independently re-decodes source PBF/style selections for a deterministic correctness subset;
- uses a separately implemented strict raw protobuf/MVT command parser for source parity; it does not import the normalizer's parser or use the high-level decoder as an oracle;
- rejects shared fake-empty blocks, missing groups, manifest-only count claims, malformed geometry, and unresolved references;
- produces the same sorted renderer-contract hash for Format A and Format B under both codecs;
- produces byte-identical display-query candidate/posting multisets, ordering, and deterministic placement traces for A/B under both codecs;
- detects one-bit corruption in every file family;
- proves every `Ready(empty)` tile through the semantic-empty set and reports any source-present tile with neither a block nor empty proof as `Unavailable`;
- records PC encode/decode wall time, CPU time, peak RSS, compressed/raw bytes, and per-block latency without app runtime instrumentation.
- records p50/p95/p99/max direct/placement blocks, feature pages, path points, membership references, reconstructed heap, and bytes per display query, charging every occurrence/reference and granting no name/cross-LOD dedupe credit.

- [ ] **Step 2: Implement independent readers and deterministic reports**

The verifier may share only public enum values and documented magic/version constants. Parsing, bounds checks, decompression calls, and renderer-contract reconstruction are implemented separately. Reports include exact input/package/report hashes and separate variable benchmark audit data.

- [ ] **Step 3: Verify and commit Task 7**

```powershell
& 'C:\FlightAlert-exp8-work\.venv\Scripts\python.exe' -m unittest tools.experiment8.tests.test_independent_verify -v
git add -- tools/experiment8/independent_verify.py tools/experiment8/verify_packages.py tools/experiment8/benchmark_codecs.py tools/experiment8/tests/test_independent_verify.py
git diff --cached --check
git commit -m "Verify Experiment 8 package candidates"
```

Do not begin Task 8 until an independent specification reviewer approves verifier independence/source parity and an independent code-quality reviewer approves implementation/tests.

---

### Task 8: Execute the four-way real smoke proof

**Generated outputs:**

```text
C:\FlightAlert-exp8-work\pilot\smoke\normalized\
C:\FlightAlert-exp8-work\packages\smoke\format-a\deflate9\
C:\FlightAlert-exp8-work\packages\smoke\format-a\zstd9\
C:\FlightAlert-exp8-work\packages\smoke\format-b\deflate9\
C:\FlightAlert-exp8-work\packages\smoke\format-b\zstd9\
C:\FlightAlert-exp8-work\verification\smoke\
C:\FlightAlert-exp8-work\benchmarks\smoke\
D:\FlightAlert-test-artifacts\experiment 8\pilot-evidence\smoke\
```

- [ ] **Step 1: Revalidate smoke acquisition and normalize serial/parallel**

Expected: 154 states = 127 ready source tiles + 27 known-empty; zero network access; all PBF/sidecar hashes valid; normalized serial/parallel bytes and renderer hashes identical.

- [ ] **Step 2: Cook A/B under DEFLATE9/Zstandard9**

All four packages consume the same normalized inventory hash. Repeat each cook under workers 1 and 8 and require byte identity.

- [ ] **Step 3: Run independent full readback and source parity**

Expected:

- zero structural, integrity, source, or semantic failures;
- A/B renderer-contract hashes match under both codecs;
- compression codec does not change decoded semantics;
- every applicable required group has real nonzero records;
- the source-verified Chester River/current-phone fixture retains every applicable prominent source name as one intact label record with its complete path/style/collision inputs;
- all 27 known-empty fixtures remain explicit with no feature/posting/block;
- every present semantic-empty tile, if any, returns `Ready(empty)`;
- every block and mapped file stays within its bound;
- every authorized display query stays within active-band/placement-block/feature-page limits, and missing/corrupt parent placement data returns `Unavailable` rather than empty or child-only fallback;
- Format A stays at or below 6 MiB aggregate query scratch, Format B at or below 10 MiB with sequential reusable page decode, and reconstructed renderer heap at or below 32 MiB by exact weight; any excess fails the cook/read/verification;
- no package claims whole-world completeness from the smoke sample.

- [ ] **Step 4: Preserve the better and alternate candidates**

Record exact size/decode differences. Do not choose the world winner from smoke size alone. Preserve all four package manifests/hashes and retain both viable candidate packages on the C-drive workspace; mirror accepted independent reports, inventories, manifests, and hash trees to the D-drive evidence archive. If one codec is dominated in both size and bounded decode time, mark it inactive for Plan 3 but retain it for user testing until Plan 3 finishes.

- [ ] **Step 5: Run the cumulative suite and repository audit**

```powershell
& 'C:\FlightAlert-exp8-work\.venv\Scripts\python.exe' -m unittest discover -s tools/experiment8/tests -t . -q
git status --short --branch
```

Expected: all tests pass; generated data stays external; only intentional Experiment 8 commits and the user's original Experiment 7 dirt remain.

---

### Task 9: Authorize Plan 3 only on documentary smoke success

- [ ] Write Plan 3 with exact Stage A/Stage B projection, paired A/B comparison, Student-t 99% UCB, deterministic bootstrap p99, fixed/table/index/filesystem overhead, phone micro-package measurements, and winner gates.
- [ ] After Plan 3 selects a provisional package winner from independently equal semantics, require the immediately following Android integration plan to apply the current-phone screenshot class as a non-waivable Experiment 8 phone-render gate. Any missing applicable sourced name, oversized label, crushed spacing, fragmented/overlapping word, unstable path baseline, or unreadable collision blocks Experiment 8 success regardless of size/performance and returns work to the shared semantic/renderer contract. Reject the package format itself only when evidence traces the defect to that format's reader. Preserve the source-verified Chester River corridor micro-package, before/after screenshots, and physical-device video/frame evidence.
- [ ] Bind Plan 3 to the verified normalized schema/style/policy/package/verifier hashes from this plan.
- [ ] Do not mark Plan 2 complete or start Stage A package projection until Task 8 has zero unresolved failures.

## Plan 2 Completion Gate

Plan 2 is complete only when:

- the style audit accounts for all 916 pinned layers;
- the real smoke corpus normalizes with zero source/style/geometry failures;
- Format A and Format B under both codecs pass independent full readback;
- all four decoded renderer-contract hashes match;
- known-empty, present-empty, outside-coverage, and unavailable states remain distinct;
- serial/parallel and repeated cook outputs are deterministic;
- block/mapping/memory ceilings pass;
- the current-phone source/name fixture passes data-level whole-label/path/style gates and its degenerate visual class is carried forward as a non-waivable phone acceptance gate;
- all artifacts and reports are hash-pinned externally;
- specification and code-quality reviews approve the result.

Passing Plan 2 authorizes Plan 3. It does not authorize a full-world bake, phone installation, release, redistribution claim, or Experiment 8 success claim.
