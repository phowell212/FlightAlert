# Experiment 8 OSM Named-Waterway Source Contract

## Status and scope

This is the language-neutral source contract for the bounded Experiment 8
OpenStreetMap named-waterway pilot. It permits the exact Chester fixture and one
immutable Maryland PBF. It does not permit another region, a planet download, a
world bake, Android integration, phone changes, or a release/redistribution
claim.

The OSM lane exists to supply names on exact OSM waterway geometry when the
locked Esri source does not own a named path. It never transfers a name to Esri
geometry and never changes Esri coverage, empty-state, provenance, or IDs.

## Frozen source identities

### Chester source fixture

- URL: `https://api.openstreetmap.org/api/0.6/relation/12152277/full`
- Bytes: `140387`
- SHA-256:
  `beea8b394d26fa86e3c372b678420a5fb84af801be7378a681f2f2976f35e99d`
- OSM API version: `0.6`
- Relation ID/version: `12152277` / `2`
- Relation timestamp: `2024-11-20T18:48:42Z`
- Required tags: `type=waterway`, `name=Chester River`,
  `wikidata=Q5093696`
- Required member/source inventory: eight named `waterway=river` ways and 705
  referenced nodes

This API response is a fixture. The Maryland PBF must independently reprove its
object versions, membership, tags, and coordinates before it can become the
pilot source authority.

### Maryland pilot source

- URL:
  `https://download.geofabrik.de/north-america/us/maryland-260710.osm.pbf`
- Required bytes: `212933228`
- Provider MD5: `2642fa017680941a2fab4f96c23d9c03`
- Provider checksum URL:
  `https://download.geofabrik.de/north-america/us/maryland-260710.osm.pbf.md5`

The downloaded source must also receive an independently computed SHA-256 and a
captured PBF header/source timestamp. The dynamic `maryland-latest.osm.pbf`
alias is forbidden. A byte, MD5, SHA-256, URL, header, or timestamp mismatch is
fatal and the partial/result is quarantined.

## Frozen pilot runtime

The isolated Linux runtime is extracted without a system install:

- `osmium-tool_1.11.1-1build2_amd64.deb` SHA-256
  `d8e791ac3558aaafa95d3f6ac7329b15df2fb502bd6babff881e62830e49f906`
- `libboost-program-options1.71.0_1.71.0-6ubuntu6_amd64.deb` SHA-256
  `389095c7167251ee73667031a4c0f45083a31347cc95faddbdf5b7d22ac4c774`
- extracted `usr/bin/osmium` SHA-256
  `5575922905fb39fa87262e74ed2b0ac367086b5439468339e45bf3720c1821fc`
- extracted `libboost_program_options.so.1.71.0` SHA-256
  `16a89b0d75de54bfef18b479eb1d38710e5c242246a17baffa11eb4f2d544663`
- reported versions: `osmium 1.11.1`, `libosmium 2.15.4`
- locale: `C.UTF-8`

The runtime manifest must additionally record WSL/kernel identity, the exact
system-library versions/hashes resolved by `ldd`, and every command argument.
Changing any runtime component creates a new runtime generation and requires a
determinism reproof.

## Exact coordinate contract

OSM coordinates are signed decimal longitude/latitude values with no more than
seven nonzero decimal places. Parse them as base-10 integers, never through a
binary float:

```text
e7 = exact_decimal * 10,000,000
```

The product must be integral. Longitude is in
`[-1,800,000,000, +1,800,000,000]`; latitude is in
`[-900,000,000, +900,000,000]`. Negative zero canonicalizes to zero. The
source occurrence preserves every signed E7 coordinate and node ID in source
order.

The renderer coordinate mode is `OSM_WGS84_E7`. Web-Mercator conversion is
presentation work; it cannot alter source, geometry, variant, or label-candidate
identity. A later renderer-contract extension must freeze latitude limits,
antimeridian unwrap, overflow behavior, device projection, and conservative
retrieval bounds before any OSM codec package is accepted.

## Root selection policy v1

Canonical policy schema:
`flight-alert-exp8-osm-waterway-policy-v1`.

Canonical policy bytes SHA-256:
`7a2accdefd1ca9fb0604d83b97010e760e327cd02971c969180dc2ccea2bbac2`.

### Direct way roots

A way is a direct label root only when both conditions hold:

1. `waterway` is exactly one of `river`, `stream`, `canal`,
   `tidal_channel`, or `wadi`.
2. The same way owns at least one supported, nonblank exact display-name field.

It must also contain at least two distinct endpoint node refs, must not be
closed, and must not have affirmative `area`, `abandoned`, `construction`,
`demolished`, `disused`, `proposed`, or `razed` state. Lifecycle-prefixed
`<state>:waterway` keys are rejected even when a current `waterway` tag also
exists. Values `no`, `false`, `0`, and blank are non-affirmative.

The initial explicitly excluded values are `dam`, `weir`, `lock_gate`, `dock`,
`riverbank`, `ditch`, and `drain`. All unknown or lifecycle-only values are
audited and excluded until a new policy version admits them.

### Relation roots

A relation is a label root only when `type=waterway` and that same relation owns
at least one supported, nonblank display-name field. It need not have a
`waterway` tag. Its name applies only to explicitly referenced member geometry.

### Display-name fields

The exact direct fields are `name`, `int_name`, and `official_name`. Language
fields use `name:<language>` where the language subtag is two or three ASCII
letters and subsequent subtags are two through eight ASCII alphanumerics.
Semantic suffixes including `left`, `right`, `signed`, `pronunciation`,
`etymology`, `source`, and `old` are not display-language fields.

Values are preserved byte-for-byte as UTF-8 source strings. Blank strings are
not names. Do not normalize Unicode, transliterate, concatenate, shorten, or
copy a fallback from another object.

## True conjunctive selection

`osmium tags-filter` expressions are OR predicates. Therefore a single command
containing separate `waterway` and `name` expressions is forbidden.

The production process is sequential:

1. From the locked original PBF, emit candidate ways with an admitted
   `waterway` value and candidate relations with `type=waterway`, omitting
   referenced objects.
2. A project-owned scanner applies the exact name predicate to that candidate
   stream and emits a deterministic sorted root-ID/version manifest.
3. Run `osmium getid -r` against the original locked PBF using only those root
   IDs.
4. Run `osmium check-refs` and independently rescan the original, roots, and
   closure.

The independent verifier must not import the production selector. It reconciles
every root object/type/ID/version and every recursively referenced object. Any
addition, omission, duplicate, changed version, or missing reference is fatal.

## Root/reference separation

Selected roots and reference-only objects are different sets in every manifest
and semantic stream. Nodes, ways, or relations included only by `getid -r`
cannot produce text or a renderer candidate unless they independently pass the
root predicate.

For every selected way, all node refs must exist. For every selected relation,
all explicit node/way/relation members must exist recursively. A reference may
also be an independently selected root; in that case it remains a root and is
not counted as reference-only.

## Relation geometry

Keep relation member order and roles exactly. A way contributes its ordered
node-reference sequence. Adjacent way parts join only when the previous part's
last node ID equals the next part's first node ID. Do not reverse a way, sort
members, or join by equal/near coordinates. Disconnected members remain separate
parts. Missing objects, fewer-than-two-node member ways, node members in a line
assembly, or recursive relation cycles fail closed.

Direct named ways and their containing named relation remain separate source
occurrences. A later presentation policy may give the relation deterministic
placement priority, but it must retain both audit occurrences and may not merge
their IDs.

## Source and renderer states

The OSM lane has its own generation, coverage, semantic-empty proof, and state:

- a verified selected record produces `Ready(nonempty)`;
- a source-present unit with an independently proven empty selection produces
  `Ready(empty)` for the OSM lane;
- a missing/corrupt source, closure, policy, or package is `Unavailable`;
- coordinates outside the supported renderer domain are `OutsideCoverage`.

No OSM state can turn an Esri `Unavailable` into `Ready`, change an Esri
`KnownEmpty`, or provide attribution for an Esri record.

## Attribution and license boundary

The OSM selected/indexed package is treated conservatively as an ODbL derivative
database and remains logically and physically separable from Esri data. It must
contain the source snapshot, `OpenStreetMap contributors` attribution, the OSM
copyright URL, ODbL 1.0 URI/text, build-method/source-offer information, and
database notices. When displayed, the app must visibly expose OSM attribution
and must not claim the OSM-only name came from Esri.

The corresponding machine-readable OSM-derived database or reproducible
transformation method must be offered for public use. App-store, EULA, and DRM
terms cannot add restrictions to recipients' ODbL rights. Personal Experiment
8 proof is not an Esri redistribution review; `exportTilesAllowed` is not
redistribution permission.

## Locked legal snapshots

The pilot evidence mirrors these exact fetched documents on C and D:

| Document | SHA-256 |
| --- | --- |
| OSM copyright | `8b6edbb3e4e3adb8222bb8e54fccd33b0fdb5588dacf344910b72f264833131d` |
| ODbL 1.0 text | `1d553feead201a7619788171b43cca12675e82d70b197aa45795986bb8603e72` |
| OSM attribution guideline | `be058bf954203d7d1f6d4bf9be16498aaa0015cea8e92b402bf74cdbfe8b82b7` |
| OSM licence FAQ | `2d9c95a8d8cd35a80ee22036e15668ad1afe566c2eeb57517de9c223018c9917` |
| OSM horizontal-layers guideline | `e2f1ec9e1e1dbfff8e97dbdbc2fb8af4b1e5caa5b5defdd6ccaeb1d1ba8ba1b1` |

The legal text controls over this engineering summary. Public release requires
an applicable legal/redistribution review.

## Current Chester evidence

The canonical source inspection is 18,927 bytes with SHA-256
`0445785b4f9e0a91c5b9cd09401bbee4ca1bd60d8d893d9b5b5c33a70ea28e6f`.
It independently parses the fixture into eight direct way roots, one relation
root, 705 reference-only nodes, zero missing ways/relations, and one exact
705-node assembled relation part.

The western phone corridor is exact E7 bounds
`[-760819530, 390734860, -760087480, 392303250]`. Exactly these five direct
root-way bounding boxes intersect it:

- `87183165`
- `87183167`
- `87183234`
- `87183249`
- `1336213945`

This establishes a source-owned western `Chester River` path. Bounding-box
intersection is a fixture discovery fact, not final label applicability;
viewport/path intersection, display zoom, whole-run length, bend, spacing,
source-edge, collision, and phone visual gates still apply.

## Pilot evidence paths

- C working evidence:
  `C:\FlightAlert-exp8-work\evidence\osm-hydro-pilot`
- D durable mirror:
  `D:\FlightAlert-test-artifacts\experiment 8\evidence\osm-hydro-pilot`
- E fast pilot staging:
  `E:\FlightAlert-exp8-work\osm-hydro-pilot`

Storage preflight evidence records each volume's independently observed
identity/media class in addition to exact total/used/free/minimum bytes.

All authoritative reports are canonical UTF-8 JSON with sorted keys, compact
separators, one final LF, no timestamps in deterministic content, and no host
absolute paths. C and D evidence hashes must agree before a gate passes.
