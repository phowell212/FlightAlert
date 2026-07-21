from __future__ import annotations

import io
import hashlib
import json
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
BUILD = Path(__file__).with_name("build.cmd")
sys.path.insert(0, str(ROOT))


def _varint(value: int) -> bytes:
    if value < 0:
        value &= (1 << 64) - 1
    result = bytearray()
    while value > 0x7F:
        result.append((value & 0x7F) | 0x80)
        value >>= 7
    result.append(value)
    return bytes(result)


def _zigzag(value: int) -> int:
    return (value << 1) ^ (value >> 63)


def _field(number: int, wire: int, payload: bytes | int) -> bytes:
    result = _varint((number << 3) | wire)
    if wire == 0:
        return result + _varint(int(payload))
    if wire == 2:
        raw = bytes(payload)
        return result + _varint(len(raw)) + raw
    raise AssertionError(wire)


def _packed(values: list[int], *, zigzag: bool = False) -> bytes:
    return b"".join(_varint(_zigzag(value) if zigzag else value) for value in values)


def _info(version: int, timestamp: int, *, visible: bool = True) -> bytes:
    return b"".join(
        (
            _field(1, 0, version),
            _field(2, 0, timestamp),
            _field(6, 0, int(visible)),
        )
    )


def _string_table(strings: list[str]) -> bytes:
    return b"".join(_field(1, 2, value.encode("utf-8")) for value in strings)


def _node(
    object_id: int,
    lon_e7: int,
    lat_e7: int,
    timestamp: int,
    *,
    version: int = 1,
    visible: bool = True,
) -> bytes:
    return b"".join(
        (
            _field(1, 0, _zigzag(object_id)),
            _field(4, 2, _info(version, timestamp, visible=visible)),
            _field(8, 0, _zigzag(lat_e7)),
            _field(9, 0, _zigzag(lon_e7)),
        )
    )


def _way(
    object_id: int,
    refs: list[int],
    keys: list[int],
    vals: list[int],
    timestamp: int,
    *,
    version: int = 3,
    visible: bool = True,
) -> bytes:
    deltas: list[int] = []
    previous = 0
    for ref in refs:
        deltas.append(ref - previous)
        previous = ref
    return b"".join(
        (
            _field(1, 0, object_id),
            _field(2, 2, _packed(keys)),
            _field(3, 2, _packed(vals)),
            _field(4, 2, _info(version, timestamp, visible=visible)),
            _field(8, 2, _packed(deltas, zigzag=True)),
        )
    )


def _dense_nodes(
    rows: list[tuple[int, int, int, int, int, list[tuple[int, int]]]],
) -> bytes:
    ids: list[int] = []
    lats: list[int] = []
    lons: list[int] = []
    versions: list[int] = []
    timestamps: list[int] = []
    visible: list[int] = []
    keys_vals: list[int] = []
    previous_id = previous_lat = previous_lon = previous_timestamp = 0
    for object_id, lon, lat, version, timestamp, tags in rows:
        ids.append(object_id - previous_id)
        lons.append(lon - previous_lon)
        lats.append(lat - previous_lat)
        timestamps.append(timestamp - previous_timestamp)
        versions.append(version)
        visible.append(1)
        for key, value in tags:
            keys_vals.extend((key, value))
        keys_vals.append(0)
        previous_id, previous_lon, previous_lat, previous_timestamp = (
            object_id,
            lon,
            lat,
            timestamp,
        )
    dense_info = b"".join(
        (
            _field(1, 2, _packed(versions)),
            _field(2, 2, _packed(timestamps, zigzag=True)),
            _field(6, 2, _packed(visible)),
        )
    )
    return b"".join(
        (
            _field(1, 2, _packed(ids, zigzag=True)),
            _field(5, 2, dense_info),
            _field(8, 2, _packed(lats, zigzag=True)),
            _field(9, 2, _packed(lons, zigzag=True)),
            _field(10, 2, _packed(keys_vals)),
        )
    )


def _relation(
    object_id: int,
    members: list[tuple[int, int, int]],
    keys: list[int],
    vals: list[int],
    timestamp: int,
    *,
    version: int = 1,
) -> bytes:
    roles: list[int] = []
    member_deltas: list[int] = []
    member_types: list[int] = []
    previous = 0
    for member_type, ref, role_sid in members:
        roles.append(role_sid)
        member_deltas.append(ref - previous)
        member_types.append(member_type)
        previous = ref
    return b"".join(
        (
            _field(1, 0, object_id),
            _field(2, 2, _packed(keys)),
            _field(3, 2, _packed(vals)),
            _field(4, 2, _info(version, timestamp)),
            _field(8, 2, _packed(roles)),
            _field(9, 2, _packed(member_deltas, zigzag=True)),
            _field(10, 2, _packed(member_types)),
        )
    )


def _primitive(
    strings: list[str],
    groups: list[bytes],
    *,
    granularity: int = 100,
    date_granularity: int = 1000,
) -> bytes:
    return b"".join(
        (
            _field(1, 2, _string_table(strings)),
            *(_field(2, 2, group) for group in groups),
            _field(17, 0, granularity),
            _field(18, 0, date_granularity),
        )
    )


def _blob(blob_type: str, payload: bytes, *, compression: str = "raw") -> bytes:
    if compression == "raw":
        blob = _field(1, 2, payload)
    elif compression == "zlib":
        blob = _field(2, 0, len(payload)) + _field(3, 2, zlib.compress(payload))
    elif compression == "lz4":
        blob = _field(2, 0, len(payload)) + _field(6, 2, payload)
    else:
        raise AssertionError(compression)
    header = _field(1, 2, blob_type.encode("ascii")) + _field(3, 0, len(blob))
    return struct.pack(">I", len(header)) + header + blob


def _header(*features: str) -> bytes:
    if not features:
        features = ("OsmSchema-V0.6",)
    block = b"".join(_field(4, 2, feature.encode("ascii")) for feature in features)
    return _blob("OSMHeader", block)


def _simple_raw_pbf() -> bytes:
    strings = ["", "natural", "coastline"]
    group = b"".join(
        (
            _field(1, 2, _node(1, -761_000_000, 390_000_000, 1_782_691_201)),
            _field(1, 2, _node(2, -760_000_000, 390_500_000, 1_782_691_202)),
            _field(3, 2, _way(10, [1, 2], [1], [2], 1_782_691_260)),
        )
    )
    primitive = _primitive(strings, [group])
    return _header() + _blob("OSMData", primitive)


def _comprehensive_pbf() -> bytes:
    base = 1_704_067_200
    first_strings = ["", "note", "a b,c=d%@\t", "natural", "coastline"]
    first_group = b"".join(
        (
            _field(1, 2, _node(1, -761_000_000, 390_000_000, base + 1)),
            _field(1, 2, _node(2, -760_000_000, 390_500_000, base + 2)),
            _field(3, 2, _way(10, [1, 2], [1, 3], [2, 4], base + 10)),
        )
    )
    second_strings = [
        "",
        "name",
        "R, = %@\t",
        "boundary",
        "administrative",
        "admin_level",
        "4",
        "type",
        "multipolygon",
        "outer",
        "label role",
        "inner",
    ]
    dense = _dense_nodes(
        [
            (3, -75_900_000, 39_100_000, 4, base + 3, [(1, 2)]),
            (4, -75_800_000, 39_150_000, 5, base + 4, []),
            (5, -75_700_000, 39_200_000, 6, base + 5, []),
            (6, -75_600_000, 39_250_000, 7, base + 6, []),
        ]
    )
    second_group = b"".join(
        (
            _field(2, 2, dense),
            _field(3, 2, _way(11, [2, 3], [5, 3], [6, 4], base + 11)),
            _field(3, 2, _way(20, [3, 4], [], [], base + 20)),
            _field(3, 2, _way(21, [5, 6], [], [], base + 21)),
            _field(
                4,
                2,
                _relation(
                    100,
                    [(1, 20, 9), (0, 3, 10), (1, 21, 11)],
                    [1, 5, 3, 7],
                    [2, 6, 4, 8],
                    base + 100,
                    version=8,
                ),
            ),
        )
    )
    return b"".join(
        (
            _header("OsmSchema-V0.6", "DenseNodes"),
            _blob("OSMData", _primitive(first_strings, [first_group])),
            _blob(
                "OSMData",
                _primitive(second_strings, [second_group], granularity=1000),
                compression="zlib",
            ),
        )
    )


def _selected_way_pbf(
    refs: list[int],
    *,
    duplicate: bool = False,
    date_granularity: int = 1000,
) -> bytes:
    base = 1_704_067_200
    strings = ["", "natural", "coastline"]
    node = _field(1, 2, _node(1, -761_000_000, 390_000_000, base + 1))
    way = _field(3, 2, _way(10, refs, [1], [2], base + 10))
    group = node + way + (way if duplicate else b"")
    return _header() + _blob(
        "OSMData",
        _primitive(strings, [group], date_granularity=date_granularity),
    )


def _nested_relation_pbf(*, member_type: int = 2) -> bytes:
    strings = [
        "",
        "admin_level",
        "4",
        "boundary",
        "administrative",
        "type",
        "multipolygon",
        "outer",
    ]
    relation = _relation(
        100,
        [(member_type, 200, 7)],
        [1, 3, 5],
        [2, 4, 6],
        1_704_067_300,
    )
    return _header() + _blob(
        "OSMData",
        _primitive(strings, [_field(4, 2, relation)]),
    )


def _many_unselected_nodes_pbf(count: int) -> bytes:
    base = 1_704_067_200
    strings = ["", "natural", "coastline"]
    nodes = [
        _field(
            1,
            2,
            _node(
                object_id,
                -761_000_000 + object_id,
                390_000_000 + object_id,
                base + object_id,
            ),
        )
        for object_id in range(1, count + 3)
    ]
    nodes.append(_field(3, 2, _way(10, [1, 2], [1], [2], base + 10)))
    return _header() + _blob("OSMData", _primitive(strings, [b"".join(nodes)]))


class NativePbfBuildContractTests(unittest.TestCase):
    def test_build_wrapper_exists(self) -> None:
        self.assertTrue(BUILD.is_file(), f"missing native build wrapper: {BUILD}")

    def test_extractor_source_exists(self) -> None:
        source = BUILD.with_name("native_pbf_extractor.cpp")
        self.assertTrue(source.is_file(), f"missing native extractor source: {source}")


class NativePbfExtractorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name)
        cls.binary = cls.root / "bin" / "native_pbf_extractor.exe"
        subprocess.run(
            ["cmd.exe", "/d", "/c", str(BUILD), str(cls.binary.parent)],
            cwd=ROOT,
            check=True,
        )

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temporary.cleanup()

    def _run(self, source: bytes, *, workers: int = 1, label: str = "run"):
        case = self.root / self._testMethodName / label
        case.mkdir(parents=True)
        input_path = case / "source.osm.pbf"
        output_path = case / "closure.opl"
        input_path.write_bytes(source)
        result = self._invoke(input_path, output_path, case / "work", workers)
        return result, input_path, output_path, Path(str(output_path) + ".receipt.json")

    def _invoke(
        self,
        input_path: Path,
        output_path: Path,
        work_path: Path,
        workers: int,
        extra_args: tuple[str, ...] = (),
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                str(self.binary),
                "--input",
                str(input_path),
                "--output",
                str(output_path),
                "--work-dir",
                str(work_path),
                "--workers",
                str(workers),
                *extra_args,
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )

    def _extract(self, source: bytes, *, workers: int = 1, label: str = "run"):
        result, input_path, output_path, receipt_path = self._run(
            source,
            workers=workers,
            label=label,
        )
        self.assertEqual(result.returncode, 0, result.stderr or result.stdout)
        self.assertTrue(receipt_path.is_file(), "successful extraction omitted receipt")
        self.assertFalse(
            (output_path.parent / "work").exists(),
            "successful extraction retained its work directory",
        )
        return (
            output_path.read_bytes(),
            json.loads(receipt_path.read_text("utf-8")),
            input_path,
            output_path,
        )

    def _assert_rejected(self, source: bytes, message: str, *, label: str) -> None:
        result, _, output_path, receipt_path = self._run(source, label=label)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(message.lower(), (result.stderr + result.stdout).lower())
        self.assertFalse(output_path.exists())
        self.assertFalse(receipt_path.exists())

    def test_extracts_raw_direct_coastline_as_strict_opl(self) -> None:
        from tools.experiment8.osm_global_waterway_package import (
            StrictOplNode,
            StrictOplWay,
            iter_strict_waterway_opl,
        )

        output, _, _, _ = self._extract(_simple_raw_pbf())
        records = tuple(iter_strict_waterway_opl(io.BytesIO(output)))

        self.assertEqual(
            [type(record.value) for record in records],
            [StrictOplNode, StrictOplNode, StrictOplWay],
        )
        self.assertEqual([record.value.object_id for record in records], [1, 2, 10])
        self.assertEqual(records[0].value.longitude_e7, -761_000_000)
        self.assertEqual(records[0].value.latitude_e7, 390_000_000)
        self.assertEqual(records[2].value.tags, (("natural", "coastline"),))
        self.assertEqual(records[2].value.node_refs, (1, 2))

    def test_extracts_zlib_dense_direct_and_relation_closure_exactly(self) -> None:
        from tools.experiment8.osm_boundary_renderer import select_boundary_opl
        from tools.experiment8.osm_global_waterway_package import (
            StrictOplNode,
            StrictOplRelation,
            StrictOplWay,
            iter_strict_waterway_opl,
        )

        output, _, _, _ = self._extract(_comprehensive_pbf())
        records = tuple(iter_strict_waterway_opl(io.BytesIO(output)))
        values = [record.value for record in records]

        self.assertEqual(
            [(type(value), value.object_id) for value in values],
            [
                (StrictOplNode, 1),
                (StrictOplNode, 2),
                (StrictOplNode, 3),
                (StrictOplNode, 4),
                (StrictOplNode, 5),
                (StrictOplNode, 6),
                (StrictOplWay, 10),
                (StrictOplWay, 11),
                (StrictOplWay, 20),
                (StrictOplWay, 21),
                (StrictOplRelation, 100),
            ],
        )
        dense = values[2]
        self.assertEqual((dense.longitude_e7, dense.latitude_e7), (-759_000_000, 391_000_000))
        self.assertEqual(dense.version, 4)
        self.assertEqual(dense.timestamp, "2024-01-01T00:00:03Z")
        self.assertEqual(dense.tags, (("name", "R, = %@\t"),))
        relation = values[-1]
        self.assertEqual(relation.version, 8)
        self.assertEqual(
            relation.tags,
            (
                ("name", "R, = %@\t"),
                ("admin_level", "4"),
                ("boundary", "administrative"),
                ("type", "multipolygon"),
            ),
        )
        self.assertEqual(
            tuple((member.object_type, member.ref, member.role, member.ordinal) for member in relation.members),
            (("w", 20, "outer", 0), ("n", 3, "label role", 1), ("w", 21, "inner", 2)),
        )
        self.assertNotIn(b"\t", output)
        self.assertNotIn(b"\r", output)
        self.assertIn(b"n3@label%20%role", output)

        selected = select_boundary_opl(io.BytesIO(output))
        self.assertEqual(
            [item.way.object_id for item in selected.ways],
            [10, 11, 20, 21],
        )
        self.assertEqual(set(selected.nodes), {1, 2, 3, 4, 5, 6})

    def test_worker_counts_produce_identical_opl_and_worker_invariant_semantics(self) -> None:
        case = self.root / self._testMethodName
        case.mkdir(parents=True)
        input_path = case / "source.osm.pbf"
        input_path.write_bytes(_comprehensive_pbf())
        first_path = case / "workers-1.opl"
        tenth_path = case / "workers-10.opl"
        first_result = self._invoke(input_path, first_path, case / "work-1", 1)
        tenth_result = self._invoke(input_path, tenth_path, case / "work-10", 10)
        self.assertEqual(first_result.returncode, 0, first_result.stderr or first_result.stdout)
        self.assertEqual(tenth_result.returncode, 0, tenth_result.stderr or tenth_result.stdout)
        first = first_path.read_bytes()
        tenth = tenth_path.read_bytes()
        first_receipt = json.loads(Path(str(first_path) + ".receipt.json").read_text("utf-8"))
        tenth_receipt = json.loads(Path(str(tenth_path) + ".receipt.json").read_text("utf-8"))

        self.assertEqual(first, tenth)
        self.assertEqual(first_receipt["semantic"]["output"], tenth_receipt["semantic"]["output"])
        self.assertEqual(first_receipt["semanticIdentitySha256"], tenth_receipt["semanticIdentitySha256"])
        self.assertEqual(first_receipt["execution"]["workerCount"], 1)
        self.assertEqual(tenth_receipt["execution"]["workerCount"], 10)

    def test_defaults_to_ten_workers(self) -> None:
        case = self.root / self._testMethodName
        case.mkdir(parents=True)
        input_path = case / "source.osm.pbf"
        output_path = case / "closure.opl"
        input_path.write_bytes(_simple_raw_pbf())
        result = subprocess.run(
            [
                str(self.binary),
                "--input",
                str(input_path),
                "--output",
                str(output_path),
                "--work-dir",
                str(case / "work"),
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr or result.stdout)
        receipt = json.loads(Path(str(output_path) + ".receipt.json").read_text("utf-8"))
        self.assertEqual(receipt["execution"]["workerCount"], 10)

    def test_receipt_binds_source_output_counts_and_detects_tampering(self) -> None:
        output, receipt, input_path, output_path = self._extract(_comprehensive_pbf())

        self.assertEqual(receipt["schema"], "flightalert.osm-boundary-extraction-receipt.v1")
        self.assertEqual(receipt["state"], "complete")
        self.assertEqual(receipt["semantic"]["input"]["bytes"], input_path.stat().st_size)
        self.assertEqual(receipt["semantic"]["input"]["sha256"], hashlib.sha256(input_path.read_bytes()).hexdigest())
        self.assertEqual(receipt["semantic"]["output"], {"bytes": len(output), "sha256": hashlib.sha256(output).hexdigest()})
        self.assertEqual(receipt["semantic"]["counts"]["inputObjects"], {"nodes": 6, "ways": 4, "relations": 1})
        self.assertEqual(receipt["semantic"]["counts"]["outputObjects"], {"nodes": 6, "ways": 4, "relations": 1})
        semantic_bytes = json.dumps(receipt["semantic"], ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.assertEqual(receipt["semanticIdentitySha256"], hashlib.sha256(semantic_bytes).hexdigest())
        self.assertRegex(receipt["semantic"]["tool"]["sourceSha256"], r"^[0-9a-f]{64}$")
        self.assertRegex(receipt["execution"]["executable"]["sha256"], r"^[0-9a-f]{64}$")
        resources = receipt["execution"]["resourceBounds"]
        self.assertEqual(resources["maxWorkBytes"], 1_099_511_627_776)
        self.assertEqual(resources["maxOutputBytes"], 68_719_476_736)
        self.assertLessEqual(resources["peakWorkBytes"], resources["maxWorkBytes"])
        self.assertLessEqual(receipt["semantic"]["output"]["bytes"], resources["maxOutputBytes"])
        output_path.write_bytes(output + b"tamper")
        self.assertNotEqual(receipt["semantic"]["output"]["sha256"], hashlib.sha256(output_path.read_bytes()).hexdigest())

    def test_fails_closed_for_malformed_protobuf_unsupported_compression_and_missing_ref(self) -> None:
        self._assert_rejected(
            _header() + _blob("OSMData", b"\x0a\x80"),
            "protobuf",
            label="malformed",
        )
        self._assert_rejected(
            _header() + _blob("OSMData", _primitive([""], []), compression="lz4"),
            "compression",
            label="compression",
        )
        self._assert_rejected(
            _selected_way_pbf([1, 999]),
            "missing node",
            label="missing-ref",
        )

    def test_skips_unusable_relation_geometry_and_rejects_missing_or_duplicate_selected_objects(self) -> None:
        for member_type, label in ((2, "nested"), (0, "node-member")):
            output, receipt, _, _ = self._extract(
                _nested_relation_pbf(member_type=member_type),
                label=label,
            )
            self.assertEqual(output, b"")
            self.assertEqual(
                receipt["semantic"]["counts"]["outputObjects"],
                {"nodes": 0, "ways": 0, "relations": 0},
            )
        self._assert_rejected(
            _nested_relation_pbf(member_type=3),
            "invalid member type",
            label="invalid-member-type",
        )
        self._assert_rejected(
            _nested_relation_pbf(member_type=1),
            "missing way",
            label="missing-relation-way",
        )
        self._assert_rejected(
            _selected_way_pbf([1, 1], duplicate=True),
            "duplicate",
            label="duplicate-way",
        )
        self._assert_rejected(
            _selected_way_pbf([1]),
            "unusable geometry",
            label="short-way",
        )

    def test_fails_closed_for_invalid_utf8_and_subsecond_timestamp(self) -> None:
        bad_table = _field(1, 2, b"") + _field(1, 2, b"\xff")
        bad_utf8_block = _field(1, 2, bad_table) + _field(17, 0, 100)
        self._assert_rejected(
            _header() + _blob("OSMData", bad_utf8_block),
            "utf-8",
            label="utf8",
        )
        self._assert_rejected(
            _selected_way_pbf([1, 1], date_granularity=1),
            "whole second",
            label="subsecond",
        )

    def test_fails_closed_for_invalid_tags_overflow_framing_and_required_features(self) -> None:
        base = 1_704_067_200
        strings = ["", "natural", "coastline"]
        duplicate_tags = b"".join(
            (
                _field(1, 2, _node(1, -761_000_000, 390_000_000, base + 1)),
                _field(3, 2, _way(10, [1, 1], [1, 1], [2, 2], base + 10)),
            )
        )
        self._assert_rejected(
            _header() + _blob("OSMData", _primitive(strings, [duplicate_tags])),
            "duplicate tag",
            label="duplicate-tag",
        )
        overflowing_node = b"".join(
            (
                _field(1, 0, _zigzag(1)),
                _field(4, 2, _info(1, base + 1)),
                _field(8, 0, _zigzag(0)),
                _field(9, 0, _zigzag((1 << 63) - 1)),
            )
        )
        self._assert_rejected(
            _header() + _blob(
                "OSMData",
                _primitive([""], [_field(1, 2, overflowing_node)]),
            ),
            "overflows",
            label="coordinate-overflow",
        )
        self._assert_rejected(
            _header() + b"\x00\x00\x00\x05\x0a",
            "truncated blobheader",
            label="truncated-framing",
        )
        self._assert_rejected(
            _header("OsmSchema-V0.6", "UnsupportedFixtureFeature"),
            "unsupported required pbf feature",
            label="required-feature",
        )

    def test_current_visibility_and_canonical_admin_level_are_fail_closed(self) -> None:
        from tools.experiment8.osm_global_waterway_package import iter_strict_waterway_opl

        base = 1_704_067_200
        coastline_strings = ["", "natural", "coastline"]
        nodes = b"".join(
            (
                _field(1, 2, _node(1, -761_000_000, 390_000_000, base + 1)),
                _field(1, 2, _node(2, -760_000_000, 390_500_000, base + 2)),
            )
        )
        invisible_way = nodes + _field(
            3,
            2,
            _way(10, [1, 2], [1], [2], base + 10, visible=False),
        )
        output, _, _, _ = self._extract(
            _header() + _blob("OSMData", _primitive(coastline_strings, [invisible_way])),
            label="invisible-way",
        )
        self.assertEqual(tuple(iter_strict_waterway_opl(io.BytesIO(output))), ())

        admin_strings = ["", "boundary", "administrative", "admin_level", "04"]
        noncanonical = nodes + _field(
            3,
            2,
            _way(11, [1, 2], [1, 3], [2, 4], base + 11),
        )
        output, _, _, _ = self._extract(
            _header() + _blob("OSMData", _primitive(admin_strings, [noncanonical])),
            label="noncanonical-admin",
        )
        self.assertEqual(tuple(iter_strict_waterway_opl(io.BytesIO(output))), ())

        invisible_node_group = b"".join(
            (
                _field(1, 2, _node(1, -761_000_000, 390_000_000, base + 1)),
                _field(1, 2, _node(2, -760_000_000, 390_500_000, base + 2, visible=False)),
                _field(3, 2, _way(10, [1, 2], [1], [2], base + 10)),
            )
        )
        self._assert_rejected(
            _header() + _blob("OSMData", _primitive(coastline_strings, [invisible_node_group])),
            "missing node",
            label="invisible-node",
        )

    def test_refuses_overwrite_overlap_and_more_than_ten_workers(self) -> None:
        _, _, input_path, output_path = self._extract(_simple_raw_pbf(), label="first")
        receipt_path = Path(str(output_path) + ".receipt.json")
        second_work = output_path.parent / "second-work"
        overwrite = subprocess.run(
            [
                str(self.binary),
                "--input",
                str(input_path),
                "--output",
                str(output_path),
                "--work-dir",
                str(second_work),
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(overwrite.returncode, 0)
        self.assertIn("exists", (overwrite.stderr + overwrite.stdout).lower())
        self.assertTrue(output_path.is_file())
        self.assertTrue(receipt_path.is_file())

        too_many_output = output_path.parent / "too-many.opl"
        too_many = subprocess.run(
            [
                str(self.binary),
                "--input",
                str(input_path),
                "--output",
                str(too_many_output),
                "--work-dir",
                str(output_path.parent / "too-many-work"),
                "--workers",
                "11",
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(too_many.returncode, 0)
        self.assertIn("workers", (too_many.stderr + too_many.stdout).lower())
        self.assertFalse(too_many_output.exists())

        overlap_output = output_path.parent / "overlap.opl"
        overlap = subprocess.run(
            [
                str(self.binary),
                "--input",
                str(input_path),
                "--output",
                str(overlap_output),
                "--work-dir",
                str(output_path.parent),
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(overlap.returncode, 0)
        self.assertIn("overlap", (overlap.stderr + overlap.stdout).lower())
        self.assertFalse(overlap_output.exists())

    def test_unselected_nodes_do_not_consume_closure_work_quota(self) -> None:
        case = self.root / self._testMethodName
        case.mkdir(parents=True)
        input_path = case / "source.osm.pbf"
        output_path = case / "closure.opl"
        input_path.write_bytes(_many_unselected_nodes_pbf(9_000))

        result = self._invoke(
            input_path,
            output_path,
            case / "work",
            10,
            ("--max-work-bytes", "65536"),
        )

        self.assertEqual(result.returncode, 0, result.stderr or result.stdout)
        self.assertTrue(output_path.is_file())

    def test_enforces_work_and_output_byte_quotas_without_publishable_artifacts(self) -> None:
        case = self.root / self._testMethodName
        case.mkdir(parents=True)
        input_path = case / "source.osm.pbf"
        input_path.write_bytes(_simple_raw_pbf())
        for label, extra, message in (
            ("work", ("--max-work-bytes", "1"), "work byte quota"),
            ("output", ("--max-output-bytes", "1"), "output byte quota"),
        ):
            with self.subTest(label=label):
                output_path = case / f"{label}.opl"
                work_path = case / f"{label}-work"
                result = self._invoke(input_path, output_path, work_path, 1, extra)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, (result.stderr + result.stdout).lower())
                self.assertFalse(output_path.exists())
                self.assertFalse(Path(str(output_path) + ".receipt.json").exists())
                self.assertFalse(work_path.exists())

        zero_output = case / "zero.opl"
        zero_work = case / "zero-work"
        zero = self._invoke(
            input_path,
            zero_output,
            zero_work,
            1,
            ("--max-work-bytes", "0"),
        )
        self.assertNotEqual(zero.returncode, 0)
        self.assertIn("positive", (zero.stderr + zero.stdout).lower())
        self.assertFalse(zero_work.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
