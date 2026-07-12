from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from tools.experiment8.large_source_acquire import (
    LargeSourceAcquireError,
    LargeSourceSpec,
    acquire_large_source,
)


class FakeCurl:
    def __init__(self, content: bytes, clock_values: list[str]) -> None:
        self.content = content
        self.calls: list[list[str]] = []
        self.clock_values = iter(clock_values)

    def clock(self) -> str:
        return next(self.clock_values)

    def __call__(self, args: list[str], stderr_path: Path) -> bytes:
        self.calls.append(args)
        dump_path = Path(args[args.index("--dump-header") + 1])
        effective = "https://objects.example.test/planet-260629.osm.pbf"
        if "--head" in args:
            dump_path.write_bytes(
                b"HTTP/1.1 302 Found\r\nLocation: "
                + effective.encode("ascii")
                + b"\r\n\r\nHTTP/1.1 200 OK\r\n"
                + f"Content-Length: {len(self.content)}\r\n".encode("ascii")
                + b"Accept-Ranges: bytes\r\n"
                + b"ETag: \"fixture-etag\"\r\n"
                + b"Last-Modified: Sat, 04 Jul 2026 06:48:05 GMT\r\n\r\n"
            )
        else:
            state_path = dump_path.parent.parent / "acquisition-state.json"
            state = json.loads(state_path.read_text(encoding="utf-8"))
            if state["status"] != "downloading" or not state["attempts"][-1]["localDownloadStartedUtc"]:
                raise AssertionError("download evidence was not persisted before curl GET")
            output = Path(args[args.index("--output") + 1])
            resume = output.stat().st_size if output.exists() else 0
            with output.open("ab") as stream:
                stream.write(self.content[resume:])
            dump_path.write_bytes(
                b"HTTP/1.1 206 Partial Content\r\n"
                + f"Content-Length: {len(self.content) - resume}\r\n".encode("ascii")
                + b"ETag: \"fixture-etag\"\r\n\r\n"
            )
            stderr_path.write_text("fixture progress\n", encoding="utf-8")
        return effective.encode("ascii")


class LargeSourceAcquireTest(unittest.TestCase):
    def test_resumable_download_persists_honest_times_and_double_readback(self) -> None:
        content = (b"real source bytes\x00" * 4096) + b"tail"
        md5 = hashlib.md5(content).hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            part = root / "planet-260629.osm.pbf.part"
            part.write_bytes(content[:1234])
            fake = FakeCurl(
                content,
                [
                    "2026-07-11T08:30:00Z",
                    "2026-07-11T08:30:01Z",
                    "2026-07-11T08:30:02Z",
                    "2026-07-11T08:31:00Z",
                    "2026-07-11T08:31:01Z",
                ],
            )
            spec = LargeSourceSpec(
                url="https://planet.example.test/pbf/planet-260629.osm.pbf",
                file_name="planet-260629.osm.pbf",
                expected_bytes=len(content),
                expected_md5=md5,
            )

            report = acquire_large_source(root, spec, curl_runner=fake, clock=fake.clock)

            final = root / spec.file_name
            self.assertEqual(content, final.read_bytes())
            self.assertFalse(part.exists())
            self.assertEqual(1234, report["resumeBytes"])
            self.assertEqual("2026-07-11T08:30:02Z", report["localDownloadStartedUtc"])
            self.assertEqual("2026-07-11T08:31:00Z", report["localDownloadFinishedUtc"])
            self.assertEqual(report["preInstallSha256"], report["installedReadbackSha256"])
            self.assertEqual(hashlib.sha256(content).hexdigest(), report["installedReadbackSha256"])
            self.assertEqual(md5, report["installedReadbackMd5"])
            self.assertTrue(report["sourceLockSha256"])
            self.assertEqual(2, len(fake.calls))
            persisted = json.loads((root / "acquisition-report.json").read_text(encoding="utf-8"))
            self.assertEqual(report, persisted)

    def test_hash_mismatch_preserves_part_and_never_publishes_final(self) -> None:
        content = b"actual"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fake = FakeCurl(
                content,
                [
                    "2026-07-11T08:30:00Z",
                    "2026-07-11T08:30:01Z",
                    "2026-07-11T08:30:02Z",
                    "2026-07-11T08:31:00Z",
                    "2026-07-11T08:31:01Z",
                ],
            )
            spec = LargeSourceSpec(
                url="https://planet.example.test/pbf/planet-260629.osm.pbf",
                file_name="planet-260629.osm.pbf",
                expected_bytes=len(content),
                expected_md5=hashlib.md5(b"different").hexdigest(),
            )

            with self.assertRaises(LargeSourceAcquireError):
                acquire_large_source(root, spec, curl_runner=fake, clock=fake.clock)

            self.assertTrue((root / "planet-260629.osm.pbf.part").is_file())
            self.assertFalse((root / "planet-260629.osm.pbf").exists())
            self.assertFalse((root / "acquisition-report.json").exists())
            state = json.loads((root / "acquisition-state.json").read_text(encoding="utf-8"))
            self.assertEqual("failed", state["status"])
            self.assertIn("MD5", state["failure"])

    def test_spec_rejects_moving_alias_and_non_https_source(self) -> None:
        with self.assertRaises(LargeSourceAcquireError):
            LargeSourceSpec(
                url="https://planet.example.test/pbf/planet-latest.osm.pbf",
                file_name="planet-latest.osm.pbf",
                expected_bytes=1,
                expected_md5="0" * 32,
            )
        with self.assertRaises(LargeSourceAcquireError):
            LargeSourceSpec(
                url="http://planet.example.test/pbf/planet-260629.osm.pbf",
                file_name="planet-260629.osm.pbf",
                expected_bytes=1,
                expected_md5="0" * 32,
            )


if __name__ == "__main__":
    unittest.main()
