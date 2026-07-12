from __future__ import annotations

import io
import contextlib
import hashlib
import json
import os
import sys
import tempfile
import threading
import time
import subprocess
import unittest
from dataclasses import replace
from pathlib import Path
from unittest import mock

import tools.experiment8.osm_planet_selection as selection_module
import tools.experiment8.osm_planet_selection_verifier as verifier_module
import tools.experiment8.run_osm_global_selection as runner_module


try:
    from tools.experiment8.run_osm_global_selection import (
        ProgressReader,
        FileLock,
        AtomicRunLock,
        AttemptJournal,
        RunConfig,
        RunnerError,
        canonical_json_bytes,
        compute_space_watermark,
        current_process_identity,
        load_config,
        preflight,
        process_identity_is_alive,
        inspect_run,
        run_global_selection,
    )
except (ModuleNotFoundError, ImportError):
    ProgressReader = None
    FileLock = None
    AtomicRunLock = None
    AttemptJournal = None
    RunConfig = None
    RunnerError = RuntimeError
    canonical_json_bytes = None
    compute_space_watermark = None
    current_process_identity = None
    load_config = None
    preflight = None
    process_identity_is_alive = None
    inspect_run = None
    run_global_selection = None


class _ExplodingStream:
    def __init__(self) -> None:
        self._reads = 0

    def read(self, size: int = -1) -> bytes:
        self._reads += 1
        if self._reads == 1:
            return b"abc"
        raise RuntimeError("fixture reader exploded")


class RunnerPrimitiveTests(unittest.TestCase):
    def test_canonical_json_is_exact_utf8_sorted_single_line(self) -> None:
        self.assertIsNotNone(canonical_json_bytes)
        assert canonical_json_bytes is not None
        document = {"z": "M\u00e9xico", "a": [2, 1]}
        raw = canonical_json_bytes(document)
        self.assertEqual(raw, b'{"a":[2,1],"z":"M\xc3\xa9xico"}\n')
        self.assertEqual(json.loads(raw), document)

    def test_progress_reader_preserves_read_seek_eof_and_errors(self) -> None:
        self.assertIsNotNone(ProgressReader)
        assert ProgressReader is not None
        source = io.BytesIO(b"0123456789")
        progress = ProgressReader(source, total_bytes=10)

        self.assertEqual(progress.read(3), b"012")
        self.assertEqual(progress.tell(), 3)
        self.assertEqual(progress.seek(1), 1)
        target = bytearray(4)
        self.assertEqual(progress.readinto(target), 4)
        self.assertEqual(bytes(target), b"1234")
        self.assertEqual(progress.read(), b"56789")
        self.assertEqual(progress.read(), b"")
        self.assertEqual(progress.bytes_consumed, 12)
        self.assertEqual(progress.total_bytes, 10)

        exploding = ProgressReader(_ExplodingStream(), total_bytes=3)
        self.assertEqual(exploding.read(), b"abc")
        with self.assertRaisesRegex(RuntimeError, "fixture reader exploded"):
            exploding.read()
        self.assertEqual(exploding.bytes_consumed, 3)

        lines = ProgressReader(io.BytesIO(b"aa\nbbb\ncccc"), total_bytes=11)
        self.assertEqual(lines.readline(), b"aa\n")
        self.assertEqual(lines.readlines(4), [b"bbb\n"])
        self.assertEqual(next(lines), b"cccc")
        with self.assertRaises(StopIteration):
            next(lines)
        self.assertEqual(lines.bytes_consumed, 11)

        buffered = ProgressReader(io.BufferedReader(io.BytesIO(b"xyz123")), total_bytes=6)
        self.assertEqual(buffered.read1(2), b"xy")
        target = bytearray(2)
        self.assertEqual(buffered.readinto1(target), 2)
        self.assertEqual(bytes(target), b"z1")
        self.assertEqual(buffered.read(), b"23")
        self.assertEqual(buffered.bytes_consumed, 6)

    def test_space_watermark_exposes_every_conservative_component(self) -> None:
        self.assertIsNotNone(compute_space_watermark)
        assert compute_space_watermark is not None
        result = compute_space_watermark(
            production_opl_bytes=4_347_353_464,
            broad_opl_bytes=16_008_341_070,
        )
        self.assertEqual(result["reserveBytes"], 5_000_000_000)
        self.assertGreaterEqual(result["productionOutputBytes"], 2 * 4_347_353_464)
        self.assertGreaterEqual(
            result["verifierScratchOutputBytes"],
            16_008_341_070 + 4_347_353_464,
        )
        self.assertEqual(
            result["atomicDuplicateBytes"],
            result["productionOutputBytes"]
            + result["verifierScratchOutputBytes"],
        )
        components = (
            "reserveBytes",
            "productionOutputBytes",
            "verifierScratchOutputBytes",
            "atomicDuplicateBytes",
            "documentaryBytes",
        )
        self.assertEqual(result["requiredFreeBytes"], sum(result[key] for key in components))

    def test_scratch_inventory_tolerates_only_entry_disappearance_during_cleanup(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            transient = root / "transient.bin"
            stable = root / "stable.bin"
            transient.write_bytes(b"gone")
            stable.write_bytes(b"stable")
            original_lstat = runner_module.os.lstat

            def racing_lstat(path):
                if Path(path) == transient and transient.exists():
                    transient.unlink()
                    raise FileNotFoundError("injected concurrent scratch cleanup")
                return original_lstat(path)

            with mock.patch.object(runner_module.os, "lstat", side_effect=racing_lstat):
                inventory = runner_module._directory_inventory(root)
            self.assertEqual(inventory["fileCount"], 1)
            self.assertEqual(inventory["bytes"], len(b"stable"))
            self.assertEqual(inventory["transientMissingEntries"], 1)
            self.assertEqual(inventory["entries"], [{"bytes": 6, "path": "stable.bin"}])


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while True:
            raw = handle.read(1024 * 1024)
            if not raw:
                return digest.hexdigest()
            digest.update(raw)


class RunnerConfigTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.runs = self.root / "runs"
        self.mirror = self.root / "mirror"
        self.runs.mkdir()
        self.mirror.mkdir()
        self.inputs = self.root / "inputs"
        self.inputs.mkdir()
        self.paths = {
            "dependencyLock": self.inputs / "requirements.lock",
            "planetPbf": self.inputs / "planet.pbf",
            "productionPbf": self.inputs / "production.pbf",
            "productionOpl": self.inputs / "production.opl",
            "broadPbf": self.inputs / "broad.pbf",
            "broadOpl": self.inputs / "broad.opl",
        }
        payloads = {
            "dependencyLock": b"fixture-dependency==1.0\n",
            "planetPbf": b"tiny planet identity",
            "productionPbf": b"tiny production pbf",
            "productionOpl": (
                b"w1 v1 t2026-06-28T23:59:59Z Tname=River,waterway=river Nn1,n2\n"
            ),
            "broadPbf": b"tiny broad pbf",
            "broadOpl": (
                b"w1 v1 t2026-06-28T23:59:59Z Tname=River,waterway=river Nn1,n2\n"
            ),
        }
        for role, path in self.paths.items():
            path.write_bytes(payloads[role])

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _lock(self, path: Path):
        assert FileLock is not None
        return FileLock(path=path, bytes=path.stat().st_size, sha256=_sha256(path))

    def _config(self):
        assert RunConfig is not None
        interpreter = Path(sys.executable).resolve(strict=True)
        return RunConfig(
            runs_root=self.runs,
            mirror_root=self.mirror,
            interpreter=self._lock(interpreter),
            dependency_lock=self._lock(self.paths["dependencyLock"]),
            planet_pbf=self._lock(self.paths["planetPbf"]),
            production_pbf=self._lock(self.paths["productionPbf"]),
            production_opl=self._lock(self.paths["productionOpl"]),
            broad_pbf=self._lock(self.paths["broadPbf"]),
            broad_opl=self._lock(self.paths["broadOpl"]),
            selector_sha256=_sha256(Path(selection_module.__file__).resolve()),
            verifier_sha256=_sha256(Path(verifier_module.__file__).resolve()),
            policy_sha256=selection_module.GLOBAL_POLICY_SHA256,
            selection_profile=selection_module.FIXTURE_NAME_ENVELOPE_PROFILE,
            verification_profile=verifier_module.FIXTURE_BROAD_ENVELOPE_PROFILE,
            workers=1,
            heartbeat_seconds=1,
        )

    def test_legacy_config_without_authenticated_reference_limits_is_rejected(self) -> None:
        document = self._config().to_document()
        document.pop("limits", None)

        with self.assertRaisesRegex(RunnerError, "runner config keys mismatch"):
            RunConfig.from_document(document)

    def test_reference_limits_are_exact_finite_grammar_bounds_and_reject_drift(self) -> None:
        limits_type = getattr(runner_module, "AuthenticatedReferenceLimits", None)
        self.assertIsNotNone(limits_type)
        limits = limits_type.from_authenticated_opl_bytes(
            4_347_353_464,
            16_008_341_070,
        )
        self.assertEqual(limits.selection_max_total_references, 2_173_676_732)
        self.assertEqual(limits.verification_max_total_references, 8_004_170_535)

        canonical = self._config().to_document()
        expected = canonical["limits"]["selection"]["maxTotalReferences"]
        for unsafe in (expected - 1, expected + 1):
            with self.subTest(unsafe=unsafe):
                document = json.loads(json.dumps(canonical))
                document["limits"]["selection"]["maxTotalReferences"] = unsafe
                document["limits"]["verification"]["maxTotalReferences"] = unsafe
                with self.assertRaisesRegex(
                    RunnerError,
                    "not exactly bound to the authenticated OPL byte identities",
                ):
                    RunConfig.from_document(document)

    def test_canonical_config_round_trip_and_explicit_cli_code_locks(self) -> None:
        self.assertIsNotNone(load_config)
        assert load_config is not None
        config = self._config()
        path = self.root / "runner-config.json"
        path.write_bytes(canonical_json_bytes(config.to_document()))

        loaded = load_config(
            path,
            selector_sha256=config.selector_sha256,
            verifier_sha256=config.verifier_sha256,
            policy_sha256=config.policy_sha256,
        )
        self.assertEqual(loaded, config)

        path.write_bytes(json.dumps(config.to_document(), indent=2).encode("utf-8"))
        with self.assertRaisesRegex(RunnerError, "canonical"):
            load_config(
                path,
                selector_sha256=config.selector_sha256,
                verifier_sha256=config.verifier_sha256,
                policy_sha256=config.policy_sha256,
            )
        path.write_bytes(canonical_json_bytes(config.to_document()))
        with self.assertRaisesRegex(RunnerError, "explicit selector"):
            load_config(
                path,
                selector_sha256="0" * 64,
                verifier_sha256=config.verifier_sha256,
                policy_sha256=config.policy_sha256,
            )

    def test_preflight_binds_runtime_code_policy_inputs_and_computed_space(self) -> None:
        self.assertIsNotNone(preflight)
        assert preflight is not None
        context = preflight(self._config())
        try:
            self.assertEqual(context.runtime["interpreter"]["sha256"], _sha256(Path(sys.executable).resolve()))
            self.assertEqual(context.identities["selectorSha256"], self._config().selector_sha256)
            self.assertEqual(context.identities["verifierSha256"], self._config().verifier_sha256)
            self.assertEqual(
                context.identities["runnerSha256"],
                _sha256(Path(runner_module.__file__).resolve()),
            )
            self.assertEqual(context.identities["policySha256"], selection_module.GLOBAL_POLICY_SHA256)
            self.assertEqual(context.identities["runtimeSha256"], context.runtime_sha256)
            self.assertEqual(set(context.inputs), {
                "planetPbf", "productionPbf", "productionOpl", "broadPbf", "broadOpl"
            })
            self.assertGreaterEqual(context.free_bytes, context.space_watermark["requiredFreeBytes"])
            self.assertRegex(context.content_run_id, r"\Afae8-osm-global-[0-9a-f]{32}\Z")
            self.assertEqual(list(self.runs.iterdir()), [])
        finally:
            context.close()

    def test_content_run_identity_includes_worker_setting_that_changes_verifier_observation(self) -> None:
        first = preflight(self._config())
        second = preflight(replace(self._config(), workers=2))
        try:
            self.assertNotEqual(first.content_run_id, second.content_run_id)
        finally:
            first.close()
            second.close()

    def test_every_identity_and_space_mismatch_fails_before_attempt_output(self) -> None:
        self.assertIsNotNone(preflight)
        assert preflight is not None
        base = self._config()
        wrong = "0" * 64
        cases = (
            replace(base, interpreter=replace(base.interpreter, sha256=wrong)),
            replace(base, dependency_lock=replace(base.dependency_lock, sha256=wrong)),
            replace(base, selector_sha256=wrong),
            replace(base, verifier_sha256=wrong),
            replace(base, policy_sha256=wrong),
            replace(base, planet_pbf=replace(base.planet_pbf, bytes=base.planet_pbf.bytes + 1)),
            replace(base, production_pbf=replace(base.production_pbf, sha256=wrong)),
            replace(base, production_opl=replace(base.production_opl, sha256=wrong)),
            replace(base, broad_pbf=replace(base.broad_pbf, sha256=wrong)),
            replace(base, broad_opl=replace(base.broad_opl, sha256=wrong)),
        )
        for case in cases:
            with self.subTest(case=case):
                with self.assertRaises(RunnerError):
                    preflight(case)
                self.assertEqual(list(self.runs.iterdir()), [])

        disk_usage = os.statvfs(self.runs) if hasattr(os, "statvfs") else None
        del disk_usage
        with mock.patch(
            "tools.experiment8.run_osm_global_selection.shutil.disk_usage",
            return_value=mock.Mock(total=10, used=9, free=1),
        ):
            with self.assertRaisesRegex(RunnerError, "free space"):
                preflight(base)
        self.assertEqual(list(self.runs.iterdir()), [])

    def test_noncanonical_or_reparse_input_path_is_rejected(self) -> None:
        self.assertIsNotNone(preflight)
        assert preflight is not None
        config = self._config()
        noncanonical = Path(str(self.paths["productionOpl"].parent / ".." / "inputs" / "production.opl"))
        with self.assertRaisesRegex(RunnerError, "canonical"):
            preflight(replace(config, production_opl=replace(config.production_opl, path=noncanonical)))
        self.assertEqual(list(self.runs.iterdir()), [])

        link = self.root / "linked-production.opl"
        try:
            link.symlink_to(self.paths["productionOpl"])
        except OSError:
            self.skipTest("host cannot create a symlink fixture")
        with self.assertRaisesRegex(RunnerError, "canonical|reparse|symbolic"):
            preflight(replace(config, production_opl=replace(config.production_opl, path=link)))
        self.assertEqual(list(self.runs.iterdir()), [])


class RunnerLeaseAndJournalTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.runs = self.root / "runs"
        self.runs.mkdir()

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_process_identity_rejects_pid_reuse_token(self) -> None:
        self.assertIsNotNone(current_process_identity)
        self.assertIsNotNone(process_identity_is_alive)
        assert current_process_identity is not None
        assert process_identity_is_alive is not None
        identity = current_process_identity()
        self.assertTrue(process_identity_is_alive(identity))
        forged = dict(identity)
        forged["processStartToken"] = "forged-start-token"
        self.assertFalse(process_identity_is_alive(forged))

    def test_atomic_runner_race_admits_exactly_one_live_owner(self) -> None:
        self.assertIsNotNone(AtomicRunLock)
        self.assertIsNotNone(current_process_identity)
        assert AtomicRunLock is not None
        assert current_process_identity is not None
        identity = current_process_identity()
        barrier = threading.Barrier(2)
        winner_holds = threading.Event()
        release_winner = threading.Event()
        outcomes: list[str] = []
        outcome_lock = threading.Lock()

        def contend() -> None:
            barrier.wait()
            try:
                lease = AtomicRunLock.acquire(self.runs, identity)
            except RunnerError:
                with outcome_lock:
                    outcomes.append("rejected")
                return
            with outcome_lock:
                outcomes.append("owner")
            winner_holds.set()
            release_winner.wait(5)
            lease.release()

        threads = [threading.Thread(target=contend) for _ in range(2)]
        for thread in threads:
            thread.start()
        self.assertTrue(winner_holds.wait(5))
        time.sleep(0.05)
        release_winner.set()
        for thread in threads:
            thread.join(5)
            self.assertFalse(thread.is_alive())
        self.assertEqual(sorted(outcomes), ["owner", "rejected"])
        self.assertFalse((self.runs / ".global-selection.lock").exists())

    def test_lock_reclaims_only_proven_stale_identity_not_malformed_owner(self) -> None:
        identity = current_process_identity()
        lock_dir = self.runs / ".global-selection.lock"
        lock_dir.mkdir()
        (lock_dir / "owner.json").write_bytes(b"{}\n")
        with self.assertRaisesRegex(RunnerError, "unverifiable"):
            AtomicRunLock.acquire(self.runs, identity)
        self.assertTrue(lock_dir.is_dir())
        (lock_dir / "owner.json").unlink()
        lock_dir.rmdir()

        stale = dict(identity)
        stale["processStartToken"] = "proven-reused-pid"
        lock_dir.mkdir()
        owner = {
            "acquiredUtc": "2026-07-11T00:00:00.000Z",
            "processIdentity": stale,
            "schema": "flight-alert-exp8-osm-global-lock-v1",
            "token": "1" * 64,
        }
        (lock_dir / "owner.json").write_bytes(canonical_json_bytes(owner))
        lease = AtomicRunLock.acquire(self.runs, identity)
        lease.release()
        self.assertFalse(lock_dir.exists())

    def test_journal_heartbeat_is_canonical_monotonic_atomic_and_non_daemon(self) -> None:
        self.assertIsNotNone(AttemptJournal)
        self.assertIsNotNone(current_process_identity)
        assert AttemptJournal is not None
        assert current_process_identity is not None
        attempt = self.runs / "run" / "attempts" / "attempt-1"
        attempt.mkdir(parents=True)
        progress = ProgressReader(io.BytesIO(b"abcdef"), total_bytes=6)
        journal = AttemptJournal(
            attempt_dir=attempt,
            run_id="fae8-osm-global-" + "1" * 32,
            attempt_id="attempt-1",
            process_identity=current_process_identity(),
            identities={"policySha256": "2" * 64},
            paths={"productionStage": str(attempt / "production")},
            progress={"productionOpl": progress},
            free_bytes=9_000_000_000,
            heartbeat_interval_seconds=0.05,
        )
        journal.start()
        self.assertFalse(journal.thread.daemon)
        progress.read(2)
        time.sleep(0.14)
        before = json.loads((attempt / "status.json").read_bytes())
        journal.transition("producer")
        time.sleep(0.08)
        journal.stop()
        after_raw = (attempt / "status.json").read_bytes()
        after = json.loads(after_raw)

        self.assertEqual(after_raw, canonical_json_bytes(after))
        self.assertGreater(after["heartbeatSequence"], before["heartbeatSequence"])
        self.assertGreaterEqual(after["elapsedNanoseconds"], before["elapsedNanoseconds"])
        self.assertEqual(after["phase"], "producer")
        self.assertEqual(after["progress"]["productionOpl"]["bytesConsumed"], 2)
        self.assertEqual(after["lastDurableTransition"]["phase"], "producer")
        event_lines = (attempt / "events.jsonl").read_bytes().splitlines(keepends=True)
        self.assertEqual(len(event_lines), 2)
        events = [json.loads(raw) for raw in event_lines]
        self.assertEqual([event["phase"] for event in events], ["preflight", "producer"])
        self.assertEqual([event["sequence"] for event in events], [1, 2])
        for raw, event in zip(event_lines, events):
            self.assertEqual(raw, canonical_json_bytes(event))
        self.assertEqual(list(attempt.glob(".*.tmp")), [])

    def test_each_nonterminal_phase_can_record_exact_terminal_failure_without_complete(self) -> None:
        phases = (
            "preflight",
            "producer",
            "producer_readback",
            "verifier",
            "verifier_readback",
            "archive_manifest",
            "complete",
        )
        chain = (
            "producer",
            "producer_readback",
            "verifier",
            "verifier_readback",
            "archive_manifest",
            "complete",
        )
        for index, target in enumerate(phases):
            with self.subTest(phase=target):
                attempt = self.runs / f"run-{index}" / "attempts" / f"attempt-{index}"
                attempt.mkdir(parents=True)
                journal = AttemptJournal(
                    attempt_dir=attempt,
                    run_id="fae8-osm-global-" + f"{index + 1:032x}",
                    attempt_id=f"attempt-{index}",
                    process_identity=current_process_identity(),
                    identities={},
                    paths={},
                    progress={},
                    free_bytes=9_000_000_000,
                    heartbeat_interval_seconds=1,
                )
                for phase in chain:
                    if journal.phase == target:
                        break
                    journal.transition(phase)
                error = {
                    "class": "fixture.PhaseFailure",
                    "message": f"failed in {target}",
                    "phase": target,
                }
                journal.transition("failed", error=error)
                status = json.loads((attempt / "status.json").read_bytes())
                last_event = json.loads((attempt / "events.jsonl").read_bytes().splitlines()[-1])
                self.assertEqual(status["phase"], "failed")
                self.assertEqual(last_event["error"], error)
                self.assertFalse((attempt.parent.parent / "complete.json").exists())

    def test_inspect_requires_fresh_heartbeat_and_exact_held_process_identity(self) -> None:
        self.assertIsNotNone(inspect_run)
        assert inspect_run is not None
        run_id = "fae8-osm-global-" + "a" * 32
        run_dir = self.runs / run_id
        attempt = run_dir / "attempts" / "attempt-1"
        attempt.mkdir(parents=True)
        identity = current_process_identity()
        lease = AtomicRunLock.acquire(self.runs, identity)
        try:
            journal = AttemptJournal(
                attempt_dir=attempt,
                run_id=run_id,
                attempt_id="attempt-1",
                process_identity=identity,
                identities={},
                paths={},
                progress={},
                free_bytes=9_000_000_000,
                heartbeat_interval_seconds=1,
            )
            journal.transition("producer")
            self.assertEqual(inspect_run(run_dir)["state"], "running")
            status_path = attempt / "status.json"
            status = json.loads(status_path.read_bytes())
            status["processIdentity"]["processStartToken"] = "reused-pid-fixture"
            status_path.write_bytes(canonical_json_bytes(status))
            self.assertEqual(inspect_run(run_dir)["state"], "stale")
        finally:
            lease.release()

    def test_inspect_selects_exact_live_lock_owner_not_lexicographically_later_old_attempt(self) -> None:
        run_id = "fae8-osm-global-" + "b" * 32
        run_dir = self.runs / run_id
        old_attempt = run_dir / "attempts" / "attempt-z-old"
        old_attempt.mkdir(parents=True)
        old = AttemptJournal(
            attempt_dir=old_attempt,
            run_id=run_id,
            attempt_id="attempt-z-old",
            process_identity=current_process_identity(),
            identities={},
            paths={},
            progress={},
            free_bytes=9_000_000_000,
            heartbeat_interval_seconds=1,
        )
        old.transition(
            "failed",
            error={"class": "fixture.Old", "message": "old", "phase": "preflight"},
        )
        (run_dir / "attempts" / "attempt-y-crashed-before-journal").mkdir()

        identity = current_process_identity()
        lease = AtomicRunLock.acquire(self.runs, identity)
        try:
            active_attempt = run_dir / "attempts" / "attempt-a-active"
            active_attempt.mkdir()
            active = AttemptJournal(
                attempt_dir=active_attempt,
                run_id=run_id,
                attempt_id="attempt-a-active",
                process_identity=identity,
                identities={},
                paths={},
                progress={},
                free_bytes=9_000_000_000,
                heartbeat_interval_seconds=1,
            )
            active.transition("producer")
            inspection = inspect_run(run_dir)
            self.assertEqual(inspection["state"], "running", inspection)
            self.assertEqual(inspection["attemptId"], "attempt-a-active")
        finally:
            lease.release()


class RunnerEndToEndTests(RunnerConfigTests):
    def test_run_acquires_single_owner_before_potentially_long_input_hash_preflight(self) -> None:
        observed_lock: list[bool] = []

        def stop_in_preflight(config):
            del config
            observed_lock.append((self.runs / ".global-selection.lock").is_dir())
            raise RunnerError("stop after lock-order proof")

        with mock.patch.object(runner_module, "preflight", side_effect=stop_in_preflight):
            with self.assertRaisesRegex(RunnerError, "lock-order proof"):
                run_global_selection(self._config())
        self.assertEqual(observed_lock, [True])
        self.assertFalse((self.runs / ".global-selection.lock").exists())

    def test_tiny_public_api_run_completes_with_last_marker_and_documentary_only_mirror(self) -> None:
        self.assertIsNotNone(run_global_selection)
        self.assertIsNotNone(inspect_run)
        assert run_global_selection is not None
        assert inspect_run is not None
        outcome = run_global_selection(self._config())

        self.assertTrue(outcome.complete_path.is_file())
        self.assertTrue(outcome.final_manifest_path.is_file())
        final_raw = outcome.final_manifest_path.read_bytes()
        final = json.loads(final_raw)
        self.assertEqual(final_raw, canonical_json_bytes(final))
        semantic_raw = canonical_json_bytes(final["semantic"])
        self.assertEqual(hashlib.sha256(semantic_raw).hexdigest(), final["semanticSha256"])

        status = json.loads((outcome.attempt_dir / "status.json").read_bytes())
        self.assertEqual(status["phase"], "complete")
        events = [
            json.loads(raw)
            for raw in (outcome.attempt_dir / "events.jsonl").read_bytes().splitlines()
        ]
        self.assertEqual(
            [event["phase"] for event in events],
            [
                "preflight",
                "producer",
                "producer_readback",
                "verifier",
                "verifier_readback",
                "archive_manifest",
                "complete",
            ],
        )
        latest_attempt_mtime = max(
            path.stat().st_mtime_ns
            for path in outcome.attempt_dir.rglob("*")
            if path.is_file()
        )
        self.assertGreaterEqual(outcome.complete_path.stat().st_mtime_ns, latest_attempt_mtime)

        mirrored = {path.relative_to(outcome.mirror_dir).as_posix() for path in outcome.mirror_dir.rglob("*") if path.is_file()}
        self.assertEqual(
            mirrored,
            {
                "events.jsonl",
                "final-manifest.json",
                "mirror-manifest.json",
                "status.json",
                "verification-observation.json",
                "verification-report.json",
            },
        )
        self.assertFalse(any(name.endswith((".bin", ".opl", ".pbf")) for name in mirrored))
        inspection = inspect_run(outcome.run_dir)
        self.assertEqual(inspection["state"], "complete", inspection)

    def test_run_passes_exact_compatible_limits_and_hashes_them_into_evidence(self) -> None:
        config = self._config()
        expected = config.validated_reference_limits()
        with mock.patch.object(
            selection_module,
            "scan_planet_roots",
            wraps=selection_module.scan_planet_roots,
        ) as producer_call, mock.patch.object(
            verifier_module,
            "verify_planet_roots",
            wraps=verifier_module.verify_planet_roots,
        ) as verifier_call:
            outcome = run_global_selection(config)

        selection_limits = producer_call.call_args.kwargs.get("limits")
        verification_limits = verifier_call.call_args.kwargs.get("limits")
        self.assertIsInstance(selection_limits, selection_module.SelectionLimits)
        self.assertIsInstance(verification_limits, verifier_module.VerificationLimits)
        self.assertEqual(
            selection_limits.max_total_references,
            expected.selection_max_total_references,
        )
        self.assertEqual(
            verification_limits.max_total_references,
            expected.verification_max_total_references,
        )
        self.assertEqual(
            replace(
                selection_limits,
                max_total_references=selection_module.SelectionLimits().max_total_references,
            ),
            selection_module.SelectionLimits(),
        )
        self.assertEqual(
            replace(
                verification_limits,
                max_total_references=verifier_module.VerificationLimits().max_total_references,
            ),
            verifier_module.VerificationLimits(),
        )

        semantic = json.loads(outcome.final_manifest_path.read_bytes())["semantic"]
        self.assertEqual(
            semantic["identities"]["referenceLimits"],
            expected.to_document(),
        )
        self.assertEqual(
            semantic["producer"]["limits"],
            {"maxTotalReferences": expected.selection_max_total_references},
        )
        self.assertEqual(
            semantic["verifier"]["limits"],
            {"maxTotalReferences": expected.verification_max_total_references},
        )

    def test_verifier_failure_is_exact_and_restart_preserves_old_attempt_immutably(self) -> None:
        config = self._config()
        with mock.patch.object(
            verifier_module,
            "verify_planet_roots",
            side_effect=verifier_module.VerificationError("injected verifier failure"),
        ):
            with self.assertRaisesRegex(RunnerError, "injected verifier failure"):
                run_global_selection(config)
        context = preflight(config)
        try:
            run_id = context.content_run_id
        finally:
            context.close()
        failed_run = self.runs / run_id
        failed_attempts = sorted((failed_run / "attempts").iterdir())
        self.assertEqual(len(failed_attempts), 1)
        failed_attempt = failed_attempts[0]
        status = json.loads((failed_attempt / "status.json").read_bytes())
        self.assertEqual(status["phase"], "failed")
        failed_event = json.loads((failed_attempt / "events.jsonl").read_bytes().splitlines()[-1])
        self.assertEqual(failed_event["error"]["phase"], "verifier")
        self.assertEqual(
            failed_event["error"]["class"],
            "tools.experiment8.osm_planet_selection_verifier.VerificationError",
        )
        self.assertEqual(failed_event["error"]["message"], "injected verifier failure")
        self.assertFalse((failed_run / "complete.json").exists())
        old_facts = {
            path.relative_to(failed_attempt).as_posix(): (path.stat().st_size, _sha256(path))
            for path in failed_attempt.rglob("*")
            if path.is_file()
        }

        outcome = run_global_selection(config)
        attempts = sorted((outcome.run_dir / "attempts").iterdir())
        self.assertEqual(len(attempts), 2)
        self.assertNotEqual(attempts[0].name, attempts[1].name)
        self.assertEqual(
            old_facts,
            {
                path.relative_to(failed_attempt).as_posix(): (path.stat().st_size, _sha256(path))
                for path in failed_attempt.rglob("*")
                if path.is_file()
            },
        )

    def test_runner_records_fail_closed_attempt_for_each_execution_phase(self) -> None:
        config = self._config()
        cases = (
            (
                "producer",
                mock.patch.object(
                    selection_module,
                    "scan_planet_roots",
                    side_effect=selection_module.SelectionError("producer fixture failure"),
                ),
            ),
            (
                "producer_readback",
                mock.patch.object(
                    runner_module,
                    "_readback_producer",
                    side_effect=RunnerError("producer readback fixture failure"),
                ),
            ),
            (
                "verifier_readback",
                mock.patch.object(
                    runner_module,
                    "_readback_verifier",
                    side_effect=RunnerError("verifier readback fixture failure"),
                ),
            ),
            (
                "archive_manifest",
                mock.patch.object(
                    runner_module,
                    "_validate_producer_inventory",
                    side_effect=RunnerError("archive validation fixture failure"),
                ),
            ),
            (
                "complete",
                mock.patch.object(
                    runner_module,
                    "_archive_documents",
                    side_effect=RunnerError("mirror fixture failure"),
                ),
            ),
        )
        for expected_phase, patcher in cases:
            context = preflight(config)
            try:
                run_dir = self.runs / context.content_run_id
            finally:
                context.close()
            attempts_root = run_dir / "attempts"
            before = set(attempts_root.iterdir()) if attempts_root.is_dir() else set()
            with self.subTest(phase=expected_phase), patcher:
                with self.assertRaises(RunnerError):
                    run_global_selection(config)
            created = set(attempts_root.iterdir()) - before
            self.assertEqual(len(created), 1)
            failed_attempt = created.pop()
            status = json.loads((failed_attempt / "status.json").read_bytes())
            failed_event = json.loads((failed_attempt / "events.jsonl").read_bytes().splitlines()[-1])
            self.assertEqual(status["phase"], "failed")
            self.assertEqual(failed_event["error"]["phase"], expected_phase)
            self.assertFalse((run_dir / "complete.json").exists())
            self.assertEqual(list(self.mirror.iterdir()), [])

    def test_complete_inspection_fails_closed_on_artifact_status_result_or_mirror_drift(self) -> None:
        outcome = run_global_selection(self._config())
        paths = (
            outcome.attempt_dir / "production" / "root-ids.txt",
            outcome.attempt_dir / "status.json",
            outcome.attempt_dir / "verification-report.json",
            outcome.final_manifest_path,
            outcome.mirror_dir / "verification-observation.json",
        )
        for path in paths:
            with self.subTest(path=path.name):
                original = path.read_bytes()
                path.write_bytes(original + b"drift")
                inspection = inspect_run(outcome.run_dir)
                self.assertEqual(inspection["state"], "failed", inspection)
                path.write_bytes(original)
                self.assertEqual(inspect_run(outcome.run_dir)["state"], "complete")

    def test_repeated_fixture_runs_have_identical_semantic_manifest_only(self) -> None:
        first = run_global_selection(self._config())
        second_runs = self.root / "runs-second"
        second_mirror = self.root / "mirror-second"
        second_runs.mkdir()
        second_mirror.mkdir()
        second_config = replace(
            self._config(), runs_root=second_runs, mirror_root=second_mirror
        )
        second = run_global_selection(second_config)
        first_manifest = json.loads(first.final_manifest_path.read_bytes())
        second_manifest = json.loads(second.final_manifest_path.read_bytes())
        self.assertEqual(
            canonical_json_bytes(first_manifest["semantic"]),
            canonical_json_bytes(second_manifest["semantic"]),
        )
        self.assertNotEqual(first_manifest["execution"], second_manifest["execution"])

    def test_tests_and_runner_never_name_or_open_live_multigigabyte_streams(self) -> None:
        source = Path(runner_module.__file__).read_text(encoding="utf-8")
        for forbidden in (
            "display-name-envelope-candidates.osmium-1.11.1.opl",
            "true-waterway-broad.osmium-1.11.1.opl",
            "E:\\FlightAlert-exp8-work\\planet-260629",
        ):
            self.assertNotIn(forbidden, source)


class RunnerCliTests(unittest.TestCase):
    def test_foreground_cli_is_canonical_parent_independent_and_requires_explicit_code_locks(self) -> None:
        main = getattr(runner_module, "main", None)
        self.assertIsNotNone(main)
        assert main is not None
        with tempfile.TemporaryDirectory() as temporary:
            run_dir = Path(temporary).resolve() / ("fae8-osm-global-" + "c" * 32)
            run_dir.mkdir()
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                exit_code = main(["inspect", "--run-dir", str(run_dir)])
            raw = output.getvalue().encode("utf-8")
            document = json.loads(raw)
            self.assertEqual(exit_code, 0)
            self.assertEqual(raw, canonical_json_bytes(document))
            self.assertEqual(document["state"], "failed")

            completed = subprocess.run(
                [
                    sys.executable,
                    str(Path(runner_module.__file__).resolve()),
                    "inspect",
                    "--run-dir",
                    str(run_dir),
                ],
                cwd=Path.cwd(),
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
                timeout=30,
            )
            self.assertEqual(completed.returncode, 0, completed.stderr.decode("utf-8", errors="replace"))
            subprocess_document = json.loads(completed.stdout)
            self.assertEqual(completed.stdout, canonical_json_bytes(subprocess_document))
            self.assertEqual(subprocess_document["state"], "failed")

            config = Path(temporary).resolve() / "config.json"
            config.write_bytes(b"{}\n")
            parser_errors = io.StringIO()
            with contextlib.redirect_stderr(parser_errors), self.assertRaises(SystemExit):
                main(["run", "--config", str(config)])
            self.assertIn("--selector-sha256", parser_errors.getvalue())

        source = Path(runner_module.__file__).read_text(encoding="utf-8")
        self.assertIn('if __name__ == "__main__":', source)
        self.assertNotIn("input(", source)

if __name__ == "__main__":
    unittest.main()
