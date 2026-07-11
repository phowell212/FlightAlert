from __future__ import annotations

import concurrent.futures
import datetime as dt
import gzip
import hashlib
import json
import os
import socket
import tempfile
import threading
import unittest
import urllib.error
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Iterator
from unittest import mock

from mapbox_vector_tile import decode, encode

from tools.experiment8.acquire import (
    AcquisitionError,
    AcquisitionResult,
    PbfCache,
    acquire_manifest,
)
from tools.experiment8.model import TileKey


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _mvt() -> bytes:
    return encode(
        {
            "name": "places",
            "features": [
                {
                    "geometry": {"type": "Point", "coordinates": [100, 200]},
                    "properties": {"name": "Fixture"},
                    "id": 1,
                }
            ],
        },
        default_options={"y_coord_down": True},
    )


def _wire(pbf: bytes) -> bytes:
    return gzip.compress(pbf, mtime=0)


_DEFAULT_POPULATION_TILES = (
    TileKey(0, 0, 0),
    TileKey(1, 0, 0),
    TileKey(3, 2, 1),
    TileKey(4, 8, 9),
    TileKey(5, 4, 7),
)


def _write_source_lock(
    base: Path,
    population_tiles: tuple[TileKey, ...] = _DEFAULT_POPULATION_TILES,
) -> tuple[Path, str]:
    base.mkdir(parents=True, exist_ok=True)
    population = base / "population.tsv"
    unique_tiles = sorted({tile.packed: tile for tile in population_tiles}.values(), key=lambda tile: tile.packed)
    with population.open("w", encoding="utf-8", newline="\n") as output:
        output.write("serviceId\tserviceName\tz\tx\ty\n")
        for tile in unique_tiles:
            output.write(f"10\tfixture\t{tile.z}\t{tile.x}\t{tile.y}\n")
    population_counts: dict[str, int] = {}
    for tile in unique_tiles:
        population_counts[str(tile.z)] = population_counts.get(str(tile.z), 0) + 1
    population_sha = _sha(population.read_bytes())
    style = base / "style.json"
    metadata = base / "metadata.json"
    style.write_bytes(b'{"version":8,"layers":[]}\n')
    metadata.write_text(
        json.dumps(
            {
                "currentVersion": 10.81,
                "serviceItemId": "274684d7a9d74ca4b87f529776feb3a2",
                "tiles": ["tile/{z}/{y}/{x}.pbf"],
                "tileInfo": {"format": "pbf", "rows": 512, "cols": 512},
                "resourceInfo": {"tileCompression": "gzip"},
                "minLOD": 0,
                "maxLOD": 16,
            },
            sort_keys=True,
        ),
        encoding="utf-8",
    )
    style_sha = _sha(style.read_bytes())
    metadata_sha = _sha(metadata.read_bytes())
    descriptor = base / "world-basemap-v2-source-lock.json"
    descriptor.write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "source": "World_Basemap_v2 VectorTileServer",
                "serviceUrl": "https://basemaps.arcgis.com/arcgis/rest/services/World_Basemap_v2/VectorTileServer",
                "tileUrlTemplate": "https://basemaps.arcgis.com/arcgis/rest/services/World_Basemap_v2/VectorTileServer/tile/{z}/{y}/{x}.pbf",
                "styleSha256": style_sha,
                "metadataSha256": metadata_sha,
            },
            sort_keys=True,
        ),
        encoding="utf-8",
    )
    descriptor_sha = _sha(descriptor.read_bytes())
    verified = base / "verified-source-lock.json"
    verified.write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "sourceName": "World_Basemap_v2 VectorTileServer",
                "serviceUrl": "https://basemaps.arcgis.com/arcgis/rest/services/World_Basemap_v2/VectorTileServer",
                "sourceLockPath": str(descriptor),
                "sourceLockSha256": descriptor_sha,
                "stylePath": str(style),
                "styleSha256": style_sha,
                "metadataPath": str(metadata),
                "metadataSha256": metadata_sha,
                "populationPath": str(population),
                "population": {
                    "rowCount": len(unique_tiles),
                    "countsByZoom": population_counts,
                    "sha256": population_sha,
                },
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    return verified, _sha(verified.read_bytes())


class _ServerState:
    def __init__(self, responses: list[tuple[int, bytes, dict[str, str]]]) -> None:
        self.responses = responses
        self.requests: list[dict[str, object]] = []
        self.lock = threading.Lock()

    def response(self) -> tuple[int, bytes, dict[str, str]]:
        with self.lock:
            index = min(len(self.requests) - 1, len(self.responses) - 1)
            return self.responses[index]


@contextmanager
def _server(
    responses: list[tuple[int, bytes, dict[str, str]]],
) -> Iterator[tuple[str, _ServerState]]:
    state = _ServerState(responses)

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            with state.lock:
                state.requests.append(
                    {
                        "path": self.path,
                        "acceptEncoding": self.headers.get("Accept-Encoding"),
                        "userAgent": self.headers.get("User-Agent"),
                    }
                )
            status, body, headers = state.response()
            self.send_response(status)
            for name, value in headers.items():
                self.send_header(name, value)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format: str, *args: object) -> None:
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", state
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def _success(wire: bytes) -> tuple[int, bytes, dict[str, str]]:
    return (
        200,
        wire,
        {
            "Content-Type": "application/octet-stream",
            "Content-Encoding": "gzip",
            "ETag": '"fixture-etag"',
            "Last-Modified": "Fri, 26 Jun 2026 02:33:08 GMT",
            "Cache-Control": "no-transform, max-age=43200",
        },
    )


def _cache(
    base: Path,
    server_url: str,
    *,
    sleeper=lambda _: None,
    max_attempts: int = 3,
    population_tiles: tuple[TileKey, ...] = _DEFAULT_POPULATION_TILES,
    max_cache_bytes: int = 23_500_000_000,
    min_free_bytes: int = 0,
    max_wire_bytes: int = 16 * 1024 * 1024,
    max_pbf_bytes: int = 64 * 1024 * 1024,
) -> PbfCache:
    lock, lock_sha = _write_source_lock(base / "source", population_tiles)
    return PbfCache(
        root=base / "cache",
        verified_source_lock_path=lock,
        expected_verified_source_lock_sha256=lock_sha,
        request_url_template=f"{server_url}/tile/{{z}}/{{y}}/{{x}}.pbf",
        allow_loopback_http=True,
        timeout_seconds=2.0,
        max_attempts=max_attempts,
        max_cache_bytes=max_cache_bytes,
        min_free_bytes=min_free_bytes,
        max_wire_bytes=max_wire_bytes,
        max_pbf_bytes=max_pbf_bytes,
        sleeper=sleeper,
    )


class PbfCacheTests(unittest.TestCase):
    def test_rejects_unlocked_https_template_override(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            lock, lock_sha = _write_source_lock(base / "source")
            with self.assertRaisesRegex(AcquisitionError, "override.*loopback"):
                PbfCache(
                    root=base / "cache",
                    verified_source_lock_path=lock,
                    expected_verified_source_lock_sha256=lock_sha,
                    request_url_template="https://unlocked.example/tile/{z}/{y}/{x}.pbf",
                )

    def test_rejects_password_only_credentials_in_loopback_template(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            lock, lock_sha = _write_source_lock(base / "source")
            with self.assertRaisesRegex(AcquisitionError, "credential-free"):
                PbfCache(
                    root=base / "cache",
                    verified_source_lock_path=lock,
                    expected_verified_source_lock_sha256=lock_sha,
                    request_url_template="http://:secret@127.0.0.1/tile/{z}/{y}/{x}.pbf",
                    allow_loopback_http=True,
                )

    def test_fetches_gzip_decodes_valid_pbf_and_commits_atomically(self) -> None:
        pbf = _mvt()
        wire = _wire(pbf)
        with tempfile.TemporaryDirectory() as directory, _server([_success(wire)]) as (url, state):
            base = Path(directory)
            cache = _cache(base, url)
            tile = TileKey(11, 585, 783)

            with mock.patch("mapbox_vector_tile.decode", wraps=decode) as decode_mock:
                result = cache.acquire(tile)

            self.assertEqual(result.status, "ready")
            self.assertFalse(result.cache_hit)
            self.assertEqual(result.response_sha256, _sha(wire))
            self.assertEqual(result.pbf_sha256, _sha(pbf))
            self.assertEqual(result.response_bytes, len(wire))
            self.assertEqual(result.pbf_bytes, len(pbf))
            self.assertNotEqual(result.response_sha256, result.pbf_sha256)
            decode_mock.assert_called_once_with(pbf, default_options={"y_coord_down": True})
            self.assertEqual(state.requests[0]["path"], "/tile/11/783/585.pbf")
            self.assertEqual(state.requests[0]["acceptEncoding"], "identity")
            paths = cache.entry_paths(tile)
            self.assertEqual(paths.wire_path.read_bytes(), wire)
            self.assertEqual(paths.pbf_path.read_bytes(), pbf)
            independently_decoded = decode(
                paths.pbf_path.read_bytes(), default_options={"y_coord_down": True}
            )
            feature = independently_decoded["places"]["features"][0]
            self.assertEqual(feature["id"], 1)
            self.assertEqual(feature["properties"]["name"], "Fixture")
            self.assertEqual(feature["geometry"]["coordinates"], [100, 200])
            sidecar = json.loads(paths.sidecar_path.read_text(encoding="utf-8"))
            verified = json.loads((base / "source" / "verified-source-lock.json").read_text())
            metadata = json.loads((base / "source" / "metadata.json").read_text())
            generation = {
                "coordinateConvention": "xyz-cache/url-z-y-x",
                "currentVersion": str(metadata["currentVersion"]),
                "maxLOD": metadata["maxLOD"],
                "metadataSha256": verified["metadataSha256"],
                "minLOD": metadata["minLOD"],
                "serviceItemId": metadata["serviceItemId"],
                "serviceUrl": verified["serviceUrl"],
                "styleSha256": verified["styleSha256"],
                "tileCols": metadata["tileInfo"]["cols"],
                "tileCompression": metadata["resourceInfo"]["tileCompression"],
                "tileFormat": metadata["tileInfo"]["format"],
                "tileRows": metadata["tileInfo"]["rows"],
                "tileUrlTemplate": json.loads(
                    (base / "source" / "world-basemap-v2-source-lock.json").read_text()
                )["tileUrlTemplate"],
            }
            expected_generation = _sha(
                json.dumps(generation, sort_keys=True, separators=(",", ":")).encode()
            )
            self.assertEqual(sidecar["tile"], {"z": 11, "x": 585, "y": 783})
            self.assertEqual(sidecar["relativeCacheKey"], "tiles/11/585/783")
            self.assertEqual(sidecar["sourceGenerationId"], expected_generation)
            self.assertEqual(sidecar["verifiedSourceLockSha256"], _sha((base / "source" / "verified-source-lock.json").read_bytes()))
            self.assertEqual(sidecar["sourceLockSha256"], verified["sourceLockSha256"])
            self.assertEqual(sidecar["metadataSha256"], verified["metadataSha256"])
            self.assertEqual(sidecar["styleSha256"], verified["styleSha256"])
            self.assertEqual(sidecar["serviceItemId"], metadata["serviceItemId"])
            self.assertEqual(sidecar["currentVersion"], str(metadata["currentVersion"]))
            requested_url = f"{url}/tile/11/783/585.pbf"
            self.assertEqual(sidecar["requestedUrl"], requested_url)
            self.assertEqual(sidecar["finalUrl"], requested_url)
            self.assertEqual(
                sidecar["request"],
                {
                    "acceptEncoding": "identity",
                    "method": "GET",
                    "userAgent": "FlightAlert-Experiment8/1.0",
                },
            )
            self.assertEqual(sidecar["response"]["status"], 200)
            self.assertEqual(sidecar["response"]["contentType"], "application/octet-stream")
            self.assertEqual(sidecar["response"]["contentEncoding"], "gzip")
            self.assertEqual(sidecar["response"]["contentLength"], str(len(wire)))
            self.assertEqual(sidecar["response"]["etag"], '"fixture-etag"')
            self.assertEqual(
                sidecar["response"]["lastModified"], "Fri, 26 Jun 2026 02:33:08 GMT"
            )
            self.assertEqual(sidecar["response"]["cacheControl"], "no-transform, max-age=43200")
            self.assertEqual(sidecar["wire"]["codec"], "gzip")
            self.assertEqual(sidecar["wire"]["bytes"], len(wire))
            self.assertEqual(sidecar["wire"]["sha256"], _sha(wire))
            self.assertEqual(sidecar["wire"]["integrity"], "passed")
            self.assertEqual(sidecar["decoded"]["codec"], "mvt-pbf")
            self.assertEqual(sidecar["decoded"]["bytes"], len(pbf))
            self.assertEqual(sidecar["decoded"]["sha256"], _sha(pbf))
            self.assertEqual(sidecar["decoded"]["layerCount"], 1)
            self.assertEqual(sidecar["decoded"]["featureCount"], 1)
            self.assertEqual(sidecar["decoded"]["decode"], "passed")
            self.assertIs(sidecar["decoded"]["yCoordDown"], True)
            self.assertEqual(sidecar["attempts"], 1)
            self.assertRegex(sidecar["acquiredAtUtc"], r"^\d{4}-\d\d-\d\dT.*Z$")
            acquired_at = dt.datetime.fromisoformat(sidecar["acquiredAtUtc"].replace("Z", "+00:00"))
            self.assertEqual(acquired_at.utcoffset(), dt.timedelta(0))
            self.assertEqual(list((base / "cache").rglob("*.tmp")), [])

    def test_production_url_and_generation_are_path_independent(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            generations: list[str] = []
            for name in ("one", "two"):
                lock, lock_sha = _write_source_lock(base / name / "source")
                cache = PbfCache(
                    root=base / name / "cache",
                    verified_source_lock_path=lock,
                    expected_verified_source_lock_sha256=lock_sha,
                    min_free_bytes=0,
                )
                generations.append(cache.source_generation_id)
                self.assertEqual(
                    cache._url(TileKey(11, 585, 783)),
                    "https://basemaps.arcgis.com/arcgis/rest/services/World_Basemap_v2/"
                    "VectorTileServer/tile/11/783/585.pbf",
                )
            self.assertEqual(generations[0], generations[1])

    def test_resume_reuses_only_hash_verified_cache_and_corruption_refetches(self) -> None:
        pbf = _mvt()
        wire = _wire(pbf)
        with tempfile.TemporaryDirectory() as directory, _server([_success(wire)]) as (url, state):
            base = Path(directory)
            cache = _cache(base, url)
            tile = TileKey(5, 10, 12)
            self.assertEqual(cache.acquire(tile).status, "ready")
            self.assertEqual(cache.acquire(tile).cache_hit, True)
            self.assertEqual(len(state.requests), 1)

            paths = cache.entry_paths(tile)
            sidecar = json.loads(paths.sidecar_path.read_text(encoding="utf-8"))
            sidecar["requestedUrl"] = "http://127.0.0.1:1/wrong"
            paths.sidecar_path.write_text(json.dumps(sidecar), encoding="utf-8")
            self.assertEqual(cache.acquire(tile).status, "ready")
            self.assertEqual(len(state.requests), 2)

            paths.pbf_path.write_bytes(b"corrupt")
            repaired = cache.acquire(tile)
            self.assertEqual(repaired.status, "ready")
            self.assertFalse(repaired.cache_hit)
            self.assertEqual(len(state.requests), 3)
            self.assertEqual(paths.pbf_path.read_bytes(), pbf)
            self.assertTrue(any((base / "cache" / "quarantine").rglob("*")))

            sidecar = json.loads(paths.sidecar_path.read_text(encoding="utf-8"))
            sidecar["sourceGenerationId"] = "0" * 64
            paths.sidecar_path.write_text(json.dumps(sidecar), encoding="utf-8")
            self.assertEqual(cache.acquire(tile).status, "ready")
            self.assertEqual(len(state.requests), 4)

    def test_resume_succeeds_after_server_is_stopped_without_network(self) -> None:
        pbf = _mvt()
        wire = _wire(pbf)
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            tile = TileKey(5, 10, 12)
            with _server([_success(wire)]) as (url, state):
                cache = _cache(base, url)
                cold = cache.acquire(tile)
                self.assertEqual(cold.status, "ready")
                self.assertFalse(cold.cache_hit)
                self.assertEqual(len(state.requests), 1)
            paths = cache.entry_paths(tile)
            sidecar_before = paths.sidecar_path.read_bytes()

            with mock.patch.object(
                cache._opener, "open", side_effect=AssertionError("network must not be touched")
            ) as open_mock:
                warm = cache.acquire(tile)

            self.assertEqual(warm.status, "ready")
            self.assertTrue(warm.cache_hit)
            self.assertEqual(warm.attempts, 0)
            self.assertEqual(open_mock.call_count, 0)
            self.assertEqual(paths.sidecar_path.read_bytes(), sidecar_before)

    def test_cache_sidecar_provenance_tampering_forces_refetch(self) -> None:
        pbf = _mvt()
        wire = _wire(pbf)
        mutations = (
            (("verifiedSourceLockSha256",), "0" * 64),
            (("serviceItemId",), "wrong-service"),
            (("request", "acceptEncoding"), "gzip"),
            (("response", "contentLength"), "999999"),
            (("decoded", "yCoordDown"), False),
        )
        for field_path, replacement in mutations:
            with self.subTest(field_path=field_path), tempfile.TemporaryDirectory() as directory, _server(
                [_success(wire)]
            ) as (url, state):
                base = Path(directory)
                cache = _cache(base, url)
                tile = TileKey(3, 2, 1)
                self.assertEqual(cache.acquire(tile).status, "ready")
                paths = cache.entry_paths(tile)
                sidecar = json.loads(paths.sidecar_path.read_text(encoding="utf-8"))
                target = sidecar
                for field in field_path[:-1]:
                    target = target[field]
                target[field_path[-1]] = replacement
                mutated_bytes = (json.dumps(sidecar, sort_keys=True) + "\n").encode()
                paths.sidecar_path.write_bytes(mutated_bytes)

                repaired = cache.acquire(tile)

                self.assertEqual(repaired.status, "ready")
                self.assertFalse(repaired.cache_hit)
                self.assertEqual(len(state.requests), 2)
                quarantined = list((base / "cache" / "quarantine").rglob(f"{tile.y}.json"))
                self.assertEqual(len(quarantined), 1)
                self.assertEqual(quarantined[0].read_bytes(), mutated_bytes)

    def test_orphan_cache_entry_is_quarantined_and_redirect_origin_is_rejected(self) -> None:
        pbf = _mvt()
        wire = _wire(pbf)
        with tempfile.TemporaryDirectory() as directory, _server([_success(wire)]) as (url, state):
            base = Path(directory)
            cache = _cache(base, url)
            tile = TileKey(4, 4, 5)
            paths = cache.entry_paths(tile)
            paths.pbf_path.parent.mkdir(parents=True, exist_ok=True)
            paths.pbf_path.write_bytes(pbf)

            self.assertEqual(cache.acquire(tile).status, "ready")
            self.assertEqual(len(state.requests), 1)
            self.assertTrue(any((base / "cache" / "quarantine").rglob("*")))
            with self.assertRaisesRegex(AcquisitionError, "left locked source origin"):
                cache._validate_final_url(f"{url}/tile/4/5/4.pbf", "https://evil.invalid/tile")

    def test_cross_origin_redirect_is_blocked_before_target_contact(self) -> None:
        pbf = _mvt()
        with tempfile.TemporaryDirectory() as directory, _server([_success(_wire(pbf))]) as (
            target_url,
            target_state,
        ), _server([]) as (redirect_url, redirect_state):
            redirect_state.responses = [
                (
                    302,
                    b"",
                    {"Location": f"{target_url}/forbidden", "Content-Type": "text/plain"},
                )
            ]
            cache = _cache(Path(directory), redirect_url, max_attempts=1)

            result = cache.acquire(TileKey(3, 1, 2))

            self.assertEqual(result.status, "failed")
            self.assertIn("locked source origin", result.error or "")
            self.assertEqual(len(redirect_state.requests), 1)
            self.assertEqual(len(target_state.requests), 0)

    def test_zoom_outside_locked_lod_never_touches_network(self) -> None:
        pbf = _mvt()
        with tempfile.TemporaryDirectory() as directory, _server([_success(_wire(pbf))]) as (url, state):
            cache = _cache(Path(directory), url)
            result = cache.acquire(TileKey(17, 0, 0))
            self.assertEqual(result.status, "failed")
            self.assertEqual(result.attempts, 0)
            self.assertIn("LOD", result.error or "")
            self.assertEqual(len(state.requests), 0)

    def test_http_error_remains_failed_not_known_empty(self) -> None:
        with tempfile.TemporaryDirectory() as directory, _server(
            [(404, b"missing", {"Content-Type": "text/plain"})]
        ) as (url, state):
            cache = _cache(Path(directory), url)
            tile = TileKey(4, 1, 2)

            result = cache.acquire(tile)

            self.assertEqual(result.status, "failed")
            self.assertEqual(result.attempts, 1)
            self.assertIn("404", result.error or "")
            paths = cache.entry_paths(tile)
            self.assertFalse(paths.wire_path.exists())
            self.assertFalse(paths.pbf_path.exists())
            self.assertFalse(paths.sidecar_path.exists())
            self.assertEqual(len(state.requests), 1)

    def test_rejects_truncated_gzip_malformed_pbf_and_provider_json(self) -> None:
        pbf = _mvt()
        cases = (
            ("gzip", _success(_wire(pbf)[:-3])),
            ("PBF", _success(_wire(b"not a vector tile"))),
            (
                "content type",
                (200, b'{"error":"provider"}', {"Content-Type": "application/json"}),
            ),
        )
        for label, response in cases:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory, _server(
                [response]
            ) as (url, _):
                cache = _cache(Path(directory), url, max_attempts=1)
                result = cache.acquire(TileKey(3, 2, 1))
                self.assertEqual(result.status, "failed")
                self.assertIn(label.lower(), (result.error or "").lower())
                self.assertFalse(cache.entry_paths(TileKey(3, 2, 1)).sidecar_path.exists())

    def test_enforces_wire_and_inflated_pbf_byte_ceilings(self) -> None:
        pbf = _mvt()
        wire = _wire(pbf)
        cases = (
            ({"max_wire_bytes": len(wire) - 1}, "wire response exceeds"),
            ({"max_pbf_bytes": len(pbf) - 1}, "decoded PBF exceeds"),
        )
        for arguments, expected_error in cases:
            with self.subTest(expected_error=expected_error), tempfile.TemporaryDirectory() as directory, _server(
                [_success(wire)]
            ) as (url, state):
                base = Path(directory)
                cache = _cache(base, url, max_attempts=1, **arguments)
                tile = TileKey(3, 2, 1)

                result = cache.acquire(tile)

                self.assertEqual(result.status, "failed")
                self.assertEqual(result.attempts, 1)
                self.assertIn(expected_error, result.error or "")
                self.assertEqual(len(state.requests), 1)
                paths = cache.entry_paths(tile)
                self.assertFalse(paths.wire_path.exists())
                self.assertFalse(paths.pbf_path.exists())
                self.assertFalse(paths.sidecar_path.exists())
                self.assertEqual(list((base / "cache").rglob("*.tmp")), [])

        with tempfile.TemporaryDirectory() as directory, _server([_success(wire)]) as (url, _):
            exact = _cache(
                Path(directory),
                url,
                max_attempts=1,
                max_wire_bytes=len(wire),
                max_pbf_bytes=len(pbf),
            )
            self.assertEqual(exact.acquire(TileKey(3, 2, 1)).status, "ready")

    def test_rejects_gzip_magic_crc_and_isize_corruption(self) -> None:
        wire = _wire(_mvt())
        corruptions: dict[str, bytes] = {}
        for label, index in (("magic", 0), ("CRC", -8), ("length", -4)):
            mutated = bytearray(wire)
            mutated[index] ^= 0x01
            corruptions[label] = bytes(mutated)
        for label, corrupted in corruptions.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory, _server(
                [_success(corrupted)]
            ) as (url, _):
                base = Path(directory)
                cache = _cache(base, url, max_attempts=1)
                tile = TileKey(3, 2, 1)

                result = cache.acquire(tile)

                self.assertEqual(result.status, "failed")
                self.assertIn(label.lower(), (result.error or "").lower())
                paths = cache.entry_paths(tile)
                self.assertFalse(paths.wire_path.exists())
                self.assertFalse(paths.pbf_path.exists())
                self.assertFalse(paths.sidecar_path.exists())

    def test_rejects_octet_stream_prefix_media_type(self) -> None:
        pbf = _mvt()
        with tempfile.TemporaryDirectory() as directory, _server(
            [
                (
                    200,
                    _wire(pbf),
                    {
                        "Content-Type": "application/octet-streamish",
                        "Content-Encoding": "gzip",
                    },
                )
            ]
        ) as (url, _):
            result = _cache(Path(directory), url, max_attempts=1).acquire(TileKey(3, 2, 1))
            self.assertEqual(result.status, "failed")
            self.assertIn("content type", result.error or "")

    def test_rejects_gzip_wire_without_locked_content_encoding(self) -> None:
        pbf = _mvt()
        with tempfile.TemporaryDirectory() as directory, _server(
            [(200, _wire(pbf), {"Content-Type": "application/octet-stream"})]
        ) as (url, _):
            cache = _cache(Path(directory), url, max_attempts=1)
            result = cache.acquire(TileKey(2, 1, 1))
            self.assertEqual(result.status, "failed")
            self.assertIn("content encoding", (result.error or "").lower())

    def test_retries_only_429_and_5xx(self) -> None:
        pbf = _mvt()
        wire = _wire(pbf)
        sleeps: list[float] = []
        responses = [
            (500, b"retry", {"Content-Type": "text/plain"}),
            (429, b"wait", {"Content-Type": "text/plain", "Retry-After": "0"}),
            _success(wire),
        ]
        with tempfile.TemporaryDirectory() as directory, _server(responses) as (url, state):
            cache = _cache(Path(directory), url, sleeper=sleeps.append)
            result = cache.acquire(TileKey(2, 1, 1))
            self.assertEqual(result.status, "ready")
            self.assertEqual(result.attempts, 3)
            self.assertEqual(len(state.requests), 3)
            self.assertEqual(sleeps, [1.0, 0.0])

    def test_retries_timeout_but_not_permanent_url_error(self) -> None:
        pbf = _mvt()
        with tempfile.TemporaryDirectory() as directory, _server([_success(_wire(pbf))]) as (url, state):
            base = Path(directory)
            timeout_sleeps: list[float] = []
            cache = _cache(base / "timeout", url, sleeper=timeout_sleeps.append)
            real_open = cache._opener.open
            calls = 0

            def timeout_then_open(*args: object, **kwargs: object):
                nonlocal calls
                calls += 1
                if calls < 3:
                    raise urllib.error.URLError(socket.timeout("timed out"))
                return real_open(*args, **kwargs)

            with mock.patch.object(cache._opener, "open", side_effect=timeout_then_open):
                timeout_result = cache.acquire(TileKey(2, 1, 1))
            self.assertEqual(timeout_result.status, "ready")
            self.assertEqual(timeout_result.attempts, 3)
            self.assertEqual(len(timeout_sleeps), 2)
            self.assertEqual(len(state.requests), 1)

            permanent_sleeps: list[float] = []
            permanent = _cache(base / "permanent", url, sleeper=permanent_sleeps.append)
            with mock.patch.object(
                permanent._opener,
                "open",
                side_effect=urllib.error.URLError("permanent DNS failure"),
            ) as open_mock:
                permanent_result = permanent.acquire(TileKey(2, 2, 1))
            self.assertEqual(permanent_result.status, "failed")
            self.assertEqual(permanent_result.attempts, 1)
            self.assertEqual(open_mock.call_count, 1)
            self.assertEqual(permanent_sleeps, [])

    def test_storage_watermarks_prevent_cache_growth(self) -> None:
        pbf = _mvt()
        cases = (
            ({"max_cache_bytes": 1}, "cache byte budget"),
            (
                {"max_cache_bytes": 10**18, "min_free_bytes": 10**18},
                "free-space watermark",
            ),
        )
        for cache_arguments, expected_error in cases:
            with self.subTest(expected_error=expected_error), tempfile.TemporaryDirectory() as directory, _server(
                [_success(_wire(pbf))]
            ) as (url, state):
                cache = _cache(Path(directory), url, max_attempts=1, **cache_arguments)
                tile = TileKey(2, 1, 1)

                result = cache.acquire(tile)

                self.assertEqual(result.status, "failed")
                self.assertEqual(result.attempts, 1)
                self.assertIn(expected_error, result.error or "")
                self.assertEqual(len(state.requests), 1)
                paths = cache.entry_paths(tile)
                self.assertFalse(paths.wire_path.exists())
                self.assertFalse(paths.pbf_path.exists())
                self.assertFalse(paths.sidecar_path.exists())

    def test_storage_reservations_are_concurrency_safe(self) -> None:
        with tempfile.TemporaryDirectory() as directory, _server([]) as (url, _):
            cache = _cache(Path(directory), url, max_cache_bytes=10, min_free_bytes=0)
            start = threading.Barrier(3)
            release = threading.Event()
            rejected = threading.Event()
            outcomes: list[str] = []
            outcomes_lock = threading.Lock()

            def reserve() -> None:
                start.wait()
                try:
                    cache._reserve_storage(6)
                except AcquisitionError:
                    with outcomes_lock:
                        outcomes.append("rejected")
                    rejected.set()
                    return
                try:
                    with outcomes_lock:
                        outcomes.append("reserved")
                    release.wait(timeout=5)
                finally:
                    cache._release_storage(6, committed=False)

            threads = [threading.Thread(target=reserve) for _ in range(2)]
            for thread in threads:
                thread.start()
            start.wait()
            self.assertTrue(rejected.wait(timeout=5))
            release.set()
            for thread in threads:
                thread.join(timeout=5)
                self.assertFalse(thread.is_alive())
            self.assertCountEqual(outcomes, ["reserved", "rejected"])

    def test_cache_writer_lock_rejects_a_second_cache_process(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            caches = []
            for name in ("one", "two"):
                lock, lock_sha = _write_source_lock(base / name / "source")
                caches.append(
                    PbfCache(
                        root=base / "shared-cache",
                        verified_source_lock_path=lock,
                        expected_verified_source_lock_sha256=lock_sha,
                        min_free_bytes=0,
                    )
                )
            with caches[0].exclusive_writer():
                with self.assertRaisesRegex(AcquisitionError, "another Experiment 8 writer"):
                    with caches[1].exclusive_writer():
                        self.fail("second writer must not acquire the same cache")

    def test_cache_commit_failure_leaves_no_false_ready_entry_and_can_retry(self) -> None:
        pbf = _mvt()
        wire = _wire(pbf)
        for failing_install in (2, 3):
            with self.subTest(failing_install=failing_install), tempfile.TemporaryDirectory() as directory, _server(
                [_success(wire)]
            ) as (url, state):
                base = Path(directory)
                cache = _cache(base, url, max_attempts=1)
                tile = TileKey(3, 2, 1)
                real_replace = os.replace
                replace_calls = 0

                def fail_install(source: object, destination: object) -> None:
                    nonlocal replace_calls
                    replace_calls += 1
                    self.assertEqual(Path(source).parent, Path(destination).parent)
                    if replace_calls == failing_install:
                        raise OSError("injected cache install failure")
                    real_replace(source, destination)

                with mock.patch("tools.experiment8.acquire.os.replace", side_effect=fail_install):
                    failed = cache.acquire(tile)

                self.assertEqual(failed.status, "failed")
                self.assertIn("injected cache install failure", failed.error or "")
                paths = cache.entry_paths(tile)
                self.assertFalse(paths.wire_path.exists())
                self.assertFalse(paths.pbf_path.exists())
                self.assertFalse(paths.sidecar_path.exists())
                self.assertEqual(list((base / "cache").rglob("*.tmp")), [])

                repaired = cache.acquire(tile)
                self.assertEqual(repaired.status, "ready")
                self.assertFalse(repaired.cache_hit)
                self.assertEqual(len(state.requests), 2)


class AcquisitionManifestTests(unittest.TestCase):
    def test_rejects_sample_over_pilot_row_limit(self) -> None:
        with tempfile.TemporaryDirectory() as directory, _server([]) as (url, state):
            base = Path(directory)
            sample = base / "oversized.jsonl"
            with sample.open("w", encoding="utf-8", newline="\n") as output:
                for index in range(123_284):
                    output.write(
                        json.dumps(
                            {
                                "sourceState": "known_empty",
                                "z": 16,
                                "x": index % (1 << 16),
                                "y": index // (1 << 16),
                            },
                            separators=(",", ":"),
                        )
                        + "\n"
                    )
            cache = _cache(base, url)

            with self.assertRaisesRegex(AcquisitionError, "pilot row limit"):
                acquire_manifest(sample, _sha(sample.read_bytes()), cache, 1, base / "out")

            self.assertEqual(len(state.requests), 0)

    def test_rejects_oversized_sample_line(self) -> None:
        with tempfile.TemporaryDirectory() as directory, _server([]) as (url, state):
            base = Path(directory)
            sample = base / "oversized-line.jsonl"
            sample.write_text(
                json.dumps(
                    {"padding": "x" * 20_000, "sourceState": "present", "z": 0, "x": 0, "y": 0}
                )
                + "\n",
                encoding="utf-8",
            )
            cache = _cache(base, url)

            with self.assertRaisesRegex(AcquisitionError, "sample line.*byte limit"):
                acquire_manifest(sample, _sha(sample.read_bytes()), cache, 1, base / "out")

            self.assertEqual(len(state.requests), 0)

    def test_rejects_sample_source_state_contradictions_before_network(self) -> None:
        pbf = _mvt()
        cases = (
            ('{"sourceState":"known_empty","z":0,"x":0,"y":0}\n', "known_empty"),
            ('{"sourceState":"present","z":1,"x":1,"y":1}\n', "present"),
            (
                '{"sourceState":"known_empty","z":1,"x":1,"y":1}\n'
                '{"sourceState":"known_empty","z":1,"x":1,"y":1}\n',
                "duplicate sample tile",
            ),
            (
                '{"sourceState":"known_empty","z":1,"x":1,"y":1}\n'
                '{"sourceState":"present","z":1,"x":1,"y":1}\n',
                "duplicate sample tile",
            ),
            (
                '{"sourceState":"present","z":0,"x":0,"y":0}\n'
                '{"sourceState":"known_empty","z":0,"x":0,"y":0}\n',
                "duplicate sample tile",
            ),
        )
        for sample_text, expected in cases:
            with self.subTest(expected=expected), tempfile.TemporaryDirectory() as directory, _server(
                [_success(_wire(pbf))]
            ) as (url, state):
                base = Path(directory)
                sample = base / "sample.jsonl"
                sample.write_text(sample_text, encoding="utf-8")
                cache = _cache(base, url)

                with self.assertRaisesRegex(AcquisitionError, expected):
                    acquire_manifest(sample, _sha(sample.read_bytes()), cache, 1, base / "out")

                self.assertEqual(len(state.requests), 0)

    def test_rehashes_population_before_trusting_sample_states(self) -> None:
        pbf = _mvt()
        with tempfile.TemporaryDirectory() as directory, _server([_success(_wire(pbf))]) as (url, state):
            base = Path(directory)
            sample = base / "sample.jsonl"
            sample.write_text('{"sourceState":"present","z":0,"x":0,"y":0}\n', encoding="utf-8")
            cache = _cache(base, url)
            population_bytes = cache.source.population_path.read_bytes()
            cache.source.population_path.write_bytes(population_bytes.replace(b"fixture", b"fixturE", 1))

            with self.assertRaisesRegex(AcquisitionError, "population SHA-256 mismatch"):
                acquire_manifest(sample, _sha(sample.read_bytes()), cache, 1, base / "out")

            self.assertEqual(len(state.requests), 0)

    def test_rejects_oversized_population_line_before_network(self) -> None:
        pbf = _mvt()
        with tempfile.TemporaryDirectory() as directory, _server([_success(_wire(pbf))]) as (url, state):
            base = Path(directory)
            sample = base / "sample.jsonl"
            sample.write_text('{"sourceState":"present","z":0,"x":0,"y":0}\n', encoding="utf-8")
            cache = _cache(base, url)
            population_lines = cache.source.population_path.read_text(encoding="utf-8").splitlines()
            population_lines[1] = "10\t" + ("x" * 5000) + "\t0\t0\t0"
            cache.source.population_path.write_text("\n".join(population_lines) + "\n", encoding="utf-8")

            with self.assertRaisesRegex(AcquisitionError, "population line.*byte limit"):
                acquire_manifest(sample, _sha(sample.read_bytes()), cache, 1, base / "out")

            self.assertEqual(len(state.requests), 0)

    def test_known_empty_fixture_rows_are_proven_and_never_requested(self) -> None:
        pbf = _mvt()
        with tempfile.TemporaryDirectory() as directory, _server([_success(_wire(pbf))]) as (url, state):
            base = Path(directory)
            sample = base / "fixtures.jsonl"
            sample.write_text(
                '{"sourceState":"present","z":1,"x":0,"y":0}\n'
                '{"sourceState":"known_empty","z":1,"x":1,"y":1}\n',
                encoding="utf-8",
            )
            cache = _cache(base, url)

            summary = acquire_manifest(
                sample,
                _sha(sample.read_bytes()),
                cache,
                1,
                base / "out",
            )

            self.assertEqual(summary.input_row_count, 2)
            self.assertEqual(summary.row_count, 1)
            self.assertEqual(summary.skipped_known_empty_count, 1)
            self.assertEqual(len(state.requests), 1)
            on_disk = json.loads((base / "out" / "acquisition-summary.json").read_text())
            self.assertEqual(on_disk["inputRowCount"], on_disk["rowCount"] + on_disk["skippedKnownEmptyCount"])
            self.assertEqual(on_disk["verifiedSourceLockSha256"], cache.source.verified_lock_sha256)
            self.assertEqual(on_disk["populationSha256"], cache.source.population.sha256)
            self.assertEqual(on_disk["populationRowCount"], cache.source.population.row_count)
            self.assertEqual(on_disk["maxCacheBytes"], cache.max_cache_bytes)
            self.assertEqual(on_disk["minFreeBytes"], cache.min_free_bytes)

    def test_worker_count_does_not_change_deterministic_inventory(self) -> None:
        pbf = _mvt()
        wire = _wire(pbf)
        with tempfile.TemporaryDirectory() as directory, _server([_success(wire)]) as (url, _):
            base = Path(directory)
            sample = base / "sample.jsonl"
            tiles = [TileKey(5, 4, 7), TileKey(3, 2, 1), TileKey(4, 8, 9)]
            with sample.open("w", encoding="utf-8", newline="\n") as output:
                for tile in tiles:
                    output.write(json.dumps({"z": tile.z, "x": tile.x, "y": tile.y}) + "\n")
            sample_sha = _sha(sample.read_bytes())
            lock, lock_sha = _write_source_lock(base / "source")

            def cache_at(name: str) -> PbfCache:
                return PbfCache(
                    root=base / name / "cache",
                    verified_source_lock_path=lock,
                    expected_verified_source_lock_sha256=lock_sha,
                    request_url_template=f"{url}/tile/{{z}}/{{y}}/{{x}}.pbf",
                    allow_loopback_http=True,
                    timeout_seconds=2.0,
                    min_free_bytes=0,
                )

            first_cache = cache_at("first")
            second_cache = cache_at("second")

            first = acquire_manifest(sample, sample_sha, first_cache, 1, base / "out-one")
            second = acquire_manifest(sample, sample_sha, second_cache, 4, base / "out-two")

            self.assertEqual(first.ready_count, 3)
            self.assertEqual(second.ready_count, 3)
            self.assertEqual(
                (base / "out-one" / "acquisition.jsonl").read_bytes(),
                (base / "out-two" / "acquisition.jsonl").read_bytes(),
            )
            self.assertEqual(
                (base / "out-one" / "acquisition-summary.json").read_bytes(),
                (base / "out-two" / "acquisition-summary.json").read_bytes(),
            )
            deterministic = (
                (base / "out-one" / "acquisition.jsonl").read_bytes()
                + (base / "out-one" / "acquisition-summary.json").read_bytes()
            )
            self.assertNotIn(str(base).encode(), deterministic)
            rows = [json.loads(line) for line in (base / "out-one" / "acquisition.jsonl").read_text().splitlines()]
            self.assertEqual(
                [(row["z"], row["x"], row["y"]) for row in rows],
                [(tile.z, tile.x, tile.y) for tile in sorted(tiles, key=lambda item: item.packed)],
            )

    def test_manifest_bounds_submitted_work_to_twice_the_worker_count(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            tiles = tuple(TileKey(4, x, 0) for x in range(10))
            sample = base / "sample.jsonl"
            with sample.open("w", encoding="utf-8", newline="\n") as output:
                for tile in tiles:
                    output.write(json.dumps({"z": tile.z, "x": tile.x, "y": tile.y}) + "\n")
            cache = _cache(
                base,
                "http://127.0.0.1:9",
                population_tiles=tiles,
            )
            release = threading.Event()
            started = threading.Event()
            active = 0
            active_lock = threading.Lock()

            def blocked_acquire(tile: TileKey) -> AcquisitionResult:
                nonlocal active
                with active_lock:
                    active += 1
                    if active >= 2:
                        started.set()
                release.wait(timeout=5)
                return AcquisitionResult(
                    tile=tile,
                    status="ready",
                    response_sha256="1" * 64,
                    pbf_sha256="2" * 64,
                    response_bytes=1,
                    pbf_bytes=1,
                    attempts=1,
                    error=None,
                    layer_count=1,
                    feature_count=1,
                )

            real_executor_type = concurrent.futures.ThreadPoolExecutor
            maximum_outstanding = 0

            class TrackingExecutor:
                def __init__(self, max_workers: int) -> None:
                    self.executor = real_executor_type(max_workers=max_workers)
                    self.outstanding = 0
                    self.lock = threading.Lock()

                def __enter__(self):
                    self.executor.__enter__()
                    return self

                def __exit__(self, *arguments: object):
                    return self.executor.__exit__(*arguments)

                def submit(self, function, *arguments, **keywords):
                    nonlocal maximum_outstanding
                    with self.lock:
                        if self.outstanding >= 4:
                            raise AssertionError("more than twice workers were submitted")
                        self.outstanding += 1
                        maximum_outstanding = max(maximum_outstanding, self.outstanding)
                    future = self.executor.submit(function, *arguments, **keywords)

                    def completed(_: object) -> None:
                        with self.lock:
                            self.outstanding -= 1

                    future.add_done_callback(completed)
                    return future

            errors: list[BaseException] = []

            def run() -> None:
                try:
                    acquire_manifest(sample, _sha(sample.read_bytes()), cache, 2, base / "out")
                except BaseException as error:
                    errors.append(error)

            with mock.patch.object(cache, "acquire", side_effect=blocked_acquire), mock.patch(
                "tools.experiment8.acquire.concurrent.futures.ThreadPoolExecutor",
                TrackingExecutor,
            ):
                thread = threading.Thread(target=run)
                thread.start()
                self.assertTrue(started.wait(timeout=5))
                release.set()
                thread.join(timeout=10)
                self.assertFalse(thread.is_alive())

            self.assertEqual(errors, [])
            self.assertLessEqual(maximum_outstanding, 4)

    def test_cold_and_offline_warm_runs_have_identical_deterministic_outputs(self) -> None:
        pbf = _mvt()
        wire = _wire(pbf)
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            sample = base / "sample.jsonl"
            sample.write_text('{"z":0,"x":0,"y":0}\n{"z":1,"x":0,"y":0}\n', encoding="utf-8")
            sample_sha = _sha(sample.read_bytes())
            with _server([_success(wire)]) as (url, _):
                cache = _cache(base, url)
                cold = acquire_manifest(sample, sample_sha, cache, 2, base / "cold")
            sidecars_before = {
                path.relative_to(base / "cache"): path.read_bytes()
                for path in (base / "cache").rglob("*.json")
            }
            with mock.patch.object(
                cache._opener, "open", side_effect=AssertionError("warm run must be offline")
            ) as open_mock:
                warm = acquire_manifest(sample, sample_sha, cache, 2, base / "warm")

            self.assertEqual(cold.acquisition_sha256, warm.acquisition_sha256)
            self.assertEqual(
                (base / "cold" / "acquisition.jsonl").read_bytes(),
                (base / "warm" / "acquisition.jsonl").read_bytes(),
            )
            self.assertEqual(
                (base / "cold" / "acquisition-summary.json").read_bytes(),
                (base / "warm" / "acquisition-summary.json").read_bytes(),
            )
            self.assertNotEqual(
                (base / "cold" / "acquisition-audit.jsonl").read_bytes(),
                (base / "warm" / "acquisition-audit.jsonl").read_bytes(),
            )
            self.assertTrue(
                all(row["cacheHit"] is False for row in map(json.loads, (base / "cold" / "acquisition-audit.jsonl").read_text().splitlines()))
            )
            self.assertTrue(
                all(row["cacheHit"] is True for row in map(json.loads, (base / "warm" / "acquisition-audit.jsonl").read_text().splitlines()))
            )
            self.assertEqual(open_mock.call_count, 0)
            self.assertEqual(
                {
                    path.relative_to(base / "cache"): path.read_bytes()
                    for path in (base / "cache").rglob("*.json")
                },
                sidecars_before,
            )

    def test_rejects_sample_hash_mismatch(self) -> None:
        pbf = _mvt()
        with tempfile.TemporaryDirectory() as directory, _server([_success(_wire(pbf))]) as (url, _):
            base = Path(directory)
            sample = base / "sample.jsonl"
            sample.write_text('{"z":0,"x":0,"y":0}\n', encoding="utf-8")
            cache = _cache(base, url)
            with self.assertRaisesRegex(AcquisitionError, "sample SHA-256 mismatch"):
                acquire_manifest(sample, "0" * 64, cache, 1, base / "out")

    def test_rejects_empty_sample(self) -> None:
        with tempfile.TemporaryDirectory() as directory, _server([]) as (url, state):
            base = Path(directory)
            sample = base / "empty.jsonl"
            sample.write_bytes(b"")
            cache = _cache(base, url)
            with self.assertRaisesRegex(AcquisitionError, "sample is empty"):
                acquire_manifest(sample, _sha(b""), cache, 1, base / "out")
            self.assertEqual(len(state.requests), 0)

    def test_output_replace_failure_restores_complete_previous_inventory(self) -> None:
        pbf = _mvt()
        with tempfile.TemporaryDirectory() as directory, _server([_success(_wire(pbf))]) as (url, _):
            base = Path(directory)
            cache = _cache(base, url)
            first_sample = base / "first.jsonl"
            first_sample.write_text('{"z":0,"x":0,"y":0}\n', encoding="utf-8")
            output = base / "out"
            acquire_manifest(first_sample, _sha(first_sample.read_bytes()), cache, 1, output)
            original = {
                name: (output / name).read_bytes()
                for name in (
                    "acquisition.jsonl",
                    "acquisition-audit.jsonl",
                    "acquisition-summary.json",
                )
            }
            second_sample = base / "second.jsonl"
            second_sample.write_text(
                '{"z":0,"x":0,"y":0}\n{"z":1,"x":0,"y":0}\n',
                encoding="utf-8",
            )
            real_replace = os.replace

            def fail_summary_install(source: object, destination: object) -> None:
                if Path(destination).name == "acquisition-summary.json" and Path(source).suffix == ".tmp":
                    raise OSError("injected acquisition summary install failure")
                real_replace(source, destination)

            with mock.patch(
                "tools.experiment8.acquire.os.replace", side_effect=fail_summary_install
            ):
                with self.assertRaisesRegex(OSError, "injected acquisition summary"):
                    acquire_manifest(
                        second_sample,
                        _sha(second_sample.read_bytes()),
                        cache,
                        1,
                        output,
                    )

            self.assertEqual(
                {name: (output / name).read_bytes() for name in original},
                original,
            )
            self.assertEqual(list(output.glob("*.tmp")), [])
            self.assertEqual(list(output.glob("*.bak")), [])


if __name__ == "__main__":
    unittest.main()
