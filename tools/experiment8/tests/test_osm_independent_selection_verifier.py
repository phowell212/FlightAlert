from __future__ import annotations

import copy
import hashlib
import json
import re
import unittest
from dataclasses import fields, replace

from tools.experiment8.osm_independent_selection_verifier import (
    BROAD_EXTRACTION_OUTPUT_WSL_PATH,
    LOCKED_SOURCE_WSL_PATH,
    BroadExtractionCommandContract,
    IndependentSelectionVerificationError,
    IndependentSelectionVerificationResult,
    VerificationLimits,
    parse_canonical_root_ids,
    parse_canonical_selection_material,
    verify_independent_selection,
)


_TIMESTAMP = "2026-07-10T19:30:40Z"


def _canonical(document: dict[str, object]) -> bytes:
    return (
        json.dumps(
            document,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode("utf-8")


def _object_sha(document: dict[str, object]) -> str:
    return hashlib.sha256(_canonical(document)).hexdigest()


def _way_root(
    object_id: int,
    refs: list[int],
    tags: list[list[str]],
    display_names: list[list[str]],
    waterway: str,
) -> dict[str, object]:
    source: dict[str, object] = {
        "id": object_id,
        "nodeRefs": refs,
        "objectType": "way",
        "tags": tags,
        "timestamp": _TIMESTAMP,
        "version": 2,
    }
    return {
        **source,
        "displayNames": display_names,
        "objectSha256": _object_sha(source),
        "waterway": waterway,
    }


def _relation_root() -> dict[str, object]:
    source: dict[str, object] = {
        "id": 30,
        "members": [
            {"objectType": "way", "ordinal": 0, "ref": 10, "role": "main"},
            {"objectType": "node", "ordinal": 1, "ref": 100, "role": ""},
        ],
        "objectType": "relation",
        "tags": [["name", "Relation Thirty"], ["type", "waterway"]],
        "timestamp": _TIMESTAMP,
        "version": 3,
    }
    return {
        **source,
        "displayNames": [["name", "Relation Thirty"]],
        "objectSha256": _object_sha(source),
    }


def _broad_xml() -> bytes:
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="independent-test">
  <bounds minlat="0" minlon="0" maxlat="1" maxlon="1"/>
  <way id="10" version="2" timestamp="{_TIMESTAMP}">
    <nd ref="100"/><nd ref="101"/>
    <tag k="waterway" v="river"/><tag k="name" v="River Ten"/>
  </way>
  <way id="11" version="2" timestamp="{_TIMESTAMP}">
    <nd ref="102"/><nd ref="103"/>
    <tag k="official_name" v="Río Once"/><tag k="waterway" v="stream"/>
  </way>
  <way id="12" version="2" timestamp="{_TIMESTAMP}">
    <nd ref="104"/><nd ref="105"/>
    <tag k="waterway" v="canal"/><tag k="name:en-US" v="Canal &amp; Twelve"/>
  </way>
  <way id="13" version="2" timestamp="{_TIMESTAMP}">
    <nd ref="106"/><nd ref="107"/>
    <tag k="waterway" v="tidal_channel"/><tag k="int_name" v="Channel Thirteen"/>
  </way>
  <way id="14" version="2" timestamp="{_TIMESTAMP}">
    <nd ref="108"/><nd ref="109"/>
    <tag k="name:fr" v="Oued Quatorze"/><tag k="waterway" v="wadi"/>
  </way>
  <way id="20" version="2" timestamp="{_TIMESTAMP}">
    <nd ref="200"/><nd ref="201"/>
    <tag k="name" v="Ditch Twenty"/><tag k="waterway" v="ditch"/>
  </way>
  <way id="21" version="2" timestamp="{_TIMESTAMP}">
    <nd ref="210"/><nd ref="211"/>
    <tag k="name:left" v="Not a language"/><tag k="waterway" v="river"/>
  </way>
  <way id="22" version="2" timestamp="{_TIMESTAMP}">
    <nd ref="220"/><nd ref="220"/>
    <tag k="name" v="Closed Twenty Two"/><tag k="waterway" v="river"/>
  </way>
  <way id="23" version="2" timestamp="{_TIMESTAMP}">
    <nd ref="230"/>
    <tag k="name" v="Short Twenty Three"/><tag k="waterway" v="stream"/>
  </way>
  <way id="24" version="2" timestamp="{_TIMESTAMP}">
    <nd ref="240"/><nd ref="241"/>
    <tag k="area" v="yes"/><tag k="name" v="Area Twenty Four"/><tag k="waterway" v="canal"/>
  </way>
  <way id="25" version="2" timestamp="{_TIMESTAMP}">
    <nd ref="250"/><nd ref="251"/>
    <tag k="disused" v="yes"/><tag k="name" v="Old Twenty Five"/><tag k="waterway" v="wadi"/>
  </way>
  <way id="26" version="2" timestamp="{_TIMESTAMP}">
    <nd ref="260"/><nd ref="261"/>
    <tag k="construction:waterway" v="no"/><tag k="name" v="Lifecycle Twenty Six"/><tag k="waterway" v="river"/>
  </way>
  <way id="27" version="2" timestamp="{_TIMESTAMP}">
    <nd ref="270"/><nd ref="271"/>
    <tag k="name:de-old" v="Unsupported suffix"/><tag k="waterway" v="river"/>
  </way>
  <relation id="30" version="3" timestamp="{_TIMESTAMP}">
    <member type="way" ref="10" role="main"/>
    <member type="node" ref="100" role=""/>
    <tag k="type" v="waterway"/><tag k="name" v="Relation Thirty"/>
  </relation>
  <relation id="31" version="3" timestamp="{_TIMESTAMP}">
    <tag k="name:old" v="Historical only"/><tag k="type" v="waterway"/>
  </relation>
</osm>
'''.encode("utf-8")


def _root_ids() -> bytes:
    return b"w10\nw11\nw12\nw13\nw14\nr30\n"


def _material_document() -> dict[str, object]:
    root_ids = _root_ids()
    return {
        "candidateCounts": {"nodes": 0, "relations": 2, "ways": 13},
        "rejectedRelationCounts": {"no_display_name": 1},
        "rejectedRelations": [{"id": 31, "reason": "no_display_name"}],
        "rejectedWayCounts": {
            "area": 1,
            "closed": 1,
            "insufficient_geometry": 1,
            "lifecycle": 2,
            "no_display_name": 2,
            "unsupported_waterway": 1,
        },
        "rejectedWays": [
            {"id": 20, "reason": "unsupported_waterway"},
            {"id": 21, "reason": "no_display_name"},
            {"id": 22, "reason": "closed"},
            {"id": 23, "reason": "insufficient_geometry"},
            {"id": 24, "reason": "area"},
            {"id": 25, "reason": "lifecycle"},
            {"id": 26, "reason": "lifecycle"},
            {"id": 27, "reason": "no_display_name"},
        ],
        "rootIdsSha256": hashlib.sha256(root_ids).hexdigest(),
        "roots": [
            _way_root(
                10,
                [100, 101],
                [["name", "River Ten"], ["waterway", "river"]],
                [["name", "River Ten"]],
                "river",
            ),
            _way_root(
                11,
                [102, 103],
                [["official_name", "Río Once"], ["waterway", "stream"]],
                [["official_name", "Río Once"]],
                "stream",
            ),
            _way_root(
                12,
                [104, 105],
                [["name:en-US", "Canal & Twelve"], ["waterway", "canal"]],
                [["name:en-US", "Canal & Twelve"]],
                "canal",
            ),
            _way_root(
                13,
                [106, 107],
                [["int_name", "Channel Thirteen"], ["waterway", "tidal_channel"]],
                [["int_name", "Channel Thirteen"]],
                "tidal_channel",
            ),
            _way_root(
                14,
                [108, 109],
                [["name:fr", "Oued Quatorze"], ["waterway", "wadi"]],
                [["name:fr", "Oued Quatorze"]],
                "wadi",
            ),
            _relation_root(),
        ],
        "schema": "flight-alert-exp8-osm-selection-material-v1",
        "selectedCounts": {"relations": 1, "ways": 5},
    }


def _material() -> bytes:
    return _canonical(_material_document())


class BroadExtractionCommandContractTests(unittest.TestCase):
    def test_exact_future_e_drive_argv_is_data_only(self) -> None:
        contract = BroadExtractionCommandContract()

        self.assertEqual(contract.source_wsl_path, LOCKED_SOURCE_WSL_PATH)
        self.assertEqual(contract.output_wsl_path, BROAD_EXTRACTION_OUTPUT_WSL_PATH)
        self.assertEqual(
            contract.argv,
            (
                r"C:\Windows\System32\wsl.exe",
                "-d",
                "Ubuntu",
                "--",
                "/usr/bin/env",
                "-i",
                "LC_ALL=C.UTF-8",
                "LANG=C.UTF-8",
                "LANGUAGE=C",
                "LD_LIBRARY_PATH=/home/haquilus/flightalert-exp8-tools/osmium-1.11.1/root/usr/lib/x86_64-linux-gnu",
                "/home/haquilus/flightalert-exp8-tools/osmium-1.11.1/root/usr/bin/osmium",
                "tags-filter",
                "--no-progress",
                "-R",
                "--generator",
                "flight-alert-exp8-osmium-1.11.1",
                "-f",
                "osm",
                "-O",
                "-o",
                "/mnt/e/FlightAlert-exp8-work/osm-hydro-pilot/selection/waterway-broad-v1.osm",
                "/mnt/e/FlightAlert-exp8-work/osm-hydro-pilot/source/maryland-260710.osm.pbf",
                "w/waterway",
                "r/type=waterway",
            ),
        )

    def test_contract_rejects_ambiguous_or_nonabsolute_paths(self) -> None:
        with self.assertRaises(IndependentSelectionVerificationError):
            BroadExtractionCommandContract(source_wsl_path="relative/source.pbf")
        with self.assertRaises(IndependentSelectionVerificationError):
            BroadExtractionCommandContract(output_wsl_path="relative/output.osm")
        with self.assertRaises(IndependentSelectionVerificationError):
            BroadExtractionCommandContract(
                source_wsl_path="/mnt/e/same",
                output_wsl_path="/mnt/e/same",
            )


class IndependentSelectionVerifierTests(unittest.TestCase):
    def test_success_reconciles_every_policy_outcome_and_emits_generic_report(self) -> None:
        first = verify_independent_selection(_broad_xml(), _root_ids(), _material())
        second = verify_independent_selection(_broad_xml(), _root_ids(), _material())

        self.assertEqual(first, second)
        self.assertEqual(
            tuple(field.name for field in fields(IndependentSelectionVerificationResult)),
            ("report_bytes", "report_sha256"),
        )
        self.assertEqual(
            first.report_sha256,
            hashlib.sha256(first.report_bytes).hexdigest(),
        )
        report = json.loads(first.report_bytes)
        self.assertEqual(first.report_bytes, _canonical(report))
        self.assertEqual(
            report["profile"],
            "flight-alert-exp8-osm-independent-selection-generic-v1",
        )
        self.assertEqual(report["selectedCounts"], {"relations": 1, "ways": 5})
        self.assertEqual(report["selectedRootIds"], ["w10", "w11", "w12", "w13", "w14", "r30"])
        self.assertEqual(report["rejectedWayCounts"], _material_document()["rejectedWayCounts"])
        self.assertEqual(
            report["candidateEnvelope"],
            {
                "relationTag": ["type", "waterway"],
                "wayTagKey": "waterway",
            },
        )
        self.assertNotIn("broadExtraction", report)
        self.assertTrue(report["verified"])
        lowered = first.report_bytes.lower()
        for forbidden in (b"maryland", b"/mnt/", b"c:\\\\", b"provenance", b"sourcepath"):
            self.assertNotIn(forbidden, lowered)

    def test_every_selected_way_field_and_relation_member_is_exact(self) -> None:
        mutations = {
            "way version": lambda d: d["roots"][0].__setitem__("version", 9),
            "way timestamp": lambda d: d["roots"][0].__setitem__("timestamp", "2026-07-10T19:30:41Z"),
            "way refs": lambda d: d["roots"][0].__setitem__("nodeRefs", [100, 999]),
            "way tags": lambda d: d["roots"][0].__setitem__("tags", [["name", "Forged"], ["waterway", "river"]]),
            "way display": lambda d: d["roots"][0].__setitem__("displayNames", [["name:left", "Forged"]]),
            "way value": lambda d: d["roots"][0].__setitem__("waterway", "stream"),
            "way hash": lambda d: d["roots"][0].__setitem__("objectSha256", "0" * 64),
            "member type": lambda d: d["roots"][5]["members"][0].__setitem__("objectType", "relation"),
            "member ref": lambda d: d["roots"][5]["members"][0].__setitem__("ref", 999),
            "member role": lambda d: d["roots"][5]["members"][0].__setitem__("role", "side"),
            "member ordinal": lambda d: d["roots"][5]["members"][0].__setitem__("ordinal", 1),
            "relation version": lambda d: d["roots"][5].__setitem__("version", 4),
            "relation timestamp": lambda d: d["roots"][5].__setitem__("timestamp", "2026-07-10T19:30:41Z"),
            "relation tags": lambda d: d["roots"][5].__setitem__("tags", [["name", "Forged"], ["type", "waterway"]]),
            "relation display": lambda d: d["roots"][5].__setitem__("displayNames", [["name", "Forged"]]),
            "relation hash": lambda d: d["roots"][5].__setitem__("objectSha256", "f" * 64),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label):
                document = copy.deepcopy(_material_document())
                mutate(document)
                with self.assertRaises(IndependentSelectionVerificationError):
                    verify_independent_selection(_broad_xml(), _root_ids(), _canonical(document))

    def test_additions_omissions_duplicates_and_changed_counts_fail(self) -> None:
        cases: list[tuple[bytes, bytes]] = [
            (_root_ids().replace(b"w14\n", b""), _material()),
            (_root_ids().replace(b"r30\n", b"r30\nr31\n"), _material()),
            (_root_ids().replace(b"w12\n", b"w12\nw12\n"), _material()),
        ]
        changed = copy.deepcopy(_material_document())
        changed["candidateCounts"]["ways"] = 12
        cases.append((_root_ids(), _canonical(changed)))
        changed = copy.deepcopy(_material_document())
        changed["selectedCounts"]["ways"] = 4
        cases.append((_root_ids(), _canonical(changed)))
        changed = copy.deepcopy(_material_document())
        changed["rejectedWayCounts"]["unsupported_waterway"] = 0
        cases.append((_root_ids(), _canonical(changed)))

        for root_ids, material in cases:
            with self.subTest(root_ids=root_ids, material_sha=hashlib.sha256(material).hexdigest()):
                with self.assertRaises(IndependentSelectionVerificationError):
                    verify_independent_selection(_broad_xml(), root_ids, material)

    def test_candidate_object_missing_from_broad_source_fails(self) -> None:
        broad = re.sub(
            br'  <way id="14".*?  </way>\n',
            b"",
            _broad_xml(),
            count=1,
            flags=re.DOTALL,
        )
        self.assertNotIn(b'<way id="14"', broad)
        with self.assertRaises(IndependentSelectionVerificationError):
            verify_independent_selection(broad, _root_ids(), _material())

    def test_root_id_parser_is_strict_canonical_ascii(self) -> None:
        self.assertEqual(parse_canonical_root_ids(b""), ((), ()))
        self.assertEqual(parse_canonical_root_ids(_root_ids()), ((10, 11, 12, 13, 14), (30,)))
        invalid = (
            b"w01\n",
            b"w0\n",
            b"w10",
            b"w10\r\n",
            b"r30\nw10\n",
            b"w11\nw10\n",
            b"r31\nr30\n",
            b"n10\n",
            b"w9223372036854775808\n",
            b"w10\n\n",
            b"w\xff\n",
        )
        for raw in invalid:
            with self.subTest(raw=raw):
                with self.assertRaises(IndependentSelectionVerificationError):
                    parse_canonical_root_ids(raw)

    def test_material_parser_rejects_noncanonical_or_ambiguous_json(self) -> None:
        self.assertEqual(parse_canonical_selection_material(_material()), _material_document())
        noncanonical = json.dumps(_material_document(), ensure_ascii=False, indent=2).encode("utf-8") + b"\n"
        invalid = (
            _material()[:-1],
            _material()[:-1] + b"\r\n",
            b"\xef\xbb\xbf" + _material(),
            noncanonical,
            b'{"schema":"a","schema":"b"}\n',
            b'{"candidateCounts":NaN}\n',
            b"[]\n",
        )
        for raw in invalid:
            with self.subTest(raw_sha=hashlib.sha256(raw).hexdigest()):
                with self.assertRaises(IndependentSelectionVerificationError):
                    parse_canonical_selection_material(raw)

    def test_bounded_entity_free_xml_parser_fails_closed(self) -> None:
        entity_xml = _broad_xml().replace(
            b'<osm version="0.6" generator="independent-test">',
            b'<!DOCTYPE osm [<!ENTITY river "River">]><osm version="0.6" generator="independent-test">',
        )
        external_xml = _broad_xml().replace(
            b'<osm version="0.6" generator="independent-test">',
            b'<!DoCtYpE osm SYSTEM "file:///etc/passwd"><osm version="0.6" generator="independent-test">',
        )
        for raw in (entity_xml, external_xml):
            with self.subTest(raw_sha=hashlib.sha256(raw).hexdigest()):
                with self.assertRaises(IndependentSelectionVerificationError):
                    verify_independent_selection(raw, _root_ids(), _material())

    def test_xml_duplicates_invalid_metadata_and_filter_contamination_fail(self) -> None:
        duplicate_object = _broad_xml().replace(
            b"</osm>\n",
            b'<way id="10" version="2" timestamp="2026-07-10T19:30:40Z"><nd ref="1"/><nd ref="2"/><tag k="waterway" v="river"/><tag k="name" v="duplicate"/></way>\n</osm>\n',
        )
        duplicate_tag = _broad_xml().replace(
            b'<tag k="waterway" v="river"/><tag k="name" v="River Ten"/>',
            b'<tag k="waterway" v="river"/><tag k="name" v="River Ten"/><tag k="name" v="again"/>',
            1,
        )
        invalid_id = _broad_xml().replace(b'<way id="10"', b'<way id="01"', 1)
        invalid_timestamp = _broad_xml().replace(_TIMESTAMP.encode(), b"2026-07-10 19:30:40Z", 1)
        node = _broad_xml().replace(
            b"</osm>\n",
            b'<node id="999" version="1" timestamp="2026-07-10T19:30:40Z" lat="0" lon="0"/>\n</osm>\n',
        )
        nonwater_way = _broad_xml().replace(b'<tag k="waterway" v="ditch"/>', b'<tag k="highway" v="path"/>', 1)
        nonwater_relation = _broad_xml().replace(b'<tag k="type" v="waterway"/>', b'<tag k="type" v="route"/>', 1)

        for raw in (
            duplicate_object,
            duplicate_tag,
            invalid_id,
            invalid_timestamp,
            node,
            nonwater_way,
            nonwater_relation,
        ):
            with self.subTest(raw_sha=hashlib.sha256(raw).hexdigest()):
                with self.assertRaises(IndependentSelectionVerificationError):
                    verify_independent_selection(raw, _root_ids(), _material())

    def test_all_input_and_structure_ceilings_are_enforced(self) -> None:
        base = VerificationLimits()
        cases = (
            replace(base, max_broad_xml_bytes=len(_broad_xml()) - 1),
            replace(base, max_objects=1),
            replace(base, max_references=1),
            replace(base, max_tags=1),
            replace(base, max_text_utf8_bytes=3),
        )
        for limits in cases:
            with self.subTest(limits=limits):
                with self.assertRaises(IndependentSelectionVerificationError):
                    verify_independent_selection(_broad_xml(), _root_ids(), _material(), limits=limits)

        with self.assertRaises(IndependentSelectionVerificationError):
            parse_canonical_root_ids(
                _root_ids(),
                limits=replace(base, max_root_ids_bytes=len(_root_ids()) - 1),
            )
        with self.assertRaises(IndependentSelectionVerificationError):
            parse_canonical_selection_material(
                _material(),
                limits=replace(base, max_selection_material_bytes=len(_material()) - 1),
            )
        for invalid in (-1, True):
            with self.subTest(invalid=invalid):
                with self.assertRaises(IndependentSelectionVerificationError):
                    VerificationLimits(max_objects=invalid)  # type: ignore[arg-type]

    def test_inputs_must_be_exact_immutable_bytes(self) -> None:
        with self.assertRaises(IndependentSelectionVerificationError):
            verify_independent_selection(bytearray(_broad_xml()), _root_ids(), _material())  # type: ignore[arg-type]
        with self.assertRaises(IndependentSelectionVerificationError):
            parse_canonical_root_ids(bytearray(_root_ids()))  # type: ignore[arg-type]
        with self.assertRaises(IndependentSelectionVerificationError):
            parse_canonical_selection_material(bytearray(_material()))  # type: ignore[arg-type]


if __name__ == "__main__":
    unittest.main()
