from __future__ import annotations

import io
import json
import unittest
from pathlib import Path
from types import MappingProxyType
from unittest.mock import patch


class RunGlobalWaterwayRecoveryCliTests(unittest.TestCase):
    def test_recover_render_routes_all_required_arguments_and_writes_canonical_json(self) -> None:
        from tools.experiment8 import osm_global_waterway_package as pipeline

        result = pipeline.GlobalWaterwayBuildResult(
            "complete",
            Path("output"),
            MappingProxyType({"schema": "fixture-receipt"}),
        )
        stdout = io.StringIO()
        stderr = io.StringIO()
        argv = [
            "recover-render",
            "--extraction",
            "extraction",
            "--output",
            "output",
            "--work",
            "work",
            "--package-id",
            "package",
            "--failure-log",
            "stderr.log",
            "--backup-receipt",
            "backup.json",
            "--checkpoint-features",
            "7",
            "--size-policy",
            "complete-uncompressed-visual-evaluation-v1",
        ]
        with patch.object(
            pipeline, "recover_global_waterway_package", return_value=result
        ) as recover, patch("sys.stdout", stdout), patch("sys.stderr", stderr):
            self.assertEqual(0, pipeline.main(argv))
        recover.assert_called_once_with(
            extraction_directory=Path("extraction"),
            output_directory=Path("output"),
            work_directory=Path("work"),
            package_id="package",
            failure_log=Path("stderr.log"),
            backup_receipt=Path("backup.json"),
            checkpoint_features=7,
            size_policy_mode="complete-uncompressed-visual-evaluation-v1",
        )
        expected = {
            "outputDirectory": "output",
            "receipt": {"schema": "fixture-receipt"},
            "state": "complete",
        }
        self.assertEqual(
            json.dumps(expected, sort_keys=True, separators=(",", ":")) + "\n",
            stdout.getvalue(),
        )
        self.assertEqual("", stderr.getvalue())

    def test_recover_render_rejection_is_canonical_structured_stderr(self) -> None:
        from tools.experiment8 import osm_global_waterway_package as pipeline

        stderr = io.StringIO()
        with patch.object(
            pipeline,
            "recover_global_waterway_package",
            side_effect=pipeline.GlobalWaterwayPackageError("rejected exactly"),
        ), patch("sys.stdout", io.StringIO()), patch("sys.stderr", stderr):
            result = pipeline.main(
                [
                    "recover-render",
                    "--extraction",
                    "extraction",
                    "--output",
                    "output",
                    "--work",
                    "work",
                    "--package-id",
                    "package",
                    "--failure-log",
                    "stderr.log",
                    "--backup-receipt",
                    "backup.json",
                ]
            )
        self.assertEqual(2, result)
        document = json.loads(stderr.getvalue())
        self.assertEqual("failed", document["state"])
        self.assertEqual("rejected exactly", document["error"]["message"])
        self.assertEqual(
            json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n",
            stderr.getvalue(),
        )

    def test_ordinary_render_does_not_enter_recovery(self) -> None:
        from tools.experiment8 import osm_global_waterway_package as pipeline

        paused = pipeline.GlobalWaterwayBuildResult(
            "paused", Path("output"), MappingProxyType({"checkpoint": {}})
        )
        with patch.object(
            pipeline, "render_global_waterway_package", return_value=paused
        ) as render, patch.object(
            pipeline, "recover_global_waterway_package"
        ) as recover, patch("sys.stdout", io.StringIO()):
            self.assertEqual(
                0,
                pipeline.main(
                    [
                        "render",
                        "--extraction",
                        "extraction",
                        "--output",
                        "output",
                        "--work",
                        "work",
                    ]
                ),
            )
        render.assert_called_once()
        self.assertEqual(
            "budgeted-release-v1",
            render.call_args.kwargs["size_policy_mode"],
        )
        recover.assert_not_called()

        stderr = io.StringIO()
        with patch.object(
            pipeline,
            "render_global_waterway_package",
            side_effect=pipeline.GlobalWaterwayPackageError(
                "renderer checkpoint identity differs from exact predecessor"
            ),
        ) as render, patch.object(
            pipeline, "recover_global_waterway_package"
        ) as recover, patch("sys.stdout", io.StringIO()), patch(
            "sys.stderr", stderr
        ):
            self.assertEqual(
                2,
                pipeline.main(
                    [
                        "render",
                        "--extraction",
                        "extraction",
                        "--output",
                        "output",
                        "--work",
                        "work",
                    ]
                ),
            )
        render.assert_called_once()
        recover.assert_not_called()
        failure = json.loads(stderr.getvalue())
        self.assertEqual("failed", failure["state"])
        self.assertIn("checkpoint identity", failure["error"]["message"])

    def test_validate_recovery_routes_without_rendering_or_mutation(self) -> None:
        from tools.experiment8 import osm_global_waterway_package as pipeline

        validation = MappingProxyType(
            {
                "recoveryRunIdentitySha256": "a" * 64,
                "schema": (
                    "flightalert.experiment8.osm-global-waterway-"
                    "render-recovery-validation.v1"
                ),
                "state": "accepted",
            }
        )
        stdout = io.StringIO()
        argv = [
            "validate-recovery",
            "--extraction",
            "extraction",
            "--output",
            "output",
            "--work",
            "work",
            "--package-id",
            "package",
            "--failure-log",
            "stderr.log",
            "--backup-receipt",
            "backup.json",
            "--checkpoint-features",
            "7",
            "--size-policy",
            "complete-uncompressed-visual-evaluation-v1",
        ]
        with patch.object(
            pipeline,
            "validate_global_waterway_render_recovery",
            return_value=validation,
            create=True,
        ) as validate, patch.object(
            pipeline, "recover_global_waterway_package"
        ) as recover, patch.object(
            pipeline, "render_global_waterway_package"
        ) as render, patch("sys.stdout", stdout):
            self.assertEqual(0, pipeline.main(argv))
        validate.assert_called_once_with(
            extraction_directory=Path("extraction"),
            output_directory=Path("output"),
            work_directory=Path("work"),
            package_id="package",
            failure_log=Path("stderr.log"),
            backup_receipt=Path("backup.json"),
            checkpoint_features=7,
            size_policy_mode="complete-uncompressed-visual-evaluation-v1",
        )
        recover.assert_not_called()
        render.assert_not_called()
        expected = {"state": "accepted", "validation": dict(validation)}
        self.assertEqual(
            json.dumps(expected, sort_keys=True, separators=(",", ":")) + "\n",
            stdout.getvalue(),
        )


if __name__ == "__main__":
    unittest.main()
