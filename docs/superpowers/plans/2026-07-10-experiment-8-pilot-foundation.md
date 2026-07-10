# Experiment 8 Pilot Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce the verified Experiment 8 source lock, complete source-size catalog, deterministic Stage A/Stage B sample manifests, and resumable hash-verified Stage A PBF cache required by both competing package formats.

**Architecture:** Small Python modules under `tools/experiment8/` own immutable models, source verification, Experiment 6 source-size recovery, deterministic stratified sampling, and bounded HTTPS acquisition. Generated data and dependencies live under `D:\FlightAlert-test-artifacts\experiment 8`; the Android app and current dictionary renderer are untouched by this plan.

**Tech Stack:** Python 3.10.11 standard library, `mapbox-vector-tile==2.2.0`, `zstandard==0.25.0`, `unittest`, Esri PBF over HTTPS

## Global Constraints

- Use the C-drive working tree as the authoritative repository.
- Preserve the user's existing Experiment 7 working changes.
- Keep generated packages, PBFs, logs, reports, and Python dependencies outside the Git repository.
- Lock the population to the SHA-256 values and exact z0-z16 counts in the approved Experiment 8 pilot design.
- Never turn a missing, failed, malformed, or mismatched source response into a known-empty tile.
- Never launch a full-world PBF fetch or package bake from this plan.
- Use bounded concurrency and atomic temporary-file replacement.
- Do not add app-runtime debug or measurement code.
- All selection and output ordering must be deterministic across worker counts.

## Plan Series

This is Plan 1 of the Experiment 8 package pilot. Plan 2 implements the common semantic normalizer and Format A/B codecs. Plan 3 executes the package comparison, projections, and winner decision. Completing this plan does not complete Experiment 8.

---

### Task 1: Immutable source and tile models

**Files:**
- Create: `tools/experiment8/__init__.py`
- Create: `tools/experiment8/model.py`
- Create: `tools/experiment8/source_lock.py`
- Create: `tools/experiment8/verify_source.py`
- Create: `tools/experiment8/tests/__init__.py`
- Create: `tools/experiment8/tests/test_source_lock.py`

**Interfaces:**
- Produces: `TileKey`, `SourceLock`, `PopulationSummary`, `sha256_file()`, `verify_source_lock()`, and the `verify_source` CLI.
- Consumes: no earlier task interfaces.

- [x] **Step 1: Write failing source-lock and tile-model tests**

Create tests that assert:

```python
class TileKeyTests(unittest.TestCase):
    def test_pack_round_trip(self):
        key = TileKey(16, 63809, 42195)
        self.assertEqual(TileKey.from_packed(key.packed), key)

    def test_rejects_coordinate_outside_zoom(self):
        with self.assertRaises(ValueError):
            TileKey(5, 32, 0)

    def test_center_lon_lat_is_finite(self):
        lon, lat = TileKey(0, 0, 0).center_lon_lat()
        self.assertAlmostEqual(lon, 0.0)
        self.assertAlmostEqual(lat, 0.0)

class SourceLockTests(unittest.TestCase):
    def test_verifies_expected_hashes_and_population_counts(self):
        # Build tiny metadata/style/population fixtures in TemporaryDirectory.
        # Pass their computed hashes and {0: 1, 1: 4} to verify_source_lock().
        # Assert the returned lock contains five unique tiles and both hashes.

    def test_rejects_duplicate_population_coordinate(self):
        # Repeat one TSV coordinate and assert SourceLockError.

    def test_rejects_hash_mismatch(self):
        # Supply a deliberately wrong style hash and assert SourceLockError.
```

- [x] **Step 2: Run the tests and verify RED**

Run:

```powershell
python -m unittest tools.experiment8.tests.test_source_lock -v
```

Expected: import failure because `tools.experiment8.model` and `source_lock` do not exist.

- [x] **Step 3: Implement immutable models and source verification**

Implement these public shapes:

```python
@dataclass(frozen=True, order=True, slots=True)
class TileKey:
    z: int
    x: int
    y: int

    def __post_init__(self) -> None:
        if not 0 <= self.z <= 29:
            raise ValueError(f"zoom out of range: {self.z}")
        limit = 1 << self.z
        if not 0 <= self.x < limit or not 0 <= self.y < limit:
            raise ValueError(f"tile out of range: {self.z}/{self.x}/{self.y}")

    @property
    def packed(self) -> int:
        return (self.z << 58) | (self.x << 29) | self.y

    @classmethod
    def from_packed(cls, value: int) -> "TileKey":
        if not 0 <= value < (1 << 63):
            raise ValueError(f"packed tile out of range: {value}")
        mask = (1 << 29) - 1
        return cls(z=(value >> 58) & 0x1F, x=(value >> 29) & mask, y=value & mask)

    def center_lon_lat(self) -> tuple[float, float]:
        scale = 1 << self.z
        lon = ((self.x + 0.5) / scale) * 360.0 - 180.0
        mercator_y = math.pi * (1.0 - 2.0 * (self.y + 0.5) / scale)
        lat = math.degrees(math.atan(math.sinh(mercator_y)))
        return lon, lat

@dataclass(frozen=True, slots=True)
class PopulationSummary:
    row_count: int
    counts_by_zoom: Mapping[int, int]
    sha256: str

@dataclass(frozen=True, slots=True)
class SourceLock:
    source_name: str
    service_url: str
    source_lock_path: Path
    source_lock_sha256: str
    style_path: Path
    metadata_path: Path
    style_sha256: str
    metadata_sha256: str
    population_path: Path
    population: PopulationSummary
```

`verify_source_lock()` must hash the raw source descriptor before parsing it, require the pinned schema-v1 descriptor hash, require an absolute credential-free HTTPS service URL, cross-check the descriptor's style and metadata identities, stream the TSV, require the exact header `serviceId serviceName z x y`, validate each tile, detect duplicate packed keys, calculate the population SHA-256, verify exact per-zoom counts, and return `SourceLock`. The exact population hash binds every `serviceId` and `serviceName` byte. Errors use a dedicated `SourceLockError` and include the violated invariant.

`verify_source.py` accepts `--lock-dir`, `--population`, `--expected-lock-sha256`, the expected style, metadata, and population SHA-256 values, exactly one of `--expected-counts-json` or `--expected-counts-file`, and `--out`. The counts-file form accepts BOM or BOM-free UTF-8 and is the recommended PowerShell interface. It writes the verified lock descriptor atomically as JSON and exits nonzero on any mismatch.

- [x] **Step 4: Run Task 1 tests**

Run:

```powershell
python -m unittest tools.experiment8.tests.test_source_lock -v
```

Expected: all Task 1 tests pass.

- [x] **Step 5: Commit Task 1**

```powershell
git add -- tools/experiment8/__init__.py tools/experiment8/model.py tools/experiment8/source_lock.py tools/experiment8/verify_source.py tools/experiment8/tests/__init__.py tools/experiment8/tests/test_source_lock.py
git diff --cached --check
git commit -m "Add Experiment 8 source lock models"
```

### Task 2: Recover the complete source-size catalog

**Files:**
- Create: `tools/experiment8/source_sizes.py`
- Create: `tools/experiment8/tests/test_source_sizes.py`

**Interfaces:**
- Consumes: `TileKey` from Task 1.
- Produces: `SourceSizeRecord`, `build_source_size_catalog()`, deterministic `source-sizes.tsv`, and `source-sizes-summary.json`.

- [x] **Step 1: Write failing source-size catalog tests**

Tests create temporary Experiment 6-style shard directories containing `package/tile-index.tsv` and assert:

```python
def test_merges_identical_duplicate_rows_from_two_roots():
    # Same tile/sourceSha256/sourceBytes in D-like and E-like roots.
    # Assert one sorted output row and duplicateCopies == 1.

def test_rejects_conflicting_duplicate_source_hash():
    # Same z/x/y but different sourceSha256 must raise SourceSizeError.

def test_reports_population_coordinates_missing_from_catalog():
    # Population has three tiles, catalogs have two; assert missing count/key.

def test_tail_order_is_source_bytes_then_packed_key():
    # Assert stable descending size and ascending packed-key tie break.
```

- [x] **Step 2: Run tests and verify RED**

```powershell
python -m unittest tools.experiment8.tests.test_source_sizes -v
```

Expected: import failure for `source_sizes`.

- [x] **Step 3: Implement catalog recovery**

Implement `SourceSizeRecord` exactly as shown. Expose
`build_source_size_catalog(population_path, shard_roots, output_dir) -> SourceSizeSummary`.

```python
@dataclass(frozen=True, slots=True)
class SourceSizeRecord:
    tile: TileKey
    source_sha256: str
    source_bytes: int
    decoded_bytes: int
    feature_count: int
```

The function recursively reads only `package/tile-index.tsv`, validates the exact required columns, merges D/E copies, rejects conflicts, writes a packed-key-sorted TSV atomically, and writes a JSON summary containing input roots, row count, missing population count/list, duplicate-copy count, hashes, and per-zoom counts. It must never read FAR6 payload bytes. Return the summary object only after both output files have been flushed and atomically replaced.

- [x] **Step 4: Run Task 2 and cumulative tests**

```powershell
python -m unittest tools.experiment8.tests.test_source_sizes tools.experiment8.tests.test_source_lock -v
```

Expected: all tests pass.

- [x] **Step 5: Commit Task 2**

```powershell
git add -- tools/experiment8/source_sizes.py tools/experiment8/tests/test_source_sizes.py
git diff --cached --check
git commit -m "Recover Experiment 8 source size catalog"
```

### Task 3: Deterministic stratified sample manifests

**Files:**
- Create: `tools/experiment8/sample.py`
- Create: `tools/experiment8/make_sample.py`
- Create: `tools/experiment8/tests/test_sample.py`

**Interfaces:**
- Consumes: `TileKey`, verified source-lock descriptor, verified population TSV, packed-key source-size TSV, and its hash-bound summary.
- Produces: `build_sample_manifest()`, Stage A/Stage B `sample.jsonl`, `summary.json`, and `fixtures.jsonl`.

- [ ] **Step 1: Write failing sampler tests**

Tests assert:

```python
def test_equal_area_band_boundaries_are_stable():
    self.assertEqual(latitude_band(-90.0), 0)
    self.assertEqual(latitude_band(0.0), 3)
    self.assertEqual(latitude_band(89.0), 5)

def test_low_zooms_are_census_units():
    # With census_max_z=1, every z0/z1 row is emitted as selection="census".

def test_random_selection_uses_lowest_seeded_hashes():
    # Compute ranks independently and compare the selected two tiles.

def test_certainty_tails_are_disjoint_from_random_sample():
    # The largest source-byte tile is "tail" and random sampling fills from remainder.

def test_uncatalogued_tiles_are_certainty_units():
    # A population tile absent from source-sizes.tsv must be selection="uncatalogued".

def test_same_inputs_produce_byte_identical_manifests():
    # Build twice with different input row order and compare SHA-256.

def test_stage_a_keys_are_subset_of_stage_b_even_when_random_becomes_tail():
    # Compare keys, not selection labels.

def test_fixture_manifest_preserves_present_and_source_proven_empty_rows():
    # Every requested coordinate is retained with present/known_empty state.
```

- [ ] **Step 2: Run tests and verify RED**

```powershell
python -m unittest tools.experiment8.tests.test_sample -v
```

Expected: import failure for `sample`.

- [ ] **Step 3: Implement the sampler and CLI**

Use exact latitude edges `[-90, -41.810315, -19.471221, 0, 19.471221, 41.810315, 90]`, longitude edges every 45 degrees, and SHA-256 rank text `flight-alert-exp8-pilot-v1|z|x|y`. Intervals are half-open with the final upper endpoint inclusive; internal boundaries belong north/east. Compare raw rank digest bytes, then packed keys.

Implement a memory-bounded external merge:

1. verify the source-lock, population, catalog-summary, and catalog hashes;
2. external-sort population packed keys in bounded binary runs;
3. merge them with the already packed-sorted source-size catalog, rejecting catalog extras and writing a fixed-width classified spool with a missing-size sentinel;
4. compute per-zoom top tails, then scan the spool for stratum/certainty counts and bounded lowest-rank heaps;
5. scan again and write canonical packed-key-sorted JSONL and summaries atomically.

CLI:

```powershell
python -m tools.experiment8.make_sample `
  --verified-source-lock <verified-source-lock.json> `
  --population <present-vector-tiles.tsv> `
  --source-sizes <source-sizes.tsv> `
  --source-size-summary <source-sizes-summary.json> `
  --stage a|b `
  --out <directory>
```

Stage A uses census z0-z8, 32 random units per nonempty z9-z16 cell, and 32 tails per z9-z16. Stage B uses the same ordering with 256 random and 256 tail units. Selection precedence is `census`, `uncatalogued`, `tail`, then `random`, and random selection refills from the remainder. Summary JSON records all input/output hashes, stratum population/certainty/random counts, exact count reconciliation, selection counts, exact parameters, and missing-size certainty units.

Create a fixed non-projection fixture manifest containing the tile at zooms 5, 8, 11, 13, and 16 for these exact named `(latitude, longitude)` points, then deduplicate coordinates while preserving every sorted fixture name. Classify each row `sourceState="present"` when its key is in the pinned population or `sourceState="known_empty"` when the pinned population proves absence; do not silently drop absent rows:

```python
FIXTURE_POINTS = (
    ("new-york", 40.7128, -74.0060),
    ("london", 51.5074, -0.1278),
    ("sao-paulo", -23.5505, -46.6333),
    ("cape-town", -33.9249, 18.4241),
    ("cairo", 30.0444, 31.2357),
    ("mumbai", 19.0760, 72.8777),
    ("tokyo", 35.6762, 139.6503),
    ("sydney", -33.8688, 151.2093),
    ("yellowstone", 44.4280, -110.5885),
    ("amazon", -3.4653, -62.2159),
    ("greenland", 72.0, -40.0),
    ("fiji", -17.7134, 178.0650),
    ("aleutian-antimeridian", 52.0, 179.5),
    ("us-canada-boundary", 49.0, -123.0),
    ("india-pakistan-boundary", 32.5, 74.5),
    ("west-bank", 31.8, 35.2),
    ("western-sahara", 24.0, -13.0),
    ("great-barrier-reef", -18.2871, 147.6992),
)
```

- [ ] **Step 4: Run Task 3 and cumulative tests**

```powershell
python -m unittest discover -s tools/experiment8/tests -v
```

Expected: all tests pass.

- [ ] **Step 5: Commit Task 3**

```powershell
git add -- tools/experiment8/sample.py tools/experiment8/make_sample.py tools/experiment8/tests/test_sample.py
git diff --cached --check
git commit -m "Add deterministic Experiment 8 pilot sampling"
```

### Task 4: Resumable source acquisition cache

**Files:**
- Create: `tools/experiment8/acquire.py`
- Create: `tools/experiment8/fetch_sample.py`
- Create: `tools/experiment8/tests/test_acquire.py`

**Interfaces:**
- Consumes: verified `SourceLock` and sample JSONL.
- Produces: `PbfCache`, immutable per-tile PBF/metadata files, `acquisition.jsonl`, and `acquisition-summary.json`.

- [ ] **Step 1: Write failing acquisition tests**

Use `mapbox_vector_tile.encode` to create a minimal real vector tile in memory, serve it from a temporary local `ThreadingHTTPServer`, and assert:

Write these six concrete tests, each with a complete arrange/act/assert body:

- `test_fetches_gzip_decodes_valid_pbf_and_commits_atomically` checks that the decoded PBF and sidecar exist, no temporary file remains, and the decoded tile has the expected feature.
- `test_resume_reuses_only_hash_verified_cache_entry` stops the server after the first request and proves the second request succeeds from the verified cache without network access.
- `test_corrupt_cached_payload_is_quarantined_and_refetched` mutates one cached byte, restarts the server, and proves a fresh valid response replaces it while the corrupt artifact is retained under the quarantine directory.
- `test_http_error_remains_failed_not_known_empty` serves HTTP 404 and asserts a failed result with no PBF file and no known-empty marker.
- `test_response_coordinate_and_source_generation_are_recorded` asserts the exact z/x/y, source-lock hashes, URL, response hash, decoded PBF hash, byte lengths, and acquisition timestamp fields.
- `test_worker_count_does_not_change_sorted_acquisition_manifest` acquires the same shuffled input with worker counts 1 and 4 and asserts byte-identical JSONL after normalizing only the intentionally variable acquisition timestamps.

- [ ] **Step 2: Run tests and verify RED**

```powershell
$env:PYTHONPATH='D:\FlightAlert-test-artifacts\experiment 8\python-packages'
python -m unittest tools.experiment8.tests.test_acquire -v
```

Expected: import failure for `acquire`.

- [ ] **Step 3: Implement bounded HTTPS acquisition**

Implement:

```python
@dataclass(frozen=True, slots=True)
class AcquisitionResult:
    tile: TileKey
    status: Literal["ready", "failed"]
    response_sha256: str | None
    pbf_sha256: str | None
    response_bytes: int
    pbf_bytes: int
    attempts: int
    error: str | None
```

`PbfCache.acquire(tile: TileKey) -> AcquisitionResult` owns one tile's verified cache lifecycle. `acquire_manifest(sample_path, cache, workers, output_dir) -> AcquisitionSummary` schedules those calls and writes the deterministic manifest and summary.

Use `urllib.request` with HTTPS only for production URLs, explicit timeout, gzip handling, `mapbox_vector_tile.decode` validation, SHA-256, `.tmp` files, `os.replace`, and sidecar JSON. Permit loopback HTTP only when a constructor flag explicitly enables it for tests. Default workers are 8, maximum 16. Retry only timeout/429/5xx with capped exponential backoff and `Retry-After`; 4xx other than 429 fail immediately. Output order is packed-key sorted. A nonzero failed count makes the CLI exit nonzero.

- [ ] **Step 4: Run Task 4 and cumulative tests**

```powershell
$env:PYTHONPATH='D:\FlightAlert-test-artifacts\experiment 8\python-packages'
python -m unittest discover -s tools/experiment8/tests -v
```

Expected: all tests pass.

- [ ] **Step 5: Commit Task 4**

```powershell
git add -- tools/experiment8/acquire.py tools/experiment8/fetch_sample.py tools/experiment8/tests/test_acquire.py
git diff --cached --check
git commit -m "Add resumable Experiment 8 PBF acquisition"
```

### Task 5: Generate and verify the real pilot foundation

**Files:**
- Generated outside Git: `D:\FlightAlert-test-artifacts\experiment 8\**`
- Modify tracked files: none

**Interfaces:**
- Consumes: all Task 1-4 CLIs.
- Produces: real source lock evidence, complete size catalog, Stage A/Stage B manifests, fixture cache, smoke cache, and resumable Stage A acquisition state.

- [x] **Step 1: Install pinned external dependencies**

```powershell
python -m pip install --target 'D:\FlightAlert-test-artifacts\experiment 8\python-packages' mapbox-vector-tile==2.2.0 zstandard==0.25.0
```

Expected: packages install outside the repository and imports report versions `2.2.0` and `0.25.0`.

- [x] **Step 2: Verify the real source lock and population**

Run the source-lock CLI against the authoritative Experiment 6 source lock and population using the exact descriptor, style, metadata, population hashes, and per-zoom counts from the design. Write evidence under `D:\FlightAlert-test-artifacts\experiment 8\source-lock`.

Expected: `2,802,117` unique rows and exact z0-z16 counts; SHA-256 checks pass.

- [x] **Step 3: Recover the real source-size catalog**

Run the catalog builder with both:

```text
D:\FlightAlert-test-artifacts\experiment 6\phase1\world-basemap-v2-classified-bake-shards
E:\FlightAlert-test-artifacts\experiment 6\phase1\world-basemap-v2-classified-bake-shards
```

Expected: duplicate copies agree; the summary accounts for every population tile either with a source-size record or an explicit uncatalogued certainty key.

- [ ] **Step 4: Generate Stage A, Stage B, and fixture manifests**

Run `make_sample` twice with `--stage a` and `--stage b`.

Expected, using generated exact counts rather than treating conservative bounds as identities:

- Stage A count does not exceed `35,385`;
- Stage B count does not exceed `123,193` and contains every Stage A tile;
- all z0-z8 tiles are census units;
- all hashes and exact stratum counts are present;
- repeated generation is byte-identical.

- [ ] **Step 5: Run fixture and 64-tile smoke acquisition**

Validate every fixture state against the pinned population. Acquire every `present` fixture plus the first 64 packed-key Stage A rows to a new cache generation; do not fetch rows proven `known_empty`.

Expected: all known-empty fixtures remain explicit and unrequested; zero present-fixture/sample failures; every acquired PBF decodes; all sidecars and hashes pass an immediate resume run; and no repository file is generated.

- [ ] **Step 6: Start/resume full Stage A acquisition**

Run the bounded acquisition CLI for the complete Stage A manifest. It may resume across goal turns.

Expected completion gate: every Stage A row is `ready`, the sorted acquisition manifest matches the sample exactly, no unresolved source error remains, and total cached bytes/hash inventory are documented. Until this gate passes, Plan 2 may be implemented against the smoke corpus but no Stage A package projection may be claimed.

- [ ] **Step 7: Verify repository preservation**

```powershell
git status --short --branch
python -m unittest discover -s tools/experiment8/tests -v
```

Expected: only intentional Experiment 8 source commits plus the user's pre-existing Experiment 7 working changes; all tests pass and generated artifacts remain external.
