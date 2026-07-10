# Experiment 8 Whole-World Package Pilot Design

**Status:** Approved under the user's standing direction to continue, make goal-directed technical decisions, test equally promising alternatives, keep the stronger result, and preserve the other for later testing.

## Objective

Experiment 8 must produce a source-honest, genuinely usable, whole-world offline reference system on the phone with documentary proof. The complete required on-phone footprint must remain below `25,000,000,000` bytes.

This specification covers the first Experiment 8 subproject: build an evidence-grade package pilot that selects the storage format and proves whether a full world bake is allowed. It does not declare Experiment 8 successful. Later subprojects cover the full bake, Android integration, installation, and physical-device acceptance.

## Success Accounting

The final `25,000,000,000`-byte ceiling counts the greater of logical and allocated bytes for:

- the installed release APK;
- the single active reference package;
- all indexes, coverage maps, strings, styles, manifests, and integrity files;
- mandatory prepared or runtime reference caches after the complete validation workload;
- any duplicate private or external copy required by the app.

Old packages, duplicate inactive copies, temporary staging files, videos, traces, source PBFs, and detached audit evidence do not belong on the accepted phone. They remain on the PC.

The runtime package has a `23,500,000,000`-byte authorization ceiling and a `22,000,000,000`-byte design target. The remaining space protects the final total against the APK, filesystem allocation, mandatory caches, and estimation error.

## Whole-World and Usability Definition

The source population is the complete, validated Esri `World_Basemap_v2` z0-z16 tilemap frame: `2,802,117` present source tiles. Every coordinate in the service extent must resolve to exactly one of:

- real decoded reference content;
- a source-proven known-empty result;
- outside the declared Web Mercator service extent;
- an explicit unavailable/corrupt state that cannot be activated as success.

The package stores the real reference content Flight Alert displays over imagery:

- place labels and hierarchy;
- water geometry and labels;
- national, state/province, county/equivalent, coastline, and disputed boundaries;
- public lands and protected-area reference content;
- roads, road labels, rail, ferry, airport, and required transportation context.

Unrelated basemap fills, buildings, landcover decoration, and content that is not part of the accepted reference overlays are not package requirements. Information may not be removed merely to reduce size when it is visible in the accepted reference groups. Storage savings must come from typed representation, deduplication, quantization, compression, and appropriate style-defined LOD variants.

The current Esri raster reference overlays remain the visual comparison floor. No inferred label, fabricated line, empty shared payload, or synthetic aviation feature is permitted.

## Authoritative Inputs

The pilot records and verifies these inputs before work begins:

1. Source lock:
   `D:\FlightAlert-test-artifacts\experiment 6\phase1\world-basemap-v2-source-lock`
2. Source style SHA-256:
   `92cec535724bebd560ce18ba47f5ddbc803e9bef61d8450bd24098f941276c5b`
3. Source metadata SHA-256:
   `29586b422c8a5a9baa942551f9d1af634dcaea0c95e04aae47f571f48ef48136`
4. Coordinate population:
   `D:\FlightAlert-test-artifacts\experiment 6\phase1\world-basemap-v2-tilemap-enumeration\present-vector-tiles.tsv`
5. Coordinate-population SHA-256:
   `ef3a0ab58d422add4c50a85525ef578fcb9106570f99ce9d529d2c7626cf85a3`
6. Per-zoom present counts:
   `1, 4, 16, 64, 216, 684, 1,808, 5,024, 15,024, 43,680, 113,848, 278,192, 474,992, 584,176, 588,788, 491,940, 203,660` for z0 through z16.
7. Typed format specimen:
   `D:\FlightAlert-test-artifacts\experiment 3\phase2\phone-package-pull-codex\dc-baltimore-z10-z11-phone-v1`
8. Experiment 4 FAD2 engineering reference:
   `D:\FlightAlert-test-artifacts\experiment 4\tools\build_esri_reference_package.py`

The locked structured service is:

```text
https://basemaps.arcgis.com/arcgis/rest/services/World_Basemap_v2/VectorTileServer
```

Its style is `resources/styles/root.json`, and its source tile pattern is `tile/{z}/{y}/{x}.pbf`. The visual QA services are Esri `Reference/World_Boundaries_and_Places` and `Reference/World_Transportation` MapServers.

Experiment 3 Phase 1 and its original typed-builder scripts are missing. The surviving 58-tile package is a validated format specimen, not reusable world content. Experiment 6 raster/class-mask records and Experiment 7 empty records are not semantic source inputs.

Before a release claim, the live service metadata/style are fetched again and either match the source lock or establish a new, fully documented source generation. `exportTilesAllowed=true` is a technical capability, not redistribution permission. Experiment 8 may prove personal on-device use, but it must not claim redistribution rights without an applicable Esri/provider license review.

## Approaches

### A. Typed Per-Tile Blocks — Control

Each source/render tile contains typed record sections with shared string and style IDs, numeric feature identifiers, integer geometry, placement data, visibility intervals, and source-derived semantic classifications.

Advantages:

- the retained Experiment 3 package proves the basic binary shape;
- simple random access and independent validation;
- establishes the minimum renderer decode cost without JSON.

Risk:

- geometry and features repeat at tile seams and across zoom levels;
- world storage may exceed the ceiling even after context pruning.

### B. Canonical Features with Metatile Postings — Recommended Candidate

Canonical records store each feature once per required geometry/LOD variant. Sparse metatile postings contain sorted feature IDs and precomputed placement references for the render tiles that consume them.

Advantages:

- long boundaries, rivers, roads, and repeated labels reuse canonical geometry and metadata;
- postings are small and geographically local;
- cross-tile label ownership and collision order are stable before runtime;
- unchanged blocks can be content-addressed in later updates.

Risk:

- canonical identity is harder when the source lacks stable IDs or changes geometry between LODs;
- dense metatiles may require bounded subdivision;
- feature-block resolution can increase seeks or decoded working set.

### C. Grouped Pre-Rendered Raster Metatiles — Rejected for This Pilot

Experiment 6 reached approximately `27.794` GB before preserving real text/semantic records and failed the 25 GB ceiling. Raster/class masks cannot reconstruct honest labels or boundaries. This result remains preserved as the low-phone-work comparison, but it is not rebuilt in Experiment 8's semantic pilot.

## Common Normalized Record Contract

Both A and B consume one normalization pipeline and must emit semantically identical renderer inputs. Every record has:

- `featureId`, `dedupeId`, and `canonicalVariantId` as 64-bit numeric identifiers;
- numeric layer group and feature kind;
- source feature ID when present, source layer ID, and compiled style token IDs;
- string-table IDs for visible text and required language variants;
- style-derived minimum/maximum zoom and fade intervals;
- integer-quantized geometry with part offsets and bounds;
- label anchor or line-placement path, priority, stable ownership cell, collision group/box, repeat distance, and rotation policy when applicable;
- administrative hierarchy, disputed/coastline/water-boundary flags, protected-area flag, and transportation/water class when applicable;
- a source-audit hash that links the hot record to detached provenance without storing audit strings in the draw payload.

Unsupported style expressions or ambiguous classifications are explicit extraction failures. Property presence alone may not imply a boolean value; in particular, `DisputeID=0` is false.

## Renderer-Facing Contract

The two package adapters eventually return the same typed result:

```kotlin
interface ReferenceTileSource : Closeable {
    val descriptor: ReferenceSourceDescriptor
    val styles: ReferenceStyleCatalog
    fun loadTile(key: ReferenceTileKey): ReferenceTileLoad
}

sealed interface ReferenceTileLoad {
    data class Ready(val tile: ReferenceRenderTile) : ReferenceTileLoad
    data object KnownEmpty : ReferenceTileLoad
    data object OutsideCoverage : ReferenceTileLoad
    data class Unavailable(val reason: ReferenceFailure) : ReferenceTileLoad
}
```

`loadTile` is blocking and worker-thread-only. `ReferenceRenderTile` contains numeric typed draw inputs and an estimated heap weight. The renderer preserves the accepted retained-frame interaction behavior, but it no longer parses JSON, infers groups/styles from strings, treats corruption as empty, or loads a world index into Java heap.

## Package Layouts

Format A:

```text
manifest.json
strings.bin
styles.bin
coverage/zNN.cov
index/zNN/iPPPP.idx
blocks/zNN/bPPPP.pack
integrity.bin
```

Format B:

```text
manifest.json
strings.bin
styles.bin
coverage/zNN.cov
features/fPPPP.idx
features/fPPPP.pack
postings/zNN/mPPPP.idx
postings/zNN/mPPPP.pack
integrity.bin
```

Indexes are sorted sparse 64-bit-key tables split into files comfortably below the approximately 2 GiB Android mapping limit. They are memory-mapped read-only. Blocks are independently compressed and geographically local; no request may inflate a large world shard to obtain one tile.

The pilot compares raw DEFLATE level 9 with Zstandard level 9 over identical normalized bytes. Codec identity is explicit in every block entry. Android already carries `zstd-jni`, but the final codec is selected only after size and device decode measurements.

Format B starts with 8x8 render-tile metatiles. A metatile whose uncompressed posting block exceeds 4 MiB subdivides deterministically to 4x4 and then 2x2. Feature blocks are content-addressed and capped at 4 MiB uncompressed. Geometry uses delta plus ZigZag varints; postings use sorted delta-coded feature identifiers.

## Deterministic Pilot Population

The projection population is the locked `2,802,117`-row z0-z16 present-tile frame.

Geographic strata use six equal-area latitude bands with edges:

```text
-90, -41.810315, -19.471221, 0, 19.471221, 41.810315, 90
```

and eight 45-degree longitude sectors with edges:

```text
-180, -135, -90, -45, 0, 45, 90, 135, 180
```

Each exact zoom and nonempty geographic cell is a separate stratum. Sampling without replacement selects the lowest SHA-256 ranks of:

```text
flight-alert-exp8-pilot-v1|z|x|y
```

Stage A:

- census every z0-z8 present tile: `22,841` tiles;
- select up to 32 ranked tiles from every nonempty geographic cell at z9-z16;
- include the 32 largest known source-byte tiles at every z9-z16 as certainty tail units;
- maximum population: approximately `35,385` tiles.

Stage B is cumulative and runs only after Stage A correctness passes:

- expand each z9-z16 geographic cell to at most 256 ranked tiles;
- expand certainty tails to the 256 largest source-byte tiles per z9-z16;
- maximum population: approximately `123,193` tiles.

Source-byte tails come from the validated Experiment 6 source-size metadata when coordinate and source hashes match. If that metadata is unavailable, source sizes are measured during a deterministic first acquisition pass. Certainty units are excluded from random means and added exactly to the projection.

A separate non-projection fixture manifest covers dense cities on every inhabited continent, rural interiors, coasts, small islands, public lands, rivers, the antimeridian, polar Web Mercator limits, and real disputed/international boundary cases. Fixtures prove semantics and visuals; they do not bias the size estimate.

## Source Acquisition and Resume

- Download only through the locked HTTPS Esri service or the documented official export service when properly authenticated.
- Cache the exact compressed response by source generation and z/x/y.
- Verify HTTP status, content type, gzip/PBF decode, coordinate, source generation, and SHA-256 before atomic rename from a temporary file.
- Apply bounded concurrency, provider-aware retry/backoff, checkpointed progress, and storage watermarks.
- A failed or missing source tile remains failed; it is never recorded as known-empty.
- Repeated runs reuse only hash-verified cache files and produce the same sample manifest.
- The full world bake is forbidden during the pilot.

GPU utilization is not a success criterion. Semantic decoding, normalization, hashing, and compression use the fastest measured CPU/I/O implementation. GPU work is admitted only if a real bounded stage is demonstrated faster end-to-end; synthetic utilization is not evidence.

## Independent Verification

The verifier is a separate reader implementation and does not call writer serialization routines. For every pilot tile it:

1. validates file-inventory SHA-256 and block/index bounds;
2. verifies compression codec, raw length, block hash, magic, version, coordinate, count ceilings, and exact EOF;
3. decodes every record and posting;
4. confirms all postings resolve to a canonical feature and ownership is unique;
5. compares normalized records against independently decoded source PBF/style results;
6. compares A and B through a sorted renderer-contract hash;
7. aggregates exact counts by zoom, layer group, feature kind, hierarchy, and class;
8. rejects shared empty payloads, malformed geometry, unresolved strings/styles, duplicate ownership, holes, or manifest-only count claims.

Serial and parallel cooks of the Stage A correctness subset must produce identical normalized semantic hashes. Container byte identity is required when ordering/compressor settings are deterministic; otherwise the manifest records why container bytes differ while decoded semantic hashes remain equal.

## Projection and Selection

Each random stratum estimates its remaining population as `N_h * mean_h`, with finite-population correction. The pilot calculates:

- a one-sided 99% Student-t upper confidence bound;
- a deterministic stratified bootstrap 99th percentile;
- exact certainty-tail bytes;
- exact full-world coverage/index/fixed-table overhead;
- a separately modeled global string/style-table bound;
- filesystem allocation overhead measured on representative phone files.

The larger statistical result is authoritative.

Neither format may proceed to a world bake unless Stage B demonstrates all of the following:

- zero semantic/source/integrity failures;
- A/B normalized renderer hashes match for required content;
- nonzero real records for every applicable required group;
- full-world package 99% upper bound at or below `23,500,000,000` bytes;
- design estimate at or below `22,000,000,000` bytes, or an explicit margin analysis proving the complete phone total remains below the hard ceiling;
- projected mapped-index and runtime working set fit the phone memory contract;
- no per-tile JSON or whole-world heap index is required.

The winner is the smallest format that preserves semantic/visual parity without materially worsening bounded random-access decode cost. If results are statistically or operationally tied, a physical-phone micro-package comparison decides. The winner continues; the other package, manifest, metrics, and verifier output remain preserved for later user testing.

If neither format passes, Experiment 8 records an honest failure and Experiment 9 may pursue the PC-server design. The gate is not relaxed to manufacture success.

## Evidence Layout

Generated data stays outside the app repository:

```text
D:\FlightAlert-test-artifacts\experiment 8\
  source-lock\
  source-cache\
  populations\
  pilot\stage-a\
  pilot\stage-b\
  packages\format-a\
  packages\format-b\
  verification\
  projections\
  logs\
```

Reusable source, cooker, verifier, projection, and contract-test code is versioned under the repository's `tools/experiment8/` tree. Run logs, caches, generated packages, videos, traces, temporary files, and bulky evidence remain under the external Experiment 8 root.

## Pilot Deliverables

The package-pilot subproject is complete only when it produces:

- a hashed source lock and population manifest;
- deterministic Stage A and Stage B sample manifests;
- resumable acquisition records with no unresolved tile;
- Format A and Format B packages for identical normalized inputs;
- independent full readback and A/B semantic-parity reports;
- DEFLATE/Zstandard size and decode comparisons;
- one-sided 99% and bootstrap projections with fixed overhead shown separately;
- an explicit winner/loser decision with both preserved;
- a go/no-go decision for the full world bake.

Passing this pilot authorizes the next Experiment 8 subproject. It does not by itself satisfy the active Experiment 8 goal.
