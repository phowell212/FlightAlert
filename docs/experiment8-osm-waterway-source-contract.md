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

Timestamp evidence has three separate meanings and must never be relabeled:

- provider HTTP `Date`: `2026-07-11T00:55:30Z`, parsed from the exact retained
  HEAD response;
- PBF header timestamp: `2026-07-10T20:21:01Z`, parsed from the exact retained
  `osmium fileinfo -e -c` transcript; and
- local download UTC: unavailable, because no contemporaneous capture was
  retained. Filesystem creation/write times and either of the two timestamps
  above are not substitutes for local acquisition evidence.

The clean-start selector result is locked to 7,944 way roots and 102 relation
roots. Canonical `root-ids.txt` is 88,831 bytes at SHA-256
`3de3a9d901ddb76f8a594dcd1d37dc8da12c11e00196f5e4b830fa184b3275e7`;
canonical `selection-material.json` is 9,135,827 bytes at SHA-256
`d49e184605d9123d75970408d1a675288df681f8ed2d0b37e3c3d74bf0afd940`.
An independent broad extraction must also be supplied and rehashed: broad XML
SHA-256 `f47eaeb4140d18674b850baab9820cf72f7f7d15c2272deb5511ea10aac91473`,
broad roots SHA-256 `3de3a9d901ddb76f8a594dcd1d37dc8da12c11e00196f5e4b830fa184b3275e7`,
broad material SHA-256
`fb9c046a6c65a9fd342a704117ae5c32d6b360b2cd1b272f31c8d68b34e87f74`,
and independent report SHA-256
`18d43ab72de95e9f0dc22cf1bcdba60b7396045342fa7875171918789ffbfe95`.
The report, roots, material, counts, and broad-input hash must reconcile before
the evidence can be assembled.

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

The selector runtime manifest additionally binds the exact CPython executable,
`python311.dll`, `python3.dll`, VC runtime DLLs, the native crypto library used
by `_hashlib`, and the loaded JSON, regex, decimal, XML, email-date, and parser
stdlib/extension files. It also records the CPython cache tag and deterministic
interpreter flags. Manifest paths are portable logical names; host checkout,
Python-install, and drive paths are forbidden. Selector code fingerprints use
the canonical logical filename `tools/experiment8/osm_hydro_source.py`, so
identical source has identical code identity across checkouts and drives.

## Provenance bundle persistence

Bundle publication uses a new staging directory and a no-replace rename. The
writer re-reads the exact staged file set and every byte before and after live
input replay, then re-reads the installed directory. Extra, missing, symlink,
reparse-point, non-regular, identity-drifting, or byte-drifting entries are
fatal and an owned incomplete target is removed. The public persisted-bundle
readback rehashes every file from disk, validates canonical `bundle-root.json`,
reconciles the source/runtime/selection cross-links, and returns hashes from
the installed bytes rather than writer memory.

## Exact coordinate contract

OSM coordinates are canonical signed decimal longitude/latitude values with no
leading plus, whitespace, exponent, separators, leading integer zeroes, or more
than seven decimal places. Parse them as base-10 integers, never through a
binary float:

```text
e7 = exact_decimal * 10,000,000
```

The product must be integral. Longitude is in
`[-1,800,000,000, +1,800,000,000]`; latitude is in
`[-900,000,000, +900,000,000]`. Negative zero canonicalizes to zero. The
source occurrence preserves every signed E7 coordinate and node ID in source
order.

OSM XML parsing is bounded before object construction. DTD/entity declarations
are forbidden; input bytes, objects, references, tags, and individual UTF-8 tag
keys/values have explicit ceilings. All object IDs and references are positive
signed-63 integers. The locked Maryland candidate XML additionally requires its
exact byte count and SHA-256 before parsing; generic fixture limits cannot mint
Maryland provenance.

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
2. A project-owned scanner applies the exact name predicate to the exact
   candidate XML bytes and returns two in-memory byte strings: deterministic
   sorted root IDs and canonical generic selection material.
3. Run `osmium getid -r` against the original locked PBF using only those root
   IDs.
4. Run `osmium check-refs` and independently rescan the original, roots, and
   closure.

Before step 1 can be trusted, an independent envelope extraction MUST inspect
every way carrying any `waterway` value and every relation with
`type=waterway`. The exact broad filter is `w/waterway` plus
`r/type=waterway`; it deliberately includes unnamed, unsupported, closed, and
lifecycle objects. A verifier that imports neither the production selector nor
its parser independently reapplies the complete policy and requires exact
root-ID and canonical selection-material equality. This prevents a too-narrow
upstream `tags-filter` command from silently hiding a class that the scanner
would otherwise have admitted.

The independent verifier must not import the production selector. It reconciles
every root object/type/ID/version and every recursively referenced object. Any
addition, omission, duplicate, changed version, invalid object, or unexplained
missing reference is fatal. The only bounded missing-reference treatment is the
locked Maryland regional-pilot profile below; it is not a whole-world rule.

The pure scanner is deliberately unable to claim Maryland, a source PBF,
runtime, command, or provenance. It accepts immutable candidate XML bytes and
performs no final writes. The verified provenance wrapper invokes that exact
imported callable, treats both returned values as untrusted, and independently
requires canonical/sorted root IDs, canonical material JSON, exact
ID/count/root-entry reconciliation, and the live policy hash. A separate binding
document hashes and lengths both materials and binds them to the exact candidate
XML, selector code, source identity, runtime, and selection commands. Only the
transactional wrapper may publish the final rooted evidence directory. A tiny
fixture may produce generic material but cannot mint Maryland evidence.

### Measured Maryland broad-envelope reconciliation

The real broad extraction and independent reconciliation completed with these
exact immutable inputs and outputs:

| Evidence | Bytes | SHA-256 |
| --- | ---: | --- |
| Maryland source PBF | `212933228` | `7a9c9baf554aa424f27d80e7aa20ccc7d2d412815613afe93b6af06ba703f99f` |
| broad waterway XML | `43320020` | `f47eaeb4140d18674b850baab9820cf72f7f7d15c2272deb5511ea10aac91473` |
| selected root IDs | `88831` | `3de3a9d901ddb76f8a594dcd1d37dc8da12c11e00196f5e4b830fa184b3275e7` |
| broad selection material | `9638521` | `fb9c046a6c65a9fd342a704117ae5c32d6b360b2cd1b272f31c8d68b34e87f74` |
| independent report | `105764` | `18d43ab72de95e9f0dc22cf1bcdba60b7396045342fa7875171918789ffbfe95` |

The broad input contains 55,984 candidate ways and 118 candidate relations.
The policy selects exactly 7,944 ways and 102 relations. Rejections are: five
closed ways, 75 lifecycle ways, 37,730 unnamed ways, 10,230 unsupported
waterway ways, and 16 unnamed relations. The independently derived root-ID
bytes exactly equal the production root-ID bytes. Relation `12152277` is
present with version 2, timestamp `2024-11-20T18:48:42Z`, eight members, and
the exact owned field `name=Chester River`.

This closes hidden selection-envelope omission for the exact Maryland source
and policy. It does not by itself prove recursive closure, admission, package
emission, world coverage, or phone rendering; those remain separate gates.

## Selection, closure audit, and admission profiles

Selection, closure, and admission are three separately hashed facts:

1. The source-bound selection manifest binds the verified generic selection
   material and contains every root that passes the tag policy.
2. The closure audit assigns every selected root exactly one status:
   `complete`, `source_incomplete`, or `invalid`.
3. The admitted-root manifest contains only roots independently proven complete
   and binds both prior manifests plus the source/runtime/command identities.

`source_incomplete` is never empty, outside coverage, unselected, or ready. It
is `Unavailable(source_incomplete)` and cannot emit a package or renderer
record. `invalid` means malformed/cyclic/contract-invalid source geometry and
is fatal; it is not a regional-clipping exception.

The sole regional profile is
`flight-alert-exp8-geofabrik-maryland-260710-regional-pilot-v1`, bound to the
exact dated Maryland source identity. It may assign `source_incomplete` only to
a selected relation root when all of these are proven:

- every missing object is an explicit member of an exact selected relation,
  with member ordinal and role retained;
- no selected root object is absent and no selected direct way has a missing
  node;
- the global missing set equals the union of independently probed incomplete
  relation-root missing sets;
- dependency propagation marks every selected relation that depends on an
  incomplete selected relation;
- one combined recursive probe over all predicted-complete relations has zero
  missing references; and
- the final admitted way-plus-relation closure exits zero and independently
  passes `check-refs` with zero missing nodes, ways, relations, and changesets.

Unknown, mismatched, or downgraded profiles fail closed. A complete-world or
planet profile has no quarantine path: `selected == complete == admitted`, and
one missing object aborts the entire build before an admitted manifest or
package can be written.

## Root/reference separation

Selected roots and reference-only objects are different sets in every manifest
and semantic stream. Nodes, ways, or relations included only by `getid -r`
cannot produce text or a renderer candidate unless they independently pass the
root predicate.

For every selected way, all node refs must exist. For every selected relation,
all explicit node/way/relation members must exist recursively. A reference may
also be an independently selected root; in that case it remains a root and is
not counted as reference-only. The Maryland profile retains every incomplete
selected relation in the closure audit while excluding that occurrence from the
admitted manifest; independently selected complete member ways remain roots.

## Relation geometry

Keep every relation member's zero-based ordinal, type, ref, and role exactly. A
way contributes its ordered node-reference sequence. Adjacent way parts join
only when the previous part's last node ID equals the next part's first node ID.
Do not reverse a way, sort members, or join by equal/near coordinates.
Disconnected members remain separate parts. Exact node members such as `source`
and `mouth` must exist in the verified closure and remain in source/audit
identity, but they contribute no coordinates to line assembly and cannot receive
line text. A relation with no assembled way parts emits no line candidate and is
not reclassified semantic empty. Missing objects, fewer-than-two-node member
ways, unsupported member types, or recursive relation cycles fail closed.

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
- a selected regional relation with proven clipped closure is
  `Unavailable(source_incomplete)` and cannot be represented as semantic empty;
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

The canonical source inspection v2 is 19,119 bytes with SHA-256
`eb997eb532b2e0fc3b8dc81bf32954230769586d988c955871d67f90b62a2ed4`.
It adds each exact relation-member ordinal to the earlier source facts.
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
- Accepted broad-selection mirror on C:
  `C:\FlightAlert-exp8-work\evidence\osm-hydro-pilot\broad-selection-v1`
- Byte-identical durable broad-selection mirror on D:
  `D:\FlightAlert-test-artifacts\experiment 8\evidence\osm-hydro-pilot\broad-selection-v1`

Each broad-selection mirror contains exactly four files and `53,153,136`
logical bytes. Every individual byte count and SHA-256 is frozen in the table
above; publication occurred only after post-copy verification on each volume.

Storage preflight evidence records each volume's independently observed
identity/media class in addition to exact total/used/free/minimum bytes.

All authoritative reports are canonical UTF-8 JSON with sorted keys, compact
separators, one final LF, no run-clock timestamps in deterministic content, and
no host absolute paths. Exact OSM object timestamps and replication timestamps
are deterministic source facts and remain in source/audit identities. C and D
evidence hashes must agree before a gate passes.
