# Flight Alert Sourced Map Text Contract

## 1. Scope

This document defines the sole language-neutral policy for all data-derived
text across reference, aviation, provider/base-map, border, place, water, and
later sourced feature families. A source adapter chooses verified source roles.
The common policy alone canonicalizes selected values, classifies scripts,
decides whether declared English is usable, records an honest gap when it is
not, and forms the canonical identity. Its one decision-path identity is
`flightalert.sourced-text.source-exact-v2`.

This contract does not integrate the semantic model, style compiler, Kotlin
renderer, or phone runtime. Those consumers must use this record without
reinterpreting source roles or script policy.

Layers may supply only exact source roles, feature meaning, prominence, style,
and placement. They must not implement script detection, translation,
transliteration, bilingual eligibility, bilingual layout, bilingual
collision/fade, or fallback-font/shaping policy. They must not infer locale
from coordinates, case-fold, search other fields, concatenate a display
string, or expose English as an independently renderable candidate. Raw source
bytes and source digests remain detached adapter/ledger evidence; the
phone-facing record contains selected source-exact text and numeric field
evidence only.

## 2. Frozen authorities and identities

The script authority is Unicode 17.0.0 `Scripts.txt`:

| Property | Frozen value |
| --- | --- |
| URL | `https://www.unicode.org/Public/17.0.0/ucd/Scripts.txt` |
| UAX #24 revision | 39 |
| Byte length | 192,460 |
| SHA-256 | `9f5e50d3abaee7d6ce09480f325c706f485ae3240912527e651954d2d6b035bf` |

The tracked complete derivative is
`tools/experiment8/data/unicode-script-profile-17.0.0.json`. Its canonical JSON
is 41,325 bytes, has 1,718 intervals covering 1,112,064 Unicode scalar values,
and has SHA-256
`4df49aafa0b507ca5517277c5f3db7faf855196a4b3a2124f4fae4e1f386fbeb`.
Surrogates are not Unicode scalars and are not intervals.

The end-trim authority is only Unicode 17.0.0 `PropList.txt` property
`White_Space`:

| Property | Frozen value |
| --- | --- |
| URL | `https://www.unicode.org/Public/17.0.0/ucd/PropList.txt` |
| Byte length | 145,465 |
| SHA-256 | `130dcddcaadaf071008bdfce1e7743e04fdfbc910886f017d9f9ac931d8c64dd` |

The policy descriptor is canonical JSON hashed as:

```text
SHA256(UTF8("FAE8SOURCEDTEXTPOL1") || 00 || descriptor_bytes)
```

For the frozen profile, the policy SHA-256 is
`ca7bd559b3d84758d17e11b5e619f04da7e5093d67d0f90506ab509e2ef6543a`.
The canonical cross-language vectors are
`tools/experiment8/data/sourced-text-conformance-v1.json`, 38,390 bytes with
SHA-256
`c5f5e9f7ab8d2f9fde7317e217e36331814c72fe280fcc023109e3ba4225c18d`.

No runtime Unicode normalization, whitespace, or script API is authoritative.
All script lookup comes from the frozen interval profile, and the exact
25-scalar end-trim set comes from the frozen `White_Space` property above.

## 3. Adapter boundary

An adapter supplies exactly four inputs:

1. the exact owning-source primary value;
2. its nonzero unsigned 64-bit source-field ID;
3. the optional value from the owning source's one declared-English role; and
4. that role's optional nonzero unsigned 64-bit source-field ID.

When a declared-English role exists but its value is absent or rejected, its
field ID remains in the record as evidence of which role was inspected. An
English value cannot be accepted without a field ID. The policy never asks the
adapter for another value.

For the Esri adapter, resolved `_name` remains primary. `_name_local` may
replace it only when the resolved primary role was `_name_global`. Only
`_name_en` may be declared-English evidence. `_name_global` must not be treated
as English fallback, and coordinates or other translation columns must not
affect the result. In the named-geometry case explicitly authorized by the
versioned Esri source-adapter policy, `_name_en` may instead be selected as the
primary role. When `_name_en` is primary, the adapter must not also supply that
same `_name_en` value or field ID as declared-English subtitle evidence, and it
must not seek another English fallback.

For the OSM adapter, `name` on the same source object is the only primary role,
and `name:en` on that exact same object is the only declared-English role.
`int_name`, `official_name`, every other `name:*`, nearby objects, parent or
child relations, coordinate inference, and every fallback are prohibited. A
later alternative requires a separately reviewed, explicitly versioned source
adapter policy; no implicit fallback is allowed.

Aviation, reference, and later adapters must likewise establish exact verified
roles before calling this same common policy. They may not create
layer-specific script, English, layout, collision, fade, shaping, or font
helpers.

## 4. Canonical display boundary

For each supplied text value, apply these operations in order:

1. require a string where the role requires text;
2. reject more than 4,096 source scalars before any script scan or allocation;
3. require strict UTF-8 representability, with no surrogate replacement;
4. reject more than 4,096 UTF-8 bytes before any script scan or canonical
   allocation;
5. remove only the maximal trailing run of the frozen `White_Space` scalars;
   and
6. retain every other scalar in exact source order.

The exact trailing set is:

```text
U+0009..U+000D, U+0020, U+0085, U+00A0, U+1680,
U+2000..U+200A, U+2028..U+2029, U+202F, U+205F, U+3000
```

Runtime-only whitespace such as U+001C is not trimmed. The length ceiling is
checked before trimming, so trimming cannot admit an oversized source value.
An empty primary after end trim is invalid. Primary and English are compared
exactly after this boundary. There is no case folding.

Normalization is `none`. Composed `Café` and decomposed `Cafe` + U+0301 remain
different source strings and form different identities. The sequence U+0627,
U+0301, U+0899 remains in that exact order; runtimes must not reorder it.

## 5. Script policy

Each source-exact scalar resolves through exactly one profile interval:

| Profile value | Policy class |
| --- | --- |
| `Common` | neutral |
| `Inherited` | neutral |
| `Latin` | strong Latin |
| `Unknown` | separately audited, neutral for subtitle triggering |
| every other explicit script | strong non-Latin |

A primary is bilingual-eligible if and only if it contains at least one strong
non-Latin scalar. Mixed Latin/non-Latin text is eligible. Unknown alone is not
evidence of non-Latin, but Unknown does not suppress a known non-Latin scalar.

A declared English value is accepted only when it contains at least one strong
Latin scalar and contains neither strong non-Latin nor Unknown. Common and
Inherited scalars, including punctuation, digits, combining marks, and emoji,
are neutral. Script acceptance does not imply glyph availability; the later
font/glyph gate remains independently fail-closed.

The 4,096-byte ceiling is part of the policy identity. Overlong primary or
declared-English strings fail before script classification with
`PRIMARY_TOO_LONG` or `ENGLISH_TOO_LONG`; they do not become a misleading gap.

## 6. Layout and English-gap decision

Layout codes are stable:

| Code | Mode |
| ---: | --- |
| 1 | `SINGLE` |
| 2 | `PRIMARY_WITH_ENGLISH` |

English-gap codes are stable:

| Code | Reason | Meaning |
| ---: | --- | --- |
| 0 | `NONE` | accepted English is present |
| 1 | `PRIMARY_NOT_ELIGIBLE` | primary has no strong non-Latin scalar |
| 2 | `ENGLISH_FIELD_IS_PRIMARY` | declared-English field ID equals the primary field ID |
| 3 | `MISSING` | no declared-English value was supplied |
| 4 | `WRONG_TYPE` | declared-English value is not a string |
| 5 | `INVALID_UTF8` | declared-English string is not strict UTF-8 representable |
| 6 | `BLANK` | canonical declared English is empty or all whitespace |
| 7 | `IDENTICAL` | canonical primary and English are exactly equal |
| 8 | `HAS_UNKNOWN` | declared English contains an Unknown scalar |
| 9 | `HAS_STRONG_NON_LATIN` | declared English contains a strong non-Latin scalar |
| 10 | `NO_STRONG_LATIN` | declared English has no strong Latin scalar |

Every supplied declared-English string is checked against the 4,096-scalar and
strict UTF-8 byte ceilings before field-ID validation, primary script
classification, or any gap return. An overlong value therefore always fails
with `ENGLISH_TOO_LONG`, including when the English field ID is missing or
equals the primary field ID, or the primary is not bilingual-eligible.

For values within that ceiling, the decision precedence is the table's policy
order after validating field-ID structure: `ENGLISH_FIELD_IS_PRIMARY`,
`PRIMARY_NOT_ELIGIBLE`, `MISSING`, `WRONG_TYPE`, `INVALID_UTF8`, `BLANK`,
`IDENTICAL`, `HAS_UNKNOWN`, `HAS_STRONG_NON_LATIN`, then `NO_STRONG_LATIN`.
An accepted value produces `PRIMARY_WITH_ENGLISH` and `NONE`. Every other valid
primary produces `SINGLE` and one nonzero gap reason.

## 7. Immutable record

`SourcedMapText` is one immutable atomic value containing:

- canonical primary text and nonzero primary source-field ID;
- optional accepted canonical English text;
- optional declared-English source-field ID, retained even for a gap;
- primary and accepted-English three-bit script signals;
- layout and English-gap enums;
- 32-byte profile and policy identities;
- canonical bytes, their ordinary SHA-256, the domain-separated full identity,
  and its 64-bit hot ID.

The record has no glyph array, per-character record, world lookup, translation
map, path, placement, or independently renderable subtitle candidate. It holds
at most the two selected strings.

Every construction path is validated. The common factory runs the one pure
decision path once. Direct construction, `dataclasses.replace`, and later
decode-style reconstruction must match the exact frozen profile/policy and the
recomputed source-exact primary/English signals, eligibility, field-role
relationship, exact equality, layout, and gap. A rejected English value is not
stored; reconstructing a non-derivable gap therefore requires detached source
evidence for validation, which is discarded after construction. A caller
cannot mint an honest-looking record with false derived fields.

## 8. Canonical byte encoding and identity

All integers are unsigned little-endian unless stated otherwise. A Blob is a
`u32` byte length followed by strict UTF-8 bytes. The record is:

| Order | Field |
| ---: | --- |
| 1 | `u8` tag `53` |
| 2 | `u8` record version `1` |
| 3 | 32-byte Unicode profile SHA-256 |
| 4 | 32-byte sourced-text policy SHA-256 |
| 5 | `u8` layout code |
| 6 | `u8` gap code |
| 7 | `u8` primary script mask: Latin bit 0, non-Latin bit 1, Unknown bit 2 |
| 8 | `u64` primary source-field ID |
| 9 | primary text Blob |
| 10 | `u8` declared-English-field presence |
| 11 | conditional `u64` declared-English source-field ID |
| 12 | `u8` accepted-English presence |
| 13 | conditional `u8` accepted-English script mask |
| 14 | conditional accepted-English text Blob |

The full identity is:

```text
full_sha256 = SHA256(UTF8("FAE8SOURCEDTEXT1") || 00 || canonical_bytes)
hot_id = unsigned-big-endian(full_sha256[0..7])
```

The full digest is authoritative collision evidence. The hot ID is only its
compact projection. Exact selected text, both field IDs when present, profile
identity, policy identity, script signals, layout, and gap are all inside the
canonical preimage.

## 9. Atomic presentation and runtime ownership

Classification happens once during offline normalization or once when a live
source is ingested. Here “normalization” means source-record normalization as a
pipeline stage, not Unicode normalization. Consumers reuse the immutable
profile table and policy
instance. No frame may rescan scripts, scan the world, translate, build a glyph
array, or allocate per-character records.

The one presentation token is
`flightalert.sourced-map-text.primary-with-english.v1`. It means the accepted
English run is smaller and italic. Point and path placers later shape primary
and English independently for bidi and font correctness, then cache one atomic
bundle. The two runs share one candidate ID, path or anchor, visibility, fade,
filter, selection, handoff, whole-block fit, and collision decision; collision
uses the union of both runs. A subtitle cannot survive or collide separately.
Feature families may vary only exact source roles, feature semantics,
prominence, style, and placement. The shared atomic policy owns bilingual
layout/collision/fade behavior and the shared shaping/font foundation owns
shaping plus fallback-font decisions.

A profile or policy mismatch is `Unavailable`, not a layer-specific fallback.
Static UI localization is a separate content domain. It shares the shaping/font
foundation but never invokes sourced-English selection or map-label policy.

## 10. Conformance and regeneration

`sourced-text-conformance-v1.json` is the one decision table for ports. It
binds the source/profile/policy domains, scalar boundaries, source-exact
combining-mark order, composed/decomposed non-equivalence, end-trim
equivalence, Latin and non-Latin scripts, bidi text, punctuation/digits, emoji,
Unknown/private-use scalars, invalid inputs, exact equality, field-ID
mutations, expected gaps, canonical hashes, full identities, and hot IDs. The
Python tests and later Kotlin tests must consume that file through their public
policy APIs. It asserts exactly one policy identity, presentation token, and
decision path. A port must not copy the cases into a second table.

The profile is regenerated only from the exact frozen `Scripts.txt` bytes:

```powershell
py -3.11 -m tools.experiment8.sourced_text `
  C:\FlightAlert-exp8-work\unicode\Scripts-17.0.0.txt `
  tools\experiment8\data\unicode-script-profile-17.0.0.json
```

Generation fails on the wrong raw length/hash, wrong Unicode version,
malformed or overlapping source records, and non-scalar/surrogate source
ranges. Public profile loading requires the exact tracked 41,325-byte profile
and its SHA-256 before JSON parsing. It reads at most the bound through one
stable open handle, verifies that the handle and path were not replaced, and
translates memory failure into a contract error. The shared generated-profile
validator still rejects noncanonical JSON, duplicate keys, wrong metadata,
descending, unsorted, overlapping, gapped, surrogate, or non-scalar intervals;
it is not a second accepted profile methodology.
