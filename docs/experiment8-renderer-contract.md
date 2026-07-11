# Experiment 8 Typed Renderer Contract

Status: normative for Experiment 8 semantic records, version `N8T1`.

This document defines the package-independent renderer meaning shared by Format A and Format B. A conforming reader can implement this contract without importing the Python writer. Package containers, compression, block indexes, and acquisition evidence do not change these bytes or their renderer order.

The words MUST, MUST NOT, SHOULD, and MAY are normative.

## 1. Truth and ownership boundaries

Three immutable record families have deliberately different jobs:

- `SourceOccurrence` is detached evidence. It binds a source tile, source feature ordinal, full source geometry, source-layer text, audit digest, and the derived feature/dedupe IDs. It is retained by the normalization evidence stream and is not required by a phone reader.
- `CanonicalVariant` is one complete immutable renderer payload. Format B may reuse a variant only when its complete canonical payload bytes are identical. Matching names, PBF IDs, approximate coordinates, or partial geometry are never sufficient.
- `TilePosting` owns one requested-tile occurrence. Its `feature_id` is the sole occurrence-specific hot renderer value. Its variant ID is a content reference; requested tile, owner tile, and world wrap are retrieval/ownership transport.

The hot renderer record is exactly one `TilePosting` joined to its referenced `CanonicalVariant`. It excludes source ordinal, source-layer strings, raw source properties, source-local geometry, and source audit SHA-256. Manifests bind the detached audit stream, and an independent verifier cross-checks every hot `feature_id` against that evidence.

A decoded PBF feature ID of zero is unavailable as a provider-stable join key. It never authorizes text/geometry transfer or deduplication.

## 2. Primitive encoding

Every canonical record starts with ASCII magic `N8T1`, followed by one unsigned record-tag byte. There is no native padding or platform-dependent field.

| Primitive | Encoding |
| --- | --- |
| `u8`, `u32`, `u64` | unsigned little-endian integer of the named width |
| `i32`, `i64` | two's-complement little-endian integer of the named width |
| Boolean | one `u8`, exactly `0` or `1` |
| Blob | `u32le byte_length`, followed by exactly that many bytes |
| String | Blob containing strict UTF-8 in Unicode NFC |
| SHA-256 | exactly 32 raw bytes |
| Tile key | one `u64le` packed as `(z << 58) | (x << 29) | y` |

Strings MUST already be NFC. An encoder MUST NOT silently normalize, truncate, case-fold, transform, or replace invalid text. Text transformation is a separate presentation token. Floats are forbidden in canonical records. Fractional zooms use integer centizooms; other fractional style values use their declared fixed integer unit.

A decoder MUST consume the exact declared field count and exact EOF. Unknown enum values, invalid UTF-8, invalid booleans, length overrun, trailing bytes, noncanonical geometry, and out-of-range integers are fatal.

Canonical UTF-8 strings are capped at 1,048,576 bytes and each ordered style/token table at 262,144 references. A decoder validates counts against both these ceilings and the bytes remaining before allocation. Package readers additionally apply the independently inflated 4,194,304-byte block ceiling from the package contract; a declared length never authorizes reading or allocating beyond its owning verified block.

## 3. Numeric enums

### Layer group

| Value | Name |
| ---: | --- |
| 1 | `PLACES` |
| 2 | `WATER` |
| 3 | `REGIONS` |
| 4 | `PUBLIC_LANDS` |
| 5 | `TRANSPORTATION` |
| 6 | `CONTEXT` |

### Feature kind

| Value | Name |
| ---: | --- |
| 1 | `LABEL` |
| 2 | `LINE` |
| 3 | `POLYGON_OUTLINE` |

### Geometry kind

| Value | Name |
| ---: | --- |
| 1 | `POINT` |
| 2 | `PATH` |
| 3 | `POLYGON` |

### Text evidence

| Value | Name |
| ---: | --- |
| 0 | `NONE` |
| 1 | `SOURCE_FIELD` |
| 2 | `PINNED_STYLE` |
| 3 | `FLIGHT_ALERT_POLICY` |
| 4 | `PROVIDER_STABLE_JOIN` |

`PROVIDER_STABLE_JOIN` requires an explicit nonzero provider ID. A zero PBF ID cannot satisfy it.

### Projection mode

| Value | Name |
| ---: | --- |
| 1 | `DIRECT_SOURCE_PATH` |
| 2 | `EXACT_PARENT_PATH` |

### Land evidence and protected status

Both enums use the same values:

| Value | Name |
| ---: | --- |
| 0 | `NOT_APPLICABLE` |
| 1 | `SOURCE_EXPLICIT` |
| 2 | `NAME_DERIVED` |
| 3 | `AMBIGUOUS` |

`DisputeID=0` means false; any nonzero integer means true.

## 4. Strict geometry

Part offsets are zero-based point offsets, not scalar-coordinate offsets. The first offset is zero; offsets are strictly increasing and in range. Coordinates are an even-length sequence of signed 64-bit `x,y` scalars. Bounds are the exact tuple `(minX,minY,maxX,maxY)` computed from those scalars. Paths have at least two points per part. Polygon rings have at least four points and remain exactly closed. Ring order, winding, closure, duplicate points, and provider buffers are preserved. No geometry is clamped, rounded, repaired, simplified, stitched, reordered, dropped, or inferred.

The hard model ceiling is 1,048,576 points per geometry.

### 4.1 Source geometry, tag `1`

After `N8T1 01`:

1. `u8 geometry_kind`
2. `u64 packed_source_tile`
3. `u8 source_zoom` (MUST equal the packed tile zoom)
4. `u64 declared_extent` (positive)
5. `u32 part_count`, followed by `part_count * u32 point_offset`
6. `u32 coordinate_scalar_count`, followed by that many `i64`
7. four `i64` exact bounds

The declared extent is source evidence. Coordinates below zero or above the extent are valid provider buffers and remain byte-exact.

### 4.2 Renderer geometry, tag `2`

After `N8T1 02`:

1. `u8 geometry_kind`
2. `u64 world_denominator`
3. parts, coordinates, and bounds in the same shape as source geometry

For a source coordinate `(sx,sy)` in tile `(z,x,y)` with declared extent `E`:

```text
nx = x*E + sx
ny = y*E + sy
D  = 2^z * E
g  = gcd(D, abs(all nx and ny values))
canonical coordinates = all numerators / g
canonical denominator = D / g
```

All intermediate and final numerators and the denominator MUST fit signed 64-bit. One shared gcd is used for the entire geometry, not one gcd per point. A decoded renderer geometry is canonical only when the gcd of its denominator and every numerator is one. This makes exact cross-LOD equality byte equality while preserving the complete unmodified path.

Renderer draw geometry stays unwrapped. Only a single-point seam identity reduces its x numerator modulo one world. `TilePosting.world_wrap` reconstructs an allowed wrapped copy. Thus x=0 and x=one-world can share an exact point anchor without mutating either draw coordinate. Lines and polygons are never seam-merged merely because endpoints look close.

Exact path/tile intersection uses integer cross-products after putting the rational path and requested tile rectangle over one common denominator. Boundaries are inclusive. Runtime clipping is temporary placement work and MUST NOT change canonical geometry bytes, geometry ID, variant ID, or label-candidate ID.

The geometry fingerprint is:

```text
SHA256("exp8-renderer-geometry-v1\0" || complete tag-2 renderer geometry)
```

## 5. Canonical typed properties and table IDs

The typed-property record uses tag `0x30`, then `u32 property_count`. Each property is a Blob NFC key followed by a Blob typed value. Properties are sorted by the UTF-8 key bytes. Value tags are:

| Tag | Value |
| ---: | --- |
| 0 | null; no payload |
| 1 | Boolean |
| 2 | `i64` |
| 3 | NFC UTF-8 String |
| 4 | Blob |

Float values and unsupported types are fatal. String/style table IDs are assigned by lexicographically sorted unique canonical bytes, never encounter order.

Fixed-integer interpolation clamps outside the interval and computes the interior rational using integers only. A fractional result rounds to nearest integer; an exact half rounds away from zero. The position interval MUST be strictly increasing.

## 6. Identity domains

Every digest preimage has an explicit ASCII domain ending in NUL. A hot ID is the first eight digest bytes interpreted as an unsigned big-endian integer. Whenever serialized as `u64`, that numeric value is written little-endian. Full 256-bit digests remain in evidence.

A cook maintains a collision registry. If one hot ID is encountered with unequal full digests, domains, or canonical bytes, the whole cook fails. It MUST NOT merge the values, truncate evidence, rehash with a salt, or choose a replacement ID.

Every normalization/cook uses one registry for the complete cook, and actual `FAE8LLB1` and `FAE8VAR1` creation passes through it. Test-only collision probes may substitute the registry's 64-bit bucket function, but returned record identities remain the true SHA-256 identities. Readers perform the equivalent check while building each hot-ID table.

### 6.1 Detached identity evidence, tag `0x33`

Every derived identity retained for audit has a self-verifying detached record:

1. Blob domain bytes, including the final NUL
2. 32-byte full SHA-256
3. `u64le` hot ID
4. Blob complete canonical preimage

The full digest MUST equal `SHA256(domain || preimage)` and the hot ID MUST equal the unsigned-big-endian first eight digest bytes. This makes feature and dedupe identities independently recomputable even though their bulky preimages are absent from the phone record.

The source-audit envelope uses tag `0x34`, followed by `u32 evidence_count` and one Blob tag-`0x33` record per entry, lexicographically sorted by the complete encoded evidence bytes. Duplicate `(domain,hot_id)` entries are invalid. The value stored as `SourceOccurrence.source_audit_sha256` is:

```text
SHA256("exp8-source-audit-v1\0" || complete tag-0x34 envelope)
```

### 6.2 Feature identity

The tag-`0x31` feature preimage is:

1. 32-byte source-generation SHA-256
2. packed tile
3. NFC source-layer String
4. Blob complete tag-`0x30` typed properties
5. Blob complete tag-`1` source geometry
6. `u32 duplicate_occurrence_ordinal`

```text
feature fingerprint = SHA256("exp8-feature-v1\0" || preimage)
```

Source generation, tile, layer, properties, exact geometry, and duplicate ordinal are therefore all identity-bearing. Raw PBF ID zero is not substituted for this identity.

### 6.3 Dedupe identity

Point labels may deduplicate only from tag `0x32` containing numeric layer group, numeric feature kind, exact NFC display text, numeric semantic class ID, and the exact single-point global anchor `(x modulo D, y, D)`:

```text
SHA256("exp8-point-label-dedupe-v1\0" || preimage)
```

For lines and polygon outlines without a separately proven nonzero provider identity, `dedupe_id` is exactly `feature_id`. They receive no cross-tile or cross-LOD semantic merge credit.

### 6.4 Source-occurrence audit identity

The complete tag-`3` source occurrence is hashed with:

```text
SHA256("exp8-source-occurrence-v1\0" || source_occurrence_bytes)
```

This occurrence fingerprint and the tag-`0x33` feature/dedupe evidence are detached and never a phone-package join requirement.

## 7. SourceOccurrence encoding, tag `3`

After `N8T1 03`:

1. `u64 packed_tile`
2. `u32 source_feature_ordinal`
3. `u64 feature_id`
4. `u64 dedupe_id`
5. NFC source-layer String
6. Blob complete tag-`1` source geometry
7. 32 raw bytes source-audit SHA-256

The occurrence tile MUST equal the source geometry tile. Altering any evidence field changes the occurrence fingerprint but does not change an independently defined canonical variant.

## 8. Line-label identity and continuity

One label is one NFC UTF-8 text run plus one complete exact placement path and collision/style inputs. No canonical stream contains per-character label records.

### 8.1 FAE8LLB1 candidate preimage, tag `6`

After `N8T1 06`, in exact order:

1. 32-byte source-feature full digest
2. 32-byte placement-geometry full digest
3. `u8 text_evidence_kind`
4. `u64 text_source_field_id`
5. `u64 placement_source_feature_id`
6. `u64 placement_geometry_id`
7. Boolean provider-ID presence, then optional `u64 provider_feature_id`
8. exact NFC visible-text String
9. 32-byte style/policy digest
10. packed source tile
11. `u8 source_zoom`
12. `u64 source_declared_extent`
13. four `i64 source_edge_domain` values in signed source-local extent units
14. `u8 projection_mode`
15. `i32 display_min_zoom_centi`
16. `i32 display_max_zoom_centi`
17. `u32 spacing_px`
18. `u32 maximum_bend_angle_degrees`
19. `u64 collision_group`
20. `i32 priority`
21. Boolean `avoid_edges`
22. Boolean `keep_upright`
23. `u8 active_band_limit`

```text
label candidate fingerprint = SHA256("FAE8LLB1\0" || tag-6 bytes)
```

The candidate numeric ID and full digest are excluded from this preimage. Every listed field is meaning or eligibility and changes the fingerprint. `placement_source_feature_id` MUST equal the unsigned-big-endian first eight bytes of `source_feature_sha256`; the geometry ID has the same required relationship to the geometry digest. Only these transport membership fields are excluded: requested retrieval tile/metatile, feature page, local ordinal, deterministic owner, and world-wrap copy.

### 8.2 Stored NormalizedPlacement, tag `0x20`

The stored placement contains, in order: `u64 label_candidate_id`, 32-byte label candidate digest, 32-byte source-feature digest, 32-byte geometry digest, evidence `u8`, source-field `u64`, placement-source-feature `u64`, placement-geometry `u64`, packed source tile, source zoom `u8`, source declared extent `u64`, four edge-domain `i64`, projection `u8`, min/max display `i32`, spacing `u32`, max angle `u32`, collision group `u64`, priority `i32`, two Boolean flags, active-band-limit `u8`, 32-byte style/policy digest, Boolean provider-ID presence, and optional provider `u64`.

Its stored candidate ID/digest MUST recompute from the variant's exact whole text and tag-6 meaning fields.

An unlabeled `LINE` or `POLYGON_OUTLINE` uses one mandatory non-applicable placement sentinel: all IDs/digests/intervals/spacing/collision/priority/flags are zero; evidence is `NONE`; source tile is `0/0/0`; declared extent is one; edge domain is `(0,0,0,0)`; projection is `DIRECT_SOURCE_PATH`; and provider ID is absent. Any other unlabeled placement is noncanonical. A `LABEL` cannot use this sentinel.

### 8.3 Parent label bands and membership

A `ParentLabelBand` stores one canonical variant/occurrence path; it does not expand that label into every child tile. A retrieval membership is derived arithmetically only when all of these are true:

- requested zoom is at least source zoom;
- requested zoom minus source zoom does not exceed `active_band_limit`;
- requested integer zoom in centizooms lies in the half-open display interval `[min,max)`;
- the unmodified rational canonical path exactly intersects the requested tile (including an explicitly named world-wrap domain).

Membership derivation is packed-tile sorted, deduplicates requested coordinates, and has a hard maximum of four membership references per candidate. A membership carries candidate ID, source-feature ID, geometry ID, requested tile, owner, metatile/page/local transport, and world wrap. A sibling that does not intersect receives nothing. A name is never transferred to equal-looking geometry, a nearby path, a parent/child source feature, or another LOD.

`avoid_edges` is evaluated against the recorded source tile and `source_edge_domain`, not descendant retrieval edges. The domain and margin are signed source-local units over `source_declared_extent`; an anchor rational is compared by integer cross multiplication after projecting the source domain with `(tile*E + local)/(2^z*E)`. Crossing a child edge does not split, rename, rehash, or reject an otherwise eligible whole label.

Viewport assembly unions memberships and deduplicates by full candidate identity before shaping and collision. One candidate therefore yields one whole word/run even when multiple tiles retrieve it. Equal text on disconnected paths remains multiple candidates. Stable repeat phase is `label_candidate_id mod spacing_px`; the locked water-label spacing and maximum bend inputs remain exact integers (`1000` px and `30` degrees for that compiled rule). Whole-label placement is atomic: the complete shaped run must fit one continuous eligible segment, satisfy the bend limit, and pass collision. Partial glyph or substring placement is forbidden.

## 9. CanonicalVariant

### 9.1 Self-addressed payload, tag `4`

The canonical variant payload excludes its own numeric/full identity and contains, in exact order:

1. `u64 dedupe_id`
2. `u64 geometry_id`
3. `u64 source_layer_id`
4. `u64 source_scale_band_id`
5. `u8 layer_group`
6. `u8 feature_kind`
7. `u32 semantic_subtype`
8. `u32 source_style_count`, then ordered `u64` source-style IDs
9. `u32 render_token_count`, then ordered `u64` presentation-token IDs
10. Boolean text presence, then optional exact NFC String
11. Blob complete tag-`2` renderer geometry
12. `i32 min_zoom_centi`
13. `i32 max_zoom_centi`
14. `i32 fade_in_centi`
15. `i32 fade_out_centi`
16. `i32 draw_order`
17. `i32 priority`
18. Blob tag-`0x20` placement
19. `u8 land_evidence`
20. `u8 protected_status`
21. `u32 flags`

The exact identity is:

```text
full variant digest = SHA256("FAE8VAR1\0" || complete tag-4 payload)
canonical_variant_id = unsigned-big-endian(full digest[0:8])
```

The geometry ID and full placement-geometry digest MUST address the exact embedded renderer geometry. The candidate ID/digest MUST address every tag-6 field and exact text. A package reader can therefore recompute the variant ID from package bytes without source evidence.

`LABEL` requires nonempty whole text, point/path geometry, and a verified label placement. `LINE` requires path geometry, no text, and the non-applicable sentinel. `POLYGON_OUTLINE` requires polygon geometry, no text, and that same sentinel. These compatibility rules prevent meaningless placement fields from creating multiple encodings of one unlabeled feature.

### 9.2 Encoded variant wrapper, tag `7`

After `N8T1 07`: `u64le canonical_variant_id`, then Blob complete tag-`4` payload. The wrapper ID MUST equal the recomputed FAE8VAR1 hot ID.

## 10. TilePosting encoding, tag `5`

After `N8T1 05`:

1. packed requested tile
2. `u64 feature_id`
3. `u64 canonical_variant_id`
4. packed deterministic owner tile
5. `i32 world_wrap`

The variant reference must resolve exactly once. Missing IDs, unequal bytes under one ID, dangling postings, or a posting joined to the wrong variant are fatal, never empty.

## 11. Public-land final-output honesty

An internal boolean is not enough. If the final numeric layer group is `PUBLIC_LANDS`, or the final ordered presentation-token IDs contain any compiled public/protected-land token, both `land_evidence` and `protected_status` MUST be `SOURCE_EXPLICIT`. Construction/decoding always enforces the group rule. Final renderer reconstruction requires the compiled public-token ID set and enforces the token rule before returning any record.

`NAME_DERIVED` and `AMBIGUOUS` evidence may be excluded or emitted only as neutral `CONTEXT` with neutral tokens. Values such as “park or farming,” “forest or park,” and “openspace or forest” do not prove public ownership and may never inherit public-land colors, labels, attribution, or claims.

## 12. Renderer reconstruction, order, and hash

The per-tile renderer order is the ascending tuple:

```text
(
  draw_order,
  priority,
  numeric layer_group,
  numeric feature_kind,
  canonical_variant_id,
  feature_id,
  complete tag-8 canonical renderer-record bytes
)
```

This is a multiset order. Duplicate postings are retained. Package layout, block order, compression codec, input order, and worker count do not participate.

The tag-`8` renderer record contains `u64 feature_id`, `u64 canonical_variant_id`, packed owner tile, `i32 world_wrap`, and Blob complete tag-`4` canonical variant payload. Requested tile is supplied by the per-tile lookup and is prefixed in the global stream.

The global stream is packed-tile sorted, then renderer-order sorted. For each record:

```text
body = u64le(packed_requested_tile) || complete tag-8 renderer record
stream item = u32le(len(body)) || body
renderer contract hash =
  SHA256("flight-alert-exp8-semantic-v1\0" || repeated stream items)
```

All four package/codec candidates must produce the same hash over equal semantics.

Including the complete tag-8 bytes in the final tie-break binds deterministic owner and world wrap. Two postings that otherwise share tile, feature, and variant IDs therefore cannot inherit caller input order or change the semantic hash when input is reversed.

### 12.1 Frozen golden vector hashes

The Task 1 fixture freezes independent regression hashes for the complete tag encodings:

| Encoding | SHA-256 |
| --- | --- |
| source geometry | `bddfdf310161d6bdc3f89e98a60aa407204017533102dca3b1c748939808761e` |
| renderer geometry | `de070e99420ac3b9b45ac6784b238e61932dbaa8872303ebc033799e2c4e7ba1` |
| source occurrence | `9bb7c196db5bb2b7ce73823f8591b434585e8ebc04546abc47a096474b0ca722` |
| FAE8LLB1 candidate bytes | `5903d74b9605fd35e3eaf64fd1d5793c80438a5b6ebffd7f3796459c18fbcca0` |
| FAE8VAR1 payload | `9e7320be9fd73b9f1616518e6ccfcd597a8c0a6386782fb69bad9806271eb984` |
| encoded variant wrapper | `7e63d385ea50fde3e97a95b98c287b1a1c75e89ed5594cdfe148105c8cba1276` |
| tile posting | `af940ee3553188877ec7d74daab57b9bfda8ad3b5061cdcf257864668a83d15a` |
| renderer record | `abc983f4f6502535afb4a633962972ef68226f56e0533a78f418f8caa01e23cc` |
| detached identity evidence | `384ff71faa80ffee18358f77b3933a35902d4f529198cd761b7b1b36c639c06b` |

The fixture's renderer-contract hash is `27e5c3d634e517e75dc2e5bc39649a01778e45d470814941821a2a13e4e85e2f`. Tests also freeze complete literal hex for the source-geometry and tile-posting records so endian and field-order errors cannot hide behind writer/reader round trips.

## 13. Exact reconstructed-heap accounting

Every reconstructed occurrence is charged independently; variant reuse or duplicate membership transport does not earn semantic heap credit. The exact weight of one renderer record is:

```text
256
+ 16 * renderer_point_count
+  4 * geometry_part_count
+  8 * (source_style_id_count + render_token_id_count)
+ UTF8_byte_length(text, or zero)
```

The hard display-query ceiling is 33,554,432 bytes. Accumulation is checked after every record and fails closed on the first excess.

## 14. Tile state semantics and Kotlin-facing boundary

This semantic contract preserves the approved blocking, worker-thread-only interface without editing Kotlin:

```kotlin
interface ReferenceTileSource : Closeable {
    fun loadTile(key: ReferenceTileKey): ReferenceTileLoad
}

sealed interface ReferenceTileLoad {
    data class Ready(val tile: ReferenceRenderTile) : ReferenceTileLoad
    data object KnownEmpty : ReferenceTileLoad
    data object OutsideCoverage : ReferenceTileLoad
    data class Unavailable(val reason: ReferenceFailure) : ReferenceTileLoad
}
```

The states are distinct:

- `Ready(nonempty)` requires a valid decoded renderer record stream.
- `Ready(empty)` requires a valid normalized source-present tile and a separate hashed semantic-empty proof.
- `KnownEmpty` requires absence from the pinned source population.
- `OutsideCoverage` means the package contract does not cover that coordinate.
- `Unavailable` covers a missing, corrupt, malformed, mismatched, incomplete, over-limit, or unresolved present tile.

Absence from a block index is never, by itself, evidence of empty. A reader MUST fail closed rather than return empty on an integrity, identity, bounds, EOF, membership, or reference error.
