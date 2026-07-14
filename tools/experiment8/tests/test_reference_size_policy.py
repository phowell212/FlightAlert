from __future__ import annotations

import hashlib
import unittest
from pathlib import Path


class ReferenceSizePolicyTests(unittest.TestCase):
    def test_exact_modes_and_policy_document_are_closed(self) -> None:
        from tools.experiment8 import reference_size_policy as policy

        self.assertEqual(
            (policy.BUDGETED_RELEASE_V1, policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1),
            policy.REFERENCE_SIZE_POLICY_MODES,
        )
        document = policy.reference_size_policy_document()
        self.assertEqual(
            "flightalert.experiment8.reference-size-policy.v2",
            document["schema"],
        )
        self.assertEqual(25_000_000_000, document["historicalBudgets"]["preferredMandatoryPhoneFootprintBytes"])
        self.assertEqual(23_500_000_000, document["historicalBudgets"]["preferredComponentPackageBytes"])
        self.assertEqual(38_500_000_000, document["historicalBudgets"]["hardComponentPackageBytes"])
        self.assertEqual(40_000_000_000, document["historicalBudgets"]["hardMandatoryPhoneFootprintBytes"])
        self.assertEqual(1_500_000_000, document["destinationReserveBytes"])
        self.assertEqual(
            (
                "fresh-destination-free-plus-exact-owned-partial-before-staging-"
                "and-fresh-final-reserve-proof"
            ),
            document["visualEvaluationCapacityBasis"],
        )
        self.assertEqual(
            "memory-only-sqlite-capacity-is-not-authority",
            document["visualEvaluationCapacityPersistence"],
        )
        with self.assertRaisesRegex(policy.ReferenceSizePolicyError, "unsupported"):
            policy.reference_size_policy_binding("ignore-size")
        for malformed in (None, True, "BUDGETED-RELEASE-V1", ""):
            with self.subTest(malformed=malformed), self.assertRaisesRegex(
                policy.ReferenceSizePolicyError, "unsupported"
            ):
                policy.reference_size_policy_binding(malformed)

    def test_binding_contains_exact_module_identity_document_and_mode(self) -> None:
        from tools.experiment8 import reference_size_policy as policy

        binding = policy.reference_size_policy_binding(policy.BUDGETED_RELEASE_V1)
        module = Path(policy.__file__).resolve()
        raw = module.read_bytes()
        self.assertEqual(policy.BUDGETED_RELEASE_V1, binding["mode"])
        self.assertEqual(policy.reference_size_policy_document(), binding["document"])
        self.assertEqual(
            "174f2e4a213e27e676578155d132b48513df9700108292af82f16a4516cdec3f",
            binding["documentSha256"],
        )
        self.assertEqual(
            {"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()},
            binding["module"],
        )
        with self.assertRaises(TypeError):
            binding["document"]["constraints"]["contentPruningAuthorized"] = True
        with self.assertRaises(TypeError):
            binding["module"]["sha256"] = "f" * 64
        with self.assertRaises(TypeError):
            dict.__setitem__(binding["document"], "schema", "forged")
        with self.assertRaises(TypeError):
            list.append(binding["document"]["modes"], "forged")

    def test_budgeted_mode_retains_strict_ceiling_and_truthful_budget_facts(self) -> None:
        from tools.experiment8 import reference_size_policy as policy

        accepted = policy.evaluate_reference_size_policy(
            mode=policy.BUDGETED_RELEASE_V1,
            required_package_bytes=38_499_999_999,
            available_destination_bytes=None,
        )
        self.assertTrue(accepted["authorized"])
        self.assertTrue(accepted["preferredComponentPackageCeilingExceeded"])
        self.assertTrue(accepted["preferredMandatoryPhoneFootprintCeilingExceeded"])
        self.assertFalse(accepted["hardComponentPackageCeilingExceeded"])
        self.assertFalse(accepted["hardMandatoryPhoneFootprintCeilingExceeded"])
        self.assertEqual(39_999_999_999, accepted["mandatoryPhoneFootprintBytes"])

        rejected = policy.evaluate_reference_size_policy(
            mode=policy.BUDGETED_RELEASE_V1,
            required_package_bytes=38_500_000_000,
            available_destination_bytes=None,
        )
        self.assertFalse(rejected["authorized"])
        self.assertTrue(rejected["hardComponentPackageCeilingExceeded"])
        self.assertTrue(rejected["hardMandatoryPhoneFootprintCeilingExceeded"])

    def test_historical_preferred_and_hard_thresholds_are_exact(self) -> None:
        from tools.experiment8 import reference_size_policy as policy

        for required, preferred, hard in (
            (23_499_999_999, False, False),
            (23_500_000_000, True, False),
            (38_499_999_999, True, False),
            (38_500_000_000, True, True),
        ):
            with self.subTest(required=required):
                decision = policy.evaluate_reference_size_policy(
                    mode=policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1,
                    required_package_bytes=required,
                    available_destination_bytes=100_000_000_000,
                )
                self.assertEqual(
                    preferred,
                    decision["preferredComponentPackageCeilingExceeded"],
                )
                self.assertEqual(
                    preferred,
                    decision[
                        "preferredMandatoryPhoneFootprintCeilingExceeded"
                    ],
                )
                self.assertEqual(
                    hard,
                    decision["hardComponentPackageCeilingExceeded"],
                )
                self.assertEqual(
                    hard,
                    decision["hardMandatoryPhoneFootprintCeilingExceeded"],
                )

    def test_visual_mode_requires_exact_dynamic_capacity_plus_reserve(self) -> None:
        from tools.experiment8 import reference_size_policy as policy

        required = 42_000_000_000
        exact = required + policy.DESTINATION_RESERVE_BYTES
        accepted = policy.evaluate_reference_size_policy(
            mode=policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1,
            required_package_bytes=required,
            available_destination_bytes=exact,
        )
        self.assertTrue(accepted["authorized"])
        self.assertTrue(accepted["hardComponentPackageCeilingExceeded"])
        self.assertTrue(accepted["hardMandatoryPhoneFootprintCeilingExceeded"])
        self.assertEqual(exact, accepted["requiredWithReserveBytes"])
        self.assertEqual(exact, accepted["availableDestinationBytes"])

        rejected = policy.evaluate_reference_size_policy(
            mode=policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1,
            required_package_bytes=required,
            available_destination_bytes=exact - 1,
        )
        self.assertFalse(rejected["authorized"])
        for malformed in (None, True, -1):
            with self.subTest(malformed=malformed), self.assertRaises(
                policy.ReferenceSizePolicyError
            ):
                policy.evaluate_reference_size_policy(
                    mode=policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1,
                    required_package_bytes=required,
                    available_destination_bytes=malformed,
                )


if __name__ == "__main__":
    unittest.main()
