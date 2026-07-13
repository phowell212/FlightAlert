from __future__ import annotations

import hashlib
import io
import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


def _canonical_bytes(value: object) -> bytes:
    return (
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
        + "\n"
    ).encode("utf-8")


def _legacy_database(path: Path) -> dict[str, object]:
    source = {
        "closureOpl": {"bytes": 333, "sha256": "3" * 64},
        "closurePbf": {"bytes": 444, "sha256": "4" * 64},
        "extractionReceiptSha256": "5" * 64,
        "planet": {"bytes": 555, "path": "fixture://legacy", "sha256": "6" * 64},
        "rootIds": {"bytes": 666, "sha256": "7" * 64},
        "selectionManifestSha256": "8" * 64,
        "selectionPolicySha256": "9" * 64,
    }
    checkpoint = {
        "ingestComplete": True,
        "inputObjects": 5,
        "lineNumber": 5,
        "nextByteOffset": 777,
        "previousKind": 2,
        "previousId": 8_000_000_000_000_401,
    }
    run_identity = {
        "checkpointObjects": 3,
        "code": {
            "source": {"bytes": 111, "sha256": "1" * 64},
            "store": {"bytes": 222, "sha256": "2" * 64},
        },
        "runtime": {"fixture": True},
        "schema": "flightalert.experiment8.osm-global-waterway-ingest-run.v1",
        "source": source,
    }
    connection = sqlite3.connect(path)
    try:
        connection.executescript(
            "CREATE TABLE meta(key TEXT PRIMARY KEY,value BLOB NOT NULL) WITHOUT ROWID;"
            "CREATE TABLE roots(kind INTEGER NOT NULL,id INTEGER NOT NULL,"
            "PRIMARY KEY(kind,id)) WITHOUT ROWID;"
            "CREATE TABLE nodes(id INTEGER PRIMARY KEY,version INTEGER NOT NULL,"
            "timestamp TEXT NOT NULL,longitude_e7 INTEGER NOT NULL,latitude_e7 INTEGER NOT NULL,"
            "tags BLOB NOT NULL,payload_sha BLOB NOT NULL);"
            "CREATE TABLE ways(id INTEGER PRIMARY KEY,version INTEGER NOT NULL,"
            "timestamp TEXT NOT NULL,tags BLOB NOT NULL,payload_sha BLOB NOT NULL);"
            "CREATE TABLE way_nodes(way_id INTEGER NOT NULL,ordinal INTEGER NOT NULL,"
            "node_id INTEGER NOT NULL,PRIMARY KEY(way_id,ordinal)) WITHOUT ROWID;"
            "CREATE TABLE relations(id INTEGER PRIMARY KEY,version INTEGER NOT NULL,"
            "timestamp TEXT NOT NULL,tags BLOB NOT NULL,payload_sha BLOB NOT NULL);"
            "CREATE TABLE relation_members(relation_id INTEGER NOT NULL,ordinal INTEGER NOT NULL,"
            "kind INTEGER NOT NULL,ref INTEGER NOT NULL,role TEXT NOT NULL,"
            "PRIMARY KEY(relation_id,ordinal)) WITHOUT ROWID;"
        )
        connection.execute(
            "INSERT INTO meta(key,value) VALUES (?,?)",
            ("runIdentity", _canonical_bytes(run_identity)),
        )
        connection.execute(
            "INSERT INTO meta(key,value) VALUES (?,?)",
            ("checkpoint", _canonical_bytes(checkpoint)),
        )
        node_ids = (8_000_000_000_000_001, 8_000_000_000_000_002)
        for ordinal, node_id in enumerate(node_ids):
            connection.execute(
                "INSERT INTO nodes VALUES (?,?,?,?,?,?,?)",
                (
                    node_id,
                    1,
                    "2026-06-29T00:00:00Z",
                    -700_000_000 + ordinal,
                    400_000_000,
                    _canonical_bytes([]),
                    hashlib.sha256(f"node-{node_id}".encode()).digest(),
                ),
            )
        way_id = 8_000_000_000_000_201
        relation_id = 8_000_000_000_000_401
        connection.execute(
            "INSERT INTO ways VALUES (?,?,?,?,?)",
            (
                way_id,
                1,
                "2026-06-29T00:00:00Z",
                _canonical_bytes([["waterway", "drain"]]),
                hashlib.sha256(b"legacy-way").digest(),
            ),
        )
        connection.executemany(
            "INSERT INTO way_nodes VALUES (?,?,?)",
            ((way_id, ordinal, node_id) for ordinal, node_id in enumerate(node_ids)),
        )
        connection.execute(
            "INSERT INTO relations VALUES (?,?,?,?,?)",
            (
                relation_id,
                1,
                "2026-06-29T00:00:00Z",
                _canonical_bytes([["name", "SyntheticLegacy"], ["type", "waterway"]]),
                hashlib.sha256(b"legacy-relation").digest(),
            ),
        )
        connection.execute(
            "INSERT INTO relation_members VALUES (?,?,?,?,?)",
            (relation_id, 0, 1, way_id, "main"),
        )
        connection.execute("INSERT INTO roots VALUES (?,?)", (2, relation_id))
        connection.commit()
    finally:
        connection.close()
    raw = path.read_bytes()
    return {
        "checkpoint": checkpoint,
        "database": {"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()},
        "legacyRunSchema": run_identity["schema"],
        "legacySourceModule": run_identity["code"]["source"],
        "legacyStore": run_identity["code"]["store"],
        "source": source,
    }


class ImmutableLegacyAdmissionAuditTests(unittest.TestCase):
    def test_fixture_audit_is_immutable_canonical_and_process_deterministic(self) -> None:
        from tools.experiment8.osm_global_waterway_legacy_admission_audit import (
            audit_immutable_legacy_database,
        )

        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "legacy-state.sqlite"
            pins = _legacy_database(database)
            pins_path = database.parent / "pins.json"
            pins_path.write_bytes(_canonical_bytes(pins))
            before = database.read_bytes()
            parent_before = sorted(path.name for path in database.parent.iterdir())
            outputs = []
            results = []
            script = (
                "import json,sys; from pathlib import Path; "
                "from tools.experiment8.osm_global_waterway_legacy_admission_audit "
                "import audit_immutable_legacy_database; "
                "audit_immutable_legacy_database(Path(sys.argv[1]),"
                "pins=json.loads(Path(sys.argv[2]).read_text(encoding='utf-8')),"
                "stdout=sys.stdout.buffer)"
            )
            for _ in range(2):
                completed = subprocess.run(
                    [sys.executable, "-c", script, str(database), str(pins_path)],
                    cwd=Path(__file__).parents[3],
                    check=False,
                    capture_output=True,
                )
                self.assertEqual(0, completed.returncode, completed.stderr.decode())
                outputs.append(completed.stdout)
                results.append(json.loads(completed.stdout.decode("utf-8")))
            self.assertEqual(outputs[0], outputs[1])
            self.assertEqual(results[0], results[1])
            self.assertEqual(before, database.read_bytes())
            self.assertEqual(parent_before, sorted(path.name for path in database.parent.iterdir()))
            self.assertEqual(
                "unsupported_member_way_waterway",
                results[0]["legacyFirstTerminal"]["histogram"]["values"][1]["reasonCode"],
            )
            self.assertEqual(1, results[0]["legacyFirstTerminal"]["ledger"]["count"])
            self.assertEqual(0, results[0]["structuralNoReachableWay"]["ledger"]["count"])
            self.assertTrue(results[0]["ingestReceiptAbsent"])
            self.assertEqual(
                pins["legacyStore"], results[0]["legacyIdentities"]["store"]
            )
            self.assertEqual(
                pins["legacySourceModule"],
                results[0]["legacyIdentities"]["sourceModule"],
            )
            self.assertTrue(outputs[0].endswith(b"\n"))
            self.assertNotIn(b"\r\n", outputs[0])
            self.assertEqual(_canonical_bytes(results[0]), outputs[0])

    def test_audit_refuses_one_byte_mutation_and_mismatched_legacy_state(self) -> None:
        from tools.experiment8.osm_global_waterway_legacy_admission_audit import (
            LegacyWaterwayAuditError,
            audit_immutable_legacy_database,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / "legacy-state.sqlite"
            pins = _legacy_database(database)
            mutated = root / "mutated.sqlite"
            raw = bytearray(database.read_bytes())
            raw[-1] ^= 1
            mutated.write_bytes(raw)
            with self.assertRaisesRegex(LegacyWaterwayAuditError, "SHA-256"):
                audit_immutable_legacy_database(mutated, pins=pins, stdout=io.BytesIO())

            connection = sqlite3.connect(database)
            try:
                checkpoint = dict(pins["checkpoint"])
                checkpoint["inputObjects"] += 1
                connection.execute(
                    "UPDATE meta SET value=? WHERE key='checkpoint'",
                    (_canonical_bytes(checkpoint),),
                )
                connection.commit()
            finally:
                connection.close()
            changed = database.read_bytes()
            repinned = dict(pins)
            repinned["database"] = {
                "bytes": len(changed),
                "sha256": hashlib.sha256(changed).hexdigest(),
            }
            with self.assertRaisesRegex(LegacyWaterwayAuditError, "checkpoint"):
                audit_immutable_legacy_database(
                    database, pins=repinned, stdout=io.BytesIO()
                )

    def test_audit_rejects_non_read_only_uri_before_sqlite_open(self) -> None:
        from tools.experiment8 import osm_global_waterway_legacy_admission_audit as audit

        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "legacy-state.sqlite"
            pins = _legacy_database(database)
            with patch.object(
                audit,
                "_immutable_sqlite_uri",
                return_value=database.resolve().as_uri() + "?mode=rw",
            ), patch.object(audit.sqlite3, "connect", wraps=sqlite3.connect) as connect:
                with self.assertRaisesRegex(audit.LegacyWaterwayAuditError, "read-only immutable"):
                    audit.audit_immutable_legacy_database(
                        database, pins=pins, stdout=io.BytesIO()
                    )
                connect.assert_not_called()

    def test_default_entry_point_is_pinned_to_exact_historical_database_and_facts(self) -> None:
        from tools.experiment8 import osm_global_waterway_legacy_admission_audit as audit

        self.assertEqual(
            Path(r"E:\FlightAlert-exp8-work\osm-global-waterway-260629-work\waterway-state.sqlite"),
            audit.HISTORICAL_DATABASE_PATH,
        )
        pins = audit.HISTORICAL_PINS
        self.assertEqual(35_272_974_336, pins["database"]["bytes"])
        self.assertEqual(
            "647daecc31e969c29e6756b50f21e5197946cefb034e7f8a42b33c6992bdb8eb",
            pins["database"]["sha256"],
        )
        self.assertEqual(
            "flightalert.experiment8.osm-global-waterway-ingest-run.v1",
            pins["legacyRunSchema"],
        )
        self.assertEqual(281_984_067, pins["checkpoint"]["inputObjects"])
        self.assertEqual(
            "a09e333ba22e8d9ed1025ea39073bce013a2e0d6c0f3b16889ef2fccdde99c9a",
            pins["legacyStore"]["sha256"],
        )
        self.assertEqual(
            "eb7a5b54d0b9efa7facb67eb13d6420c1c5639613f1a9f7658c4f563bede0d93",
            pins["legacySourceModule"]["sha256"],
        )


if __name__ == "__main__":
    unittest.main()
