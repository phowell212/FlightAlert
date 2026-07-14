from __future__ import annotations

import hashlib
import os
import tempfile
import unittest
from concurrent.futures import FIRST_COMPLETED, Future
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

from tools.experiment8.osm_global_waterway_package import GlobalWaterwayPackageError
from tools.experiment8.osm_global_waterway_renderer import (
    ExactWaterwayFeature,
    ExactWaterwayPoint,
)
from tools.experiment8.semantic_model import HotIdRegistry
from tools.experiment8.waterway_parallel_render import (
    FeatureRenderBatchJob,
    ParallelFeatureRenderer,
    ParallelRenderLimits,
    RecordingHotIdRegistry,
    SpoolDescriptor,
    decode_feature_batch_job,
    encode_feature_batch_job,
    finish_spool_directory,
    prepare_spool_directory,
    read_feature_batch,
    render_feature_batch_job,
    replay_registry_claims,
)


_SOURCE_SHA256 = hashlib.sha256(b"parallel source fixture").hexdigest()
_CLASSIFIER_SHA256 = hashlib.sha256(b"parallel classifier fixture").hexdigest()
_RUN_SHA256 = hashlib.sha256(b"parallel render run fixture").hexdigest()
_PACKAGE_ID = "world-osm-named-waterways-test"


def _feature(source_id: int = 7) -> ExactWaterwayFeature:
    points = (
        ExactWaterwayPoint(source_id * 10 + 1, -760_000_000, 390_000_000),
        ExactWaterwayPoint(source_id * 10 + 2, -757_000_000, 392_000_000),
        ExactWaterwayPoint(source_id * 10 + 3, -754_000_000, 394_000_000),
    )
    return ExactWaterwayFeature(
        source_kind="way",
        source_id=source_id,
        source_version=3,
        source_timestamp="2026-07-14T00:00:00Z",
        waterway_type="river",
        name_source_key="name",
        primary_name=f"Parallel River {source_id}",
        english_name=None,
        complete_named_relation=False,
        parts=(points,),
        required_node_ids=frozenset((points[1].node_id,)),
        source_feature_sha256=hashlib.sha256(
            f"parallel exact feature {source_id}".encode("ascii")
        ).digest(),
    )


def _job(spool_directory: Path, *features: ExactWaterwayFeature) -> FeatureRenderBatchJob:
    return FeatureRenderBatchJob(
        start_ordinal=20,
        features=tuple(features) or (_feature(),),
        source_generation_sha256=_SOURCE_SHA256,
        classifier_sha256=_CLASSIFIER_SHA256,
        zooms=(10, 11),
        render_run_identity_sha256=_RUN_SHA256,
        spool_directory=str(spool_directory),
        spool_byte_quota=16 * 1024 * 1024,
    )


def _render(root: Path) -> tuple[SpoolDescriptor, tuple[object, ...]]:
    descriptor = render_feature_batch_job(encode_feature_batch_job(_job(root, _feature())))
    frames = read_feature_batch(
        root,
        descriptor,
        expected_render_run_identity_sha256=_RUN_SHA256,
        expected_source_range_sha256=descriptor.source_range_sha256,
    )
    return descriptor, frames


def _limits(**changes: int) -> ParallelRenderLimits:
    values = {
        "workers": 2,
        "max_in_flight_jobs": 4,
        "max_in_flight_points": 24,
        "max_points_per_job": 6,
        "max_in_flight_input_bytes": 16 * 1024 * 1024,
        "max_input_bytes_per_job": 4 * 1024 * 1024,
        "max_spool_bytes": 32 * 1024 * 1024,
        "max_spool_bytes_per_job": 8 * 1024 * 1024,
    }
    values.update(changes)
    return ParallelRenderLimits(**values)


def _prepare(root: Path) -> Path:
    return prepare_spool_directory(
        root,
        package_id=_PACKAGE_ID,
        render_run_identity_sha256=_RUN_SHA256,
        source_document_sha256=_SOURCE_SHA256,
    )


class _ReverseExecutor:
    def __init__(self, *, result_mutator=None, completion_hook=None, **kwargs) -> None:
        self.creation_arguments = kwargs
        self.result_mutator = result_mutator
        self.completion_hook = completion_hook
        self.submissions: list[tuple[Future, object, tuple[object, ...]]] = []
        self.shutdown_calls: list[tuple[bool, bool]] = []
        self.peak_jobs = 0
        self.peak_points = 0
        self.peak_input_bytes = 0
        self.peak_spool_bytes = 0

    def submit(self, function, *arguments):
        future: Future = Future()
        self.submissions.append((future, function, arguments))
        pending = [row for row in self.submissions if not row[0].done()]
        jobs = [decode_feature_batch_job(row[2][0]) for row in pending]
        self.peak_jobs = max(self.peak_jobs, len(pending))
        self.peak_points = max(
            self.peak_points,
            sum(sum(len(part) for part in feature.parts) for job in jobs for feature in job.features),
        )
        self.peak_input_bytes = max(
            self.peak_input_bytes,
            sum(len(row[2][0]) for row in pending),
        )
        self.peak_spool_bytes = max(
            self.peak_spool_bytes,
            sum(job.spool_byte_quota for job in jobs),
        )
        return future

    def wait(self, futures, *, return_when):
        self.assert_wait_contract(futures, return_when)
        unfinished = [row for row in self.submissions if row[0] in futures and not row[0].done()]
        if not unfinished:
            return ({future for future in futures if future.done()}, set())
        future, function, arguments = unfinished[-1]
        try:
            result = function(*arguments)
            if self.result_mutator is not None:
                result = self.result_mutator(result)
            if self.completion_hook is not None:
                self.completion_hook(decode_feature_batch_job(arguments[0]), result)
        except BaseException as error:
            future.set_exception(error)
        else:
            future.set_result(result)
        return ({future}, set(futures) - {future})

    def assert_wait_contract(self, futures, return_when) -> None:
        if return_when is not FIRST_COMPLETED:
            raise AssertionError("scheduler did not request FIRST_COMPLETED")
        if not futures:
            raise AssertionError("scheduler waited without submitted work")

    def shutdown(self, *, wait=True, cancel_futures=False) -> None:
        self.shutdown_calls.append((wait, cancel_futures))


class _ReverseExecutorFactory:
    def __init__(self, **executor_options) -> None:
        self.executor_options = executor_options
        self.instances: list[_ReverseExecutor] = []

    def __call__(self, **kwargs) -> _ReverseExecutor:
        executor = _ReverseExecutor(**self.executor_options, **kwargs)
        self.instances.append(executor)
        return executor

    def wait(self, futures, *, return_when):
        if not self.instances:
            raise AssertionError("scheduler waited before creating its executor")
        return self.instances[0].wait(futures, return_when=return_when)


def _parallel_renderer(
    root: Path,
    features: tuple[ExactWaterwayFeature, ...],
    factory: _ReverseExecutorFactory,
    *,
    start_ordinal: int = 0,
    pause_after_features: int | None = None,
    limits: ParallelRenderLimits | None = None,
    free_bytes: int = 100_000_000_000,
) -> ParallelFeatureRenderer:
    return ParallelFeatureRenderer(
        features,
        start_ordinal=start_ordinal,
        package_id=_PACKAGE_ID,
        source_generation_sha256=_SOURCE_SHA256,
        classifier_sha256=_CLASSIFIER_SHA256,
        zooms=(10, 11),
        render_run_identity_sha256=_RUN_SHA256,
        spool_directory=root,
        pause_after_features=pause_after_features,
        limits=limits or _limits(),
        executor_factory=factory,
        wait_for_futures=factory.wait,
        disk_usage=lambda _path: SimpleNamespace(free=free_bytes),
    )


class RecordingRegistryTests(unittest.TestCase):
    def test_replay_rejects_excess_claims_without_materializing_the_iterable(self) -> None:
        worker = RecordingHotIdRegistry()
        worker.register(b"FAE8OSMID1\0", b"one")
        claim = worker.claims[0]

        def excessive_claims():
            for _ in range(4_097):
                yield claim
            raise AssertionError("replay consumed beyond its hard claim bound")

        with self.assertRaisesRegex(GlobalWaterwayPackageError, "claim.*count"):
            replay_registry_claims(HotIdRegistry(), excessive_claims())

    def test_recording_registry_replays_exact_events_in_call_order(self) -> None:
        worker = RecordingHotIdRegistry()
        first = worker.register(b"FAE8OSMID1\0", b"one")
        second = worker.register(b"FAE8OSMID1\0", b"two")
        parent = HotIdRegistry()
        replay_registry_claims(parent, worker.claims)
        self.assertEqual(first, parent.register(b"FAE8OSMID1\0", b"one"))
        self.assertEqual(second, parent.register(b"FAE8OSMID1\0", b"two"))
        self.assertEqual(
            [(b"FAE8OSMID1\0", b"one"), (b"FAE8OSMID1\0", b"two")],
            [(claim.domain, claim.canonical_bytes) for claim in worker.claims],
        )

    def test_replay_rejects_a_claim_with_a_false_fingerprint(self) -> None:
        worker = RecordingHotIdRegistry()
        worker.register(b"FAE8OSMID1\0", b"one")
        false_claim = replace(worker.claims[0], full_sha256=b"x" * 32)
        with self.assertRaisesRegex(GlobalWaterwayPackageError, "registry claim"):
            replay_registry_claims(HotIdRegistry(), (false_claim,))


class FeatureBatchJobCodecTests(unittest.TestCase):
    def test_job_cannot_cross_the_fixed_render_checkpoint_boundary(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(
                GlobalWaterwayPackageError, "checkpoint"
            ):
                replace(
                    _job(Path(temporary), _feature(7), _feature(8)),
                    start_ordinal=99,
                )

    def test_job_codec_round_trips_one_complete_immutable_job_canonically(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            job = _job(Path(temporary), _feature(7), _feature(8))
            encoded = encode_feature_batch_job(job)
            decoded = decode_feature_batch_job(encoded)
            self.assertEqual(job, decoded)
            self.assertEqual(encoded, encode_feature_batch_job(decoded))

    def test_job_decoder_rejects_trailing_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            encoded = encode_feature_batch_job(_job(Path(temporary), _feature()))
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "trailing"):
                decode_feature_batch_job(encoded + b"x")

    def test_job_decoder_range_checks_counts_before_allocating(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            encoded = bytearray(
                encode_feature_batch_job(_job(Path(temporary), _feature()))
            )
            # Magic/version plus start ordinal precede the u32 feature count.
            feature_count_offset = len(b"FAE8WRJOB") + 1 + 8
            encoded[feature_count_offset : feature_count_offset + 4] = (
                0xFFFFFFFF
            ).to_bytes(4, "little")
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "feature count"):
                decode_feature_batch_job(bytes(encoded))


class ParallelRenderLimitTests(unittest.TestCase):
    def test_limits_reject_invalid_workers_jobs_and_resources(self) -> None:
        with self.assertRaisesRegex(GlobalWaterwayPackageError, "worker count"):
            _limits(workers=0)
        with self.assertRaisesRegex(GlobalWaterwayPackageError, "in-flight job"):
            _limits(workers=3, max_in_flight_jobs=2)
        with self.assertRaisesRegex(GlobalWaterwayPackageError, "resource bound"):
            _limits(max_in_flight_points=0)

    @unittest.skipUnless(os.name == "nt", "Windows ProcessPool worker ceiling")
    def test_limits_reject_workers_above_the_windows_process_pool_ceiling(self) -> None:
        with self.assertRaisesRegex(GlobalWaterwayPackageError, "worker count"):
            _limits(workers=62, max_in_flight_jobs=62)


class ParallelSpoolOwnershipTests(unittest.TestCase):
    def test_prepare_publishes_exact_owner_and_matching_resume_removes_only_batch_names(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "spool"
            self.assertEqual(root, _prepare(root))
            owner_before = (root / "owner.json").read_bytes()
            batch = root / "000000000000-000000000001.batch"
            temporary_batch = root / "000000000001-000000000002.batch.tmp-123"
            batch.write_bytes(b"non-authoritative batch")
            temporary_batch.write_bytes(b"non-authoritative temp")

            self.assertEqual(root, _prepare(root))

            self.assertEqual(owner_before, (root / "owner.json").read_bytes())
            self.assertFalse(batch.exists())
            self.assertFalse(temporary_batch.exists())

    def test_prepare_rejects_wrong_owner_without_removing_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "spool"
            _prepare(root)
            batch = root / "000000000000-000000000001.batch"
            batch.write_bytes(b"preserve me")

            with self.assertRaisesRegex(GlobalWaterwayPackageError, "owner"):
                prepare_spool_directory(
                    root,
                    package_id=_PACKAGE_ID,
                    render_run_identity_sha256="0" * 64,
                    source_document_sha256=_SOURCE_SHA256,
                )

            self.assertEqual(b"preserve me", batch.read_bytes())

    def test_prepare_rejects_unknown_child_before_removing_known_children(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "spool"
            _prepare(root)
            batch = root / "000000000000-000000000001.batch"
            batch.write_bytes(b"preserve known evidence too")
            unknown = root / "notes.txt"
            unknown.write_bytes(b"unknown")

            with self.assertRaisesRegex(GlobalWaterwayPackageError, "unknown|child"):
                _prepare(root)

            self.assertTrue(batch.exists())
            self.assertTrue(unknown.exists())

    def test_prepare_rejects_stale_noncanonical_temp_name(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "spool"
            _prepare(root)
            stale = root / "000000000000-000000000001.batch.tmp-stale"
            stale.write_bytes(b"unknown")
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "unknown|child|temp"):
                _prepare(root)
            self.assertTrue(stale.exists())

    def test_prepare_rejects_link_or_reparse_directory_and_owner(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            real = base / "real"
            real.mkdir()
            linked = base / "linked"
            try:
                os.symlink(real, linked, target_is_directory=True)
            except OSError as error:
                self.skipTest(f"directory symlinks are unavailable: {error}")
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "link|reparse"):
                _prepare(linked)

            root = base / "spool"
            _prepare(root)
            owner = root / "owner.json"
            owner_bytes = owner.read_bytes()
            target = base / "owner-target.json"
            target.write_bytes(owner_bytes)
            owner.unlink()
            os.symlink(target, owner)
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "owner|link|reparse"):
                _prepare(root)

    def test_finish_requires_an_exact_empty_owned_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "spool"
            _prepare(root)
            remaining = root / "000000000000-000000000001.batch"
            remaining.write_bytes(b"not yet committed")
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "empty|child|batch"):
                finish_spool_directory(
                    root,
                    package_id=_PACKAGE_ID,
                    render_run_identity_sha256=_RUN_SHA256,
                    source_document_sha256=_SOURCE_SHA256,
                )
            self.assertTrue(remaining.exists())
            remaining.unlink()
            finish_spool_directory(
                root,
                package_id=_PACKAGE_ID,
                render_run_identity_sha256=_RUN_SHA256,
                source_document_sha256=_SOURCE_SHA256,
            )
            self.assertFalse(root.exists())


class ParallelFeatureRendererTests(unittest.TestCase):
    def test_renderer_rejects_owner_for_another_package(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _prepare(Path(temporary) / "spool")
            factory = _ReverseExecutorFactory()
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "owner|package"):
                ParallelFeatureRenderer(
                    (_feature(100),),
                    start_ordinal=0,
                    package_id="another-package",
                    source_generation_sha256=_SOURCE_SHA256,
                    classifier_sha256=_CLASSIFIER_SHA256,
                    zooms=(10, 11),
                    render_run_identity_sha256=_RUN_SHA256,
                    spool_directory=root,
                    limits=_limits(),
                    executor_factory=factory,
                    wait_for_futures=factory.wait,
                    disk_usage=lambda _path: SimpleNamespace(
                        free=100_000_000_000
                    ),
                )
            self.assertEqual([], factory.instances)

    def test_release_batch_deletes_only_yielded_spools_and_prunes_range_history(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _prepare(Path(temporary) / "spool")
            limits = _limits()
            factory = _ReverseExecutorFactory()
            renderer = _parallel_renderer(
                root,
                tuple(_feature(source_id) for source_id in range(110, 130)),
                factory,
                limits=limits,
            )
            peak_known_ranges = 0
            descriptors = []
            while (batch := renderer.next_batch()) is not None:
                descriptor = batch[0]
                descriptors.append(descriptor)
                renderer.release_batch(descriptor)
                self.assertFalse((root / descriptor.file_name).exists())
                peak_known_ranges = max(
                    peak_known_ranges,
                    len(renderer._known_ranges),
                )

            self.assertGreater(len(descriptors), limits.max_in_flight_jobs)
            self.assertLessEqual(peak_known_ranges, limits.max_in_flight_jobs)
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "yielded|release"):
                renderer.release_batch(descriptors[0])

    def test_default_spawn_executor_renders_one_canonical_job(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _prepare(Path(temporary) / "spool")
            limits = _limits(
                workers=1,
                max_in_flight_jobs=1,
                max_in_flight_points=3,
                max_points_per_job=3,
                max_in_flight_input_bytes=4 * 1024 * 1024,
                max_spool_bytes=8 * 1024 * 1024,
            )
            renderer = ParallelFeatureRenderer(
                (_feature(101),),
                start_ordinal=0,
                package_id=_PACKAGE_ID,
                source_generation_sha256=_SOURCE_SHA256,
                classifier_sha256=_CLASSIFIER_SHA256,
                zooms=(10, 11),
                render_run_identity_sha256=_RUN_SHA256,
                spool_directory=root,
                limits=limits,
                disk_usage=lambda _path: SimpleNamespace(free=100_000_000_000),
            )

            descriptor, exact, frames = renderer.next_batch()

            self.assertEqual((0, 1), (descriptor.start_ordinal, descriptor.end_ordinal_exclusive))
            self.assertEqual((101,), tuple(feature.source_id for feature in exact))
            self.assertEqual((0,), tuple(frame.ordinal for frame in frames))
            self.assertIsNone(renderer.next_batch())

    def test_reverse_completion_still_yields_checkpoint_and_pause_aligned_ranges_in_order(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _prepare(Path(temporary) / "spool")
            features = tuple(_feature(source_id) for source_id in range(1, 8))
            limits = _limits()
            factory = _ReverseExecutorFactory()
            renderer = _parallel_renderer(
                root,
                features,
                factory,
                start_ordinal=98,
                pause_after_features=103,
                limits=limits,
            )
            batches = []
            while True:
                batch = renderer.next_batch()
                if batch is None:
                    break
                batches.append(batch)

            descriptors = [batch[0] for batch in batches]
            self.assertEqual(
                [(98, 100), (100, 102), (102, 103)],
                [(value.start_ordinal, value.end_ordinal_exclusive) for value in descriptors],
            )
            self.assertEqual(
                list(range(98, 103)),
                [frame.ordinal for _descriptor, _features, frames in batches for frame in frames],
            )
            self.assertEqual(
                [1, 2, 3, 4, 5],
                [feature.source_id for _descriptor, exact, _frames in batches for feature in exact],
            )
            executor = factory.instances[0]
            self.assertEqual("spawn", executor.creation_arguments["mp_context"].get_start_method())
            self.assertLessEqual(executor.peak_jobs, limits.max_in_flight_jobs)
            self.assertLessEqual(executor.peak_points, limits.max_in_flight_points)
            self.assertLessEqual(executor.peak_input_bytes, limits.max_in_flight_input_bytes)
            self.assertLessEqual(executor.peak_spool_bytes, limits.max_spool_bytes)
            self.assertEqual([(True, False)], executor.shutdown_calls)
            for _future, function, arguments in executor.submissions:
                self.assertIs(render_feature_batch_job, function)
                self.assertEqual(1, len(arguments))
                self.assertIs(bytes, type(arguments[0]))
                decoded = decode_feature_batch_job(arguments[0])
                self.assertEqual(arguments[0], encode_feature_batch_job(decoded))

    def test_jobs_split_at_exact_encoded_input_bound(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _prepare(Path(temporary) / "spool")
            first = _feature(41)
            second = _feature(42)
            one = replace(_job(root, first), start_ordinal=0)
            two = replace(_job(root, first, second), start_ordinal=0)
            one_size = len(encode_feature_batch_job(one))
            two_size = len(encode_feature_batch_job(two))
            self.assertLess(one_size, two_size)
            limits = _limits(
                max_points_per_job=100,
                max_input_bytes_per_job=two_size - 1,
                max_in_flight_input_bytes=4 * two_size,
            )
            factory = _ReverseExecutorFactory()
            renderer = _parallel_renderer(root, (first, second), factory, limits=limits)
            ranges = []
            while (batch := renderer.next_batch()) is not None:
                ranges.append((batch[0].start_ordinal, batch[0].end_ordinal_exclusive))
            self.assertEqual([(0, 1), (1, 2)], ranges)
            self.assertTrue(
                all(
                    len(arguments[0]) <= limits.max_input_bytes_per_job
                    for _future, _function, arguments in factory.instances[0].submissions
                )
            )

    def test_one_feature_over_a_per_job_batching_bound_runs_alone(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _prepare(Path(temporary) / "spool")
            factory = _ReverseExecutorFactory()
            renderer = _parallel_renderer(
                root,
                (_feature(51), _feature(52)),
                factory,
                limits=_limits(max_points_per_job=2),
            )
            ranges = []
            while (batch := renderer.next_batch()) is not None:
                ranges.append((batch[0].start_ordinal, batch[0].end_ordinal_exclusive))
            self.assertEqual([(0, 1), (1, 2)], ranges)

    def test_wrong_source_range_or_out_of_root_descriptor_fails_and_preserves_spool(self) -> None:
        corruptions = (
            (lambda descriptor: replace(descriptor, source_range_sha256="0" * 64), "source-range"),
            (lambda descriptor: replace(descriptor, file_name="../outside.batch"), "name|root|range"),
        )
        for corrupt, message in corruptions:
            with self.subTest(message=message), tempfile.TemporaryDirectory() as temporary:
                root = _prepare(Path(temporary) / "spool")
                factory = _ReverseExecutorFactory(result_mutator=corrupt)
                renderer = _parallel_renderer(root, (_feature(61),), factory)
                with self.assertRaisesRegex(GlobalWaterwayPackageError, message):
                    renderer.next_batch()
                self.assertEqual(1, len(tuple(root.glob("*.batch"))))
                self.assertEqual((True, True), factory.instances[0].shutdown_calls[-1])

    def test_submission_rejects_reserved_spool_or_low_free_space_before_executor_use(self) -> None:
        cases = (
            (
                _limits(max_spool_bytes=4 * 1024 * 1024),
                100_000_000_000,
                "spool",
            ),
            (
                _limits(),
                1_500_000_000 + 8 * 1024 * 1024 - 1,
                "capacity|space|reserve",
            ),
        )
        for limits, free_bytes, message in cases:
            with self.subTest(message=message), tempfile.TemporaryDirectory() as temporary:
                root = _prepare(Path(temporary) / "spool")
                factory = _ReverseExecutorFactory()
                renderer = _parallel_renderer(
                    root,
                    (_feature(71),),
                    factory,
                    limits=limits,
                    free_bytes=free_bytes,
                )
                with self.assertRaisesRegex(GlobalWaterwayPackageError, message):
                    renderer.next_batch()
                self.assertEqual([], factory.instances[0].submissions)

    def test_excess_actual_spool_bytes_are_rejected_after_completion(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _prepare(Path(temporary) / "spool")
            limits = _limits()

            def add_excess_temp(job: FeatureRenderBatchJob, _descriptor: SpoolDescriptor) -> None:
                name = f"{job.start_ordinal:012d}-{job.start_ordinal + len(job.features):012d}.batch.tmp-999999"
                (root / name).write_bytes(b"x" * (limits.max_spool_bytes_per_job + 1))

            factory = _ReverseExecutorFactory(completion_hook=add_excess_temp)
            renderer = _parallel_renderer(root, (_feature(81),), factory, limits=limits)
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "spool.*byte|quota"):
                renderer.next_batch()
            self.assertTrue(tuple(root.glob("*.batch*")))

    def test_active_scheduler_rejects_an_unknown_child(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _prepare(Path(temporary) / "spool")
            (root / "unowned.txt").write_text("unknown", encoding="utf-8")
            factory = _ReverseExecutorFactory()
            renderer = _parallel_renderer(root, (_feature(91),), factory)
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "unknown|child"):
                renderer.next_batch()
            self.assertEqual([], factory.instances[0].submissions)

    def test_active_scheduler_rejects_same_inode_owner_content_tampering(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _prepare(Path(temporary) / "spool")
            factory = _ReverseExecutorFactory()
            renderer = _parallel_renderer(root, (_feature(92),), factory)
            owner = root / "owner.json"
            original = owner.read_bytes()
            owner.write_bytes(b"x" * len(original))

            with self.assertRaisesRegex(GlobalWaterwayPackageError, "owner"):
                renderer.next_batch()

            self.assertEqual([], factory.instances[0].submissions)


class AuthenticatedFeatureBatchTests(unittest.TestCase):
    def test_spool_anchor_fails_closed_without_windows_handle_semantics(self) -> None:
        from tools.experiment8 import waterway_parallel_render as parallel

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            anchor = parallel._AnchoredSpoolDirectory(
                root,
                "test spool directory",
            )
            with mock.patch.object(parallel.os, "name", "unsupported"):
                with self.assertRaisesRegex(
                    GlobalWaterwayPackageError, "requires Windows"
                ):
                    anchor.__enter__()
            self.assertEqual((), tuple(root.iterdir()))

    def test_worker_applies_remaining_quota_before_materializing_record_envelopes(self) -> None:
        from tools.experiment8 import waterway_parallel_render as parallel

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            job = replace(_job(root, _feature()), spool_byte_quota=128)
            with mock.patch.object(
                parallel,
                "_record_envelope",
                wraps=parallel._record_envelope,
            ) as envelope:
                with self.assertRaisesRegex(
                    GlobalWaterwayPackageError, "quota|byte bound"
                ):
                    render_feature_batch_job(encode_feature_batch_job(job))
            self.assertEqual(0, envelope.call_count)

    def test_late_record_quota_check_precedes_envelope_materialization(self) -> None:
        from tools.experiment8 import waterway_parallel_render as parallel

        feature = _feature()
        registry = RecordingHotIdRegistry()
        rendered = parallel.build_adaptive_waterway_feature(
            feature=feature,
            source_generation_sha256=_SOURCE_SHA256,
            classifier_sha256=_CLASSIFIER_SHA256,
            zooms=(10, 11),
            identity_registry=registry,
        )
        full_frame = parallel._build_feature_render_frame(
            ordinal=20,
            exact=feature,
            rendered=rendered,
            registry_claims=registry.claims,
            maximum_encoded_bytes=16 * 1024 * 1024,
        )
        full_encoded = parallel._encode_feature_render_frame(
            full_frame,
            16 * 1024 * 1024,
        )
        last_record_bytes = parallel._encoded_record_row_bytes(
            full_frame.record_rows[-1][11]
        )
        quota_before_last_record = len(full_encoded) - last_record_bytes

        with mock.patch.object(
            parallel,
            "_record_envelope",
            wraps=parallel._record_envelope,
        ) as envelope:
            with self.assertRaisesRegex(
                GlobalWaterwayPackageError, "remaining spool byte quota"
            ):
                parallel._build_feature_render_frame(
                    ordinal=20,
                    exact=feature,
                    rendered=rendered,
                    registry_claims=registry.claims,
                    maximum_encoded_bytes=quota_before_last_record,
                )

        self.assertEqual(len(full_frame.record_rows) - 1, envelope.call_count)

    def test_worker_accepts_exact_spool_quota_and_rejects_one_byte_less(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            baseline_root = base / "baseline"
            exact_root = base / "exact"
            short_root = base / "short"
            baseline_root.mkdir()
            exact_root.mkdir()
            short_root.mkdir()
            feature = _feature()
            baseline = render_feature_batch_job(
                encode_feature_batch_job(_job(baseline_root, feature))
            )
            exact = render_feature_batch_job(
                encode_feature_batch_job(
                    replace(
                        _job(exact_root, feature),
                        spool_byte_quota=baseline.byte_count,
                    )
                )
            )
            self.assertEqual(baseline.byte_count, exact.byte_count)
            self.assertEqual(baseline.sha256, exact.sha256)
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "quota"):
                render_feature_batch_job(
                    encode_feature_batch_job(
                        replace(
                            _job(short_root, feature),
                            spool_byte_quota=baseline.byte_count - 1,
                        )
                    )
                )

    def test_worker_removes_owned_temp_after_failure_so_retry_can_succeed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            job = _job(root, _feature())
            temporary_path = root / (
                f"000000000020-000000000021.batch.tmp-{os.getpid()}"
            )
            with self.assertRaisesRegex(
                GlobalWaterwayPackageError, "quota|byte bound"
            ):
                render_feature_batch_job(
                    encode_feature_batch_job(replace(job, spool_byte_quota=128))
                )
            self.assertFalse(temporary_path.exists())

            descriptor = render_feature_batch_job(encode_feature_batch_job(job))
            self.assertTrue((root / descriptor.file_name).is_file())

    def test_worker_removes_owned_temp_after_renderer_failure(self) -> None:
        from tools.experiment8 import waterway_parallel_render as parallel

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            temporary_path = root / (
                f"000000000020-000000000021.batch.tmp-{os.getpid()}"
            )
            with mock.patch.object(
                parallel,
                "build_adaptive_waterway_feature",
                side_effect=RuntimeError("injected renderer failure"),
            ):
                with self.assertRaisesRegex(RuntimeError, "renderer failure"):
                    render_feature_batch_job(
                        encode_feature_batch_job(_job(root, _feature()))
                    )
            self.assertFalse(temporary_path.exists())

    def test_worker_removes_owned_temp_after_fsync_failure(self) -> None:
        from tools.experiment8 import waterway_parallel_render as parallel

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            temporary_path = root / (
                f"000000000020-000000000021.batch.tmp-{os.getpid()}"
            )
            with mock.patch.object(
                parallel.os,
                "fsync",
                side_effect=OSError("injected fsync failure"),
            ):
                with self.assertRaisesRegex(
                    GlobalWaterwayPackageError, "published atomically"
                ):
                    render_feature_batch_job(
                        encode_feature_batch_job(_job(root, _feature()))
                    )
            self.assertFalse(temporary_path.exists())

    def test_worker_preserves_a_preexisting_deterministic_temp_file(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            temporary_path = root / (
                f"000000000020-000000000021.batch.tmp-{os.getpid()}"
            )
            sentinel = b"not owned by this worker"
            temporary_path.write_bytes(sentinel)

            with self.assertRaisesRegex(
                GlobalWaterwayPackageError, "published atomically"
            ):
                render_feature_batch_job(
                    encode_feature_batch_job(_job(root, _feature()))
                )

            self.assertEqual(sentinel, temporary_path.read_bytes())
            self.assertFalse((root / "000000000020-000000000021.batch").exists())

    def test_worker_never_replaces_a_preexisting_final_batch(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            final_path = root / "000000000020-000000000021.batch"
            temporary_path = root / (
                f"{final_path.name}.tmp-{os.getpid()}"
            )
            sentinel = b"authoritative prior batch"
            final_path.write_bytes(sentinel)

            with self.assertRaisesRegex(
                GlobalWaterwayPackageError, "published atomically"
            ):
                render_feature_batch_job(
                    encode_feature_batch_job(_job(root, _feature()))
                )

            self.assertEqual(sentinel, final_path.read_bytes())
            self.assertFalse(temporary_path.exists())

    @unittest.skipUnless(os.name == "nt", "Windows handle cleanup test")
    def test_worker_uses_owned_handle_disposition_for_failed_temp_cleanup(self) -> None:
        from tools.experiment8 import waterway_parallel_render as parallel

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            temporary_path = root / (
                f"000000000020-000000000021.batch.tmp-{os.getpid()}"
            )
            with mock.patch.object(
                parallel,
                "_mark_windows_owned_temp_for_delete",
                wraps=parallel._mark_windows_owned_temp_for_delete,
            ) as disposition:
                with self.assertRaisesRegex(
                    GlobalWaterwayPackageError, "quota|byte bound"
                ):
                    render_feature_batch_job(
                        encode_feature_batch_job(
                            replace(
                                _job(root, _feature()),
                                spool_byte_quota=128,
                            )
                        )
                    )
            self.assertEqual(1, disposition.call_count)
            self.assertFalse(temporary_path.exists())

    @unittest.skipUnless(os.name == "nt", "Windows native-handle cleanup test")
    def test_worker_cleans_native_temp_if_fd_adoption_fails(self) -> None:
        import msvcrt

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            temporary_path = root / (
                f"000000000020-000000000021.batch.tmp-{os.getpid()}"
            )
            with mock.patch.object(
                msvcrt,
                "open_osfhandle",
                side_effect=OSError("injected fd adoption failure"),
            ):
                with self.assertRaisesRegex(
                    GlobalWaterwayPackageError, "published atomically"
                ):
                    render_feature_batch_job(
                        encode_feature_batch_job(_job(root, _feature()))
                    )
            self.assertFalse(temporary_path.exists())

    @unittest.skipUnless(os.name == "nt", "Windows no-replace handle test")
    def test_worker_preserves_a_final_created_at_publication_boundary(self) -> None:
        from tools.experiment8 import waterway_parallel_render as parallel

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            final_path = root / "000000000020-000000000021.batch"
            temporary_path = root / (
                f"{final_path.name}.tmp-{os.getpid()}"
            )
            sentinel = b"racing authoritative batch"
            real_publish = parallel._publish_windows_owned_temp_no_replace

            def racing_publish(handle: object, destination: Path) -> None:
                destination.write_bytes(sentinel)
                real_publish(handle, destination)

            with mock.patch.object(
                parallel,
                "_publish_windows_owned_temp_no_replace",
                side_effect=racing_publish,
            ):
                with self.assertRaisesRegex(
                    GlobalWaterwayPackageError, "published atomically"
                ):
                    render_feature_batch_job(
                        encode_feature_batch_job(_job(root, _feature()))
                    )

            self.assertEqual(sentinel, final_path.read_bytes())
            self.assertFalse(temporary_path.exists())

    @unittest.skipUnless(os.name == "nt", "Windows retained temp handle test")
    def test_worker_retained_temp_handle_denies_source_name_replacement(self) -> None:
        from tools.experiment8 import waterway_parallel_render as parallel

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            temporary_path = root / (
                f"000000000020-000000000021.batch.tmp-{os.getpid()}"
            )
            stolen_path = root / "stolen.batch"
            real_publish = parallel._publish_windows_owned_temp_no_replace
            replacement_blocked = False

            def guarded_publish(handle: object, destination: Path) -> None:
                nonlocal replacement_blocked
                try:
                    os.replace(temporary_path, stolen_path)
                except OSError:
                    replacement_blocked = True
                real_publish(handle, destination)

            with mock.patch.object(
                parallel,
                "_publish_windows_owned_temp_no_replace",
                side_effect=guarded_publish,
            ):
                descriptor = render_feature_batch_job(
                    encode_feature_batch_job(_job(root, _feature()))
                )

            self.assertTrue(replacement_blocked)
            self.assertFalse(temporary_path.exists())
            self.assertFalse(stolen_path.exists())
            self.assertTrue((root / descriptor.file_name).is_file())

    @unittest.skipUnless(os.name == "nt", "Windows post-rename rollback test")
    def test_worker_disposes_exact_published_file_after_post_rename_failure(self) -> None:
        from tools.experiment8 import waterway_parallel_render as parallel

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            final_path = root / "000000000020-000000000021.batch"
            temporary_path = root / (
                f"{final_path.name}.tmp-{os.getpid()}"
            )
            real_publish = parallel._publish_windows_owned_temp_no_replace

            def publish_then_fail(handle: object, destination: Path) -> None:
                real_publish(handle, destination)
                raise RuntimeError("injected post-rename failure")

            with mock.patch.object(
                parallel,
                "_publish_windows_owned_temp_no_replace",
                side_effect=publish_then_fail,
            ):
                with self.assertRaisesRegex(RuntimeError, "post-rename failure"):
                    render_feature_batch_job(
                        encode_feature_batch_job(_job(root, _feature()))
                    )

            self.assertFalse(temporary_path.exists())
            self.assertFalse(final_path.exists())

    @unittest.skipUnless(os.name == "nt", "Windows close rollback test")
    def test_worker_disposes_published_file_when_first_close_attempt_fails(self) -> None:
        from tools.experiment8 import waterway_parallel_render as parallel

        class _FailFirstClose:
            def __init__(self, wrapped: object) -> None:
                self._wrapped = wrapped
                self.close_calls = 0

            def __getattr__(self, name: str):
                return getattr(self._wrapped, name)

            def close(self) -> None:
                self.close_calls += 1
                if self.close_calls == 1:
                    raise OSError("injected first close failure")
                self._wrapped.close()

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            final_path = root / "000000000020-000000000021.batch"
            temporary_path = root / (
                f"{final_path.name}.tmp-{os.getpid()}"
            )
            real_create = parallel._create_windows_owned_temp

            def create_with_close_failure(path: Path):
                return _FailFirstClose(real_create(path))

            with mock.patch.object(
                parallel,
                "_create_windows_owned_temp",
                side_effect=create_with_close_failure,
            ):
                with self.assertRaisesRegex(
                    GlobalWaterwayPackageError, "published atomically"
                ):
                    render_feature_batch_job(
                        encode_feature_batch_job(_job(root, _feature()))
                    )

            self.assertFalse(temporary_path.exists())
            self.assertFalse(final_path.exists())

    def test_reader_requires_parent_computed_source_range_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            descriptor, _ = _render(root)
            with self.assertRaises(TypeError):
                read_feature_batch(
                    root,
                    descriptor,
                    expected_render_run_identity_sha256=_RUN_SHA256,
                )

    def test_reader_rejects_wrong_parent_source_range_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            descriptor, _ = _render(root)
            with self.assertRaisesRegex(
                GlobalWaterwayPackageError, "source-range SHA-256"
            ):
                read_feature_batch(
                    root,
                    descriptor,
                    expected_render_run_identity_sha256=_RUN_SHA256,
                    expected_source_range_sha256="0" * 64,
                )

    def test_worker_writes_and_reads_one_authenticated_contiguous_batch(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            job = _job(root, _feature(7), _feature(8))
            descriptor = render_feature_batch_job(encode_feature_batch_job(job))
            self.assertEqual((20, 22), (descriptor.start_ordinal, descriptor.end_ordinal_exclusive))
            self.assertEqual("000000000020-000000000022.batch", descriptor.file_name)
            self.assertEqual(
                descriptor.byte_count,
                (root / descriptor.file_name).stat().st_size,
            )
            self.assertEqual(1, (root / descriptor.file_name).stat().st_nlink)
            frames = read_feature_batch(
                root,
                descriptor,
                expected_render_run_identity_sha256=_RUN_SHA256,
                expected_source_range_sha256=descriptor.source_range_sha256,
            )
            self.assertEqual((20, 21), tuple(frame.ordinal for frame in frames))
            self.assertEqual((7, 8), tuple(frame.source_id for frame in frames))
            self.assertTrue(all(frame.registry_claims for frame in frames))
            self.assertTrue(all(frame.identity_rows for frame in frames))
            self.assertTrue(all(frame.record_rows for frame in frames))
            self.assertTrue(all(frame.posting_bytes > 0 for frame in frames))

    def test_worker_frame_rows_and_identity_sets_match_serial_renderer_bytes(self) -> None:
        from tools.experiment8.osm_global_waterway_renderer import (
            build_adaptive_waterway_feature,
        )
        from tools.experiment8.osm_global_waterway_store import (
            _expected_rendered_feature_rows,
        )
        from tools.experiment8.semantic_model import variant_fingerprint

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            feature = _feature()
            job = _job(root, feature)
            descriptor = render_feature_batch_job(encode_feature_batch_job(job))
            frame = read_feature_batch(
                root,
                descriptor,
                expected_render_run_identity_sha256=_RUN_SHA256,
                expected_source_range_sha256=descriptor.source_range_sha256,
            )[0]
            rendered = build_adaptive_waterway_feature(
                feature=feature,
                source_generation_sha256=job.source_generation_sha256,
                classifier_sha256=job.classifier_sha256,
                zooms=job.zooms,
            )
            serial_rows = _expected_rendered_feature_rows(feature, rendered)
            self.assertEqual(
                serial_rows,
                tuple(
                    sorted(
                        frame.record_rows,
                        key=lambda row: (row[0], row[1], row[2], row[3]),
                    )
                ),
            )
            expected_identities = {
                (
                    "feature_ids",
                    feature.source_feature_sha256[:8],
                    feature.source_feature_sha256,
                )
            }
            for records in rendered.tiles.values():
                for record in records:
                    variant = record.renderer_record.variant
                    sourced = record.sourced_text
                    assert sourced is not None
                    expected_identities.update(
                        (
                            (
                                "geometry_ids",
                                variant.geometry_id.to_bytes(8, "big"),
                                variant.placement.placement_geometry_sha256,
                            ),
                            (
                                "label_ids",
                                variant.placement.label_candidate_id.to_bytes(
                                    8, "big"
                                ),
                                variant.placement.label_candidate_sha256,
                            ),
                            (
                                "variant_ids",
                                variant.canonical_variant_id.to_bytes(8, "big"),
                                variant_fingerprint(variant).full_sha256,
                            ),
                            (
                                "sourced_ids",
                                sourced.hot_id.to_bytes(8, "big"),
                                sourced.full_sha256,
                            ),
                        )
                    )
            self.assertEqual(expected_identities, set(frame.identity_rows))
            self.assertEqual(
                {"feature_ids", "geometry_ids", "label_ids", "variant_ids", "sourced_ids"},
                {row[0] for row in frame.identity_rows},
            )
            self.assertEqual(
                sum(len(row[11]) for row in serial_rows), frame.posting_bytes
            )

    def test_reader_rejects_wrong_file_hash(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            descriptor, _ = _render(root)
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "SHA-256"):
                read_feature_batch(
                    root,
                    replace(descriptor, sha256="0" * 64),
                    expected_render_run_identity_sha256=_RUN_SHA256,
                    expected_source_range_sha256=descriptor.source_range_sha256,
                )

    def test_reader_rejects_wrong_ordinal_descriptor(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            descriptor, _ = _render(root)
            path = root / descriptor.file_name
            contents = bytearray(path.read_bytes())
            start_offset = len(b"FAE8WRBATCH") + 1
            contents[start_offset : start_offset + 8] = (19).to_bytes(8, "little")
            path.write_bytes(contents)
            changed = replace(
                descriptor,
                sha256=hashlib.sha256(contents).hexdigest(),
            )
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "ordinal|range"):
                read_feature_batch(
                    root,
                    changed,
                    expected_render_run_identity_sha256=_RUN_SHA256,
                    expected_source_range_sha256=descriptor.source_range_sha256,
                )

    def test_reader_rejects_wrong_render_run(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            descriptor, _ = _render(root)
            path = root / descriptor.file_name
            contents = bytearray(path.read_bytes())
            run_offset = len(b"FAE8WRBATCH") + 1 + 8 + 8
            contents[run_offset : run_offset + 32] = b"\xff" * 32
            path.write_bytes(contents)
            changed = replace(
                descriptor,
                sha256=hashlib.sha256(contents).hexdigest(),
            )
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "render run"):
                read_feature_batch(
                    root,
                    changed,
                    expected_render_run_identity_sha256=_RUN_SHA256,
                    expected_source_range_sha256=descriptor.source_range_sha256,
                )

    def test_reader_reconciles_authenticated_point_count_with_frames(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            descriptor, _ = _render(root)
            path = root / descriptor.file_name
            contents = bytearray(path.read_bytes())
            point_offset = len(b"FAE8WRBATCH") + 1 + 8 + 8 + 32 + 32
            false_point_count = descriptor.point_count + 1
            contents[point_offset : point_offset + 8] = false_point_count.to_bytes(
                8, "little"
            )
            path.write_bytes(contents)
            changed = replace(
                descriptor,
                point_count=false_point_count,
                sha256=hashlib.sha256(contents).hexdigest(),
            )
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "point count"):
                read_feature_batch(
                    root,
                    changed,
                    expected_render_run_identity_sha256=_RUN_SHA256,
                    expected_source_range_sha256=descriptor.source_range_sha256,
                )

    def test_reader_rejects_authenticated_trailing_byte(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            descriptor, _ = _render(root)
            path = root / descriptor.file_name
            path.write_bytes(path.read_bytes() + b"x")
            contents = path.read_bytes()
            changed = replace(
                descriptor,
                byte_count=len(contents),
                sha256=hashlib.sha256(contents).hexdigest(),
            )
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "trailing"):
                read_feature_batch(
                    root,
                    changed,
                    expected_render_run_identity_sha256=_RUN_SHA256,
                    expected_source_range_sha256=descriptor.source_range_sha256,
                )

    def test_reader_rejects_out_of_root_descriptor_name(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            descriptor, _ = _render(root)
            outside = root.parent / "outside.batch"
            outside.write_bytes((root / descriptor.file_name).read_bytes())
            self.addCleanup(outside.unlink, missing_ok=True)
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "name|root"):
                read_feature_batch(
                    root,
                    replace(descriptor, file_name="../outside.batch"),
                    expected_render_run_identity_sha256=_RUN_SHA256,
                    expected_source_range_sha256=descriptor.source_range_sha256,
                )

    def test_reader_rejects_symlink_or_reparse_spool(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            descriptor, _ = _render(root)
            path = root / descriptor.file_name
            target = root / "real.batch"
            path.replace(target)
            try:
                os.symlink(target, path)
            except OSError as error:
                self.skipTest(f"file symlinks are unavailable: {error}")
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "link|reparse|regular"):
                read_feature_batch(
                    root,
                    descriptor,
                    expected_render_run_identity_sha256=_RUN_SHA256,
                    expected_source_range_sha256=descriptor.source_range_sha256,
                )

    def test_worker_rejects_a_symlink_or_reparse_spool_ancestor(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            real_parent = base / "real-parent"
            real_spool = real_parent / "spool"
            real_spool.mkdir(parents=True)
            linked_parent = base / "linked-parent"
            try:
                os.symlink(real_parent, linked_parent, target_is_directory=True)
            except OSError as error:
                self.skipTest(f"directory symlinks are unavailable: {error}")
            with self.assertRaisesRegex(
                GlobalWaterwayPackageError, "ancestor|link|reparse"
            ):
                render_feature_batch_job(
                    encode_feature_batch_job(
                        _job(linked_parent / "spool", _feature())
                    )
                )
            self.assertEqual((), tuple(real_spool.iterdir()))

    @unittest.skipUnless(os.name == "nt", "Windows directory-handle race test")
    def test_worker_holds_spool_ancestor_chain_through_temp_creation(self) -> None:
        from tools.experiment8 import waterway_parallel_render as parallel

        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            real_parent = base / "real-parent"
            spool = real_parent / "spool"
            spool.mkdir(parents=True)
            outside_parent = base / "outside-parent"
            outside_spool = outside_parent / "spool"
            outside_spool.mkdir(parents=True)
            moved_parent = base / "moved-parent"
            probe = base / "symlink-probe"
            try:
                os.symlink(outside_parent, probe, target_is_directory=True)
            except OSError as error:
                self.skipTest(f"directory symlinks are unavailable: {error}")
            else:
                probe.unlink()

            real_create = parallel._create_windows_owned_temp
            race_attempted = False
            race_blocked = False

            def racing_create(path: Path):
                nonlocal race_attempted, race_blocked
                if not race_attempted:
                    race_attempted = True
                    try:
                        os.replace(real_parent, moved_parent)
                    except OSError:
                        race_blocked = True
                    else:
                        os.symlink(
                            outside_parent,
                            real_parent,
                            target_is_directory=True,
                        )
                return real_create(path)

            render_error: BaseException | None = None
            descriptor: SpoolDescriptor | None = None
            try:
                with mock.patch.object(
                    parallel,
                    "_create_windows_owned_temp",
                    side_effect=racing_create,
                ):
                    descriptor = render_feature_batch_job(
                        encode_feature_batch_job(_job(spool, _feature()))
                    )
            except BaseException as error:
                render_error = error

            outside_names = tuple(path.name for path in outside_spool.iterdir())
            if real_parent.is_symlink():
                real_parent.unlink()
                os.replace(moved_parent, real_parent)

            self.assertTrue(race_attempted)
            self.assertTrue(
                race_blocked,
                "the spool ancestor was replaceable while the worker opened its temp file",
            )
            self.assertEqual((), outside_names)
            if render_error is not None:
                raise render_error
            assert descriptor is not None
            self.assertTrue((spool / descriptor.file_name).is_file())

    @unittest.skipUnless(os.name == "nt", "Windows publication guard race test")
    def test_worker_holds_spool_ancestor_chain_through_publication(self) -> None:
        from tools.experiment8 import waterway_parallel_render as parallel

        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            real_parent = base / "real-parent"
            spool = real_parent / "spool"
            spool.mkdir(parents=True)
            outside_parent = base / "outside-parent"
            outside_spool = outside_parent / "spool"
            outside_spool.mkdir(parents=True)
            moved_parent = base / "moved-parent"
            probe = base / "symlink-probe"
            try:
                os.symlink(outside_parent, probe, target_is_directory=True)
            except OSError as error:
                self.skipTest(f"directory symlinks are unavailable: {error}")
            else:
                probe.unlink()

            real_publish = parallel._publish_windows_owned_temp_no_replace
            race_attempted = False
            race_blocked = False

            def racing_publish(handle: object, destination: Path) -> None:
                nonlocal race_attempted, race_blocked
                race_attempted = True
                try:
                    os.replace(real_parent, moved_parent)
                except OSError:
                    race_blocked = True
                else:
                    os.symlink(
                        outside_parent,
                        real_parent,
                        target_is_directory=True,
                    )
                real_publish(handle, destination)

            render_error: BaseException | None = None
            descriptor: SpoolDescriptor | None = None
            try:
                with mock.patch.object(
                    parallel,
                    "_publish_windows_owned_temp_no_replace",
                    side_effect=racing_publish,
                ):
                    descriptor = render_feature_batch_job(
                        encode_feature_batch_job(_job(spool, _feature()))
                    )
            except BaseException as error:
                render_error = error

            outside_names = tuple(path.name for path in outside_spool.iterdir())
            if real_parent.is_symlink():
                real_parent.unlink()
                os.replace(moved_parent, real_parent)

            self.assertTrue(race_attempted)
            self.assertTrue(
                race_blocked,
                "the spool ancestor was replaceable during final publication",
            )
            self.assertEqual((), outside_names)
            if render_error is not None:
                raise render_error
            assert descriptor is not None
            self.assertTrue((spool / descriptor.file_name).is_file())

    def test_reader_rejects_reparse_attribute_even_for_regular_mode(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            descriptor, _ = _render(root)
            path = root / descriptor.file_name
            real_lstat = os.lstat

            def marked_lstat(candidate: object):
                result = real_lstat(candidate)
                if Path(candidate) != path:
                    return result
                values = list(result)
                while len(values) <= 16:
                    values.append(0)
                values[16] = getattr(result, "st_file_attributes", 0) | 0x400
                return os.stat_result(values)

            with mock.patch(
                "tools.experiment8.waterway_parallel_render.os.lstat",
                side_effect=marked_lstat,
            ):
                with self.assertRaisesRegex(
                    GlobalWaterwayPackageError, "reparse|regular"
                ):
                    read_feature_batch(
                        root,
                        descriptor,
                        expected_render_run_identity_sha256=_RUN_SHA256,
                        expected_source_range_sha256=descriptor.source_range_sha256,
                    )


if __name__ == "__main__":
    unittest.main()
