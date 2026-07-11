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
| `u8`, `u16`, `u32`, `u64` | unsigned little-endian integer of the named width |
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

### Placement source kind

| Value | Name |
| ---: | --- |
| 0 | `NONE` |
| 1 | `DIRECT_SOURCE_POINT` |
| 2 | `SOURCE_OWNED_AREA_LABEL_POINT` |
| 3 | `DIRECT_SOURCE_PATH` |
| 4 | `EXACT_PARENT_PATH` |

`NONE` is valid only in the non-applicable sentinel. A point label requires
kind 1 or 2 and an exact source point. A path label requires kind 3 or 4 and
the complete corresponding source-owned path. There is no inferred-centroid,
screen-midpoint, or baked-anchor kind.

### Prominence tier

| Value | Name |
| ---: | --- |
| 0 | `GLOBAL_MAJOR` |
| 1 | `REGIONAL_MAJOR` |
| 2 | `LOCAL` |
| 3 | `FINE` |

The numeric order is the label-collision order: a smaller value is more
prominent. The zero value is also the mandatory prominence-tier component of
the non-applicable placement sentinel; it has no prominence meaning when text
evidence is `NONE`.

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

Detached `FAE8LLB1` and `FAE8VAR1` evidence uses the same record and MUST carry
the complete updated candidate or variant preimage. Consequently semantic
priority, prominence tier, optional provider rank, complete-geometry measure
bucket, and prominence rule ID are all independently full-digest-verifiable;
retaining only the 64-bit hot ID is not sufficient evidence.

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
14. `u8 placement_source_kind`
15. `i32 display_min_zoom_centi`
16. `i32 display_max_zoom_centi`
17. `u32 spacing_px`
18. `u32 maximum_bend_angle_degrees`
19. `u64 collision_group`
20. `i32 semantic_priority`
21. `u8 prominence_tier`
22. Boolean provider-rank presence, then optional `i32 provider_rank`
23. `u16 complete_geometry_measure_bucket`
24. `u64 prominence_rule_id`
25. 32-byte `prominence_decision_sha256`
26. Boolean `avoid_edges`
27. Boolean `keep_upright`
28. `u8 active_band_limit`

```text
label candidate fingerprint = SHA256("FAE8LLB1\0" || tag-6 bytes)
```

The candidate numeric ID and full digest are excluded from this preimage. Every listed field is meaning or eligibility and changes the fingerprint. `placement_source_feature_id` MUST equal the unsigned-big-endian first eight bytes of `source_feature_sha256`; the geometry ID has the same required relationship to the geometry digest. Only these transport membership fields are excluded: requested retrieval tile/metatile, feature page, local ordinal, deterministic owner, and world-wrap copy.

`semantic_priority` is the exact compiled cartographic class priority; smaller
values win. `provider_rank` is present only when the source-specific rank has
been independently verified; a present rank wins over an absent rank and a
smaller present value wins. A larger `complete_geometry_measure_bucket` wins.
`prominence_rule_id` identifies the exact independently reproducible rule and
MUST be nonzero for an applicable label. The generic placement `priority` field
from the pre-order-tuple draft no longer exists and MUST NOT be decoded as
`semantic_priority`.

`prominence_decision_sha256` MUST equal SHA-256 of the complete canonical
`FAE8PDEC1\0` decision retained in detached source evidence. Zero is forbidden
for a label. The independent package verifier MUST resolve the digest and prove
that its subtype, policy, order tuple, evidence, and source context exactly
reconstruct these candidate fields before admitting the variant.

### 8.2 Stored NormalizedPlacement, tag `0x20`

The stored placement contains, in order: `u64 label_candidate_id`, 32-byte label
candidate digest, 32-byte source-feature digest, 32-byte geometry digest,
evidence `u8`, source-field `u64`, placement-source-feature `u64`,
placement-geometry `u64`, packed source tile, source zoom `u8`, source declared
extent `u64`, four edge-domain `i64`, placement-source kind `u8`, min/max display `i32`,
spacing `u32`, max angle `u32`, collision group `u64`, semantic priority `i32`,
prominence tier `u8`, Boolean provider-rank presence and optional provider rank
`i32`, complete-geometry measure bucket `u16`, prominence rule ID `u64`,
32-byte prominence-decision digest, two Boolean placement flags,
active-band-limit `u8`, 32-byte style/policy digest, Boolean provider-feature-ID
presence, and optional provider feature ID `u64`.

Its stored candidate ID/digest MUST recompute from the variant's exact whole text and tag-6 meaning fields.

An unlabeled `LINE` or `POLYGON_OUTLINE` uses one mandatory non-applicable
placement sentinel: all IDs, digests, intervals, spacing, collision,
semantic-priority, measure-bucket, prominence-rule, and flags are zero;
prominence tier is numeric zero (`GLOBAL_MAJOR`), evidence is `NONE`; source
tile is `0/0/0`; declared extent is one; edge domain is `(0,0,0,0)`;
placement-source kind is `NONE`; the prominence-decision and style/policy
digests are zero; and both provider rank and provider feature ID are absent.
Any other unlabeled placement is noncanonical. A `LABEL` cannot use this
sentinel.

The pre-order-tuple, old `projection_mode`, and pre-prominence-decision tag-`6`
and tag-`0x20` byte layouts are obsolete prerelease encodings. A decoder MUST
follow the exact field order above and reject those old bytes as malformed; it
MUST NOT infer defaults for missing order, provenance, or decision fields.

### 8.3 Parent label bands and membership

A `ParentLabelBand` stores one canonical variant/occurrence path; it does not expand that label into every child tile. A retrieval membership is derived arithmetically only when all of these are true:

- requested zoom is at least source zoom;
- requested zoom minus source zoom does not exceed `active_band_limit`;
- requested integer zoom in centizooms lies in the half-open display interval `[min,max)`;
- the unmodified rational canonical path exactly intersects the requested tile (including an explicitly named world-wrap domain).

Membership derivation is packed-tile sorted, deduplicates requested coordinates, and has a hard maximum of four membership references per candidate. A membership carries candidate ID, source-feature ID, geometry ID, requested tile, owner, metatile/page/local transport, and world wrap. A sibling that does not intersect receives nothing. A name is never transferred to equal-looking geometry, a nearby path, a parent/child source feature, or another LOD.

`avoid_edges` is evaluated against the recorded source tile and `source_edge_domain`, not descendant retrieval edges. The domain and margin are signed source-local units over `source_declared_extent`; an anchor rational is compared by integer cross multiplication after projecting the source domain with `(tile*E + local)/(2^z*E)`. Crossing a child edge does not split, rename, rehash, or reject an otherwise eligible whole label.

Viewport assembly unions memberships and deduplicates by full candidate identity before shaping and collision. One candidate therefore yields one whole word/run even when multiple tiles retrieve it. Equal text on disconnected paths remains multiple candidates. Stable repeat phase is `label_candidate_id mod spacing_px`; the locked water-label spacing and maximum bend inputs remain exact integers (`1000` px and `30` degrees for that compiled rule). Whole-label placement is atomic: the complete shaped run must fit one continuous eligible segment, satisfy the bend limit, and pass collision. Partial glyph or substring placement is forbidden.

### 8.4 Source-owned point and area-label placement

A point label MUST use `DIRECT_SOURCE_POINT` or
`SOURCE_OWNED_AREA_LABEL_POINT`. Its one-point renderer geometry, source
feature, visible-text evidence, source field, and optional stable provider join
MUST all resolve through detached source audit. The point is projected through
the current fractional viewport transform every frame. A bake or renderer MUST
NOT infer a polygon centroid, representative point, screen anchor, nearby
same-name feature, or fallback position.

`DIRECT_SOURCE_POINT` means the labeled source feature itself owns the exact
point. `SOURCE_OWNED_AREA_LABEL_POINT` means the provider emitted an explicit
label point for an area; it does not authorize computing a point from area
geometry. If the visible text comes through `PROVIDER_STABLE_JOIN`, the exact
nonzero provider ID and both source occurrences remain in audit evidence.

The complete NFC string is shaped once with the same explicit shaping inputs
as a line label and `textScaleX=1`. Eligibility requires a positive shaped
advance and a source-valid point. Collision uses the whole shaped bounding box
expanded by ascent, descent, halo, and the locked padding; static chrome avoid
rectangles participate. The entire expanded box must remain inside the locked
viewport edge clearance or the label is absent. Partial text, abbreviation,
horizontal condensation, per-glyph records, centroid fallback, and clipped
edge placement are forbidden. Candidate ordering and filter state use the same
exact global tuple as path labels.

### 8.5 Adaptive line placement at the current viewport

A line-label record stores the complete exact source-owned path plus placement
policy; it MUST NOT store one baked anchor, one baked midpoint, one baked
rotation, or per-glyph positions as the authoritative placement. Those choices
would be correct only for one projection and zoom.

Viewport zoom eligibility uses `centizoom = roundHalfAwayFromZero(zoom * 100)`;
nonnegative map zoom therefore uses `floor(zoom * 100 + 0.5)`. Fade alpha is a
deterministic fixed-point function of this centizoom and the variant's exact
fade interval. Integer source-tile zoom may select retrieval membership, but it
cannot stand in for the current fractional viewport zoom when choosing style,
fit, or placement.

For every current fractional zoom, pan, viewport size, and world wrap, the
renderer projects every part of the unmodified canonical path into current
screen space and derives temporary visible contiguous runs. It MUST NOT choose
the first part or the part with the most points as a placement shortcut.
Disconnected source parts never join. Temporary clipping, screen-space subpath
choice, arclength, direction, anchor, and glyph positions are presentation
state: they do not alter source, geometry, candidate, variant, or package
identity.

The complete NFC string is shaped once with explicit typeface/font identity,
locale, bidi direction, density, font scale, user text scale, letter spacing,
and `textScaleX=1`. The minimum eligible subpath length in current pixels is
exactly:

```text
shapedAdvance + 2 * endClearance
```

No occupancy fraction below one, condensation, horizontal scaling, truncation,
substring, zero-clamped impossible offset, or per-character record is allowed.
The temporary path bend is the rounded-up maximum angular span of all nonzero
segment tangents beneath the text span; the compiled water rule rejects spans
over 30 degrees. `keep_upright` may reverse only the temporary presentation
path. It never mutates canonical geometry.

Repeat slots are deterministic alternatives over complete-part source
arclength. Their phase is `label_candidate_id mod spacing_px`; Experiment 8 v1
accepts at most one presentation instance per candidate/world wrap in one
viewport. Collision uses conservative path capsules expanded by shaped
ascent/descent, halo, and clearance—not a midpoint rectangle. Static chrome
avoid rectangles participate in collision; moving aircraft do not, preventing
label jitter.

The selected subpath MUST adapt when zoom changes how the river is visible. The
renderer may retain the prior subpath while it remains eligible, but it MUST
re-evaluate against current geometry before text becomes compressed, fragmented,
off-path, or excessively bent. When the previous run becomes ineligible, the
renderer chooses the deterministic next eligible whole-word run and uses the
accepted label fade/handoff behavior; it never pins the old midpoint or draws a
partial word. If no current run is legible, the whole label is absent.

Global candidate order is the exact ascending tuple:

```text
(
  semantic_priority,
  prominence_tier,
  0 if provider_rank is present else 1,
  provider_rank if present else 0,
  65535 - complete_geometry_measure_bucket,
  label_candidate_id
)
```

Thus verified provider rank presence wins over absence, a smaller present rank
wins, and a larger complete-geometry bucket wins. The `CanonicalVariant.priority`
field is separate renderer-record/draw ordering and MUST NOT participate in this
viewport label-collision order. Within one candidate, eligible runs score
lexicographically: the previous still-eligible source span first; greater
minimum screen clearance; lower bend; smaller distance to the largest
unblocked viewport center; smaller canonical part/segment/source position;
repeat ordinal; then candidate ID. Input tile, worker, and decode order cannot
affect either ordering.

The five order fields MUST come from one source-bound canonical prominence
decision, not unrelated bake heuristics. The presentation policy domain is
`FAE8PRES1\0`, and the current policy SHA-256 is
`40f4e98394dacfaaad7cdc195858d0b56fc72ba5c83ccfc1e75d71fff6f6395c`.
The detached decision begins with `FAE8PDEC1\0` and binds, in order: policy
digest, subtype `u32`, semantic priority `i32`, tier `u8`, provider-rank
presence plus optional `i32`, measure bucket `u16`, rule ID `u64`, evidence
kind `u8`, evidence value `i64`, source-generation digest, classifier digest,
and nonzero source-field ID `u64`. A bare verification Boolean or legacy
provider-tier value is not evidence.

Semantic priority is `prominence_tier_code * 1000 +
within_tier_class_priority`; every within-tier value is unique and below 1000.
Therefore a stronger tier always wins across every feature family even though
semantic priority is the tuple's first field. The complete-geometry measure
bucket is zero unless the appropriate complete measure was independently
verified; a positive measure uses the locked base-2 exponent plus 10-bit
fractional-mantissa rule, saturating at 65535. A fragment measure cannot enter
this field. `prominence_rule_id` is the first eight digest bytes, interpreted
unsigned big-endian, of `SHA256("FAE8RULE1\0" || subtype_u32le || tier_u8 ||
evidence_kind_u8)` and MUST be nonzero for a label.

The decision is evidence-self-consistent, not merely well typed. Provider-rank
evidence requires `evidence_value == provider_rank` and a zero geometry bucket.
Capital level, population, complete area, complete relation length, and typed
default each have one exact allowed subtype/value/tier/bucket relationship.
Typed-default evidence stores the numeric subtype as its value. A provider-rank
decision cannot mix a separately unbound fallback population, capital, area,
or length. The `prominence_decision_sha256` in both candidate and stored
placement binds this complete decision.

Canonical full alpha is exactly 1000 milli-alpha. Label fade is zero at or
below minimum centizoom and full at or above full-alpha centizoom. Outline fade
is zero at or below minimum and at or above maximum, rises to full alpha at its
full-alpha boundary, remains full through its fade-out boundary, then falls to
zero at maximum. The zero-at-maximum rule takes precedence when fade-out equals
maximum, so the immediately preceding centizoom remains full and maximum is
zero. Every division rounds to nearest integer with exact halves
away from zero. All four tier defaults and the nonnegative centizoom formula
`floor(zoom * 100 + 0.5)` are part of the policy digest.

Previous placement stores candidate ID, wrap, canonical part/segment, and
source-position fraction, never a screen midpoint. Every frame reprojects and
revalidates that source span. An ineligible span hands off between two complete
runs using complementary alpha for at most 220 ms. Reference labels MUST NOT be
baked into a retained bitmap that is scaled across zoom. Outlines may use a
retained bitmap, but labels are drawn live from current validated typed
candidates.

Runtime caching may retain shaped runs, decoded world paths/cumulative source
positions, and settled screen placements. Cache keys include every shaping
input, candidate/geometry/style/filter identity, projection/world wrap, exact
viewport transform/dimensions, zoom, and chrome-obstacle revision. A cache hit
cannot bypass current fit, bend, edge, collision, or filter validation;
eviction causes recomputation, never information loss.

Acceptance is multi-zoom and temporal, not one screenshot: the same Chester
source candidate must yield intact, appropriately sized `Chester River` runs
following the visible western river shape at each required zoom; fractional
zoom and pan must show smooth motion or an accepted whole-label handoff; and no
sample may exhibit crushed spacing, scattered glyphs, a fixed off-shape anchor,
or duplicate tile-fragment words.

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

`CanonicalVariant.priority` remains the generic renderer-record priority used
by the renderer order in section 12 after `draw_order`. It is not a label
semantic priority, is not a substitute for the explicit placement tuple, and
cannot affect viewport label collision ordering.

The exact identity is:

```text
full variant digest = SHA256("FAE8VAR1\0" || complete tag-4 payload)
canonical_variant_id = unsigned-big-endian(full digest[0:8])
```

The geometry ID and full placement-geometry digest MUST address the exact embedded renderer geometry. The candidate ID/digest MUST address every tag-6 field and exact text. A package reader can therefore recompute the variant ID from package bytes without source evidence.

`LABEL` requires nonempty whole text, point/path geometry, a nonzero canonical
prominence-decision digest, and a compatible non-`NONE` placement-source kind:
point geometry accepts only the two point kinds and path geometry accepts only
the two path kinds. Its semantic priority must fall in
`[tier_code * 1000, (tier_code + 1) * 1000)`. `LINE` requires path geometry, no
text, and the non-applicable sentinel. `POLYGON_OUTLINE` requires polygon
geometry, no text, and that same sentinel. These compatibility rules prevent
inferred point placement, cross-tier priority inversion, and meaningless
placement fields from creating accepted encodings.

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
| FAE8LLB1 candidate bytes | `dbdbdf0035ad8600d4af7423e2e95170f7e264fa1d0b5917a85629c5e12e5784` |
| FAE8VAR1 payload | `7f593b1f358509530140a2cd483505e6a85a887e247843a4509de2fee5ad7bef` |
| encoded variant wrapper | `3d3b0aa6be79420abbdf48eed2f5adc5f8ebf40a354123e47d4ec4f62fc319f3` |
| tile posting | `de6aef78fffc7c163275e07cfb3ed38027076b4259511d9e5998870809e4fe90` |
| renderer record | `7ade0bc7d06a97313a939eaf8e5487a41b5fe6fcf366e7eb0174e9464f57e170` |
| detached identity evidence | `384ff71faa80ffee18358f77b3933a35902d4f529198cd761b7b1b36c639c06b` |

The fixture's renderer-contract hash is `f21dd173f781fbae1847f582c5f0376d315350f318af0e5a3d43ec8511794e13`. Tests also freeze complete literal hex for the source-geometry and tile-posting records so endian and field-order errors cannot hide behind writer/reader round trips.

## 13. Exact reconstructed-heap accounting

Every reconstructed occurrence is charged independently; variant reuse or duplicate membership transport does not earn semantic heap credit. The exact weight of one renderer record is:

```text
312
+ 16 * renderer_point_count
+  4 * geometry_part_count
+  8 * (source_style_id_count + render_token_id_count)
+ UTF8_byte_length(text, or zero)
```

The fixed `312` bytes include one 24-byte aligned presentation-order block:
semantic priority/tier/provider-rank presence/measure bucket, provider-rank
storage, and prominence rule ID, plus the 32-byte canonical prominence-decision
digest. The block and digest are reserved for every reconstructed placement,
including the non-applicable sentinel, so provider-rank absence does not create
two heap formulas. The hard display-query ceiling is 33,554,432 bytes.
Accumulation is checked after every record and fails closed on the first excess.

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
