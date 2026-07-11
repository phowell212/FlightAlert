# Experiment 8 Adaptive Reference Labels And Filters

Status: design candidate under implementation and independent review. This is
not device acceptance evidence.

## Purpose

Experiment 8 must make the whole-world offline reference layer genuinely useful,
not merely small or structurally valid. A prominent sourced feature must be
eligible at an appropriate zoom, its complete name must remain legible, and its
placement must adapt to the geometry visible in the current viewport. Lesser
features must enter later according to source-evidenced prominence. Users must
be able to filter the resulting semantic classes independently without the app
claiming a class the installed package does not contain.

The hard rule is zero visual degeneracy. Missing prominent names, premature
minor-feature clutter, oversized text, horizontal condensation, broken words,
scattered glyphs, unstable path text, duplicate tile-fragment labels, and
dominant or conflated outlines are rejection conditions.

## Captured failure

The read-only phone capture is:

`C:\Users\Phineas\Documents\FlightAlert-test-artifacts\experiment 8\label-system\chester-missing-current.png`

- bytes: `2,858,346`
- SHA-256: `c15c1910fdce33ac1e5042c58fab8091a962a345a665ef88e59201ee28f8d7d0`
- image: `904 x 2316`
- settled saved zoom read externally: `6.27801`
- policy centizoom: `628`

The capture shows no readable Chester River label over its prominent western
course. It also shows oversized place/region/water text, clipped or overlapping
words, and visually dominant coastline/boundary strokes. The capture alone does
not identify viewport center and must not be used to invent one.

## Confirmed systemic causes

The current reference renderer:

1. chooses the decoded tile geometry part with the greatest point count instead
   of assembling and evaluating the complete source-owned path;
2. accepts a water path when it is only `0.62 * measuredText`, which cannot fit
   the whole word and is the inverse of a 62-percent occupancy requirement;
3. uses a midpoint and the complete tile-fragment path without a whole-run fit or
   bend proof;
4. collides a midpoint rectangle rather than the actual curved text span;
5. deduplicates primarily by normalized text and an ad hoc screen distance;
6. gives water lines one broad style/priority instead of a prominence hierarchy;
7. limits labels with a viewport-wide count budget that can reject important
   features after minor features enter; and
8. bakes labels into a retained bitmap that may be scaled across as much as 2.5
   zoom levels, so type and path placement cease to match the current viewport.

The missing Chester name has a second source-completeness cause. The existing
Esri reference lane does not provide the western source-owned Chester path in
the required corridor. Moving an unrelated Esri label would be dishonest. The
supplemental OSM source relation `12152277` provides the real `Chester River`
name and eight source-ordered way members. The exact fixture measures about
`79,256` metres, so it qualifies through the generic complete-relation rule;
there is no Chester-specific renderer exception.

The systemic fix therefore requires both complete source acquisition and
current-viewport placement. Neither one can compensate dishonestly for the
other.

## Stable semantic taxonomy

Every renderer variant carries one numeric semantic subtype. Visible name text
or suffixes never determine or promote the subtype.

| ID | Semantic subtype | Stable filter ID |
|---:|---|---|
| 100 | country or territory | `labels.regions` |
| 110 | first-order region | `labels.regions` |
| 120 | second/local region | `labels.regions` |
| 200 | capital or major city | `labels.places` |
| 210 | city or town | `labels.places` |
| 220 | local place | `labels.places` |
| 230 | island or islet | `labels.islands` |
| 300 | ocean or sea | `labels.major_water` |
| 310 | bay or sound | `labels.major_water` |
| 320 | lake or reservoir | `labels.major_water` |
| 330 | river | `labels.rivers` |
| 340 | stream or creek | `labels.streams` |
| 350 | canal or channel | `labels.canals` |
| 360 | unspecified sourced watercourse | `labels.streams` |
| 400 | protected land | `labels.protected_lands` |
| 500 | coastline | `outlines.coastlines` |
| 510 | international boundary | `outlines.international` |
| 520 | state/province boundary | `outlines.state_province` |
| 530 | county/local boundary | `outlines.county_local` |
| 540 | other administrative boundary | `outlines.other` |
| 550 | protected-area outline | `outlines.protected_areas` |
| 560 | watershed/water boundary | `outlines.water_boundaries` |
| 570 | other sourced outline | `outlines.other` |

The filter partition is exact: every subtype belongs to one and only one stable
filter. Labels and outlines never share a filter. Coastline is independently
controllable and cannot be implied by the general border master.

## Universal prominence model

Prominence is required for every label, not only rivers. It controls minimum
zoom, fade completion, nominal text size, semantic priority, and collision
order. The four tiers are `global_major`, `regional_major`, `local`, and `fine`.

Evidence precedence is:

1. a verified provider rank or scale whose source semantics are frozen;
2. verified explicit capital/population data or verified complete geometry
   measure appropriate to that subtype; and
3. a conservative typed-subtype default.

Unverified fields are ignored. A tile fragment, incomplete relation, nearby
feature, matching string, or suffix such as `River`, `Creek`, `City`, or `Island`
cannot promote a label. The bake records the evidence kind, exact evidence
value, tier, rule ID, and policy digest so an independent verifier can reproduce
the result.

The locked canonical policy domain is `FAE8PRES1\0`; the current policy
SHA-256 is
`40f4e98394dacfaaad7cdc195858d0b56fc72ba5c83ccfc1e75d71fff6f6395c`.
An authoritative decision cannot be made from a bare "verified" Boolean. It
requires a source-generation SHA-256, classifier SHA-256, nonzero source-field
ID, evidence kind, and signed 64-bit evidence value. Provider precedence is
available only through a typed provider-evidence record that additionally
binds the raw signed 32-bit provider rank and selected tier. Legacy unbound
provider-tier fields are rejected.

The canonical `FAE8PDEC1\0` decision binds the policy digest, subtype, semantic
priority, tier, optional provider rank, complete-geometry measure bucket,
nonzero rule ID, evidence kind/value, and complete source context. The measure
bucket is zero unless the complete geometry measure was independently
verified; it is a deterministic saturating unsigned-16 logarithmic bucket and
never uses a tile fragment.

Each renderer label also carries SHA-256 of that complete canonical decision.
The candidate and variant identities bind this digest, and the independent
verifier must resolve it back to exact detached evidence. Provider-rank
decisions cannot mix unbound fallback population/capital/area/length evidence;
typed defaults and every numeric evidence kind have one exact allowed
subtype/tier/bucket relationship.

### Place evidence

For a city/town with verified population:

- at least `1,000,000`: `global_major`;
- at least `100,000`: `regional_major`;
- at least `10,000`: `local`;
- otherwise: `fine`.

A verified national capital is `global_major`; a verified regional capital is
`regional_major`. Without provider/capital/population evidence, a typed
capital/major city defaults to `regional_major`, a city/town to `local`, and a
local place to `fine`.

The place-family presentation bands are:

| Tier | Minimum centizoom | Full alpha | Text size |
|---|---:|---:|---:|
| global major | 425 | 460 | 11.50 sp |
| regional major | 525 | 560 | 10.75 sp |
| local | 650 | 690 | 9.75 sp |
| fine | 775 | 815 | 9.00 sp |

### Region evidence

Conservative defaults are country/territory `global_major`, first-order region
`regional_major`, and second/local region `local`. Their default bands therefore
enter at centizoom `250`, `450`, and `700`, with sizes `12.00`, `10.50`, and
`9.25` sp respectively. A verified provider scale may override those defaults.

### Island, water-area, and protected-land evidence

Only verified complete area may promote these fallback rules:

- island: global at `10,000 km2`, regional at `500 km2`, local at `25 km2`;
- bay/lake water area: global at `100,000 km2`, regional at `5,000 km2`, local
  at `100 km2`;
- protected land: regional at `5,000 km2`, local at `100 km2`.

An incomplete polygon cannot promote itself from its fragment area. Oceans/seas
default to `global_major`. An island or protected area without verified complete
area defaults to `fine`; a bay/sound or lake/reservoir defaults to `local`.

### Watercourse evidence and the centizoom-628 boundary

A complete named river relation is `global_major` at `500 km`,
`regional_major` at `25 km`, `local` at `5 km`, and otherwise `fine`. An
incomplete relation or unjoined way fragment cannot use a partial length to
promote itself.

| River tier | Minimum | Full alpha | Text size |
|---|---:|---:|---:|
| global major | 550 | 585 | 11.00 sp |
| regional major | 593 | 628 | 10.50 sp |
| local | 688 | 718 | 9.75 sp |
| fine | 748 | 783 | 9.25 sp |

The complete `79.256 km` Chester relation is therefore regional-major and fully
eligible at centizoom `628` through a generic rule.

Every stream/creek band starts strictly after `628`; even a verified globally
prominent stream class starts at `668`, while the conservative fine stream rule
starts at `778` and reaches full alpha at `813`. Canals/channels start at `668`
or later, with the default local rule at `728`. Unspecified watercourses start
at `708` or later, with the conservative fine rule at `798`. The current capture
must therefore contain no stream/creek/canal clutter.

## Distinct restrained styles

Each semantic subtype has a distinct stable style family. Theme-specific colors
are referenced by tokens rather than baked raw screen colors. Visible text is
never uppercased, abbreviated, stretched, or otherwise rewritten to create a
style.

- region tiers vary weight and letter spacing;
- major places are heavier than local places;
- islands use their own land label token and open spacing;
- oceans, bays, and lakes have separate water-area tokens;
- rivers are italic with `0.070 em` spacing;
- streams are smaller and more muted with `0.045 em` spacing;
- canals use a distinct engineered-water style and normal slant;
- protected land uses a separate land/protection token;
- coastline, international, state/province, county/local, protected-area,
  water-boundary, and other outlines have distinct color/pattern/width tokens.

The current heavy white coastline treatment is not an accepted target. Exact
theme colors/alpha remain subject to physical-device visual QA, but a style may
not dominate the satellite imagery or obscure aircraft.

All alpha is exact fixed point. Full opacity is `1000` milli-alpha, fractional
centizoom uses `floor(zoom * 100 + 0.5)`, and every fade division rounds to the
nearest integer with exact halves away from zero. Label and outline endpoint
behavior, every no-evidence subtype default, and the full-alpha constant are
inside the canonical policy hash. At an outline maximum, zero alpha takes
precedence even when fade-out equals that same maximum.

## Source-owned point and area labels

Point labels carry one of two explicit source kinds: direct source point or
source-owned area-label point. Both require an exact source point and verified
source text evidence. An area-label point must be emitted by the provider; the
bake and renderer cannot infer a centroid, representative point, screen anchor,
or nearby same-name fallback. The complete word is shaped without horizontal
scaling, collided as one halo-expanded bounding box against labels and static
chrome, and drawn only when the whole box remains inside edge clearance.

The `N8T1` record has no inferred-centroid state. Point geometry accepts only
the two point kinds; path geometry accepts only direct-source-path or
exact-parent-path. Every applicable label stores the nonzero digest of its
canonical prominence decision.

## Adaptive line-label placement

The package stores the complete source-owned path and policy. It does not store
one authoritative screen anchor, midpoint, rotation, tile fragment, or set of
glyph positions.

For each current fractional zoom, pan, viewport size, and world wrap:

1. retrieve and deduplicate whole candidates by full candidate identity;
2. project every complete path part into current screen space without joining
   disconnected source parts;
3. shape the complete NFC text once with explicit font, locale, bidi, density,
   font scale, user scale, and letter spacing, with `textScaleX = 1`;
4. enumerate currently visible continuous source spans;
5. require `available span >= shaped advance + 2 * end clearance`;
6. round the maximum tangent angular span beneath the word upward and reject it
   when greater than `30 degrees`;
7. keep text upright only by reversing the temporary presentation path;
8. collide conservative curved capsules expanded by ascent, descent, halo, and
   clearance, including static chrome avoid rectangles; and
9. accept at most one whole presentation instance per candidate/world wrap in
   Experiment 8 v1.

No 62-percent shortcut, horizontal condensation, substring, partial glyph run,
zero-clamped impossible offset, or tile-fragment duplicate is allowed.

Candidate order is semantic priority, prominence tier, verified provider rank,
negative complete-geometry measure bucket, then candidate ID. Semantic priority
is `tier_code * 1000 + within_tier_class_priority`, so every global-major label
sorts ahead of every regional-major label, every regional-major label ahead of
every local label, and every local label ahead of every fine label regardless
of feature family. For one candidate,
the prior still-valid source span is preferred, followed by greater clearance,
lower bend, proximity to unobstructed viewport center, canonical source
position, repeat ordinal, and candidate ID. Worker and tile-decode order cannot
change the result.

Previous placement stores canonical source part/segment/fraction, not a screen
midpoint. It is reprojected and revalidated every frame. An ineligible prior run
may hand off only between two complete runs with complementary alpha for at most
`220 ms`. Moving aircraft are not collision obstacles, preventing label jitter.

Labels are drawn live from current typed candidates. They cannot be embedded in
a retained bitmap that is scaled across zoom. Outlines may retain a bitmap when
their current transform and filter identity remain valid.

## Package-derived filter UI

The installed package includes a separately hashed `FAE8CAT1\0` semantic-class
catalog with exactly 23 ordered numeric-subtype rows. Every row carries three
separate unsigned 64-bit counts: distinct admitted feature IDs, distinct
canonical variant IDs, and canonical tile-posting records. The catalog also
binds the renderer semantic-stream hash, renderer-contract hash, and exact
presentation-policy hash. A filter is shown only when an independent verifier
has accepted the catalog and the sum of its owned distinct-feature counts is
nonzero.
Missing, corrupt, or unverified catalogs produce an honest unavailable message
and no fake toggles. A currently absent class remains hidden rather than
disabled as though it were installed.

The existing broad `Places`, `Water`, `Regions`, `Public lands`, and `Borders`
controls are insufficient. The Filters panel becomes two logical tabs:

- `Traffic`: the existing aircraft search and traffic filters;
- `Map`: package-derived `Labels` and `Outlines` sections using the stable IDs
  in this document.

The label and outline master switches gate rendering but preserve every stored
subtype choice. Turning labels off and on cannot silently re-enable streams a
user disabled. Unknown persisted IDs are ignored. Known choices for a class
temporarily absent from a package remain stored so a future verified package can
restore the user's selection. Reset uses package/policy defaults, not whatever
controls happen to be visible on one screen.

Each row uses its actual style swatch, an explicit on/off state, a minimum
`48 dp` target, and a virtual accessibility node with role, category, state, and
action. Portrait uses one readable column. Landscape-short/fold/resized layouts
may use two columns only when labels and targets remain unclipped. The panel must
remain usable with large font scale and cannot rely on color alone.

## Source-completeness gate

The Chester failure cannot be closed by a renderer screenshot alone. For every
pilot/source unit, the evidence chain separately proves:

- every source root passing the locked policy is selected;
- every selected root is classified complete, source-incomplete, or invalid;
- only independently complete roots enter the admitted manifest;
- every admitted semantic record and package posting reconciles to one selected
  source occurrence and prominence decision;
- expected prominent in-coverage source names are present as complete candidates;
- no incomplete relation or nearby same-name geometry supplies a substitute;
  and
- the verifier independently detects additions, omissions, changed versions,
  missing references, changed tiers, or changed filter/style IDs.

The Maryland regional pilot may quarantine proven clipped relations. The
whole-world build may not: selected, complete, and admitted sets must be equal.

## Acceptance gates

### Data and policy

- Exact policy bytes and SHA-256 agree between Python, Kotlin, bake output, and
  independent verifier.
- Every `N8T1` label candidate carries the authoritative semantic priority,
  tier, optional provider rank, complete-geometry measure bucket, and rule ID;
  all five fields, the placement-source kind, and the canonical-decision digest
  are part of candidate and variant identity.
- Stable subtype/filter/style IDs form the exact partition above.
- Every emitted label has reproducible source-evidenced prominence.
- Names/suffixes, fragment measures, and unverified provider fields cannot
  promote labels in negative tests.
- Package catalog counts reconcile to emitted variants/postings.

### Screenshot corridor

At the captured centizoom `628` and the exact recovered viewport once available:

- the complete OSM Chester candidate is applicable and can produce an intact,
  appropriately sized `Chester River` run on the visible western river;
- stream, creek, canal, and unspecified-watercourse candidates are absent;
- existing region/place/water labels are within their exact prominence rules;
- no clipped, overlapping, condensed, fragmented, or repeated word is accepted;
  and
- coastline can be disabled independently from every administrative outline.

### Temporal and multi-zoom

Use external capture on the physical phone over a frozen pan and fractional-zoom
matrix spanning both sides of every applicable fade boundary. The word must
follow the river shape as that shape changes, remain whole, move smoothly or use
one accepted whole-run handoff, and disappear atomically when no valid span
exists. A single screenshot cannot pass this temporal gate.

### UI and accessibility

Unit tests cover catalog availability, stable persistence, master-gate
preservation, filtering, reset, and unknown IDs. Emulator/device checks cover
portrait, landscape-short, fold/resizing, large font scale, touch targets,
keyboard/back behavior, and accessibility nodes. No text clipping or hidden
control is accepted.

### Performance

Correctness and visual gates pass first. Measure frame timing, memory, and input
continuity externally with the same app/package hashes and cold/warm scenarios.
Shaped-run and decoded-path caches are bounded and their eviction changes only
recomputation, never information. No app-side profiling code is added.

## Implementation split

To permit low-conflict parallel work:

1. Python owns canonical taxonomy, prominence, visibility, style, fit, and
   catalog policy plus deterministic bytes/tests.
2. A new pure Kotlin policy/persistence layer mirrors those exact bytes and IDs.
3. A new responsive filter-panel component renders typed rows and accessibility
   nodes from package-derived state.
4. Shared `FlightMapView`, settings, and existing panel integration occurs only
   after both pure lanes pass and their policy hashes agree.
5. The renderer integration removes live labels from the scaled retained bitmap
   and implements current-viewport whole-path placement.
6. The bake emits complete typed paths, prominence evidence, class counts, and
   independent reconciliation artifacts.

Esri satellite imagery acquisition/scheduling, aircraft sprites, and aviation
layers remain separate evidence lanes. Experiment 8 reference work does not use
their performance or correctness budgets as a shortcut.
