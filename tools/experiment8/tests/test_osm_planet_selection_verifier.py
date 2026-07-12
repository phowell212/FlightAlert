from __future__ import annotations

import ast
import hashlib
import html
import io
import json
import os
import random
import shutil
import struct
import tempfile
import unittest
from dataclasses import replace
from datetime import datetime
from pathlib import Path
from unittest import mock

import tools.experiment8.osm_planet_selection_verifier as verifier_module


try:
    from tools.experiment8.osm_planet_selection_verifier import (
        FIXTURE_BROAD_ENVELOPE_PROFILE,
        GLOBAL_POLICY_SHA256,
        LIVE_BROAD_ENVELOPE_CONVERTED_STREAM_FORMAT,
        LIVE_BROAD_ENVELOPE_OPL_BYTES,
        LIVE_BROAD_ENVELOPE_OPL_SHA256,
        LIVE_BROAD_ENVELOPE_PBF_BYTES,
        LIVE_BROAD_ENVELOPE_PBF_SHA256,
        LIVE_BROAD_ENVELOPE_PROFILE,
        LIVE_BROAD_ENVELOPE_RELATIONS,
        LIVE_BROAD_ENVELOPE_WAYS,
        VerificationBindings,
        VerificationError,
        VerificationLimits,
        verify_planet_roots,
    )
except ModuleNotFoundError:
    FIXTURE_BROAD_ENVELOPE_PROFILE = None
    GLOBAL_POLICY_SHA256 = None
    LIVE_BROAD_ENVELOPE_CONVERTED_STREAM_FORMAT = None
    LIVE_BROAD_ENVELOPE_OPL_BYTES = None
    LIVE_BROAD_ENVELOPE_OPL_SHA256 = None
    LIVE_BROAD_ENVELOPE_PBF_BYTES = None
    LIVE_BROAD_ENVELOPE_PBF_SHA256 = None
    LIVE_BROAD_ENVELOPE_PROFILE = None
    LIVE_BROAD_ENVELOPE_RELATIONS = None
    LIVE_BROAD_ENVELOPE_WAYS = None
    VerificationBindings = None
    VerificationError = None
    VerificationLimits = None
    verify_planet_roots = None


_TIMESTAMP = "2026-06-28T23:59:59Z"
_ROOT_DOMAIN = b"FAE8OSMROOT1\0"
_BUCKET_DOMAIN = b"FAE8OSMBUCKET1\0"
_REJECTION_DOMAIN = b"FAE8OSMREJ1\0"
_PRODUCTION_FIXTURE_PROFILE = "flight-alert-exp8-osm-planet-name-envelope-fixture-v1"
_BROAD_FIXTURE_PROFILE = "flight-alert-exp8-osm-planet-broad-envelope-fixture-v1"
_POLICY_SHA256 = "7ddea49ea1501790519b6b47c2cd8170ce3043218551f1b978c98ffb35e7b50c"
_EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
_EMPTY_REJECTION_LEDGER = "415a61bece8cdae925afbe20b243ac093aa43386a5f68279dc591a7b1a6768ff"
_BROAD_REJECTION_LEDGER = "33e743aa2e33c0ab3f67cbaff4bcf35ed1dec3a91bbf0a297de8544324f7d541"
_AUTHORITATIVE_PLANET_SHA256 = (
    "cd5113a1ac905fc33eef8f2a7d1276b31036b06aa07b65aa3b3fa86cc0fcc96f"
)
_ROOT_IDS = b"w10\nw20\nr30\n"
_SELECTED_TUPLES = bytes.fromhex(
    "010a00000000000000070000002d8ab2b136cc31d99a6a2b95c0282b0d880330c2b7ffc81766fa047239972bc4"
    "01140000000000000002000000bcf507a38ed8a7587e121ed52290b98a9d92273095906084c225ad236c97a1e8"
    "021e00000000000000030000004126897117fd0515c6dd9f761aa2203284faf75debb0a115d877a470e6190dbc"
)
_FROZEN_BUCKET_FRAMES = {
    56: bytes.fromhex(
        "79000000011400000000000000020000007fb5416a00000000020000000a0000006e616d653a656e2d555304000000426574610800000077617465727761790600000073747265616d0200000002000000000000000900000000000000bcf507a38ed8a7587e121ed52290b98a9d92273095906084c225ad236c97a1e8"
    ),
    182: bytes.fromhex(
        "8f000000010a00000000000000070000007fb5416a0000000003000000040000006e616d650c00000043616665cc81205269766572080000007761746572776179050000007269766572010000007a040000006c617374030000001e000000000000000a0000000000000014000000000000002d8ab2b136cc31d99a6a2b95c0282b0d880330c2b7ffc81766fa047239972bc4"
    ),
    203: bytes.fromhex(
        "b1000000021e00000000000000030000007fb5416a00000000020000000d0000006f6666696369616c5f6e616d650500000047616d6d6104000000747970650800000077617465727761790300000000000000015a00000000000000040000006d61696e0100000000460000000000000006000000736f757263650200000002500000000000000009000000736964652c726f6c654126897117fd0515c6dd9f761aa2203284faf75debb0a115d877a470e6190dbc"
    ),
}

_POLICY_DOCUMENT = {
    "allowedDirectWaterwayValues": ["river", "stream", "canal", "tidal_channel", "wadi"],
    "areaFalseValues": ["", "0", "false", "no"],
    "displayNameDirectKeys": ["name", "int_name", "official_name"],
    "displayNameLanguagePattern": r"name:([A-Za-z]{2,3})(?:-([A-Za-z0-9]{2,8}))*\Z",
    "falseTagValues": ["", "0", "false", "no"],
    "lifecycleKeyRules": ["state", "state:waterway", "waterway:state"],
    "lifecycleStates": ["abandoned", "construction", "demolished", "disused", "proposed", "razed", "destroyed", "historic", "removed", "removal"],
    "nonLanguageNameSuffixes": ["left", "right", "signed", "pronunciation", "etymology", "source", "old"],
    "nonNfcHandling": "preserve_exact_and_audit_only",
    "relationRejectionPrecedence": [[8, "RELATION_UNSUPPORTED_TYPE"], [9, "RELATION_NO_SUPPORTED_NAME_KEY"], [10, "RELATION_SUPPORTED_NAMES_ALL_BLANK"]],
    "relationType": "waterway",
    "schema": "flight-alert-exp8-osm-planet-root-policy-v1",
    "wayGeometry": {"minimumNodeRefs": 2, "open": True},
    "wayRejectionPrecedence": [[1, "WAY_UNSUPPORTED_WATERWAY_VALUE"], [2, "WAY_INSUFFICIENT_GEOMETRY"], [3, "WAY_CLOSED"], [4, "WAY_AREA"], [5, "WAY_LIFECYCLE"], [6, "WAY_NO_SUPPORTED_NAME_KEY"], [7, "WAY_SUPPORTED_NAMES_ALL_BLANK"]],
}


def _sha(label: str) -> str:
    return hashlib.sha256(label.encode("ascii")).hexdigest()


def _way(
    object_id: int,
    *,
    version: int = 1,
    tags: tuple[tuple[str, str], ...] = (("name", "River"), ("waterway", "river")),
    refs: tuple[int, ...] = (30, 10, 20),
) -> dict[str, object]:
    return {
        "kind": "way",
        "id": object_id,
        "version": version,
        "timestamp": _TIMESTAMP,
        "tags": tags,
        "refs": refs,
    }


def _relation(
    object_id: int,
    *,
    version: int = 1,
    tags: tuple[tuple[str, str], ...] = (("name", "River"), ("type", "waterway")),
    members: tuple[tuple[str, int, str], ...] = (
        ("way", 90, "main"),
        ("node", 70, "source"),
        ("relation", 80, "tributary"),
    ),
) -> dict[str, object]:
    return {
        "kind": "relation",
        "id": object_id,
        "version": version,
        "timestamp": _TIMESTAMP,
        "tags": tags,
        "members": members,
    }


def _opl_escape(value: str) -> str:
    result: list[str] = []
    for character in value:
        codepoint = ord(character)
        if character in {" ", "\t", "\n", "\r", "%", ",", "="}:
            result.append(f"%{codepoint:02x}%")
        else:
            result.append(character)
    return "".join(result)


def _opl_object(value: dict[str, object]) -> str:
    tags = ",".join(
        f"{_opl_escape(key)}={_opl_escape(tag_value)}"
        for key, tag_value in value["tags"]
    )
    if value["kind"] == "way":
        refs = ",".join(f"n{ref}" for ref in value["refs"])
        return f"w{value['id']} v{value['version']} t{value['timestamp']} T{tags} N{refs}"
    kind = {"node": "n", "way": "w", "relation": "r"}
    members = ",".join(
        f"{kind[member_kind]}{ref}@{_opl_escape(role)}"
        for member_kind, ref, role in value["members"]
    )
    return f"r{value['id']} v{value['version']} t{value['timestamp']} T{tags} M{members}"


def _opl(values: list[dict[str, object]]) -> bytes:
    return ("\n".join(_opl_object(value) for value in values) + ("\n" if values else "")).encode("utf-8")


def _xml(values: list[dict[str, object]]) -> bytes:
    lines = ['<?xml version="1.0" encoding="UTF-8"?>', '<osm version="0.6" generator="fixture">']
    for value in values:
        kind = value["kind"]
        lines.append(
            f'  <{kind} id="{value["id"]}" version="{value["version"]}" timestamp="{value["timestamp"]}">'
        )
        if kind == "way":
            for ref in value["refs"]:
                lines.append(f'    <nd ref="{ref}"/>')
        else:
            for member_kind, ref, role in value["members"]:
                lines.append(
                    f'    <member type="{member_kind}" ref="{ref}" role="{html.escape(role, quote=True)}"/>'
                )
        for key, tag_value in value["tags"]:
            lines.append(
                f'    <tag k="{html.escape(key, quote=True)}" v="{html.escape(tag_value, quote=True)}"/>'
            )
        lines.append(f"  </{kind}>")
    lines.append("</osm>")
    return ("\n".join(lines) + "\n").encode("utf-8")


def _fixture_objects() -> tuple[list[dict[str, object]], list[dict[str, object]]]:
    decomposed = "Cafe\u0301 River"
    selected_way_10 = _way(
        10,
        version=7,
        tags=(("name", decomposed), ("waterway", "river"), ("z", "last")),
        refs=(30, 10, 20),
    )
    selected_way_20 = _way(
        20,
        version=2,
        tags=(("name:en-US", "Beta"), ("waterway", "stream")),
        refs=(2, 9),
    )
    selected_relation_30 = _relation(
        30,
        version=3,
        tags=(("official_name", "Gamma"), ("type", "waterway")),
        members=(("way", 90, "main"), ("node", 70, "source"), ("relation", 80, "side,role")),
    )
    selected = [selected_way_10, selected_way_20, selected_relation_30]
    broad = [
        _way(5, tags=(("area", "yes"), ("disused", "yes"), ("waterway", "ditch")), refs=(1,)),
        _way(6, tags=(("waterway", "river"),), refs=(1,)),
        _way(7, tags=(("area", "yes"), ("disused", "yes"), ("waterway", "river")), refs=(1, 2, 1)),
        _way(8, tags=(("area", "yes"), ("disused", "yes"), ("waterway", "river")), refs=(1, 2)),
        selected_way_10,
        _way(15, tags=(("disused", "yes"), ("waterway", "river")), refs=(1, 2)),
        _way(16, tags=(("name:old", "Former"), ("waterway", "river")), refs=(1, 2)),
        _way(17, tags=(("name", " "), ("waterway", "river")), refs=(1, 2)),
        selected_way_20,
        _relation(25, tags=(("type", "route"),), members=()),
        _relation(26, tags=(("type", "waterway"),), members=()),
        selected_relation_30,
        _relation(40, tags=(("name", " "), ("type", "waterway")), members=()),
    ]
    return selected, broad


class _ShortReadStream:
    def __init__(self, raw: bytes) -> None:
        self.raw = raw
        self.offset = 0
        self.reads = 0

    def read(self, size: int) -> bytes:
        if self.offset == len(self.raw):
            return b""
        width = min(size, (1, 7, 2, 13)[self.reads % 4])
        self.reads += 1
        result = self.raw[self.offset : self.offset + width]
        self.offset += len(result)
        return result


class _ExplodingReadStream:
    def __init__(self, raw: bytes) -> None:
        self.raw = raw
        self.read_once = False

    def read(self, size: int) -> bytes:
        if not self.read_once:
            self.read_once = True
            return self.raw[: min(size, 11)]
        raise RuntimeError("injected broad reader failure")


def _canonical_json(document: dict[str, object]) -> bytes:
    return (
        json.dumps(
            document,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
        + "\n"
    ).encode("utf-8")


def _selected_artifacts(values: list[dict[str, object]]) -> dict[str, bytes]:
    def append_text(output: bytearray, value: str) -> None:
        raw = value.encode("utf-8")
        output.extend(struct.pack("<I", len(raw)))
        output.extend(raw)

    buckets = [bytearray() for _ in range(256)]
    tuples = bytearray()
    roots = bytearray()
    epoch = int(datetime.fromisoformat(_TIMESTAMP.replace("Z", "+00:00")).timestamp())
    kind_number = {"node": 0, "way": 1, "relation": 2}
    for value in sorted(values, key=lambda item: (kind_number[item["kind"]], item["id"])):
        kind = kind_number[value["kind"]]
        payload = bytearray(
            struct.pack("<BQI", kind, value["id"], value["version"])
        )
        tags = tuple(
            sorted(value["tags"], key=lambda item: item[0].encode("utf-8"))
        )
        payload.extend(struct.pack("<qI", epoch, len(tags)))
        for key, tag_value in tags:
            append_text(payload, key)
            append_text(payload, tag_value)
        if kind == 1:
            payload.extend(struct.pack("<I", len(value["refs"])))
            for ref in value["refs"]:
                payload.extend(struct.pack("<Q", ref))
        else:
            payload.extend(struct.pack("<I", len(value["members"])))
            for ordinal, (member_kind, ref, role) in enumerate(value["members"]):
                payload.extend(
                    struct.pack("<IBQ", ordinal, kind_number[member_kind], ref)
                )
                append_text(payload, role)
        digest = hashlib.sha256(_ROOT_DOMAIN + payload).digest()
        tuples.extend(
            struct.pack("<BQI32s", kind, value["id"], value["version"], digest)
        )
        roots.extend(
            f"{'w' if kind == 1 else 'r'}{value['id']}\n".encode("ascii")
        )
        bucket = hashlib.sha256(
            _BUCKET_DOMAIN + bytes((kind,)) + struct.pack("<Q", value["id"])
        ).digest()[0]
        buckets[bucket].extend(struct.pack("<I", len(payload) + 32))
        buckets[bucket].extend(payload)
        buckets[bucket].extend(digest)
    return {
        "root-ids.txt": bytes(roots),
        "selected-tuples.bin": bytes(tuples),
        **{
            f"roots-{bucket:03d}.bin": bytes(raw)
            for bucket, raw in enumerate(buckets)
        },
    }


def _rewrite_summary(stage: Path, mutate) -> None:
    path = stage / "selection-summary.json"
    document = json.loads(path.read_bytes())
    mutate(document)
    path.write_bytes(_canonical_json(document))


def _overwrite_same_inode(path: Path, raw: bytes) -> tuple[int, int]:
    before = path.stat()
    if before.st_size != len(raw):
        raise AssertionError(
            f"same-inode test replacement changed size for {path.name}: "
            f"{before.st_size} != {len(raw)}"
        )
    with path.open("r+b") as output:
        output.seek(0)
        output.write(raw)
        output.truncate()
        output.flush()
        os.fsync(output.fileno())
    after = path.stat()
    before_id = (before.st_dev, before.st_ino)
    after_id = (after.st_dev, after.st_ino)
    if before_id != after_id:
        raise AssertionError(
            f"test overwrite replaced FileId for {path.name}: "
            f"{before_id!r} != {after_id!r}"
        )
    return after_id


@unittest.skipIf(verify_planet_roots is None, "independent verifier is not implemented yet")
class PlanetSelectionVerifierTests(unittest.TestCase):
    @staticmethod
    def _production_opl() -> bytes:
        selected, _ = _fixture_objects()
        return _opl(selected)

    def _production_stage(self, root: Path) -> Path:
        raw = self._production_opl()
        stage = root / "production"
        stage.mkdir()
        inventory: list[dict[str, object]] = []
        for bucket in range(256):
            name = f"roots-{bucket:03d}.bin"
            bucket_raw = _FROZEN_BUCKET_FRAMES.get(bucket, b"")
            (stage / name).write_bytes(bucket_raw)
            inventory.append(
                {
                    "bucket": bucket,
                    "bytes": len(bucket_raw),
                    "filename": name,
                    "records": int(bucket in _FROZEN_BUCKET_FRAMES),
                    "sha256": hashlib.sha256(bucket_raw).hexdigest(),
                }
            )
        (stage / "selected-tuples.bin").write_bytes(_SELECTED_TUPLES)
        (stage / "root-ids.txt").write_bytes(_ROOT_IDS)
        policy_raw = _canonical_json(_POLICY_DOCUMENT)
        self.assertEqual(hashlib.sha256(policy_raw).hexdigest(), _POLICY_SHA256)
        summary = {
            "artifacts": {
                "buckets": {"bucketCount": 256, "entries": inventory, "records": 3},
                "rootIds": {
                    "bytes": len(_ROOT_IDS),
                    "filename": "root-ids.txt",
                    "records": 3,
                    "sha256": hashlib.sha256(_ROOT_IDS).hexdigest(),
                },
                "selectedTuples": {
                    "bytes": len(_SELECTED_TUPLES),
                    "filename": "selected-tuples.bin",
                    "recordBytes": 45,
                    "records": 3,
                    "sha256": hashlib.sha256(_SELECTED_TUPLES).hexdigest(),
                },
            },
            "identities": {
                "candidatePbfBytes": 17,
                "candidatePbfSha256": _sha("candidate-pbf"),
                "codeSha256": _sha("production-code"),
                "convertedStreamBytes": len(raw),
                "convertedStreamFormat": "opl",
                "convertedStreamSha256": hashlib.sha256(raw).hexdigest(),
                "planetSourceSha256": _sha("source"),
                "policySha256": _POLICY_SHA256,
                "runtimeSha256": _sha("production-runtime"),
            },
            "input": {
                "bytes": len(raw),
                "objects": 3,
                "references": 8,
                "relations": 1,
                "sha256": hashlib.sha256(raw).hexdigest(),
                "tags": 7,
                "ways": 2,
            },
            "policy": {
                "bytes": len(policy_raw),
                "document": _POLICY_DOCUMENT,
                "sha256": _POLICY_SHA256,
            },
            "profile": _PRODUCTION_FIXTURE_PROFILE,
            "rejections": {
                "countsByReasonId": {},
                "ledgerSha256": _EMPTY_REJECTION_LEDGER,
                "records": 0,
            },
            "schema": "flight-alert-exp8-osm-planet-selection-summary-v2",
            "selected": {
                "nonNfcFieldCount": 1,
                "nonNfcRecordCount": 1,
                "relations": 1,
                "total": 3,
                "ways": 2,
            },
        }
        (stage / "selection-summary.json").write_bytes(_canonical_json(summary))
        return stage

    def _bindings(self, broad: bytes, input_format: str = "opl"):
        assert VerificationBindings is not None
        assert GLOBAL_POLICY_SHA256 is not None
        return VerificationBindings(
            planet_source_sha256=_sha("source"),
            broad_pbf_bytes=23,
            broad_pbf_sha256=_sha("broad-pbf"),
            converted_stream_bytes=len(broad),
            converted_stream_sha256=hashlib.sha256(broad).hexdigest(),
            converted_stream_format=input_format,
            runtime_sha256=_sha("verifier-runtime"),
            policy_sha256=GLOBAL_POLICY_SHA256,
            code_sha256=_sha("verifier-code"),
        )

    def _verify(
        self,
        broad: bytes,
        production: Path,
        work: Path,
        *,
        input_format: str = "opl",
        stream=None,
        limits=None,
        workers: int = 1,
        bindings=None,
        profile=None,
        production_stream=None,
        production_input_format: str = "opl",
    ):
        kwargs = {
            "input_format": input_format,
            "workers": workers,
            "profile": _BROAD_FIXTURE_PROFILE if profile is None else profile,
        }
        if limits is not None:
            kwargs["limits"] = limits
        return verify_planet_roots(
            stream if stream is not None else io.BytesIO(broad),
            production,
            work,
            bindings
            if bindings is not None
            else self._bindings(broad, input_format=input_format),
            production_stream=(
                io.BytesIO(self._production_opl())
                if production_stream is None
                else production_stream
            ),
            production_input_format=production_input_format,
            **kwargs,
        )

    def test_module_has_no_production_parser_or_policy_import(self) -> None:
        source = Path("tools/experiment8/osm_planet_selection_verifier.py").read_text(encoding="utf-8")
        tree = ast.parse(source)
        imported = []
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imported.extend(alias.name for alias in node.names)
            elif isinstance(node, ast.ImportFrom):
                imported.append(node.module or "")
        self.assertFalse(any(name.endswith("osm_planet_selection") for name in imported), imported)
        self.assertNotIn("from tools.experiment8.osm_planet_selection", source)

        test_tree = ast.parse(Path(__file__).read_text(encoding="utf-8"))
        production_calls = [
            node.lineno
            for node in ast.walk(test_tree)
            if isinstance(node, ast.Call)
            and isinstance(node.func, ast.Name)
            and node.func.id == "scan_planet_roots"
        ]
        self.assertEqual(production_calls, [])

    def test_frozen_fixture_hand_encodes_selected_objects_tuples_roots_and_inventory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            stage = self._production_stage(Path(temporary))
            self.assertEqual((stage / "selected-tuples.bin").read_bytes(), _SELECTED_TUPLES)
            self.assertEqual((stage / "root-ids.txt").read_bytes(), _ROOT_IDS)
            for bucket in range(256):
                self.assertEqual(
                    (stage / f"roots-{bucket:03d}.bin").read_bytes(),
                    _FROZEN_BUCKET_FRAMES.get(bucket, b""),
                )
            summary = json.loads((stage / "selection-summary.json").read_bytes())
            self.assertEqual(summary["artifacts"]["buckets"]["records"], 3)
            self.assertEqual(
                [entry["records"] for entry in summary["artifacts"]["buckets"]["entries"]],
                [int(bucket in _FROZEN_BUCKET_FRAMES) for bucket in range(256)],
            )

    def test_independent_opl_selection_external_sorts_and_exactly_matches_production(self) -> None:
        _, broad_objects = _fixture_objects()
        random.Random(0xFAE8).shuffle(broad_objects)
        broad = _opl(broad_objects)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            limits = replace(VerificationLimits(), sort_chunk_records=2)
            first = self._verify(
                broad,
                production,
                work,
                stream=_ShortReadStream(broad),
                limits=limits,
                workers=1,
            )
            second = self._verify(
                broad,
                production,
                work,
                limits=limits,
                workers=9,
            )

            self.assertEqual(first.report_bytes, second.report_bytes)
            report = json.loads(first.report_bytes)
            self.assertEqual(first.report_sha256, hashlib.sha256(first.report_bytes).hexdigest())
            self.assertEqual(report["selected"], {"nonNfcFieldCount": 1, "nonNfcRecordCount": 1, "relations": 1, "total": 3, "ways": 2})
            self.assertEqual(report["comparison"]["selectedTupleRecords"], 3)
            self.assertEqual(report["comparison"]["rootIdRecords"], 3)
            self.assertEqual(report["rejections"]["countsByReasonId"], {str(reason): 1 for reason in range(1, 11)})
            self.assertEqual(first.rejection_ledger_sha256, _BROAD_REJECTION_LEDGER)
            self.assertEqual(list(work.iterdir()), [])

    def test_production_stream_is_required_and_accepts_exact_short_reads(self) -> None:
        _, broad_objects = _fixture_objects()
        broad = _opl(broad_objects)
        production_raw = self._production_opl()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            with self.assertRaises(TypeError):
                verify_planet_roots(
                    io.BytesIO(broad),
                    production,
                    work,
                    self._bindings(broad),
                    input_format="opl",
                    profile=_BROAD_FIXTURE_PROFILE,
                )
            result = self._verify(
                broad,
                production,
                work,
                production_stream=_ShortReadStream(production_raw),
            )
            self.assertEqual(result.selected_way_count, 2)
            self.assertEqual(result.selected_relation_count, 1)
            self.assertEqual(list(work.iterdir()), [])

    def test_production_stream_identity_format_and_failures_clean_lifecycle(self) -> None:
        selected, broad_objects = _fixture_objects()
        broad = _opl(broad_objects)
        production_raw = self._production_opl()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            forged_hash = root / "forged-production-hash"
            shutil.copytree(production, forged_hash)

            def forge_hash(summary: dict[str, object]) -> None:
                summary["identities"]["convertedStreamSha256"] = "0" * 64
                summary["input"]["sha256"] = "0" * 64

            _rewrite_summary(forged_hash, forge_hash)
            format_stream = _ExplodingReadStream(production_raw)
            cases = (
                (
                    "wrong-stream",
                    production,
                    io.BytesIO(_opl([selected[0]])),
                    "opl",
                ),
                (
                    "forged-hash",
                    forged_hash,
                    io.BytesIO(production_raw),
                    "opl",
                ),
                (
                    "truncated",
                    production,
                    io.BytesIO(production_raw[:-1]),
                    "opl",
                ),
                (
                    "reader-failure",
                    production,
                    _ExplodingReadStream(production_raw),
                    "opl",
                ),
                (
                    "wrong-format",
                    production,
                    format_stream,
                    "xml",
                ),
            )
            observed_failures: dict[str, str] = {}
            for label, stage, stream, production_format in cases:
                work = root / f"work-{label}"
                work.mkdir()
                try:
                    self._verify(
                        broad,
                        stage,
                        work,
                        production_stream=stream,
                        production_input_format=production_format,
                    )
                except VerificationError as error:
                    observed_failures[label] = str(error)
                self.assertEqual(list(work.iterdir()), [])

            self.assertEqual(
                set(observed_failures),
                {label for label, *_ in cases},
            )
            for label, message in observed_failures.items():
                with self.subTest(label=label):
                    self.assertRegex(
                        message,
                        "production.*stream|production.*OPL|production.*format",
                    )
            self.assertFalse(
                format_stream.read_once,
                "invalid production format must fail before consuming either stream",
            )

    def test_reads_validates_and_regenerates_all_256_production_buckets(self) -> None:
        _, objects = _fixture_objects()
        broad = _opl(objects)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            clean = self._production_stage(root)

            def mutate_missing(stage: Path) -> None:
                (stage / "roots-000.bin").unlink()

            def mutate_truncated(stage: Path) -> None:
                path = stage / "roots-056.bin"
                path.write_bytes(path.read_bytes()[:-1])

            def mutate_extra(stage: Path) -> None:
                (stage / "roots-256.bin").write_bytes(b"extra")

            def mutate_inventory(stage: Path) -> None:
                _rewrite_summary(
                    stage,
                    lambda summary: summary["artifacts"]["buckets"]["entries"][56].__setitem__("records", 2),
                )

            def mutate_substituted(stage: Path) -> None:
                moved = (stage / "roots-056.bin").read_bytes()
                (stage / "roots-056.bin").write_bytes(b"")
                (stage / "roots-057.bin").write_bytes(moved)

                def update(summary: dict[str, object]) -> None:
                    entries = summary["artifacts"]["buckets"]["entries"]
                    for bucket, raw in ((56, b""), (57, moved)):
                        entries[bucket]["bytes"] = len(raw)
                        entries[bucket]["records"] = int(bool(raw))
                        entries[bucket]["sha256"] = hashlib.sha256(raw).hexdigest()

                _rewrite_summary(stage, update)

            cases = {
                "missing": mutate_missing,
                "truncated": mutate_truncated,
                "extra": mutate_extra,
                "inventory": mutate_inventory,
                "substituted": mutate_substituted,
            }
            for label, mutate in cases.items():
                stage = root / f"bucket-{label}"
                shutil.copytree(clean, stage)
                mutate(stage)
                work = root / f"work-{label}"
                work.mkdir()
                with self.subTest(label=label), self.assertRaisesRegex(
                    VerificationError,
                    "bucket|inventory|production stage|artifact",
                ):
                    self._verify(broad, stage, work)
                self.assertEqual(list(work.iterdir()), [])

    def test_final_production_inventory_rejects_reader_injected_extra_path(self) -> None:
        _, objects = _fixture_objects()
        broad = _opl(objects)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            extra = production / "concurrent-extra.bin"
            real_iterdir = Path.iterdir
            production_reads = 0

            def inject_on_second_production_read(path: Path):
                nonlocal production_reads
                if path == production:
                    production_reads += 1
                    if production_reads == 2:
                        extra.write_bytes(b"preserve concurrent production path")
                return real_iterdir(path)

            with mock.patch(
                "pathlib.Path.iterdir",
                autospec=True,
                side_effect=inject_on_second_production_read,
            ):
                with self.assertRaisesRegex(
                    VerificationError,
                    "final.*inventory|inventory.*changed",
                ):
                    self._verify(broad, production, work)
            self.assertGreaterEqual(production_reads, 2)
            self.assertEqual(extra.read_bytes(), b"preserve concurrent production path")

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            extra = production / "post-scratch-terminal-extra.bin"
            real_terminalize = verifier_module._VerifierScratch.terminalize

            def inject_after_scratch_terminal(scratch, expected):
                result = real_terminalize(scratch, expected)
                extra.write_bytes(b"preserve combined-terminal production path")
                return result

            with mock.patch.object(
                verifier_module._VerifierScratch,
                "terminalize",
                new=inject_after_scratch_terminal,
            ):
                with self.assertRaisesRegex(
                    VerificationError,
                    "final.*inventory|inventory.*changed",
                ):
                    self._verify(broad, production, work)
            self.assertEqual(
                extra.read_bytes(),
                b"preserve combined-terminal production path",
            )
            self.assertEqual(list(work.iterdir()), [])

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            replaced = production / "root-ids.txt"
            replacement = b"w999999\n"
            real_iterdir = Path.iterdir
            production_reads = 0
            replacement_denied = False

            def replace_artifact_on_second_production_read(path: Path):
                nonlocal production_reads, replacement_denied
                if path == production:
                    production_reads += 1
                    if production_reads == 2:
                        try:
                            replaced.unlink()
                            replaced.write_bytes(replacement)
                        except PermissionError:
                            replacement_denied = True
                return real_iterdir(path)

            failure: VerificationError | None = None
            with mock.patch(
                "pathlib.Path.iterdir",
                autospec=True,
                side_effect=replace_artifact_on_second_production_read,
            ):
                try:
                    self._verify(broad, production, work)
                except VerificationError as error:
                    failure = error
            self.assertGreaterEqual(production_reads, 2)
            if replacement_denied:
                self.assertIsNone(failure)
            else:
                self.assertIsNotNone(failure)
                assert failure is not None
                self.assertRegex(
                    str(failure),
                    "final|identity|tampered|SHA-256|semantic",
                )
            self.assertEqual(
                replaced.read_bytes(),
                _ROOT_IDS if replacement_denied else replacement,
            )
            self.assertEqual(list(work.iterdir()), [])
            self.assertEqual(list(work.iterdir()), [])

    def test_combined_terminal_rejects_new_production_hard_link(self) -> None:
        _, objects = _fixture_objects()
        broad = _opl(objects)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            alias = root / "terminal-production-hard-link.bin"
            real_terminalize = verifier_module._VerifierScratch.terminalize
            linked = False

            def add_link_after_scratch_terminal(scratch, expected):
                nonlocal linked
                result = real_terminalize(scratch, expected)
                os.link(production / "root-ids.txt", alias)
                linked = True
                return result

            with mock.patch.object(
                verifier_module._VerifierScratch,
                "terminalize",
                new=add_link_after_scratch_terminal,
            ):
                with self.assertRaisesRegex(
                    VerificationError,
                    "hard link|link count|identity|drift|changed",
                ):
                    self._verify(broad, production, work)
            self.assertTrue(linked)
            self.assertTrue(alias.exists())
            self.assertEqual(alias.read_bytes(), _ROOT_IDS)
            self.assertEqual(list(work.iterdir()), [])

    def test_identical_byte_inode_replacement_after_first_inventory_is_rejected(self) -> None:
        _, objects = _fixture_objects()
        broad = _opl(objects)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            target = production / "root-ids.txt"
            moved = root / "root-ids-from-first-inventory.bin"
            real_iterdir = Path.iterdir
            attempted = False
            replacement_ids: tuple[tuple[int, int], tuple[int, int]] | None = None

            def replace_after_first_inventory(path: Path):
                iterator = real_iterdir(path)
                if path != production or attempted:
                    return iterator

                def enumerated_then_replaced():
                    nonlocal attempted, replacement_ids
                    for entry in iterator:
                        yield entry
                    attempted = True
                    raw = target.read_bytes()
                    before = target.stat()
                    os.replace(target, moved)
                    target.write_bytes(raw)
                    after = target.stat()
                    replacement_ids = (
                        (before.st_dev, before.st_ino),
                        (after.st_dev, after.st_ino),
                    )

                return enumerated_then_replaced()

            with mock.patch(
                "pathlib.Path.iterdir",
                autospec=True,
                side_effect=replace_after_first_inventory,
            ):
                with self.assertRaisesRegex(
                    VerificationError,
                    "inventory|identity|pin|changed|replacement|denied",
                ):
                    self._verify(broad, production, work)
            self.assertTrue(attempted)
            if replacement_ids is not None:
                self.assertNotEqual(*replacement_ids)

    def test_same_inode_scratch_overwrites_before_and_after_comparisons_are_rejected(self) -> None:
        selected, broad_objects = _fixture_objects()
        changed = dict(selected[0])
        changed["version"] = selected[0]["version"] + 1
        changed_key = (changed["kind"], changed["id"])
        alternate_selected = [
            changed if (value["kind"], value["id"]) == changed_key else value
            for value in selected
        ]
        production_artifacts = _selected_artifacts(selected)
        alternate_artifacts = _selected_artifacts(alternate_selected)
        changed_bucket_name = next(
            name
            for name in sorted(production_artifacts)
            if name.startswith("roots-")
            and production_artifacts[name] != alternate_artifacts[name]
        )
        root_replacement = production_artifacts["root-ids.txt"].replace(
            b"w10\n",
            b"w11\n",
            1,
        )
        replacements = {
            "selected-tuples.bin": alternate_artifacts["selected-tuples.bin"],
            "root-ids.txt": root_replacement,
            changed_bucket_name: alternate_artifacts[changed_bucket_name],
        }
        for name, replacement in replacements.items():
            self.assertEqual(len(replacement), len(production_artifacts[name]))
            self.assertNotEqual(replacement, production_artifacts[name])
        real_compare = verifier_module._compare_files

        broad = _opl(broad_objects)
        for target_name, replacement in replacements.items():
            with self.subTest(
                transition="immediately before comparison",
                artifact=target_name,
            ):
                with tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary)
                    production = self._production_stage(root)
                    work = root / "work"
                    work.mkdir()
                    overwritten: dict[str, tuple[int, int]] = {}

                    def overwrite_before_compare(
                        expected,
                        expected_name,
                        actual,
                        label,
                        *,
                        owner=None,
                    ):
                        if expected_name == target_name:
                            try:
                                overwritten[actual.name] = _overwrite_same_inode(
                                    actual,
                                    replacement,
                                )
                            except PermissionError as error:
                                overwritten[actual.name] = (-1, -1)
                                raise VerificationError(
                                    f"scratch SEALED write denied for {actual.name}"
                                ) from error
                        return real_compare(
                            expected,
                            expected_name,
                            actual,
                            label,
                            owner=owner,
                        )

                    with mock.patch.object(
                        verifier_module,
                        "_compare_files",
                        side_effect=overwrite_before_compare,
                    ):
                        with self.assertRaisesRegex(
                            VerificationError,
                            "scratch|SEALED|sealed|changed|drift|write|comparison|identity",
                        ):
                            self._verify(broad, production, work)
                    self.assertEqual(
                        set(overwritten),
                        {f"verifier-{target_name}"},
                    )

        with self.subTest(transition="after all comparisons before report facts"):
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                production = self._production_stage(root)
                work = root / "work"
                work.mkdir()
                overwritten: dict[str, tuple[int, int]] = {}

                def overwrite_after_last_compare(
                    expected,
                    expected_name,
                    actual,
                    label,
                    *,
                    owner=None,
                ):
                    result = real_compare(
                        expected,
                        expected_name,
                        actual,
                        label,
                        owner=owner,
                    )
                    if expected_name == "roots-255.bin":
                        tuples = owner.path("verifier-selected-tuples.bin")
                        try:
                            overwritten[tuples.name] = _overwrite_same_inode(
                                tuples,
                                alternate_artifacts["selected-tuples.bin"],
                            )
                        except PermissionError as error:
                            overwritten[tuples.name] = (-1, -1)
                            raise VerificationError(
                                f"scratch SEALED terminal write denied for {tuples.name}"
                            ) from error
                    return result

                with mock.patch.object(
                    verifier_module,
                    "_compare_files",
                    side_effect=overwrite_after_last_compare,
                ):
                    with self.assertRaisesRegex(
                        VerificationError,
                        "scratch|sealed|changed|drift|write|terminal|identity",
                    ):
                        self._verify(broad, production, work)
                self.assertEqual(
                    set(overwritten),
                    {"verifier-selected-tuples.bin"},
                )

    def test_scratch_untracked_hard_links_fail_at_seal_and_cleanup(self) -> None:
        with self.subTest(transition="WRITING to SEALED"):
            with tempfile.TemporaryDirectory() as temporary:
                work = Path(temporary)
                scratch = verifier_module._VerifierScratch(work)
                outside = work / "seal-alias.bin"
                writer = scratch.create("owned.bin")
                writer.write(b"owned scratch bytes")
                os.link(scratch.path("owned.bin"), outside)
                try:
                    with self.assertRaisesRegex(
                        VerificationError,
                        "cleanup|link|WRITING|SEALED|changed|identity|ownership",
                    ):
                        scratch.seal("owned.bin", writer)
                finally:
                    scratch.__del__()
                    if outside.exists():
                        outside.unlink()

    @unittest.skipUnless(os.name == "nt", "sealed sharing is NTFS-specific")
    def test_verifier_sealed_handle_denies_new_writers(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            scratch = verifier_module._VerifierScratch(work)
            writer = scratch.create("owned.bin")
            writer.write(b"owned scratch bytes")
            scratch.seal("owned.bin", writer)
            target = scratch.path("owned.bin")
            try:
                with self.assertRaises(PermissionError):
                    target.open("r+b")
            finally:
                scratch.cleanup()

    def test_deleted_scratch_guardian_close_failure_retries_without_stale_file_id(self) -> None:
        class FailCloseOnce:
            def __init__(self, raw) -> None:
                self.raw = raw
                self.failed = False

            def close(self) -> None:
                if not self.failed:
                    self.failed = True
                    raise OSError("injected deleted guardian close failure")
                self.raw.close()

            def __getattr__(self, name: str):
                return getattr(self.raw, name)

        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            scratch = verifier_module._VerifierScratch(work)
            target = scratch.path("owned.bin")
            writer = scratch.create("owned.bin")
            writer.write(b"owned scratch bytes")
            guardian = FailCloseOnce(scratch._guardians[target])
            scratch._guardians[target] = guardian

            with self.assertRaisesRegex(
                VerificationError,
                "close deleted guardian.*injected",
            ):
                scratch.cleanup()
            self.assertFalse(target.exists())
            self.assertNotIn(target, scratch._owned_files)
            self.assertIs(scratch._guardians[target], guardian)

            scratch.cleanup()
            self.assertEqual(list(work.iterdir()), [])
            self.assertEqual(scratch._guardians, {})

        with self.subTest(transition="cleanup disposition"):
            with tempfile.TemporaryDirectory() as temporary:
                work = Path(temporary)
                scratch = verifier_module._VerifierScratch(work)
                target = scratch.path("owned.bin")
                with scratch.create("owned.bin") as output:
                    output.write(b"owned scratch bytes")
                    output.flush()
                    os.fsync(output.fileno())
                outside = work / "cleanup-alias.bin"
                real_unlink = scratch._unlink_exact

                def add_alias_during_delete(path: Path, expected_file_id) -> None:
                    if path != target:
                        return real_unlink(path, expected_file_id)
                    os.link(path, outside)
                    if os.name == "nt":
                        fd = verifier_module._open_windows_delete_fd(
                            path,
                            f"scratch artifact {path.name}",
                            directory=False,
                        )
                        try:
                            verifier_module._mark_windows_fd_for_delete(
                                fd,
                                f"scratch artifact {path.name}",
                                require_single_link=False,
                            )
                        finally:
                            os.close(fd)
                    else:
                        os.unlink(path)

                try:
                    with mock.patch.object(
                        scratch,
                        "_unlink_exact",
                        side_effect=add_alias_during_delete,
                    ):
                        with self.assertRaisesRegex(
                            VerificationError,
                            "cleanup|link|FileId|ownership",
                        ):
                            scratch.cleanup()
                    self.assertTrue(outside.exists())
                finally:
                    scratch.__del__()
                    if outside.exists():
                        outside.unlink()

    def test_production_artifacts_are_pinned_before_every_comparison(self) -> None:
        selected, broad_objects = _fixture_objects()
        frozen = _selected_artifacts(selected)
        self.assertEqual(frozen["selected-tuples.bin"], _SELECTED_TUPLES)
        self.assertEqual(frozen["root-ids.txt"], _ROOT_IDS)
        for bucket in range(256):
            self.assertEqual(
                frozen[f"roots-{bucket:03d}.bin"],
                _FROZEN_BUCKET_FRAMES.get(bucket, b""),
            )

        alternate_by_key: dict[tuple[str, int], dict[str, object]] = {}
        for value in selected:
            alternate = dict(value)
            alternate["id"] = value["id"] + 1_000
            alternate_by_key[(value["kind"], value["id"])] = alternate
        alternate_selected = list(alternate_by_key.values())
        alternate_broad = [
            alternate_by_key.get((value["kind"], value["id"]), value)
            for value in broad_objects
        ]
        broad = _opl(alternate_broad)
        alternate = _selected_artifacts(alternate_selected)
        self.assertNotEqual(
            alternate["selected-tuples.bin"],
            frozen["selected-tuples.bin"],
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            saved = root / "saved-production"
            saved.mkdir()
            alternate_stage = root / "alternate-production"
            alternate_stage.mkdir()
            for name, raw in alternate.items():
                (alternate_stage / name).write_bytes(raw)

            swapped: list[str] = []
            swap_attempted = False

            def swap_to_alternate() -> None:
                nonlocal swap_attempted
                swap_attempted = True
                for name in sorted(alternate):
                    os.replace(production / name, saved / name)
                    try:
                        os.replace(alternate_stage / name, production / name)
                    except BaseException:
                        os.replace(saved / name, production / name)
                        raise
                    swapped.append(name)

            def restore_originals() -> None:
                while swapped:
                    name = swapped.pop()
                    current = production / name
                    if current.exists():
                        os.replace(current, alternate_stage / name)
                    os.replace(saved / name, current)

            class SwapAtEof:
                def __init__(self, raw: bytes) -> None:
                    self.raw = raw
                    self.offset = 0

                def read(self, size: int) -> bytes:
                    if self.offset < len(self.raw):
                        chunk = self.raw[self.offset : self.offset + size]
                        self.offset += len(chunk)
                        return chunk
                    if not swap_attempted:
                        swap_to_alternate()
                    return b""

            real_iterdir = Path.iterdir

            def restore_before_final_inventory(path: Path):
                if path == production and swapped:
                    restore_originals()
                return real_iterdir(path)

            failure: VerificationError | None = None
            try:
                with mock.patch(
                    "pathlib.Path.iterdir",
                    autospec=True,
                    side_effect=restore_before_final_inventory,
                ):
                    self._verify(
                        broad,
                        production,
                        work,
                        stream=SwapAtEof(broad),
                    )
            except VerificationError as error:
                failure = error
            finally:
                restore_originals()

            self.assertTrue(swap_attempted)
            self.assertIsNotNone(failure)
            assert failure is not None
            self.assertRegex(str(failure), "comparison|identity|drifted|production")
            self.assertEqual((production / "selected-tuples.bin").read_bytes(), _SELECTED_TUPLES)
            self.assertEqual((production / "root-ids.txt").read_bytes(), _ROOT_IDS)
            self.assertEqual(list(work.iterdir()), [])

    def test_production_hard_link_added_after_pin_is_rejected_before_report(self) -> None:
        _, broad_objects = _fixture_objects()
        broad = _opl(broad_objects)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            outside = root / "post-pin-production-alias.bin"

            class LinkAtEof:
                def __init__(self, raw: bytes) -> None:
                    self.raw = raw
                    self.offset = 0

                def read(self, size: int) -> bytes:
                    if self.offset < len(self.raw):
                        chunk = self.raw[self.offset : self.offset + size]
                        self.offset += len(chunk)
                        return chunk
                    if not outside.exists():
                        os.link(production / "selected-tuples.bin", outside)
                    return b""

            try:
                with self.assertRaisesRegex(
                    VerificationError,
                    "link|terminal|production|identity|evidence",
                ):
                    self._verify(
                        broad,
                        production,
                        work,
                        stream=LinkAtEof(broad),
                    )
                self.assertTrue(outside.exists())
            finally:
                if outside.exists():
                    outside.unlink()

    def test_bucket_and_frame_ceilings_are_declared_and_enforced_before_reads(self) -> None:
        limits = VerificationLimits()
        self.assertIsInstance(getattr(limits, "max_production_bucket_bytes", None), int)
        self.assertIsInstance(getattr(limits, "max_production_total_bucket_bytes", None), int)
        self.assertIsInstance(getattr(limits, "max_production_frame_bytes", None), int)

        _, objects = _fixture_objects()
        broad = _opl(objects)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            bounded = replace(
                limits,
                max_production_bucket_bytes=len(_FROZEN_BUCKET_FRAMES[56]) - 1,
            )
            with self.assertRaisesRegex(VerificationError, "bucket byte ceiling"):
                self._verify(broad, production, work, limits=bounded)
            total_bucket_bytes = sum(len(raw) for raw in _FROZEN_BUCKET_FRAMES.values())
            with self.assertRaisesRegex(VerificationError, "total bucket byte ceiling"):
                self._verify(
                    broad,
                    production,
                    work,
                    limits=replace(
                        limits,
                        max_production_total_bucket_bytes=total_bucket_bytes - 1,
                    ),
                )
            largest_body = max(
                struct.unpack_from("<I", raw)[0]
                for raw in _FROZEN_BUCKET_FRAMES.values()
            )
            with self.assertRaisesRegex(VerificationError, "frame byte ceiling"):
                self._verify(
                    broad,
                    production,
                    work,
                    limits=replace(
                        limits,
                        max_production_frame_bytes=largest_body - 1,
                    ),
                )

    def test_canonical_report_bytes_ignore_raw_format_order_sort_partition_and_runtime(self) -> None:
        _, objects = _fixture_objects()
        forward = _opl(objects)
        reversed_opl = _opl(list(reversed(objects)))
        shuffled = list(objects)
        random.Random(0xC0DEC).shuffle(shuffled)
        xml = _xml(shuffled)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            results = (
                self._verify(forward, production, work, limits=replace(VerificationLimits(), sort_chunk_records=2), workers=1),
                self._verify(
                    reversed_opl,
                    production,
                    work,
                    limits=replace(VerificationLimits(), sort_chunk_records=7),
                    workers=11,
                    bindings=replace(self._bindings(reversed_opl), runtime_sha256=_sha("another-runtime")),
                ),
                self._verify(
                    xml,
                    production,
                    work,
                    input_format="xml",
                    limits=replace(VerificationLimits(), sort_chunk_records=3),
                    workers=4,
                ),
            )
            self.assertEqual(results[0].report_bytes, results[1].report_bytes)
            self.assertEqual(results[0].report_bytes, results[2].report_bytes)
            self.assertEqual(results[0].report_sha256, results[1].report_sha256)
            self.assertEqual(results[0].report_sha256, results[2].report_sha256)
            observations = [getattr(result, "observation_bytes", None) for result in results]
            self.assertTrue(all(type(value) is bytes for value in observations))
            self.assertEqual(len(set(observations)), 3)

    def test_result_is_prebuilt_before_terminal_linearization(self) -> None:
        _, objects = _fixture_objects()
        broad = _opl(objects)
        events: list[str] = []
        real_result = verifier_module.VerificationResult
        real_terminalize = verifier_module._VerifierScratch.terminalize

        def build_result(*args, **kwargs):
            events.append("result")
            return real_result(*args, **kwargs)

        def terminalize(scratch, expected):
            events.append("terminal")
            return real_terminalize(scratch, expected)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            with mock.patch.object(
                verifier_module,
                "VerificationResult",
                side_effect=build_result,
            ), mock.patch.object(
                verifier_module._VerifierScratch,
                "terminalize",
                new=terminalize,
            ):
                result = self._verify(broad, production, work)

        self.assertIsInstance(result, real_result)
        self.assertEqual(events, ["result", "terminal"])

    def test_official_opl_escape_grammar_matches_independent_unicode_rule(self) -> None:
        limits = VerificationLimits()
        decode = verifier_module._decode_opl
        self.assertEqual(decode("%A%", "fixture", limits), "\n")
        self.assertEqual(decode("%25%", "fixture", limits), "%")
        self.assertEqual(decode("%1F600%", "fixture", limits), "\U0001f600")
        self.assertEqual(decode("%000041%", "fixture", limits), "A")
        for raw in ("%0%", "%00%", "%%", "%0000041%", "%G%", "%D800%", "%110000%", "direct@ambiguous"):
            with self.subTest(raw=raw), self.assertRaises(VerificationError):
                decode(raw, "fixture", limits)

    def test_verifier_decimal_fields_are_bounded_before_int_for_opl_and_xml(self) -> None:
        huge = "9" * 5_000
        with self.assertRaises(VerificationError):
            verifier_module._positive(huge, (1 << 63) - 1, "fixture ID")
        _, objects = _fixture_objects()
        valid_opl = _opl(objects)
        valid_xml = _xml(objects)
        malformed = (
            ("opl", valid_opl.replace(b"w5 ", f"w{huge} ".encode("ascii"), 1)),
            ("opl", valid_opl.replace(b"v1 ", f"v{huge} ".encode("ascii"), 1)),
            ("opl", valid_opl.replace(b"n1", f"n{huge}".encode("ascii"), 1)),
            ("xml", valid_xml.replace(b'id="5"', f'id="{huge}"'.encode("ascii"), 1)),
            ("xml", valid_xml.replace(b'version="1"', f'version="{huge}"'.encode("ascii"), 1)),
            ("xml", valid_xml.replace(b'ref="1"', f'ref="{huge}"'.encode("ascii"), 1)),
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            for index, (input_format, broad) in enumerate(malformed):
                work = root / f"digits-{index}"
                work.mkdir()
                with self.subTest(input_format=input_format, index=index), self.assertRaises(VerificationError):
                    self._verify(broad, production, work, input_format=input_format)
                self.assertEqual(list(work.iterdir()), [])

    def test_separator_heavy_verifier_records_use_bounded_scans_and_typed_memory_errors(self) -> None:
        source = Path(verifier_module.__file__).read_text(encoding="utf-8")
        tree = ast.parse(source)
        bounded = {"_parse_opl_record", "_opl_tags"}
        split_calls = []
        for node in tree.body:
            if isinstance(node, ast.FunctionDef) and node.name in bounded:
                split_calls.extend(
                    (node.name, child.lineno)
                    for child in ast.walk(node)
                    if isinstance(child, ast.Call)
                    and isinstance(child.func, ast.Attribute)
                    and child.func.attr == "split"
                )
        self.assertEqual(split_calls, [])

        selected, _ = _fixture_objects()
        separator_heavy = dict(selected[0])
        separator_heavy["tags"] = tuple((f"k{index}", "v") for index in range(8_000))
        broad = _opl([separator_heavy])
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            limits = replace(
                VerificationLimits(),
                max_line_bytes=len(broad) - 1,
                max_tags_per_object=3,
            )
            with self.assertRaisesRegex(VerificationError, "per-object tag ceiling"):
                self._verify(broad, production, work, limits=limits)
            self.assertEqual(list(work.iterdir()), [])

            with mock.patch.object(verifier_module, "_payload", side_effect=MemoryError("injected allocation")):
                with self.assertRaisesRegex(VerificationError, "memory|allocation"):
                    self._verify(_opl(_fixture_objects()[1]), production, work)
            self.assertEqual(list(work.iterdir()), [])

    def test_early_state_allocation_memory_error_is_translated_before_scratch_creation(self) -> None:
        _, objects = _fixture_objects()
        broad = _opl(objects)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            observed: BaseException | None = None
            with mock.patch.object(
                verifier_module,
                "_BroadStats",
                side_effect=MemoryError("injected early state allocation"),
            ):
                try:
                    self._verify(broad, production, work)
                except BaseException as error:
                    observed = error
            self.assertIsInstance(observed, VerificationError)
            self.assertRegex(str(observed), "memory|allocation")
            self.assertEqual(list(work.iterdir()), [])

    def test_success_cleanup_memory_error_is_translated_after_owned_files_are_removed(self) -> None:
        _, objects = _fixture_objects()
        broad = _opl(objects)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            real_cleanup = verifier_module._VerifierScratch.cleanup

            def cleanup_then_fail(scratch) -> None:
                real_cleanup(scratch)
                raise MemoryError("injected successful cleanup allocation")

            observed: BaseException | None = None
            with mock.patch.object(
                verifier_module._VerifierScratch,
                "cleanup",
                autospec=True,
                side_effect=cleanup_then_fail,
            ):
                try:
                    self._verify(broad, production, work)
                except BaseException as error:
                    observed = error
            self.assertIsInstance(observed, VerificationError)
            self.assertRegex(str(observed), "memory|allocation")
            self.assertEqual(list(work.iterdir()), [])

    def test_shuffled_broad_order_has_identical_selection_and_rejection_ledger(self) -> None:
        _, objects = _fixture_objects()
        forward = _opl(objects)
        reverse = _opl(list(reversed(objects)))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            first = self._verify(forward, production, work)
            second = self._verify(reverse, production, work)
            first_report = json.loads(first.report_bytes)
            second_report = json.loads(second.report_bytes)
            self.assertEqual(first.rejection_ledger_sha256, second.rejection_ledger_sha256)
            self.assertEqual(first.report_bytes, second.report_bytes)
            self.assertEqual(first_report["comparison"], second_report["comparison"])
            self.assertNotEqual(
                json.loads(first.observation_bytes)["convertedStream"]["sha256"],
                json.loads(second.observation_bytes)["convertedStream"]["sha256"],
            )

    def test_xml_sax_and_opl_reproduce_exact_selected_tuples_members_and_non_nfc_audit(self) -> None:
        _, objects = _fixture_objects()
        random.Random(77).shuffle(objects)
        opl = _opl(objects)
        xml = _xml(objects)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            opl_result = self._verify(opl, production, work, input_format="opl")
            xml_result = self._verify(xml, production, work, input_format="xml", stream=_ShortReadStream(xml))
            opl_report = json.loads(opl_result.report_bytes)
            xml_report = json.loads(xml_result.report_bytes)
            self.assertEqual(opl_report["comparison"], xml_report["comparison"])
            self.assertEqual(opl_report["selected"], xml_report["selected"])
            self.assertEqual(opl_result.rejection_ledger_sha256, xml_result.rejection_ledger_sha256)

    def test_xml_sax_accepts_bounded_osmium_header_metadata_without_treating_it_as_objects(self) -> None:
        _, objects = _fixture_objects()
        raw = _xml(objects)
        header = (
            b'<bounds minlat="-90" minlon="-180" maxlat="90" maxlon="180"/>\n'
            b'<meta osm_base="2026-06-28T23:59:59Z"/>\n'
            b'<note>osmium broad fixture</note>\n'
        )
        raw = raw.replace(b'<osm version="0.6" generator="fixture">\n', b'<osm version="0.6" generator="fixture">\n' + header)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            result = self._verify(raw, production, work, input_format="xml")
            self.assertEqual(result.selected_way_count, 2)
            self.assertEqual(result.selected_relation_count, 1)

    def test_additions_omissions_and_topology_or_member_order_changes_are_fatal(self) -> None:
        _, base = _fixture_objects()
        cases: dict[str, list[dict[str, object]]] = {}
        cases["omission"] = [value for value in base if not (value["kind"] == "way" and value["id"] == 20)]
        cases["addition"] = [*base, _way(99, tags=(("name", "Extra"), ("waterway", "canal")))]
        changed_refs = [dict(value) for value in base]
        changed_refs[next(index for index, value in enumerate(changed_refs) if value["kind"] == "way" and value["id"] == 10)]["refs"] = (10, 30, 20)
        cases["way ref order"] = changed_refs
        changed_members = [dict(value) for value in base]
        relation = changed_members[next(index for index, value in enumerate(changed_members) if value["kind"] == "relation" and value["id"] == 30)]
        relation["members"] = tuple(reversed(relation["members"]))
        cases["relation member ordinal"] = changed_members
        blank = [dict(value) for value in base]
        blank[next(index for index, value in enumerate(blank) if value["kind"] == "way" and value["id"] == 20)]["tags"] = (("name", " "), ("waterway", "stream"))
        cases["blank selected name"] = blank
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            for label, values in cases.items():
                broad = _opl(values)
                with self.subTest(label=label), self.assertRaisesRegex(VerificationError, "addition|omission|tuple|root|selection"):
                    self._verify(broad, production, work)

    def test_duplicate_history_is_detected_after_external_sort(self) -> None:
        _, objects = _fixture_objects()
        duplicate = dict(next(value for value in objects if value["kind"] == "way" and value["id"] == 10))
        duplicate["version"] = 8
        values = [*objects, duplicate]
        random.Random(19).shuffle(values)
        broad = _opl(values)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            with self.assertRaisesRegex(VerificationError, "duplicate|history"):
                self._verify(broad, production, work, limits=replace(VerificationLimits(), sort_chunk_records=2))

    def test_malformed_opl_and_xml_are_fatal_without_leaving_work_files(self) -> None:
        _, objects = _fixture_objects()
        valid_opl = _opl(objects)
        valid_xml = _xml(objects)
        malformed = (
            ("opl", valid_opl.replace(b"w5 ", b"n5 ", 1)),
            ("opl", valid_opl.replace(b"v1 ", b"v0 ", 1)),
            ("opl", valid_opl.replace(_TIMESTAMP.encode(), b"2026-02-30T00:00:00Z", 1)),
            ("opl", valid_opl.replace(b"waterway=ditch", b"waterway=%zz%", 1)),
            ("opl", valid_opl.replace(b"waterway=ditch", b"waterway=ditch,waterway=river", 1)),
            ("opl", valid_opl[:-1]),
            ("opl", valid_opl.replace(b"River", b"\xff", 1)),
            ("xml", valid_xml.replace(b"<osm ", b"<!DOCTYPE osm [<!ENTITY x 'y'>]><osm ", 1)),
            ("xml", valid_xml.replace(b'id="5"', b'id="0"', 1)),
            ("xml", valid_xml.replace(b'version="1"', b'version="0"', 1)),
            ("xml", valid_xml.replace(_TIMESTAMP.encode(), b"2026-02-30T00:00:00Z", 1)),
            ("xml", valid_xml.replace(b"</way>", b'<tag k="waterway" v="river"/></way>', 1)),
            ("xml", valid_xml.replace(b"</osm>", b'<node id="1" version="1" timestamp="2026-06-28T23:59:59Z"/></osm>')),
            ("xml", valid_xml.replace(b"Gamma", b"\xff", 1)),
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            witness = work / "witness"
            witness.write_bytes(b"same")
            for input_format, broad in malformed:
                with self.subTest(input_format=input_format, digest=hashlib.sha256(broad).hexdigest()), self.assertRaises(VerificationError):
                    self._verify(broad, production, work, input_format=input_format)
                self.assertEqual({path.name: path.read_bytes() for path in work.iterdir()}, {"witness": b"same"})

    def test_hard_ceilings_and_invalid_inputs_fail_with_bounded_cleanup(self) -> None:
        assert VerificationLimits is not None
        _, objects = _fixture_objects()
        broad = _opl(objects)
        base = VerificationLimits()
        tag_heavy = _opl([
            _way(4, tags=(("a", "b"), ("c", "d"), ("name", "x"), ("waterway", "river"))),
            *objects,
        ])
        reference_heavy = _opl([_way(4, refs=(1, 2, 3, 4)), *objects])
        text_heavy = _opl([_way(4, tags=(("name", "x" * 14), ("waterway", "river"))), *objects])
        cases = (
            (broad, replace(base, max_input_bytes=len(broad) - 1), "input byte ceiling"),
            (broad, replace(base, max_line_bytes=5), "line byte ceiling"),
            (broad, replace(base, max_objects=3), "object ceiling"),
            (broad, replace(base, max_total_tags=7), "tag ceiling"),
            (broad, replace(base, max_total_references=8), "reference ceiling"),
            (tag_heavy, replace(base, max_tags_per_object=3), "per-object tag ceiling"),
            (reference_heavy, replace(base, max_references_per_object=3), "per-object reference ceiling"),
            (text_heavy, replace(base, max_text_utf8_bytes=13), "text UTF-8 byte ceiling"),
            (broad, replace(base, sort_chunk_records=2, max_sort_runs=1), "external-sort run ceiling"),
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            for case_broad, limits, message in cases:
                with self.subTest(message=message), self.assertRaisesRegex(VerificationError, message):
                    self._verify(case_broad, production, work, limits=limits)
            tuple_bytes = (production / "selected-tuples.bin").stat().st_size
            root_bytes = (production / "root-ids.txt").stat().st_size
            with self.assertRaisesRegex(VerificationError, "production tuple byte ceiling"):
                self._verify(
                    broad,
                    production,
                    work,
                    limits=replace(base, max_production_tuple_bytes=tuple_bytes - 1),
                )
            with self.assertRaisesRegex(VerificationError, "production root-ID byte ceiling"):
                self._verify(
                    broad,
                    production,
                    work,
                    limits=replace(base, max_production_root_ids_bytes=root_bytes - 1),
                )
            invalid = (
                lambda: verify_planet_roots(
                    bytearray(broad),
                    production,
                    work,
                    self._bindings(broad),
                    production_stream=io.BytesIO(self._production_opl()),
                ),
                lambda: verify_planet_roots(
                    io.BytesIO(broad),
                    production,
                    work / "missing",
                    self._bindings(broad),
                    production_stream=io.BytesIO(self._production_opl()),
                ),
                lambda: verify_planet_roots(
                    io.BytesIO(broad),
                    production,
                    work,
                    object(),
                    production_stream=io.BytesIO(self._production_opl()),
                ),
                lambda: verify_planet_roots(
                    io.BytesIO(broad),
                    production,
                    work,
                    self._bindings(broad),
                    production_stream=io.BytesIO(self._production_opl()),
                    input_format="json",
                ),
                lambda: verify_planet_roots(
                    io.BytesIO(broad),
                    production,
                    work,
                    self._bindings(broad),
                    production_stream=io.BytesIO(self._production_opl()),
                    workers=0,
                ),
                lambda: verify_planet_roots(
                    io.BytesIO(broad),
                    production,
                    work,
                    self._bindings(broad),
                    production_stream=io.BytesIO(self._production_opl()),
                    profile="flight-alert-exp8-osm-planet-broad-verifier-generic-v1",
                ),
                lambda: VerificationLimits(sort_chunk_records=0),
            )
            for call in invalid:
                with self.subTest(call=call), self.assertRaises(VerificationError):
                    call()
            reader_work = root / "reader-work"
            reader_work.mkdir()
            with self.assertRaisesRegex(VerificationError, "injected broad reader failure"):
                self._verify(
                    broad,
                    production,
                    reader_work,
                    stream=_ExplodingReadStream(broad),
                )
            self.assertEqual(list(reader_work.iterdir()), [])

    def test_production_tuple_root_summary_tampering_is_fatal(self) -> None:
        _, objects = _fixture_objects()
        broad = _opl(objects)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            clean = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            cases = {
                "tuple": ("selected-tuples.bin", lambda raw: raw + raw[:45]),
                "root": ("root-ids.txt", lambda raw: raw.replace(b"w20\n", b"")),
                "summary": ("selection-summary.json", lambda raw: raw.replace(b'"nonNfcFieldCount":1', b'"nonNfcFieldCount":0')),
            }
            for label, (filename, mutate) in cases.items():
                stage = root / f"mutated-{label}"
                shutil.copytree(clean, stage)
                path = stage / filename
                path.write_bytes(mutate(path.read_bytes()))
                with self.subTest(label=label), self.assertRaises(VerificationError):
                    self._verify(broad, stage, work)

            fabricated = root / "fabricated-production-rejection"
            shutil.copytree(clean, fabricated)

            def fabricate_rejected_input(summary: dict[str, object]) -> None:
                summary["input"]["objects"] = 4
                summary["input"]["ways"] = 3
                summary["rejections"] = {
                    "countsByReasonId": {"1": 1},
                    "ledgerSha256": "0" * 64,
                    "records": 1,
                }

            _rewrite_summary(fabricated, fabricate_rejected_input)
            with self.assertRaisesRegex(
                VerificationError,
                "production.*input|production.*rejection|production stream",
            ):
                self._verify(broad, fabricated, work)
            self.assertEqual(list(work.iterdir()), [])

        selected, _ = _fixture_objects()
        selected_way = selected[0]
        rejected_way = _way(
            6,
            tags=(("waterway", "river"),),
            refs=(1, 2),
        )
        same_stream = _opl([rejected_way, selected_way])
        selected_tuple = _SELECTED_TUPLES[:45]
        selected_root = b"w10\n"

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            clean = self._production_stage(root)
            observed_failures: dict[str, str] = {}
            for label, claimed_objects in (("ledger", 2), ("partition", 3)):
                stage = root / f"production-{label}"
                shutil.copytree(clean, stage)
                for bucket in range(256):
                    bucket_raw = (
                        _FROZEN_BUCKET_FRAMES[182]
                        if bucket == 182
                        else b""
                    )
                    (stage / f"roots-{bucket:03d}.bin").write_bytes(bucket_raw)
                (stage / "selected-tuples.bin").write_bytes(selected_tuple)
                (stage / "root-ids.txt").write_bytes(selected_root)

                def update_summary(
                    summary: dict[str, object],
                    input_objects: int = claimed_objects,
                ) -> None:
                    bucket_summary = summary["artifacts"]["buckets"]
                    bucket_summary["records"] = 1
                    for bucket, entry in enumerate(bucket_summary["entries"]):
                        bucket_raw = (
                            _FROZEN_BUCKET_FRAMES[182]
                            if bucket == 182
                            else b""
                        )
                        entry["bytes"] = len(bucket_raw)
                        entry["records"] = int(bucket == 182)
                        entry["sha256"] = hashlib.sha256(bucket_raw).hexdigest()
                    root_summary = summary["artifacts"]["rootIds"]
                    root_summary["bytes"] = len(selected_root)
                    root_summary["records"] = 1
                    root_summary["sha256"] = hashlib.sha256(selected_root).hexdigest()
                    tuple_summary = summary["artifacts"]["selectedTuples"]
                    tuple_summary["bytes"] = len(selected_tuple)
                    tuple_summary["records"] = 1
                    tuple_summary["sha256"] = hashlib.sha256(selected_tuple).hexdigest()
                    summary["identities"]["convertedStreamBytes"] = len(same_stream)
                    summary["identities"]["convertedStreamSha256"] = hashlib.sha256(
                        same_stream
                    ).hexdigest()
                    summary["input"] = {
                        "bytes": len(same_stream),
                        "objects": input_objects,
                        "references": 5,
                        "relations": 0,
                        "sha256": hashlib.sha256(same_stream).hexdigest(),
                        "tags": 4,
                        "ways": input_objects,
                    }
                    summary["rejections"] = {
                        "countsByReasonId": {"6": 1},
                        "ledgerSha256": "0" * 64,
                        "records": 1,
                    }
                    summary["selected"] = {
                        "nonNfcFieldCount": 1,
                        "nonNfcRecordCount": 1,
                        "relations": 0,
                        "total": 1,
                        "ways": 1,
                    }

                _rewrite_summary(stage, update_summary)
                work = root / f"work-{label}"
                work.mkdir()
                try:
                    self._verify(
                        same_stream,
                        stage,
                        work,
                        production_stream=io.BytesIO(same_stream),
                    )
                except VerificationError as error:
                    observed_failures[label] = str(error)
                self.assertEqual(list(work.iterdir()), [])

            self.assertEqual(set(observed_failures), {"ledger", "partition"})
            self.assertRegex(observed_failures["ledger"], "rejection.*ledger|ledger.*rejection")
            self.assertRegex(
                observed_failures["partition"],
                "input.*selected.*rejection|partition",
            )

    def test_policy_and_bucket_numeric_types_are_exact_not_python_equal(self) -> None:
        _, objects = _fixture_objects()
        broad = _opl(objects)

        def policy_map_bool_as_int(summary: dict[str, object]) -> None:
            summary["policy"]["document"]["wayGeometry"]["open"] = 1

        def policy_map_int_as_float(summary: dict[str, object]) -> None:
            summary["policy"]["document"]["wayGeometry"][
                "minimumNodeRefs"
            ] = 2.0

        def policy_list_int_as_bool(summary: dict[str, object]) -> None:
            summary["policy"]["document"]["wayRejectionPrecedence"][0][0] = True

        def policy_list_int_as_float(summary: dict[str, object]) -> None:
            summary["policy"]["document"]["relationRejectionPrecedence"][0][
                0
            ] = 8.0

        def bucket_zero_as_bool(summary: dict[str, object]) -> None:
            summary["artifacts"]["buckets"]["entries"][0]["bucket"] = False

        def bucket_one_as_float(summary: dict[str, object]) -> None:
            summary["artifacts"]["buckets"]["entries"][1]["bucket"] = 1.0

        cases = (
            ("policy-map-bool-as-int", policy_map_bool_as_int),
            ("policy-map-int-as-float", policy_map_int_as_float),
            ("policy-list-int-as-bool", policy_list_int_as_bool),
            ("policy-list-int-as-float", policy_list_int_as_float),
            ("bucket-zero-as-bool", bucket_zero_as_bool),
            ("bucket-one-as-float", bucket_one_as_float),
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            clean = self._production_stage(root)
            observed_failures: dict[str, str] = {}
            for label, mutate in cases:
                stage = root / label
                shutil.copytree(clean, stage)
                _rewrite_summary(stage, mutate)
                work = root / f"work-{label}"
                work.mkdir()
                try:
                    self._verify(broad, stage, work)
                except VerificationError as error:
                    observed_failures[label] = str(error)
                self.assertEqual(list(work.iterdir()), [])
            self.assertEqual(
                set(observed_failures),
                {label for label, _ in cases},
            )
            for label, message in observed_failures.items():
                with self.subTest(label=label):
                    self.assertRegex(message, "policy|bucket.*identity|integer")

    def test_wrong_or_generic_production_profile_is_rejected_before_broad_input(self) -> None:
        _, objects = _fixture_objects()
        broad = _opl(objects)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            clean = self._production_stage(root)
            for label, profile in (
                ("generic", "flight-alert-exp8-osm-planet-selection-generic-v1"),
                ("live", "flight-alert-exp8-osm-planet-name-envelope-260629-v1"),
            ):
                stage = root / f"profile-{label}"
                shutil.copytree(clean, stage)
                _rewrite_summary(stage, lambda summary, value=profile: summary.__setitem__("profile", value))
                work = root / f"profile-work-{label}"
                work.mkdir()
                with self.subTest(label=label), self.assertRaisesRegex(VerificationError, "production.*profile"):
                    self._verify(broad, stage, work)
                self.assertEqual(list(work.iterdir()), [])

            live = root / "live-claims"
            shutil.copytree(clean, live)
            _rewrite_summary(
                live,
                lambda summary: summary.__setitem__(
                    "profile",
                    "flight-alert-exp8-osm-planet-name-envelope-260629-v1",
                ),
            )
            work = root / "live-work"
            work.mkdir()
            with self.assertRaisesRegex(VerificationError, "live name-envelope.*summary"):
                verify_planet_roots(
                    _ExplodingReadStream(broad),
                    live,
                    work,
                    self._bindings(broad),
                    production_stream=io.BytesIO(self._production_opl()),
                    input_format="opl",
                )
            self.assertEqual(list(work.iterdir()), [])

    def test_verifier_work_directory_must_be_empty_before_start(self) -> None:
        _, objects = _fixture_objects()
        broad = _opl(objects)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            witness = work / "caller-owned"
            witness.write_bytes(b"preserve")
            with self.assertRaisesRegex(VerificationError, "work directory must be empty"):
                self._verify(broad, production, work)
            self.assertEqual({path.name: path.read_bytes() for path in work.iterdir()}, {witness.name: b"preserve"})

    def test_verifier_does_not_remove_concurrent_paths_and_surfaces_cleanup_failure(self) -> None:
        _, objects = _fixture_objects()
        broad = _opl(objects)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            real_create = verifier_module._create_atomic_binary_writer

            def collide(path: Path, label: str):
                if path.name == "run-000000.bin" and not path.exists():
                    path.write_bytes(b"concurrent")
                return real_create(path, label)

            with mock.patch.object(
                verifier_module,
                "_create_atomic_binary_writer",
                side_effect=collide,
            ):
                with self.assertRaisesRegex(VerificationError, "cleanup.*concurrent|cleanup.*not empty"):
                    self._verify(
                        broad,
                        production,
                        work,
                        limits=replace(VerificationLimits(), sort_chunk_records=2),
                    )
            preserved = list(work.rglob("run-000000.bin"))
            self.assertEqual(len(preserved), 1)
            self.assertEqual(preserved[0].read_bytes(), b"concurrent")

    def test_verifier_cleanup_failure_overrides_primary_failure_without_deleting_unknowns(self) -> None:
        _, objects = _fixture_objects()
        broad = _opl(objects)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            real_delete = verifier_module._delete_exact_owned_path

            def fail_run_unlink(
                path: Path,
                expected_file_id,
                *,
                directory,
                label,
            ):
                if not directory and path.name.startswith("run-"):
                    raise VerificationError("injected verifier cleanup failure")
                return real_delete(
                    path,
                    expected_file_id,
                    directory=directory,
                    label=label,
                )

            bad_bindings = replace(
                self._bindings(broad),
                converted_stream_sha256="0" * 64,
            )
            with mock.patch.object(
                verifier_module,
                "_delete_exact_owned_path",
                side_effect=fail_run_unlink,
            ):
                with self.assertRaisesRegex(VerificationError, "cleanup.*injected verifier cleanup failure"):
                    self._verify(
                        broad,
                        production,
                        work,
                        limits=replace(VerificationLimits(), sort_chunk_records=2),
                        bindings=bad_bindings,
                    )

    def test_cleanup_preserves_replacement_swapped_after_identity_check(self) -> None:
        replacement = b"caller-owned replacement"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            work = root / "work"
            work.mkdir()
            scratch = verifier_module._VerifierScratch(work)
            target = scratch.path("owned.bin")
            with scratch.create("owned.bin") as output:
                output.write(b"owned scratch bytes")
                output.flush()
                os.fsync(output.fileno())
            moved = root / "moved-owned.bin"
            real_stat = verifier_module.os.stat
            swapped = False

            def swap_after_identity(value, *args, **kwargs):
                nonlocal swapped
                status = real_stat(value, *args, **kwargs)
                if (
                    not swapped
                    and isinstance(value, (str, os.PathLike))
                    and Path(value) == target
                ):
                    os.replace(target, moved)
                    target.write_bytes(replacement)
                    swapped = True
                return status

            failure: VerificationError | None = None
            with mock.patch.object(
                verifier_module.os,
                "stat",
                side_effect=swap_after_identity,
            ):
                try:
                    scratch.cleanup()
                except VerificationError as error:
                    failure = error

            self.assertTrue(swapped)
            self.assertIsNotNone(failure)
            assert failure is not None
            self.assertRegex(str(failure), "cleanup|identity|replacement|not empty")
            self.assertEqual(target.read_bytes(), replacement)
            self.assertTrue(moved.exists())

    def test_scratch_replacements_are_preserved_and_cleanup_fails_closed(self) -> None:
        _, objects = _fixture_objects()
        broad = _opl(objects)
        replacement = b"caller-owned replacement"
        outcomes: dict[str, tuple[bool, bytes | None, str]] = {}

        class ReplaceRunAtEof:
            def __init__(self, raw: bytes, work: Path) -> None:
                self.raw = raw
                self.work = work
                self.offset = 0
                self.replaced_path: Path | None = None

            def read(self, size: int) -> bytes:
                if self.offset < len(self.raw):
                    chunk = self.raw[self.offset : self.offset + size]
                    self.offset += len(chunk)
                    return chunk
                if self.replaced_path is None:
                    run = next(self.work.rglob("run-000000.bin"))
                    run.unlink()
                    run.write_bytes(replacement)
                    self.replaced_path = run
                return b""

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "run-work"
            work.mkdir()
            stream = ReplaceRunAtEof(broad, work)
            failure: VerificationError | None = None
            try:
                self._verify(
                    broad,
                    production,
                    work,
                    stream=stream,
                    limits=replace(VerificationLimits(), sort_chunk_records=2),
                    bindings=replace(
                        self._bindings(broad),
                        converted_stream_sha256="0" * 64,
                    ),
                )
            except VerificationError as error:
                failure = error
            self.assertIsNotNone(failure)
            self.assertIsNotNone(stream.replaced_path)
            assert stream.replaced_path is not None
            assert failure is not None
            run_exists = stream.replaced_path.exists()
            outcomes["run"] = (
                run_exists,
                stream.replaced_path.read_bytes() if run_exists else None,
                str(failure),
            )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "spool-work"
            work.mkdir()
            real_create = verifier_module._create_atomic_binary_writer
            replaced_spool: Path | None = None

            def replace_spool_when_outputs_start(path: Path, label: str):
                nonlocal replaced_spool
                if (
                    path.name == "verifier-selected-tuples.bin"
                    and replaced_spool is None
                ):
                    spool = next(work.rglob("selected-object-frames.bin"))
                    spool.unlink()
                    spool.write_bytes(replacement)
                    replaced_spool = spool
                return real_create(path, label)

            failure = None
            with mock.patch.object(
                verifier_module,
                "_create_atomic_binary_writer",
                side_effect=replace_spool_when_outputs_start,
            ):
                try:
                    self._verify(
                        broad,
                        production,
                        work,
                        limits=replace(VerificationLimits(), sort_chunk_records=2),
                    )
                except VerificationError as error:
                    failure = error
            self.assertIsNotNone(failure)
            self.assertIsNotNone(replaced_spool)
            assert replaced_spool is not None
            assert failure is not None
            spool_exists = replaced_spool.exists()
            outcomes["spool"] = (
                spool_exists,
                replaced_spool.read_bytes() if spool_exists else None,
                str(failure),
            )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "output-work"
            work.mkdir()
            real_open = Path.open
            replaced_output: Path | None = None

            def replace_output_before_comparison(path: Path, *args, **kwargs):
                nonlocal replaced_output
                mode = args[0] if args else kwargs.get("mode", "r")
                if (
                    path.name == "verifier-selected-tuples.bin"
                    and mode == "rb"
                    and replaced_output is None
                ):
                    path.unlink()
                    path.write_bytes(replacement)
                    replaced_output = path
                return real_open(path, *args, **kwargs)

            failure = None
            with mock.patch(
                "pathlib.Path.open",
                autospec=True,
                side_effect=replace_output_before_comparison,
            ):
                try:
                    self._verify(
                        broad,
                        production,
                        work,
                        limits=replace(VerificationLimits(), sort_chunk_records=2),
                    )
                except VerificationError as error:
                    failure = error
            self.assertIsNotNone(failure)
            self.assertIsNotNone(replaced_output)
            assert replaced_output is not None
            assert failure is not None
            output_exists = replaced_output.exists()
            outcomes["output"] = (
                output_exists,
                replaced_output.read_bytes() if output_exists else None,
                str(failure),
            )

        self.assertEqual(set(outcomes), {"run", "spool", "output"})
        for label, (exists, raw, message) in outcomes.items():
            with self.subTest(label=label):
                self.assertTrue(exists)
                self.assertEqual(raw, replacement)
                self.assertRegex(
                    message,
                    "cleanup.*identity|cleanup.*replaced|not empty",
                )

    def test_scratch_constructor_first_status_failure_cleans_exact_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            real_fstat = verifier_module.os.fstat
            calls = 0

            def fail_first_held_status(fd):
                nonlocal calls
                calls += 1
                if calls == 1:
                    raise MemoryError("injected scratch directory status allocation")
                return real_fstat(fd)

            with mock.patch.object(
                verifier_module.os,
                "fstat",
                side_effect=fail_first_held_status,
            ):
                with self.assertRaisesRegex(MemoryError, "directory status"):
                    verifier_module._VerifierScratch(work)
            self.assertEqual(list(work.iterdir()), [])

    def test_scratch_constructor_tracking_and_directory_identity_are_transactional(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            real_file_id = verifier_module._file_id
            calls = 0

            def fail_first_identity(status):
                nonlocal calls
                calls += 1
                if calls == 1:
                    raise MemoryError("injected scratch directory identity allocation")
                return real_file_id(status)

            with mock.patch.object(
                verifier_module,
                "_file_id",
                side_effect=fail_first_identity,
            ):
                with self.assertRaisesRegex(MemoryError, "directory identity"):
                    verifier_module._VerifierScratch(work)
            self.assertEqual(list(work.iterdir()), [])

        class FailingOwnership(dict[Path, tuple[int, int]]):
            def __setitem__(self, key: Path, value: tuple[int, int]) -> None:
                del key, value
                raise MemoryError("injected scratch file ownership allocation")

        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            scratch = verifier_module._VerifierScratch(work)
            scratch._owned_files = FailingOwnership()
            with self.assertRaisesRegex(MemoryError, "file ownership"):
                scratch.create("partial.bin")
            self.assertEqual(list(scratch.directory.iterdir()), [])
            scratch.cleanup()
            self.assertEqual(list(work.iterdir()), [])

        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            scratch = verifier_module._VerifierScratch(work)
            original = scratch.directory
            moved = work / "moved-owned-scratch"
            original.rename(moved)
            original.mkdir()
            with self.assertRaisesRegex(
                VerificationError,
                "directory identity.*preserved|identity changed",
            ):
                scratch.cleanup()
            self.assertTrue(original.is_dir())
            self.assertTrue(moved.is_dir())

    def test_writer_wrapper_allocation_failure_cleans_atomic_scratch_file(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            scratch = verifier_module._VerifierScratch(work)
            try:
                with mock.patch.object(
                    verifier_module,
                    "_TrackedWriter",
                    side_effect=MemoryError("injected writer wrapper allocation"),
                ):
                    with self.assertRaisesRegex(
                        MemoryError,
                        "writer wrapper allocation",
                    ):
                        scratch.create("partial.bin")
                self.assertEqual(list(scratch.directory.iterdir()), [])
            finally:
                scratch.cleanup()

    @unittest.skipUnless(os.name == "nt", "native atomic creation is Windows-specific")
    def test_native_atomic_scratch_creation_cleans_buffer_wrapper_failure(self) -> None:
        real_fdopen = verifier_module.os.fdopen

        def fail_write_wrapper(fd, mode="r", *args, **kwargs):
            if mode == "wb":
                raise MemoryError("injected buffered writer allocation")
            return real_fdopen(fd, mode, *args, **kwargs)

        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            scratch = verifier_module._VerifierScratch(work)
            try:
                with mock.patch.object(
                    verifier_module.os,
                    "fdopen",
                    side_effect=fail_write_wrapper,
                ):
                    with self.assertRaisesRegex(
                        MemoryError,
                        "buffered writer allocation",
                    ):
                        scratch.create("partial.bin")
                self.assertEqual(list(scratch.directory.iterdir()), [])
            finally:
                scratch.cleanup()

    @unittest.skipUnless(os.name == "nt", "native atomic creation is Windows-specific")
    def test_nt_create_return_conversion_failure_deletes_preallocated_scratch_handle(self) -> None:
        import ctypes

        real_win_dll = ctypes.WinDLL
        real_ntdll = real_win_dll("ntdll", use_last_error=True)

        class RaiseAfterCreate:
            argtypes = None
            restype = None

            def __call__(self, *args):
                real = real_ntdll.NtCreateFile
                real.argtypes = self.argtypes
                real.restype = self.restype
                status = real(*args)
                if status >= 0:
                    raise MemoryError("injected NtCreateFile return conversion")
                return status

        class FakeNtdll:
            NtCreateFile = RaiseAfterCreate()
            RtlNtStatusToDosError = real_ntdll.RtlNtStatusToDosError

        def fake_win_dll(name, *args, **kwargs):
            if str(name).lower() == "ntdll":
                return FakeNtdll()
            return real_win_dll(name, *args, **kwargs)

        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "return-conversion.bin"
            with mock.patch.object(
                ctypes,
                "WinDLL",
                side_effect=fake_win_dll,
            ):
                with self.assertRaisesRegex(
                    MemoryError,
                    "NtCreateFile return conversion",
                ):
                    verifier_module._create_atomic_binary_writer(path, "fixture")
            self.assertFalse(path.exists())

    @unittest.skipUnless(os.name == "nt", "native atomic creation is Windows-specific")
    def test_atomic_scratch_file_retry_retains_secondary_fd_after_disposition_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "retained-atomic.bin"
            with mock.patch.object(
                verifier_module.os,
                "fdopen",
                side_effect=MemoryError("injected buffer allocation"),
            ), mock.patch.object(
                verifier_module,
                "_mark_windows_native_handle_for_delete",
                side_effect=MemoryError("injected disposition allocation"),
            ):
                with self.assertRaises(verifier_module._UnreleasedAtomicFile) as caught:
                    verifier_module._create_atomic_binary_writer(path, "fixture")

            retained = caught.exception
            self.assertGreater(retained.native_handle, 0)
            self.assertGreaterEqual(retained.extra_fd, 0)
            retained.retry_cleanup()
            self.assertEqual(retained.native_handle, 0)
            self.assertEqual(retained.extra_fd, -1)
            self.assertEqual(retained.extra_native_handle, 0)
            self.assertIsNone(retained.wrapped_handle)
            self.assertFalse(path.exists())

    def test_atomic_scratch_creation_does_not_use_high_level_open_create_window(self) -> None:
        real_open = Path.open
        attempted = False

        def create_then_fail(path: Path, *args, **kwargs):
            nonlocal attempted
            mode = args[0] if args else kwargs.get("mode", "r")
            if mode == "xb":
                attempted = True
                created = real_open(path, *args, **kwargs)
                created.close()
                raise MemoryError("injected high-level open wrapper allocation")
            return real_open(path, *args, **kwargs)

        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            scratch = verifier_module._VerifierScratch(work)
            try:
                with mock.patch(
                    "pathlib.Path.open",
                    autospec=True,
                    side_effect=create_then_fail,
                ):
                    writer = scratch.create("owned.bin")
                    writer.write(b"owned")
                self.assertFalse(attempted)
            finally:
                scratch.cleanup()
            self.assertEqual(list(work.iterdir()), [])

    def test_scratch_directory_creation_cannot_claim_a_post_create_replacement(self) -> None:
        nonce = "ab" * 16
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            expected = work / f"fae8-osm-verify-{nonce}"
            moved = work / "moved-created-scratch"
            real_stat = verifier_module.os.stat
            swapped = False

            def swap_before_first_path_validation(value, *args, **kwargs):
                nonlocal swapped
                if (
                    not swapped
                    and isinstance(value, (str, os.PathLike))
                    and Path(value) == expected
                ):
                    expected.rename(moved)
                    expected.mkdir()
                    swapped = True
                return real_stat(value, *args, **kwargs)

            with mock.patch.object(
                verifier_module.secrets,
                "token_hex",
                return_value=nonce,
            ), mock.patch.object(
                verifier_module.os,
                "stat",
                side_effect=swap_before_first_path_validation,
            ):
                with self.assertRaisesRegex(
                    VerificationError,
                    "creation|directory|identity|replacement|cleanup|FileId",
                ):
                    verifier_module._VerifierScratch(work)

            self.assertTrue(swapped)
            self.assertTrue(moved.is_dir())
            self.assertTrue(expected.is_dir())

    @unittest.skipUnless(os.name == "nt", "native directory wrapping is NTFS-specific")
    def test_atomic_scratch_directory_wrap_failure_deletes_exact_created_directory(self) -> None:
        import msvcrt

        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            with mock.patch.object(
                msvcrt,
                "open_osfhandle",
                side_effect=OSError("injected directory handle wrapping failure"),
            ):
                with self.assertRaisesRegex(
                    VerificationError,
                    "creation|wrapping|cleanup|directory",
                ):
                    verifier_module._VerifierScratch(work)
            self.assertEqual(list(work.iterdir()), [])

    @unittest.skipUnless(os.name == "nt", "native directory creation is NTFS-specific")
    def test_directory_nt_create_return_conversion_failure_deletes_preallocated_handle(self) -> None:
        import ctypes

        real_win_dll = ctypes.WinDLL
        real_ntdll = real_win_dll("ntdll", use_last_error=True)

        class RaiseAfterCreate:
            argtypes = None
            restype = None

            def __call__(self, *args):
                real = real_ntdll.NtCreateFile
                real.argtypes = self.argtypes
                real.restype = self.restype
                status = real(*args)
                if status >= 0:
                    raise MemoryError("injected directory NtCreateFile return conversion")
                return status

        class FakeNtdll:
            NtCreateFile = RaiseAfterCreate()
            RtlNtStatusToDosError = real_ntdll.RtlNtStatusToDosError

        def fake_win_dll(name, *args, **kwargs):
            if str(name).lower() == "ntdll":
                return FakeNtdll()
            return real_win_dll(name, *args, **kwargs)

        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "return-conversion-directory"
            with mock.patch.object(
                ctypes,
                "WinDLL",
                side_effect=fake_win_dll,
            ):
                with self.assertRaisesRegex(
                    MemoryError,
                    "directory NtCreateFile return conversion",
                ):
                    verifier_module._create_held_directory(path, "fixture")
            self.assertFalse(path.exists())

    @unittest.skipUnless(os.name == "nt", "native directory handles are NTFS-specific")
    def test_held_directory_close_failure_keeps_retryable_fd(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "held-directory"
            held = verifier_module._create_held_directory(path, "fixture")
            fd = held.fileno()
            real_close = verifier_module.os.close
            failed = False

            def fail_once(value: int) -> None:
                nonlocal failed
                if value == fd and not failed:
                    failed = True
                    raise OSError("injected held-directory close failure")
                real_close(value)

            with mock.patch.object(
                verifier_module.os,
                "close",
                side_effect=fail_once,
            ):
                with self.assertRaisesRegex(OSError, "held-directory close failure"):
                    held.close()
            self.assertEqual(held.fileno(), fd)
            held.close()
            path.rmdir()

    @unittest.skipUnless(os.name == "nt", "native directory cleanup is NTFS-specific")
    def test_directory_allocation_and_disposition_failure_retains_retryable_fd(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "retained-directory"
            with mock.patch.object(
                verifier_module,
                "_HeldDirectory",
                side_effect=MemoryError("injected held-directory allocation"),
            ), mock.patch.object(
                verifier_module,
                "_mark_windows_fd_for_delete",
                side_effect=MemoryError("injected directory disposition allocation"),
            ):
                with self.assertRaises(
                    verifier_module._UnreleasedNativeDirectory
                ) as caught:
                    verifier_module._create_held_directory(path, "fixture")

            retained = caught.exception
            self.assertGreaterEqual(retained.fd, 0)
            retained.retry_cleanup()
            self.assertEqual(retained.fd, -1)
            self.assertEqual(retained.native_handle, 0)
            self.assertFalse(path.exists())

    def test_scratch_tracking_and_deletion_failure_retains_exact_file_ownership(self) -> None:
        class TrackThenFail(dict[Path, tuple[int, int]]):
            def __init__(self) -> None:
                super().__init__()
                self.attempted_file_id: tuple[int, int] | None = None

            def __setitem__(self, key: Path, value: tuple[int, int]) -> None:
                del key
                self.attempted_file_id = value
                raise MemoryError("injected scratch ownership allocation")

        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            scratch = verifier_module._VerifierScratch(work)
            ownership = TrackThenFail()
            scratch._owned_files = ownership
            try:
                with mock.patch.object(
                    scratch,
                    "_unlink_exact",
                    side_effect=VerificationError(
                        "injected missing moved scratch FileId"
                    ),
                ):
                    with self.assertRaisesRegex(
                        VerificationError,
                        "cleanup|FileId|identity|missing|moved|ownership",
                    ):
                        scratch.create("pending.bin")

                self.assertIsNotNone(ownership.attempted_file_id)
                self.assertEqual(
                    getattr(scratch, "_pending_file_id", None),
                    ownership.attempted_file_id,
                )
                self.assertIsNotNone(
                    getattr(scratch, "_pending_guardian", None),
                )
            finally:
                scratch.__del__()

    def test_cleanup_never_succeeds_while_renamed_scratch_objects_survive(self) -> None:
        def assert_deleted_or_failed(scratch, moved: Path) -> None:
            failure: VerificationError | None = None
            try:
                scratch.cleanup()
            except VerificationError as error:
                failure = error
            if moved.exists():
                self.assertIsNotNone(failure)
                assert failure is not None
                self.assertRegex(
                    str(failure),
                    "cleanup|FileId|identity|unreleased|missing|disappeared",
                )

        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            scratch = verifier_module._VerifierScratch(work)
            target = scratch.path("owned.bin")
            with scratch.create("owned.bin") as output:
                output.write(b"owned scratch bytes")
                output.flush()
                os.fsync(output.fileno())
            moved = work / "renamed-owned-scratch-file.bin"
            target.rename(moved)
            assert_deleted_or_failed(scratch, moved)

        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            scratch = verifier_module._VerifierScratch(work)
            moved = work / "renamed-owned-scratch-directory"
            scratch.directory.rename(moved)
            assert_deleted_or_failed(scratch, moved)

    def test_live_broad_identity_is_exact_and_separate_from_name_envelope(self) -> None:
        self.assertEqual(
            getattr(verifier_module, "LIVE_PLANET_SOURCE_SHA256", None),
            _AUTHORITATIVE_PLANET_SHA256,
        )
        assert LIVE_BROAD_ENVELOPE_PBF_BYTES == 1_566_751_189
        assert LIVE_BROAD_ENVELOPE_PBF_SHA256 == "0cb9c478ce621eedf4a889b72285da49822b48961212913bb95117be76757381"
        assert LIVE_BROAD_ENVELOPE_OPL_BYTES == 16_008_341_070
        assert LIVE_BROAD_ENVELOPE_OPL_SHA256 == "dc11fd5cd430cec1aaa018a0cdce34ade1dee0e9f46ba8349e66e0c249850468"
        assert LIVE_BROAD_ENVELOPE_CONVERTED_STREAM_FORMAT == "opl"
        assert LIVE_BROAD_ENVELOPE_WAYS == 39_187_055
        assert LIVE_BROAD_ENVELOPE_RELATIONS == 145_045
        assert GLOBAL_POLICY_SHA256 == _POLICY_SHA256
        defaults = VerificationLimits()
        self.assertEqual(defaults.max_input_bytes, 16 * 1024 * 1024 * 1024)
        self.assertGreater(defaults.max_input_bytes, LIVE_BROAD_ENVELOPE_OPL_BYTES)
        self.assertGreaterEqual(
            defaults.sort_chunk_records * defaults.max_sort_runs,
            LIVE_BROAD_ENVELOPE_WAYS + LIVE_BROAD_ENVELOPE_RELATIONS,
        )
        _, objects = _fixture_objects()
        broad = _opl(objects)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            with self.assertRaisesRegex(VerificationError, "production.*profile"):
                self._verify(broad, production, work, profile=LIVE_BROAD_ENVELOPE_PROFILE)
            with self.assertRaisesRegex(VerificationError, "converted stream"):
                self._verify(
                    broad,
                    production,
                    work,
                    bindings=replace(
                        self._bindings(broad),
                        converted_stream_sha256="0" * 64,
                    ),
                )

    def test_live_boundary_accepts_separate_exact_broad_pbf_and_canonical_stream(self) -> None:
        required_fields = {
            "planet_source_sha256",
            "broad_pbf_bytes",
            "broad_pbf_sha256",
            "converted_stream_bytes",
            "converted_stream_sha256",
            "converted_stream_format",
        }
        self.assertTrue(
            required_fields.issubset(VerificationBindings.__dataclass_fields__),
            "verification bindings must distinguish broad PBF facts from converted stream facts",
        )
        _, objects = _fixture_objects()
        broad = _opl(objects)
        broad_pbf = b"locked broad PBF fixture"
        bindings = VerificationBindings(
            planet_source_sha256=_AUTHORITATIVE_PLANET_SHA256,
            broad_pbf_bytes=len(broad_pbf),
            broad_pbf_sha256=hashlib.sha256(broad_pbf).hexdigest(),
            converted_stream_bytes=len(broad),
            converted_stream_sha256=hashlib.sha256(broad).hexdigest(),
            converted_stream_format="opl",
            runtime_sha256=_sha("verifier-runtime"),
            policy_sha256=GLOBAL_POLICY_SHA256,
            code_sha256=_sha("verifier-code"),
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            _rewrite_summary(
                production,
                lambda summary: (
                    summary.__setitem__(
                        "profile",
                        "flight-alert-exp8-osm-planet-name-envelope-260629-v1",
                    ),
                    summary["identities"].__setitem__(
                        "planetSourceSha256",
                        _AUTHORITATIVE_PLANET_SHA256,
                    ),
                ),
            )
            production_summary = json.loads(
                (production / "selection-summary.json").read_bytes()
            )
            production_identities = production_summary["identities"]
            production_input = production_summary["input"]
            work = root / "work"
            work.mkdir()
            locked = {
                "LIVE_BROAD_ENVELOPE_PBF_BYTES": len(broad_pbf),
                "LIVE_BROAD_ENVELOPE_PBF_SHA256": hashlib.sha256(broad_pbf).hexdigest(),
                "LIVE_BROAD_ENVELOPE_OPL_BYTES": len(broad),
                "LIVE_BROAD_ENVELOPE_OPL_SHA256": hashlib.sha256(broad).hexdigest(),
                "LIVE_BROAD_ENVELOPE_CONVERTED_STREAM_FORMAT": "opl",
                "LIVE_BROAD_ENVELOPE_WAYS": sum(
                    value["kind"] == "way" for value in objects
                ),
                "LIVE_BROAD_ENVELOPE_RELATIONS": sum(
                    value["kind"] == "relation" for value in objects
                ),
                "_LIVE_PRODUCTION_NAME_ENVELOPE_PBF_BYTES": production_identities[
                    "candidatePbfBytes"
                ],
                "_LIVE_PRODUCTION_NAME_ENVELOPE_PBF_SHA256": production_identities[
                    "candidatePbfSha256"
                ],
                "_LIVE_PRODUCTION_NAME_ENVELOPE_OPL_BYTES": production_input["bytes"],
                "_LIVE_PRODUCTION_NAME_ENVELOPE_OPL_SHA256": production_input["sha256"],
                "_LIVE_PRODUCTION_NAME_ENVELOPE_CONVERTED_STREAM_FORMAT": "opl",
                "_LIVE_PRODUCTION_NAME_ENVELOPE_WAYS": production_input["ways"],
                "_LIVE_PRODUCTION_NAME_ENVELOPE_RELATIONS": production_input[
                    "relations"
                ],
            }
            with mock.patch.multiple(verifier_module, create=True, **locked):
                result = self._verify(
                    broad,
                    production,
                    work,
                    bindings=bindings,
                    profile=LIVE_BROAD_ENVELOPE_PROFILE,
                )
            self.assertEqual(result.planet_source_sha256, bindings.planet_source_sha256)
            self.assertEqual(result.broad_pbf_bytes, len(broad_pbf))
            self.assertEqual(result.broad_pbf_sha256, bindings.broad_pbf_sha256)
            self.assertEqual(result.converted_stream_bytes, len(broad))
            self.assertEqual(result.converted_stream_sha256, bindings.converted_stream_sha256)
            self.assertEqual(result.converted_stream_format, "opl")
            report = json.loads(result.report_bytes)
            self.assertEqual(
                report["identities"],
                {
                    "broadPbfBytes": len(broad_pbf),
                    "broadPbfSha256": bindings.broad_pbf_sha256,
                    "planetSourceSha256": bindings.planet_source_sha256,
                    "policySha256": bindings.policy_sha256,
                },
            )
            observation = json.loads(result.observation_bytes)
            self.assertEqual(
                observation["convertedStream"],
                {
                    "bytes": len(broad),
                    "format": "opl",
                    "sha256": bindings.converted_stream_sha256,
                },
            )

    def test_live_boundary_rejects_wrong_pbf_stream_and_noncanonical_format(self) -> None:
        _, objects = _fixture_objects()
        broad = _opl(objects)
        broad_xml = _xml(objects)
        broad_pbf = b"locked broad PBF fixture"
        broad_pbf_sha256 = hashlib.sha256(broad_pbf).hexdigest()
        bindings = VerificationBindings(
            planet_source_sha256=_AUTHORITATIVE_PLANET_SHA256,
            broad_pbf_bytes=len(broad_pbf),
            broad_pbf_sha256=broad_pbf_sha256,
            converted_stream_bytes=len(broad),
            converted_stream_sha256=hashlib.sha256(broad).hexdigest(),
            converted_stream_format="opl",
            runtime_sha256=_sha("verifier-runtime"),
            policy_sha256=GLOBAL_POLICY_SHA256,
            code_sha256=_sha("verifier-code"),
        )
        xml_bindings = replace(
            self._bindings(broad_xml, input_format="xml"),
            planet_source_sha256=bindings.planet_source_sha256,
            broad_pbf_bytes=len(broad_pbf),
            broad_pbf_sha256=broad_pbf_sha256,
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            _rewrite_summary(
                production,
                lambda summary: (
                    summary.__setitem__(
                        "profile",
                        "flight-alert-exp8-osm-planet-name-envelope-260629-v1",
                    ),
                    summary["identities"].__setitem__(
                        "planetSourceSha256",
                        _AUTHORITATIVE_PLANET_SHA256,
                    ),
                ),
            )
            production_summary = json.loads(
                (production / "selection-summary.json").read_bytes()
            )
            production_identities = production_summary["identities"]
            production_input = production_summary["input"]
            work = root / "work"
            work.mkdir()
            locked = {
                "LIVE_BROAD_ENVELOPE_PBF_BYTES": len(broad_pbf),
                "LIVE_BROAD_ENVELOPE_PBF_SHA256": broad_pbf_sha256,
                "LIVE_BROAD_ENVELOPE_OPL_BYTES": len(broad),
                "LIVE_BROAD_ENVELOPE_OPL_SHA256": hashlib.sha256(broad).hexdigest(),
                "LIVE_BROAD_ENVELOPE_CONVERTED_STREAM_FORMAT": "opl",
                "LIVE_BROAD_ENVELOPE_WAYS": sum(
                    value["kind"] == "way" for value in objects
                ),
                "LIVE_BROAD_ENVELOPE_RELATIONS": sum(
                    value["kind"] == "relation" for value in objects
                ),
                "_LIVE_PRODUCTION_NAME_ENVELOPE_PBF_BYTES": production_identities[
                    "candidatePbfBytes"
                ],
                "_LIVE_PRODUCTION_NAME_ENVELOPE_PBF_SHA256": production_identities[
                    "candidatePbfSha256"
                ],
                "_LIVE_PRODUCTION_NAME_ENVELOPE_OPL_BYTES": production_input["bytes"],
                "_LIVE_PRODUCTION_NAME_ENVELOPE_OPL_SHA256": production_input["sha256"],
                "_LIVE_PRODUCTION_NAME_ENVELOPE_CONVERTED_STREAM_FORMAT": "opl",
                "_LIVE_PRODUCTION_NAME_ENVELOPE_WAYS": production_input["ways"],
                "_LIVE_PRODUCTION_NAME_ENVELOPE_RELATIONS": production_input[
                    "relations"
                ],
            }
            cases = (
                (
                    broad,
                    "opl",
                    replace(
                        bindings,
                        planet_source_sha256=_sha("wrong-planet-source"),
                    ),
                    "planet source",
                ),
                (
                    broad,
                    "opl",
                    replace(bindings, broad_pbf_bytes=len(broad_pbf) + 1),
                    "broad PBF",
                ),
                (
                    broad,
                    "opl",
                    replace(bindings, broad_pbf_sha256="0" * 64),
                    "broad PBF",
                ),
                (
                    broad,
                    "opl",
                    replace(bindings, converted_stream_bytes=len(broad) + 1),
                    "converted stream",
                ),
                (
                    broad,
                    "opl",
                    replace(bindings, converted_stream_sha256="0" * 64),
                    "converted stream",
                ),
                (broad_xml, "xml", xml_bindings, "format|canonical OPL"),
            )
            with mock.patch.multiple(verifier_module, create=True, **locked):
                for raw, input_format, bad_bindings, message in cases:
                    with self.subTest(message=message), self.assertRaisesRegex(
                        VerificationError,
                        message,
                    ):
                        self._verify(
                            raw,
                            production,
                            work,
                            input_format=input_format,
                            bindings=bad_bindings,
                            profile=LIVE_BROAD_ENVELOPE_PROFILE,
                        )
                    self.assertEqual(list(work.iterdir()), [])

            wrong_planet = _sha("wrong-planet-source")
            wrong_bindings = replace(
                bindings,
                planet_source_sha256=wrong_planet,
            )
            wrong_production = root / "wrong-planet-production"
            shutil.copytree(production, wrong_production)
            _rewrite_summary(
                wrong_production,
                lambda summary: summary["identities"].__setitem__(
                    "planetSourceSha256",
                    wrong_planet,
                ),
            )
            wrong_work = root / "wrong-planet-work"
            wrong_work.mkdir()
            with mock.patch.multiple(verifier_module, create=True, **locked):
                with self.assertRaisesRegex(VerificationError, "planet source"):
                    self._verify(
                        broad,
                        wrong_production,
                        wrong_work,
                        stream=_ExplodingReadStream(broad),
                        bindings=wrong_bindings,
                        profile=LIVE_BROAD_ENVELOPE_PROFILE,
                    )
            self.assertEqual(list(wrong_work.iterdir()), [])

            real_validate_production = verifier_module._validate_production

            def bypass_only_production_planet_binding(
                stage,
                pinned,
                summary,
                supplied_bindings,
                limits,
                expected_profile,
            ):
                return real_validate_production(
                    stage,
                    pinned,
                    summary,
                    replace(
                        supplied_bindings,
                        planet_source_sha256=_AUTHORITATIVE_PLANET_SHA256,
                    ),
                    limits,
                    expected_profile,
                )

            broad_work = root / "wrong-broad-planet-work"
            broad_work.mkdir()
            with mock.patch.multiple(verifier_module, create=True, **locked):
                with mock.patch.object(
                    verifier_module,
                    "_validate_production",
                    side_effect=bypass_only_production_planet_binding,
                ):
                    with self.assertRaisesRegex(VerificationError, "planet source"):
                        self._verify(
                            broad,
                            production,
                            broad_work,
                            bindings=wrong_bindings,
                            profile=LIVE_BROAD_ENVELOPE_PROFILE,
                        )
            self.assertEqual(list(broad_work.iterdir()), [])

    def test_live_profile_is_default_and_fixture_profile_is_explicit_end_to_end(self) -> None:
        _, objects = _fixture_objects()
        broad = _opl(objects)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            production = self._production_stage(root)
            work = root / "work"
            work.mkdir()
            with self.assertRaisesRegex(VerificationError, "production.*profile|live broad"):
                verify_planet_roots(
                    io.BytesIO(broad),
                    production,
                    work,
                    self._bindings(broad),
                    production_stream=io.BytesIO(self._production_opl()),
                    input_format="opl",
                )
            result = verify_planet_roots(
                io.BytesIO(broad),
                production,
                work,
                self._bindings(broad),
                production_stream=io.BytesIO(self._production_opl()),
                input_format="opl",
                profile=_BROAD_FIXTURE_PROFILE,
            )
            self.assertEqual(result.selected_way_count, 2)
            self.assertEqual(result.selected_relation_count, 1)
        self.assertEqual(
            getattr(verifier_module, "FIXTURE_BROAD_ENVELOPE_PROFILE", None),
            _BROAD_FIXTURE_PROFILE,
        )


class PlanetSelectionVerifierRedGate(unittest.TestCase):
    def test_independent_verifier_module_exists(self) -> None:
        self.assertIsNotNone(
            verify_planet_roots,
            "tools.experiment8.osm_planet_selection_verifier must be implemented",
        )


if __name__ == "__main__":
    unittest.main()
