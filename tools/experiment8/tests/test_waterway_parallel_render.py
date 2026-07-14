from __future__ import annotations

import hashlib
import os
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest import mock

from tools.experiment8.osm_global_waterway_package import GlobalWaterwayPackageError
from tools.experiment8.osm_global_waterway_renderer import (
    ExactWaterwayFeature,
    ExactWaterwayPoint,
)
from tools.experiment8.semantic_model import HotIdRegistry
from tools.experiment8.waterway_parallel_render import (
    FeatureRenderBatchJob,
    RecordingHotIdRegistry,
    SpoolDescriptor,
    decode_feature_batch_job,
    encode_feature_batch_job,
    read_feature_batch,
    render_feature_batch_job,
    replay_registry_claims,
)


_SOURCE_SHA256 = hashlib.sha256(b"parallel source fixture").hexdigest()
_CLASSIFIER_SHA256 = hashlib.sha256(b"parallel classifier fixture").hexdigest()
_RUN_SHA256 = hashlib.sha256(b"parallel render run fixture").hexdigest()


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
