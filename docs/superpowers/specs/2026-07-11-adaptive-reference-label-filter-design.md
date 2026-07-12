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

The filter partition is exact for these 23 filter-addressable subtypes: every
listed subtype belongs to one and only one stable filter. Labels and outlines
never share a filter. Coastline is independently controllable and cannot be
implied by the general border master.

Unlabeled water geometry is a separate master-only semantic domain. It must not
masquerade as a label merely because the corresponding feature may also have a
name, and it must not masquerade as a border merely because both are drawn as
lines. The canonical IDs are:

| ID | Master-only geometry subtype | Control |
|---:|---|---|
| 2000 | watercourse line | overall reference-layer master only |
| 2010 | water-area outline | overall reference-layer master only |

These two IDs are disjoint from all label, outline, and transportation subtype
IDs. They never appear in a `FilterSpec`, the labels master cannot hide the
geometry, and the border/coastline controls cannot claim ownership of it. Their
exact source class, styling, and visibility remain bound by the renderer
variant and source-style evidence. The current locked style has exactly six
included watercourse-line rules and seven included water-area-outline rules;
the style compiler and independent verifier require that reverse census so an
extra master-only rule cannot enter silently.

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

### Local-script and English presentation

This is one app-wide sourced-map-text contract, not an Experiment 8-only
renderer trick. Reference, aviation, imagery/provider, traffic-detail map
annotations, and later source-backed map layers consume the same typed text
record, Unicode-script policy digest, shaping interface, fallback-font policy,
atomic layout rules, and cross-language conformance vectors. A feature family
may select prominence and visual style tokens; it may not invent its own script
test, source-English rule, translation fallback, or bilingual collision model.
Static UI localization remains a separate content domain, but it uses the same
Unicode-capable shaping/font foundation rather than a competing script
heuristic.

The Android boundary is one package-neutral `com.flightalert.text` engine.
It accepts either plain interface text or a canonical sourced-name record and
returns one cached shaped group. Point and path placers consume that same
group; they do not reshape or reinterpret the strings. Offline normalization
emits the canonical sourced-name decision. A live source that cannot use a bake
runs the identical versioned policy once at source ingestion and caches the
result. Python and Kotlin implementations read the same table identity and
must pass the same canonical JSON conformance vectors byte-for-byte. A policy
digest mismatch makes sourced bilingual presentation unavailable rather than
falling back to a layer-local heuristic.

Language is not the trigger for a second line. The bake classifies the exact
primary source text with Unicode 17.0.0 `Scripts.txt`, downloaded only through
the pinned source-acquisition lane from
`https://www.unicode.org/Public/17.0.0/ucd/Scripts.txt`: `192,460` bytes,
SHA-256 `9f5e50d3abaee7d6ce09480f325c706f485ae3240912527e651954d2d6b035bf`.
Its interpretation follows Unicode Standard Annex #24 revision 39; Python's
runtime `unicodedata` and Android's platform `Character.UnicodeScript` are not
authoritative because their Unicode versions differ.
`Common` and
`Inherited` characters such as spaces, punctuation, digits, and combining
marks do not trigger a subtitle by themselves. A primary containing only
Latin-script letters remains one line, including Spanish, French, Portuguese,
Vietnamese, and other accented Latin text. A primary containing any strong
non-Latin script is eligible for the two-line form.

The primary field is the exact text field selected by the owning source's
verified schema/style policy. An adapter may select a documented provider-local
field, but it may not infer a local language from coordinates or pick an
arbitrary translation column. If the verified primary is already English or
Latin transliteration, it remains the honest one-line primary even when the
source happens to carry unrelated language translations. This keeps the common
text engine separate from source-specific field semantics without allowing
each feature renderer to invent presentation behavior.

For the locked Esri World Basemap v2 adapter, the exact rule is narrower. First
resolve the pinned style's owning text field. When and only when that field is
`_name_global` and the same source occurrence contains nonblank `_name_local`,
the hash-pinned Esri adapter selects `_name_local` as primary. Otherwise the
resolved `_name` or `_name_global` remains primary. The separately authorized
named-geometry fallback may use `_name_en` as its primary and then cannot have a
subtitle. This preserves road/shield and ordinary `_name` semantics while
allowing provider-declared local names in the provider-global role. It never
uses coordinates or another translation column to infer local language.

The optional second line is permitted only when the same source occurrence
contains exact `_name_en`, the Esri adapter's independently identified English
field. `_name_global` is not reliably English and can never serve as the
subtitle. The English role and primary-substitution rule are part of the
hash-pinned Esri adapter policy. Text is
never synthesized, transliterated, machine-translated, or borrowed from a
nearby/equal-text feature. A missing, empty, identical, or source-invalid
English value produces a source-honest local-only record and an explicit
English-gap reason. A provider field whose value cannot satisfy the frozen
English-subtitle text contract is also a gap rather than permission to guess.
An accepted English value contains at least one strong Latin scalar and no
strong non-Latin or `Unknown` scalar. `Common`/`Inherited` punctuation, digits,
and marks are neutral. Comparison to the primary occurs after the shared NFC
and trailing-whitespace canonicalization and never case-folds. Raw source bytes
and their digest remain detached audit evidence.

When present, the local primary and smaller italic English subtitle are one
atomic label candidate. They share prominence, visibility, fade, collision,
selection, filtering, and handoff state. Collision uses the union of both
shaped runs; neither line may appear or disappear alone. A path label shapes
two complete coherent runs against the same eligible source-owned path and
must fit the complete two-line block without per-character placement. The
English line uses a presentation token rather than baked glyphs so physical
phone QA can tune the exact smaller size without changing text truth.

Script classification and subtitle eligibility occur once during
normalization. The phone record stores the resulting typed layout mode and
exact source-field identities. Runtime never scans the world, detects scripts
per frame, or searches translation fields: it shapes only accepted candidates
from the active viewport/zoom blocks and caches the atomic shaped result.

For the OSM supplemental lane, the corresponding source policy is `name` as
primary and exact same-object `name:en` as the only English candidate. Neither
`int_name`, `official_name`, another `name:*`, nor a nearby relation is a
fallback unless a later independently reviewed policy explicitly admits it.

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

The cook emits every reachable constant style interval for every matching
direct source-style rule. Each interval is resolved only with a centizoom inside
that same interval; the source tile's integer zoom is membership evidence, not
a viewport-style sample. An interval ending at or below `source_zoom * 100` is
unreachable and may be omitted. Otherwise separate rules, text-field stops, or
visibility/placement stops produce separate candidates with their own exact
text, source-style identity, policy digest, and half-open interval. The cook
cannot select the earliest rule or leak one rule's resolution centizoom into
another.

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

## Style-policy identity and evidence publication

The semantic-policy document includes an independently versioned behavior block
whose accepted domains are mechanically derived from the immutable tables that
the policy hashes. Digest construction executes Boolean, float, string,
missing, and unknown vectors through every public classifier and
`classification_for_style_rule`, including known-source/unowned-style cases.
The style-rule vectors also exercise an owned source/style pair with a wrong
layer type against the accepted types derived from the source-policy table. A
runtime mutation such as coercing `bool` to `int`, accepting an unknown water
symbol, or assigning a fake style to a known source therefore invalidates or
fails policy identity instead of silently retaining the same digest.

Style evidence is published under `generations/<generation-sha256>/`, with the
three exact `audit.json`, `catalog.json`, and `manifest.json` files bound by the
content-addressed generation identity. A canonical `current.json` names one
generation, but a production consumer must also receive that generation SHA-256
from an independently trusted handoff; a pointer cannot authenticate itself.
The strict reader validates canonical schemas, manifest lengths/hashes, every
semantic cross-link, single-link regular files, stable identities, and
non-reparse ancestry. Nested typed rule objects and mechanically derivable
aggregates are reconstructed rather than accepted because their local hashes
were recomputed. It rejects junction aliases and recomputed mixed
audit/catalog/manifest generations.

The persistent lock inside the canonical output is an OS advisory lock, so
process death releases ownership without leaving the output wedged. Generation
publication requires a pre-existing canonical, non-reparse immediate parent
that the operator trusts against concurrent namespace replacement; the writer
never creates a missing parent hierarchy. Generation
and pointer commits use Windows write-through replacement or POSIX replacement
plus a parent-directory metadata `fsync`; unsupported durability semantics fail
closed. The writer completes and reads back the immutable generation before
committing the pointer and never moves the current output directory out of the
way. Cleanup touches only identity-checked staging artifacts or an unreferenced
generation created by the failing writer; if a barrier reports failure after a
visible pointer rename, cleanup retains the generation named by that pointer.
Directory replacement atomicity,
including on NTFS, is not part of the design.

During migration, legacy top-level evidence files may remain beside
`current.json` and `generations/`. Generation-aware readers ignore the flat
files. Each normalizer captures and validates the independently pinned current
generation once before creating workers, retains those exact bytes and the same
generation ID for the entire run/resume identity, and consumes plural
`line_label_candidates` so every reachable interval survives. It never falls
back to the ambiguous singular call. The writer does not delete legacy or
unrelated files; explicit removal is safe only after every consumer uses the
pinned generation interface.

## Acceptance gates

### Data and policy

- Exact policy bytes and SHA-256 agree between Python, Kotlin, bake output, and
  independent verifier.
- Every public classifier's invalid-type, missing-value, unknown-selector, and
  unowned-style behavior agrees with the semantic policy's table-derived
  vectors; digest construction fails on behavior drift.
- Every matching direct style rule contributes every reachable constant
  candidate interval through `line_label_candidates` without sampling
  source-tile zoom as viewport zoom; the singular API rejects ambiguity.
- At every generation/pointer publication boundary, a reader of an existing
  output validates an independently trusted generation and obtains the exact old
  or exact new evidence generation, never a missing, partial, mixed, hardlinked,
  or reparse-aliased set.
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

### Worldwide label matrix

U.S. success is not evidence of worldwide success. Freeze a source-verified,
non-cherry-picked matrix containing at least one dense and one sparse viewport
on every inhabited continent, plus explicit antimeridian and high-latitude
cells. Across the matrix, include applicable cities, rivers, islands,
coastlines, international/first-order/local borders, protected lands, and
water boundaries at wide, entry, normal, and close fractional zooms.

The matrix must exercise exact provider text in multiple writing systems,
including CJK, Arabic or another RTL script, Devanagari, accented Latin, and at
least one additional complex-script/fallback-font case present in the locked
source. Shape and collide whole grapheme-aware runs; never split a combining
sequence, reorder source text, synthesize a translation, replace missing glyphs
silently, or use per-character path placement. For each viewport, evidence must
distinguish sourced/applicable, sourced-but-suppressed-by-prominence/collision,
source-proven absent, and unavailable. Honest gaps remain gaps and cannot be
filled by borrowing a nearby or similarly named feature.

Data-level normalization/package checks and physical-phone temporal capture
must both pass this matrix. The selected cells, source occurrences, languages,
scripts, feature classes, zoom bands, and expected honest gaps are hash-pinned
before candidate comparison. A package or renderer cannot claim whole-world
acceptance from U.S. samples, aggregate counts, or one successful script.

### UI and accessibility

Unit tests cover catalog availability, stable persistence, master-gate
preservation, filtering, reset, and unknown IDs. Emulator/device checks cover
portrait, landscape-short, fold/resizing, large font scale, touch targets,
keyboard/back behavior, and accessibility nodes. No text clipping or hidden
control is accepted.

### Performance

Performance is a co-equal blocking Experiment 8 release gate. Correctness and
visual truth are checked first so a faster candidate cannot pass by omitting,
delaying, thinning, simplifying, or destabilizing reference information. A
candidate that is visually correct but misses the performance gates also
fails; neither dimension compensates for the other.

The final combined-app gate reuses the independently validated external
Perfetto, `gfxinfo`, input-causality, memory, thermal, and ordinary-UI
measurement foundation. It adds no app-side profiler, counter, diagnostic
overlay, hidden gesture, timing log, or test-only production path. Every
artifact binds the exact source commit, APK SHA-256, installed package SHA-256,
local-reference package generation/hash, catalog/policy hashes, device build,
display mode, settings, filters, viewport, cache class, gesture script, and
active-window timestamps.

Baseline and candidate use the same physical 120 Hz phone and paired AB/BA
order. A pair is comparable only when orientation, resolution, refresh mode,
thermal status, compiler/profile state, map bounds, settled zoom, filters,
aircraft/path/aviation state, provider/cache state, and visible semantic
population are equivalent. Invalidating one member invalidates and reruns the
pair. A poor result is never an invalidation.

#### Blocking reference scenarios

Each applicable cell receives three valid 60-second repetitions after a fixed
settle; all three must pass. Timing and >=120 FPS temporal video are separate
runs so capture overhead cannot improve or poison frame evidence.

- sustained pan and real two-pointer pinch across both sides of every label,
  line, polygon, prominence, style, and fade boundary;
- sparse ocean/coastline, ordinary regional, dense metropolitan, and dense
  mixed water/place/border/transport viewports;
- the Chester River corridor at wide, entry, normal, and close zooms;
- every frozen worldwide-label matrix cell across inhabited continents,
  writing systems, density bands, feature classes, and honest-gap states;
- antimeridian wrap, high-latitude Web Mercator limits, and a legitimately
  empty/offline viewport;
- cold process/package-cache, warm process/cold decoded-path cache, and fully
  warm cache classes;
- rapid reference master/filter/category toggles, coastline-only and
  administrative-border combinations, rotation where supported, resize,
  portrait, landscape-short, and large-font UI;
- ten repeated wide-to-close-to-wide cycles followed by a 30-second settle,
  while live aircraft, selected real path when available, Esri imagery,
  aviation layers, ownship, alerts, panels, and chrome remain enabled.

#### Absolute active-rendering tier

Every active pan/pinch cell must satisfy:

- median one-second delivered-present FPS >= 90;
- full-window delivered-present FPS >= 90;
- full-window app-frame p95 < 20 ms and p99 < 50 ms;
- present-interval p99 < 50 ms and no active-window presentation gap >= 100 ms;
- Android modern jank < 5%; and
- FrameTimeline jank < 5%.

Quiescent/static cells are not forced to redraw useless frames. Across 30
declared tap-or-drag trials they instead require input-to-first-present p95 < 20
ms and maximum < 50 ms, with no added idle redraw cadence. A reference filter
change must show the first correct frame within 100 ms at p95 and 250 ms at the
maximum, including collision recomputation; an intermediate frame may not show
a semantically wrong mixture.

Visible local-reference readiness is measured from the first eligible frame to
the first complete correct reference frame for the declared viewport:

| Cache class | Wide/regional | Dense/close |
|---|---:|---:|
| decoded geometry and shaped runs warm | <= 250 ms | <= 500 ms |
| process cold, package/index warm | <= 1,000 ms | <= 2,000 ms |
| process and OS file cache cold | <= 2,000 ms | <= 5,000 ms |

No placeholder, stale wrong-location feature, lower-prominence substitute, or
partial word is counted as ready.

#### Paired regression tier

The median paired candidate delta may not degrade any already-passing primary
FPS, p95/p99, jank, input latency, readiness, decode/I/O latency, or peak/settled
memory metric by more than 10%; the target is materially below 10%. Results
within 3% for FPS/memory, 1 ms for p95/p99/input latency, 0.5 percentage points
for jank, or 5% for readiness are treated as tied and expanded to five valid
pairs. If still tied and both pass, keep the smaller/clearer implementation and
retain the alternative for later user testing. Crossing the relative gate does
not excuse missing an absolute gate.

#### Resource and soak tier

Shaped-run, collision, decoded-path, package-page, bitmap, and source caches
have deterministic entry and byte ceilings. Eviction changes only
recomputation, never information, prominence, naming, geometry, placement, or
filter results. The renderer must query/decode by viewport; no whole-world heap
index or unbounded per-pan allocation is accepted.

After one priming route, the ten-cycle soak must have a median settled-PSS slope
<= 1 MiB per cycle across the final five cycles, and its final median settled
PSS must return within the larger of 16 MiB or 10% of primed settled PSS. Record
Java heap, native heap, graphics/bitmap allocation, total PSS/RSS, page faults,
package/index reads, decoded bytes, cache ceilings, and disk-cache bytes. No
OOM, ANR, crash, unbounded growth, repeated retry spin, or whole-package scan is
accepted.

The preferred complete mandatory steady phone footprint remains
< 25,000,000,000 bytes. The user-authorized hard fallback ceiling is
< 40,000,000,000 bytes, and may be used only when the added bytes materially
preserve independently verified world coverage or non-degenerate visual
fidelity. Both ledgers include the installed APK, reference
package/index/catalog/manifest, retained mandatory install artifacts, and
bounded provider caches. Temporary cook or install duplication must be absent
from steady state. The provisional Esri
base-imagery disk-cache allocation is <= 1,000,000,000 bytes unless the final
joint storage ledger assigns a smaller value; it may never grow unbounded.

Timing runs require Android thermal status 0 at preflight, throughout the
active window, and postflight. A separate 30-minute ordinary-use soak records
battery energy, current samples, and temperature. Candidate energy use may not
exceed the valid paired baseline by more than 10%; maximum temperature rise may
not exceed the larger of the baseline rise plus 1 degree Celsius or 110% of the
baseline rise. No thermal transition or sustained frequency collapse is
accepted.

Performance completion requires raw traces, raw `gfxinfo`, input event logs,
memory samples, cache/storage inventories, thermal/power samples, exact run
manifests, independently recomputed summaries, and matched temporal video. A
host unit test, build, screenshot, single run, average FPS number, or another
deity lane's pass cannot substitute for this combined Experiment 8 gate.

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
