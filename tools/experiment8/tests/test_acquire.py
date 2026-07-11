from __future__ import annotations

import gzip
import hashlib
import json
import os
import tempfile
import threading
import unittest
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Iterator
from unittest import mock

from mapbox_vector_tile import encode

from tools.experiment8.acquire import (
    AcquisitionError,
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


def _write_source_lock(base: Path) -> tuple[Path, str]:
    base.mkdir(parents=True, exist_ok=True)
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
                "populationPath": str(base / "population.tsv"),
                "population": {"rowCount": 1, "countsByZoom": {"0": 1}, "sha256": "c" * 64},
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
) -> PbfCache:
    lock, lock_sha = _write_source_lock(base / "source")
    return PbfCache(
        root=base / "cache",
        verified_source_lock_path=lock,
        expected_verified_source_lock_sha256=lock_sha,
        request_url_template=f"{server_url}/tile/{{z}}/{{y}}/{{x}}.pbf",
        allow_loopback_http=True,
        timeout_seconds=2.0,
        max_attempts=max_attempts,
        sleeper=sleeper,
    )


class PbfCacheTests(unittest.TestCase):
    def test_fetches_gzip_decodes_valid_pbf_and_commits_atomically(self) -> None:
        pbf = _mvt()
        wire = _wire(pbf)
        with tempfile.TemporaryDirectory() as directory, _server([_success(wire)]) as (url, state):
            base = Path(directory)
            cache = _cache(base, url)
            tile = TileKey(11, 585, 783)

            result = cache.acquire(tile)

            self.assertEqual(result.status, "ready")
            self.assertFalse(result.cache_hit)
            self.assertEqual(result.response_sha256, _sha(wire))
            self.assertEqual(result.pbf_sha256, _sha(pbf))
            self.assertEqual(result.response_bytes, len(wire))
            self.assertEqual(result.pbf_bytes, len(pbf))
            self.assertEqual(state.requests[0]["path"], "/tile/11/783/585.pbf")
            self.assertEqual(state.requests[0]["acceptEncoding"], "identity")
            paths = cache.entry_paths(tile)
            self.assertEqual(paths.wire_path.read_bytes(), wire)
            self.assertEqual(paths.pbf_path.read_bytes(), pbf)
            sidecar = json.loads(paths.sidecar_path.read_text(encoding="utf-8"))
            self.assertEqual(sidecar["tile"], {"z": 11, "x": 585, "y": 783})
            self.assertEqual(sidecar["relativeCacheKey"], "tiles/11/585/783")
            self.assertEqual(sidecar["wire"]["sha256"], _sha(wire))
            self.assertEqual(sidecar["decoded"]["sha256"], _sha(pbf))
            self.assertEqual(sidecar["decoded"]["layerCount"], 1)
            self.assertEqual(sidecar["decoded"]["featureCount"], 1)
            self.assertRegex(sidecar["acquiredAtUtc"], r"^\d{4}-\d\d-\d\dT")
            self.assertEqual(list((base / "cache").rglob("*.tmp")), [])

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
            self.assertEqual(len(sleeps), 2)


class AcquisitionManifestTests(unittest.TestCase):
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
            first_cache = _cache(base / "first", url)
            second_cache = _cache(base / "second", url)

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

    def test_rejects_sample_hash_mismatch(self) -> None:
        pbf = _mvt()
        with tempfile.TemporaryDirectory() as directory, _server([_success(_wire(pbf))]) as (url, _):
            base = Path(directory)
            sample = base / "sample.jsonl"
            sample.write_text('{"z":0,"x":0,"y":0}\n', encoding="utf-8")
            cache = _cache(base, url)
            with self.assertRaisesRegex(AcquisitionError, "sample SHA-256 mismatch"):
                acquire_manifest(sample, "0" * 64, cache, 1, base / "out")

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
                '{"z":0,"x":0,"y":0}\n{"z":1,"x":1,"y":1}\n',
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
