from __future__ import annotations

import hashlib
import json
import os
import sqlite3
import stat
import sys
from pathlib import Path
from types import MappingProxyType
from typing import BinaryIO, Mapping
from urllib.parse import parse_qs, urlsplit


HISTORICAL_DATABASE_PATH = Path(
    r"E:\FlightAlert-exp8-work\osm-global-waterway-260629-work\waterway-state.sqlite"
)
HISTORICAL_PINS: Mapping[str, object] = MappingProxyType(
    {
        "checkpoint": {
            "ingestComplete": True,
            "inputObjects": 281_984_067,
            "lineNumber": 281_984_067,
            "nextByteOffset": 31_201_581_334,
            "previousId": 21_041_545,
            "previousKind": 2,
        },
        "database": {
            "bytes": 35_272_974_336,
            "sha256": (
                "647daecc31e969c29e6756b50f21e5197946cefb034e7f8a42b33c6992bdb8eb"
            ),
        },
        "legacyExpected": {
            "ledger": {
                "bytes": 94_632,
                "count": 10_674,
                "sha256": (
                    "e63bb8e77f0fa8c432492028ba5a00b6da43a10cbd7cabdf10ad4435b0c422f2"
                ),
            },
            "noReachableWayCount": 19,
            "reasonCounts": {
                "no_usable_way_geometry": 12,
                "relation_cycle": 1,
                "unsupported_member_way_waterway": 1_357,
                "unsupported_relation_waterway": 9_304,
            },
        },
        "legacyRunSchema": (
            "flightalert.experiment8.osm-global-waterway-ingest-run.v1"
        ),
        "legacySourceModule": {
            "bytes": 96_322,
            "sha256": (
                "eb7a5b54d0b9efa7facb67eb13d6420c1c5639613f1a9f7658c4f563bede0d93"
            ),
        },
        "legacyStore": {
            "bytes": 113_819,
            "sha256": (
                "a09e333ba22e8d9ed1025ea39073bce013a2e0d6c0f3b16889ef2fccdde99c9a"
            ),
        },
        "source": {
            "closureOpl": {
                "bytes": 31_201_581_334,
                "sha256": (
                    "6d9b1c25352b008180181f90d5343534dba9d3a3757ef27b5b30fe56549b1461"
                ),
            },
            "extractionReceiptSha256": (
                "7677d49d854e18bf1ed4604a61c1213a3ad0b684533c0162fa7d5e16d0acc0f7"
            ),
            "rootIds": {
                "bytes": 60_323_109,
                "sha256": (
                    "e2024e9ef743d1ae76ad3b91f117d2af7797cb2fd1307b65bcbdbb34d740e2a4"
                ),
            },
            "selectionManifestSha256": (
                "405e356bd2b7638c347e3e884d6702b44d60ea8acea61e163bef1c47d24fb46c"
            ),
            "selectionPolicySha256": (
                "7ddea49ea1501790519b6b47c2cd8170ce3043218551f1b978c98ffb35e7b50c"
            ),
        },
    }
)

_SUPPORTED_WATERWAYS = frozenset(
    ("river", "stream", "canal", "tidal_channel", "wadi")
)
_LEGACY_REASON_ORDER = (
    "unsupported_relation_waterway",
    "unsupported_member_way_waterway",
    "no_usable_way_geometry",
    "relation_cycle",
)
_REPARSE_POINT = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)


class LegacyWaterwayAuditError(RuntimeError):
    pass


def _canonical_bytes(value: object) -> bytes:
    try:
        return (
            json.dumps(
                value,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
                allow_nan=False,
            )
            + "\n"
        ).encode("utf-8", "strict")
    except (TypeError, ValueError, UnicodeError) as error:
        raise LegacyWaterwayAuditError("legacy audit JSON is not canonical") from error


def _file_signature(path: Path) -> tuple[int, int, int, int]:
    try:
        status = os.lstat(path)
    except OSError as error:
        raise LegacyWaterwayAuditError(
            "legacy database is not one regular non-link file"
        ) from error
    if (
        not stat.S_ISREG(status.st_mode)
        or getattr(status, "st_file_attributes", 0) & _REPARSE_POINT
    ):
        raise LegacyWaterwayAuditError(
            "legacy database is not one regular non-link file"
        )
    return status.st_dev, status.st_ino, status.st_size, status.st_mtime_ns


def _parent_inventory(path: Path) -> tuple[tuple[object, ...], ...]:
    result = []
    try:
        entries = list(path.parent.iterdir())
    except OSError as error:
        raise LegacyWaterwayAuditError(
            "legacy database parent inventory is unavailable"
        ) from error
    for entry in sorted(entries, key=lambda item: item.name):
        status = os.lstat(entry)
        result.append(
            (
                entry.name,
                stat.S_IFMT(status.st_mode),
                status.st_dev,
                status.st_ino,
                status.st_size,
                status.st_mtime_ns,
            )
        )
    return tuple(result)


def _inventory_fact(inventory: tuple[tuple[object, ...], ...]) -> dict[str, object]:
    raw = _canonical_bytes([list(item) for item in inventory])
    return {
        "entries": len(inventory),
        "sha256": hashlib.sha256(raw).hexdigest(),
    }


def _stream_database_identity(path: Path) -> dict[str, object]:
    digest = hashlib.sha256()
    total = 0
    with path.open("rb") as handle:
        while True:
            block = handle.read(8 * 1024 * 1024)
            if not block:
                break
            digest.update(block)
            total += len(block)
    return {"bytes": total, "sha256": digest.hexdigest()}


def _immutable_sqlite_uri(path: Path) -> str:
    return path.resolve().as_uri() + "?mode=ro&immutable=1"


def _require_immutable_uri(uri: str) -> None:
    parsed = urlsplit(uri)
    query = parse_qs(parsed.query, keep_blank_values=True)
    if query != {"immutable": ["1"], "mode": ["ro"]}:
        raise LegacyWaterwayAuditError(
            "legacy SQLite URI is not exact read-only immutable mode"
        )


def _meta(connection: sqlite3.Connection, key: str) -> object | None:
    row = connection.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
    if row is None:
        return None
    try:
        return json.loads(bytes(row[0]).decode("utf-8", "strict"))
    except (UnicodeError, json.JSONDecodeError, TypeError) as error:
        raise LegacyWaterwayAuditError(
            f"legacy database meta {key!r} is malformed"
        ) from error


def _require_subset(actual: object, expected: object, label: str) -> None:
    if isinstance(expected, Mapping):
        if not isinstance(actual, Mapping):
            raise LegacyWaterwayAuditError(f"legacy {label} differs")
        for key, value in expected.items():
            if key not in actual:
                raise LegacyWaterwayAuditError(f"legacy {label} differs")
            _require_subset(actual[key], value, f"{label}.{key}")
        return
    if actual != expected:
        raise LegacyWaterwayAuditError(f"legacy {label} differs")


def _tags(raw: object, label: str) -> dict[str, str]:
    try:
        value = json.loads(bytes(raw).decode("utf-8", "strict"))
    except (UnicodeError, json.JSONDecodeError, TypeError) as error:
        raise LegacyWaterwayAuditError(f"legacy {label} tags are malformed") from error
    if not isinstance(value, list):
        raise LegacyWaterwayAuditError(f"legacy {label} tags are malformed")
    result: dict[str, str] = {}
    for item in value:
        if (
            not isinstance(item, list)
            or len(item) != 2
            or not all(isinstance(component, str) for component in item)
            or item[0] in result
        ):
            raise LegacyWaterwayAuditError(f"legacy {label} tags are malformed")
        result[item[0]] = item[1]
    return result


def _legacy_first_terminal(
    connection: sqlite3.Connection,
    relation_id: int,
    *,
    inherited_type: str | None = None,
    stack: tuple[int, ...] = (),
) -> tuple[str | None, bool]:
    if relation_id in stack:
        return "relation_cycle", False
    row = connection.execute(
        "SELECT tags FROM relations WHERE id=?", (relation_id,)
    ).fetchone()
    if row is None:
        raise LegacyWaterwayAuditError(
            f"legacy relation traversal references missing relation {relation_id}"
        )
    declared = _tags(row[0], f"relation {relation_id}").get("waterway")
    if declared is not None and declared not in _SUPPORTED_WATERWAYS:
        return "unsupported_relation_waterway", False
    effective = declared or inherited_type
    found = False
    rows = connection.execute(
        "SELECT ordinal,kind,ref FROM relation_members "
        "WHERE relation_id=? ORDER BY ordinal",
        (relation_id,),
    )
    try:
        for expected, (ordinal, kind, ref) in enumerate(rows):
            if int(ordinal) != expected:
                raise LegacyWaterwayAuditError(
                    "legacy relation member order is malformed"
                )
            kind = int(kind)
            ref = int(ref)
            if kind == 0:
                if connection.execute(
                    "SELECT 1 FROM nodes WHERE id=?", (ref,)
                ).fetchone() is None:
                    raise LegacyWaterwayAuditError(
                        f"legacy relation references missing node {ref}"
                    )
                continue
            if kind == 1:
                way = connection.execute(
                    "SELECT tags FROM ways WHERE id=?", (ref,)
                ).fetchone()
                if way is None:
                    raise LegacyWaterwayAuditError(
                        f"legacy relation references missing way {ref}"
                    )
                exact = _tags(way[0], f"way {ref}").get("waterway")
                if (effective or exact) not in _SUPPORTED_WATERWAYS:
                    return "unsupported_member_way_waterway", found
                found = True
                continue
            if kind == 2:
                reason, child_found = _legacy_first_terminal(
                    connection,
                    ref,
                    inherited_type=effective,
                    stack=stack + (relation_id,),
                )
                if reason is not None:
                    return reason, found or child_found
                found = found or child_found
                continue
            raise LegacyWaterwayAuditError(
                f"legacy relation member kind {kind} is unsupported"
            )
    finally:
        rows.close()
    return None, found


def _has_reachable_way(
    connection: sqlite3.Connection,
    relation_id: int,
    stack: tuple[int, ...] = (),
) -> bool:
    if relation_id in stack:
        return False
    if connection.execute(
        "SELECT 1 FROM relations WHERE id=?", (relation_id,)
    ).fetchone() is None:
        raise LegacyWaterwayAuditError(
            f"legacy structural traversal references missing relation {relation_id}"
        )
    found = False
    rows = connection.execute(
        "SELECT ordinal,kind,ref FROM relation_members "
        "WHERE relation_id=? ORDER BY ordinal",
        (relation_id,),
    )
    try:
        for expected, (ordinal, kind, ref) in enumerate(rows):
            if int(ordinal) != expected:
                raise LegacyWaterwayAuditError(
                    "legacy structural relation member order is malformed"
                )
            kind = int(kind)
            ref = int(ref)
            if kind == 1:
                if connection.execute(
                    "SELECT 1 FROM ways WHERE id=?", (ref,)
                ).fetchone() is None:
                    raise LegacyWaterwayAuditError(
                        f"legacy structural traversal references missing way {ref}"
                    )
                found = True
            elif kind == 2:
                found = _has_reachable_way(
                    connection, ref, stack + (relation_id,)
                ) or found
            elif kind == 0:
                continue
            else:
                raise LegacyWaterwayAuditError(
                    f"legacy structural member kind {kind} is unsupported"
                )
    finally:
        rows.close()
    return found


def _ledger(values: list[int]) -> dict[str, object]:
    raw = (json.dumps(sorted(values), separators=(",", ":")) + "\n").encode(
        "utf-8"
    )
    return {
        "bytes": len(raw),
        "count": len(values),
        "sha256": hashlib.sha256(raw).hexdigest(),
    }


def _audit_relations(connection: sqlite3.Connection) -> dict[str, object]:
    counts = {reason: 0 for reason in _LEGACY_REASON_ORDER}
    first_terminal_ids: list[int] = []
    no_way_ids: list[int] = []
    roots = connection.execute(
        "SELECT id FROM roots WHERE kind=2 ORDER BY id"
    )
    try:
        for (raw_relation_id,) in roots:
            relation_id = int(raw_relation_id)
            reason, found = _legacy_first_terminal(connection, relation_id)
            if reason is None and not found:
                reason = "no_usable_way_geometry"
            if reason is not None:
                counts[reason] += 1
                first_terminal_ids.append(relation_id)
            if not _has_reachable_way(connection, relation_id):
                no_way_ids.append(relation_id)
    finally:
        roots.close()
    return {
        "legacyFirstTerminal": {
            "histogram": {
                "unit": "roots",
                "values": [
                    {"reasonCode": reason, "roots": counts[reason]}
                    for reason in _LEGACY_REASON_ORDER
                ],
            },
            "ledger": _ledger(first_terminal_ids),
        },
        "structuralNoReachableWay": {"ledger": _ledger(no_way_ids)},
    }


def audit_immutable_legacy_database(
    database_path: Path,
    *,
    pins: Mapping[str, object],
    stdout: BinaryIO,
) -> Mapping[str, object]:
    if not isinstance(database_path, Path) or not isinstance(pins, Mapping):
        raise LegacyWaterwayAuditError("legacy audit inputs are invalid")
    if not hasattr(stdout, "write") or not hasattr(stdout, "flush"):
        raise LegacyWaterwayAuditError("legacy audit stdout is invalid")
    before_signature = _file_signature(database_path)
    before_inventory = _parent_inventory(database_path)
    for suffix in ("-journal", "-wal", "-shm"):
        if Path(str(database_path) + suffix).exists():
            raise LegacyWaterwayAuditError(
                "legacy database has a forbidden SQLite sidecar"
            )
    actual_database = _stream_database_identity(database_path)
    if actual_database != pins.get("database"):
        label = (
            "legacy database SHA-256 differs"
            if actual_database.get("sha256")
            != (pins.get("database") or {}).get("sha256")
            else "legacy database byte length differs"
        )
        raise LegacyWaterwayAuditError(label)
    if _file_signature(database_path) != before_signature:
        raise LegacyWaterwayAuditError("legacy database changed while hashing")
    uri = _immutable_sqlite_uri(database_path)
    _require_immutable_uri(uri)
    connection = sqlite3.connect(uri, uri=True)
    try:
        connection.execute("PRAGMA query_only=ON")
        query_only = int(connection.execute("PRAGMA query_only").fetchone()[0])
        if query_only != 1:
            raise LegacyWaterwayAuditError(
                "legacy SQLite connection is not query-only"
            )
        run_identity = _meta(connection, "runIdentity")
        checkpoint = _meta(connection, "checkpoint")
        if _meta(connection, "ingestReceipt") is not None:
            raise LegacyWaterwayAuditError(
                "legacy ingest receipt must be absent"
            )
        if not isinstance(run_identity, Mapping):
            raise LegacyWaterwayAuditError("legacy run identity is absent")
        _require_subset(
            run_identity.get("schema"), pins.get("legacyRunSchema"), "run schema"
        )
        _require_subset(checkpoint, pins.get("checkpoint"), "checkpoint")
        code = run_identity.get("code")
        if not isinstance(code, Mapping):
            raise LegacyWaterwayAuditError("legacy code identity is absent")
        _require_subset(
            code.get("store"), pins.get("legacyStore"), "store identity"
        )
        _require_subset(
            code.get("source"),
            pins.get("legacySourceModule"),
            "source module identity",
        )
        _require_subset(
            run_identity.get("source"), pins.get("source"), "source identity"
        )
        relation_evidence = _audit_relations(connection)
    finally:
        connection.close()
    after_signature = _file_signature(database_path)
    after_inventory = _parent_inventory(database_path)
    if after_signature != before_signature or after_inventory != before_inventory:
        raise LegacyWaterwayAuditError(
            "legacy database or exact parent inventory changed during immutable audit"
        )
    for suffix in ("-journal", "-wal", "-shm"):
        if Path(str(database_path) + suffix).exists():
            raise LegacyWaterwayAuditError(
                "legacy immutable audit created a SQLite sidecar"
            )
    expected = pins.get("legacyExpected")
    if isinstance(expected, Mapping):
        actual_counts = {
            item["reasonCode"]: item["roots"]
            for item in relation_evidence["legacyFirstTerminal"]["histogram"][
                "values"
            ]
        }
        _require_subset(
            actual_counts, expected.get("reasonCounts"), "legacy reason reconciliation"
        )
        _require_subset(
            relation_evidence["legacyFirstTerminal"]["ledger"],
            expected.get("ledger"),
            "legacy ledger reconciliation",
        )
        _require_subset(
            relation_evidence["structuralNoReachableWay"]["ledger"]["count"],
            expected.get("noReachableWayCount"),
            "structural no-way reconciliation",
        )
    result: dict[str, object] = {
        "checkpoint": checkpoint,
        "database": actual_database,
        "ingestReceiptAbsent": True,
        "immutability": {
            "databaseSignature": {
                "bytes": before_signature[2],
                "mtimeNs": before_signature[3],
                "stDev": before_signature[0],
                "stIno": before_signature[1],
            },
            "parentInventory": _inventory_fact(before_inventory),
            "queryOnly": True,
            "sqliteUriMode": "mode=ro&immutable=1",
        },
        "legacyIdentities": {
            "sourceModule": pins["legacySourceModule"],
            "store": pins["legacyStore"],
        },
        **relation_evidence,
        "schema": "flightalert.experiment8.osm-waterway-legacy-admission-audit.v1",
    }
    raw = _canonical_bytes(result)
    if stdout.write(raw) != len(raw):
        raise LegacyWaterwayAuditError("legacy audit stdout write was incomplete")
    stdout.flush()
    return result


def main() -> int:
    stdout = getattr(sys.stdout, "buffer", None)
    if stdout is None:
        raise LegacyWaterwayAuditError(
            "legacy audit requires an exact binary stdout stream"
        )
    audit_immutable_legacy_database(
        HISTORICAL_DATABASE_PATH,
        pins=HISTORICAL_PINS,
        stdout=stdout,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())


__all__ = [
    "HISTORICAL_DATABASE_PATH",
    "HISTORICAL_PINS",
    "LegacyWaterwayAuditError",
    "audit_immutable_legacy_database",
    "main",
]
