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
