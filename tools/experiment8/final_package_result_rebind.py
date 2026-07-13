"""Bind a validated Experiment 8 package result to a final release APK."""

from __future__ import annotations

import argparse
import copy
import hashlib
import os
import re
import stat
import subprocess
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence

from tools.experiment8 import reference_package_install as installer
from tools.experiment8.reference_package_install import (
    HostInstallPlan,
    ReferencePackageInstallError,
)


CANONICAL_APK_RELATIVE_PATH = Path(
    "build/outputs/apk/debug/Flight Alert-debug.apk"
)
FINAL_APK_SELECTION = (
    "fresh final clean-source APK from exact Git HEAD; "
    "original package result validated before rebind"
)
_FULL_COMMIT = re.compile(r"[0-9a-f]{40}\Z")
_GRADLE_BUILD_ARGUMENTS = ("clean", "assembleDebug", "--no-daemon")


def _same_path(left: Path, right: Path) -> bool:
    return os.path.normcase(str(left)) == os.path.normcase(str(right))


def _resolved_beneath(repository: Path, path: Path) -> bool:
    try:
        common = Path(os.path.commonpath((str(repository), str(path))))
    except ValueError:
        return False
    return _same_path(common, repository)


def _canonical_apk_with_safe_components(repository: Path) -> Path:
    try:
        root_information = repository.stat(follow_symlinks=False)
    except OSError as error:
        raise ReferencePackageInstallError(
            "source repository changed during path validation"
        ) from error
    try:
        resolved_root = repository.resolve(strict=True)
    except OSError as error:
        raise ReferencePackageInstallError(
            "source repository cannot be resolved during path validation"
        ) from error
    if (
        not stat.S_ISDIR(root_information.st_mode)
        or installer._is_reparse(root_information)
        or not _same_path(resolved_root, repository)
    ):
        raise ReferencePackageInstallError(
            "source repository is not one stable real directory"
        )

    current = repository
    components = CANONICAL_APK_RELATIVE_PATH.parts
    for index, component in enumerate(components):
        current = current / component
        try:
            information = current.stat(follow_symlinks=False)
        except OSError as error:
            raise ReferencePackageInstallError(
                "canonical final APK path component is missing"
            ) from error
        is_leaf = index == len(components) - 1
        expected_kind = (
            stat.S_ISREG(information.st_mode)
            if is_leaf
            else stat.S_ISDIR(information.st_mode)
        )
        if not expected_kind or installer._is_reparse(information):
            raise ReferencePackageInstallError(
                "canonical final APK path contains a non-real component"
            )
        try:
            resolved = current.resolve(strict=True)
        except OSError as error:
            raise ReferencePackageInstallError(
                "canonical final APK path component cannot be resolved"
            ) from error
        if not _resolved_beneath(repository, resolved):
            raise ReferencePackageInstallError(
                "canonical final APK path escapes the source repository"
            )
    return resolved


def _source_commit(repository: Path) -> str:
    try:
        top_level = subprocess.run(
            ["git", "-C", str(repository), "rev-parse", "--show-toplevel"],
            check=False,
            capture_output=True,
        )
    except OSError as error:
        raise ReferencePackageInstallError(
            "Git top level cannot be inspected"
        ) from error
    if top_level.returncode != 0:
        raise ReferencePackageInstallError("Git top level cannot be inspected")
    top_level_lines = os.fsdecode(top_level.stdout).splitlines()
    if len(top_level_lines) != 1 or not top_level_lines[0]:
        raise ReferencePackageInstallError("Git top level is not one path")
    reported_top_level = installer._real_directory(
        Path(top_level_lines[0]), "Git top level"
    )
    if not _same_path(reported_top_level, repository):
        raise ReferencePackageInstallError(
            "source repository is not the exact Git top level"
        )

    try:
        revision = subprocess.run(
            ["git", "-C", str(repository), "rev-parse", "--verify", "HEAD"],
            check=False,
            capture_output=True,
        )
    except OSError as error:
        raise ReferencePackageInstallError("Git HEAD cannot be inspected") from error
    if revision.returncode != 0:
        raise ReferencePackageInstallError("Git HEAD cannot be inspected")
    try:
        lines = revision.stdout.decode("ascii", "strict").splitlines()
    except UnicodeError as error:
        raise ReferencePackageInstallError("Git HEAD is not strict ASCII") from error
    if len(lines) != 1 or _FULL_COMMIT.fullmatch(lines[0]) is None:
        raise ReferencePackageInstallError("Git HEAD is not one full commit")

    for arguments, dirty_message in (
        (("diff", "--quiet"), "source repository has unstaged tracked changes"),
        (
            ("diff", "--cached", "--quiet"),
            "source repository has staged tracked changes",
        ),
    ):
        try:
            result = subprocess.run(
                ["git", "-C", str(repository), *arguments],
                check=False,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
            )
        except OSError as error:
            raise ReferencePackageInstallError(
                "Git source cleanliness cannot be inspected"
            ) from error
        if result.returncode == 1:
            raise ReferencePackageInstallError(dirty_message)
        if result.returncode != 0:
            raise ReferencePackageInstallError(
                "Git source cleanliness cannot be inspected"
            )
    return lines[0]


def _clean_build_final_apk(
    repository: Path, source_commit: str, canonical_apk: Path
) -> Path:
    wrapper = installer._real_file(repository / "gradlew.bat", "Gradle wrapper")
    checked_canonical_apk = _canonical_apk_with_safe_components(repository)
    if not _same_path(checked_canonical_apk, canonical_apk):
        raise ReferencePackageInstallError(
            "pre-build canonical final APK path changed"
        )
    canonical_apk = checked_canonical_apk
    try:
        canonical_apk.unlink()
    except OSError as error:
        raise ReferencePackageInstallError(
            "pre-build canonical final APK cannot be removed"
        ) from error
    if os.path.lexists(canonical_apk):
        raise ReferencePackageInstallError(
            "pre-build canonical final APK still exists after removal"
        )

    try:
        build = subprocess.run(
            [str(wrapper), *_GRADLE_BUILD_ARGUMENTS],
            cwd=repository,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except OSError as error:
        raise ReferencePackageInstallError(
            "Gradle clean debug build cannot be started"
        ) from error
    if build.returncode != 0:
        raise ReferencePackageInstallError("Gradle clean debug build failed")
    if _source_commit(repository) != source_commit:
        raise ReferencePackageInstallError(
            "source commit or tracked cleanliness changed during Gradle build"
        )
    return _canonical_apk_with_safe_components(repository)


def _fresh_output_path(path: Path) -> Path:
    requested = Path(path)
    if not requested.name:
        raise ReferencePackageInstallError("output path has no file name")
    parent = installer._real_directory(requested.parent, "output parent")
    output = parent / requested.name
    if os.path.normcase(str(requested.resolve(strict=False))) != os.path.normcase(
        str(output)
    ):
        raise ReferencePackageInstallError("output path does not resolve in its parent")
    if os.path.lexists(output):
        raise ReferencePackageInstallError("output path already exists")
    return output


def _same_install_file(
    left: installer.InstallFile, right: installer.InstallFile
) -> bool:
    return (
        left.path == right.path
        and left.byte_length == right.byte_length
        and left.sha256 == right.sha256
        and left.stat_identity == right.stat_identity
    )


def _write_stage(path: Path, raw: bytes) -> tuple[Path, tuple[int, int]]:
    stage = path.parent / f".{path.name}.stage-{uuid.uuid4().hex}"
    identity: tuple[int, int] | None = None
    try:
        with stage.open("xb") as handle:
            opened = os.fstat(handle.fileno())
            identity = (opened.st_dev, opened.st_ino)
            handle.write(raw)
            handle.flush()
            os.fsync(handle.fileno())
    except BaseException as error:
        _cleanup_owned_stage(stage, identity)
        if isinstance(error, OSError):
            raise ReferencePackageInstallError(
                "rebound result cannot be staged"
            ) from error
        raise
    return stage, identity


def _cleanup_owned_stage(path: Path | None, identity: tuple[int, int] | None) -> None:
    if path is None or identity is None:
        return
    try:
        information = path.stat(follow_symlinks=False)
        if (information.st_dev, information.st_ino) == identity:
            path.unlink()
    except FileNotFoundError:
        return
    except OSError:
        return


def _publish_stage(
    stage: Path,
    output: Path,
    identity: tuple[int, int],
    expected_raw: bytes,
) -> None:
    if stage.parent != output.parent:
        raise ReferencePackageInstallError(
            "atomic result publication must stay in one directory"
        )
    linked = False
    try:
        os.link(stage, output, follow_symlinks=False)
        linked = True
        published = output.stat(follow_symlinks=False)
        if (published.st_dev, published.st_ino) != identity:
            raise ReferencePackageInstallError(
                "published result identity differs from its owned stage"
            )
        published_file = installer._hash_regular_file(
            output, "published rebound result"
        )
        published_raw = installer._read_bounded_regular_file(
            output,
            "published rebound result",
            installer.MAX_JSON_BYTES,
        )
        expected_sha256 = hashlib.sha256(expected_raw).hexdigest()
        if (
            published_raw != expected_raw
            or published_file.byte_length != len(expected_raw)
            or published_file.sha256 != expected_sha256
        ):
            raise ReferencePackageInstallError(
                "published rebound result differs from its canonical stage"
            )
        installer._assert_install_file_unchanged(published_file)
        stage.unlink()
    except BaseException as error:
        if linked:
            _cleanup_owned_stage(output, identity)
        if isinstance(error, ReferencePackageInstallError):
            raise
        if isinstance(error, OSError):
            raise ReferencePackageInstallError(
                "rebound result cannot be atomically published"
            ) from error
        raise


def _completed_utc() -> str:
    return (
        datetime.now(timezone.utc)
        .isoformat(timespec="microseconds")
        .replace("+00:00", "Z")
    )


def _assert_exact_stage(path: Path, expected: bytes) -> None:
    actual = installer._read_bounded_regular_file(
        path, "staged rebound result", installer.MAX_JSON_BYTES
    )
    if actual != expected:
        raise ReferencePackageInstallError("staged rebound result changed")


def rebind_final_package_result(
    *,
    package_directory: Path,
    apk_path: Path,
    source_repository: Path,
    planning_result_path: Path,
    output_path: Path,
) -> Path:
    """Validate the planning result and atomically publish its final APK binding."""

    output = _fresh_output_path(Path(output_path))
    stage: Path | None = None
    stage_identity: tuple[int, int] | None = None
    try:
        planning_result_path = installer._real_file(
            Path(planning_result_path), "planning result"
        )
        original_result_file = installer._hash_regular_file(
            planning_result_path, "planning result"
        )
        original = installer._read_strict_json(
            planning_result_path, "planning result", canonical=False
        )
        installer._assert_install_file_unchanged(original_result_file)
        original_apk = Path(
            installer._exact_string(
                installer._exact_mapping(
                    original.get("apk"), "planning result APK"
                ).get("path"),
                "planning result APK path",
            )
        )
        original_plan = HostInstallPlan.validate(
            package_directory=Path(package_directory),
            apk_path=original_apk,
            final_result_path=planning_result_path,
        )
        if not _same_install_file(
            original_result_file,
            installer._hash_regular_file(planning_result_path, "planning result"),
        ):
            raise ReferencePackageInstallError(
                "planning result changed during original validation"
            )
        if "rebind" in original:
            raise ReferencePackageInstallError(
                "planning result already contains rebind provenance"
            )

        repository = installer._real_directory(
            Path(source_repository), "source repository"
        )
        source_commit = _source_commit(repository)
        canonical_apk = _canonical_apk_with_safe_components(repository)
        final_apk = installer._real_file(Path(apk_path), "final APK")
        if not _same_path(final_apk, canonical_apk):
            raise ReferencePackageInstallError(
                "final APK is not at the canonical repository output path"
            )
        final_apk = _clean_build_final_apk(
            repository, source_commit, canonical_apk
        )
        final_apk_file = installer._hash_regular_file(final_apk, "final APK")

        rebound = copy.deepcopy(original)
        apk = installer._exact_mapping(rebound.get("apk"), "planning result APK")
        apk["path"] = str(final_apk)
        apk["bytes"] = final_apk_file.byte_length
        apk["sha256"] = final_apk_file.sha256
        apk["sourceCommit"] = source_commit
        apk["selection"] = FINAL_APK_SELECTION

        footprint = installer._exact_mapping(
            rebound.get("footprint"), "planning result footprint"
        )
        total = (
            original_plan.package_bytes
            + final_apk_file.byte_length
            + original_plan.mandatory_reserve_bytes
        )
        preferred = total < installer.PREFERRED_FOOTPRINT_CEILING_BYTES
        hard = total < installer.HARD_FOOTPRINT_CEILING_BYTES
        footprint["totalBytes"] = total
        footprint["preferredStrictlyBelow"] = preferred
        footprint["hardStrictlyBelow"] = hard
        if not hard:
            raise ReferencePackageInstallError(
                "rebound footprint is not strictly below the hard ceiling"
            )

        rebound["completedUtc"] = _completed_utc()
        rebound["rebind"] = {
            "originalResultSha256": original_result_file.sha256,
            "sourceCommit": source_commit,
        }
        rebound_raw = installer._canonical_json_bytes(rebound)
        stage, stage_identity = _write_stage(output, rebound_raw)

        rebound_plan = HostInstallPlan.validate(
            package_directory=Path(package_directory),
            apk_path=final_apk,
            final_result_path=stage,
        )
        _assert_exact_stage(stage, rebound_raw)
        if not _same_install_file(
            final_apk_file,
            installer._hash_regular_file(rebound_plan.apk_path, "final APK"),
        ):
            raise ReferencePackageInstallError("final APK changed during rebind")
        installer._assert_apk_plan_unchanged(original_plan)
        if not _same_install_file(
            original_result_file,
            installer._hash_regular_file(planning_result_path, "planning result"),
        ):
            raise ReferencePackageInstallError("planning result changed during rebind")
        if _source_commit(repository) != source_commit:
            raise ReferencePackageInstallError("source commit changed during rebind")
        installer._assert_host_plan_unchanged(rebound_plan)
        if os.path.lexists(output):
            raise ReferencePackageInstallError("output path appeared during rebind")
        _assert_exact_stage(stage, rebound_raw)
        _publish_stage(stage, output, stage_identity, rebound_raw)
        stage = None
        stage_identity = None
        return output
    finally:
        _cleanup_owned_stage(stage, stage_identity)


def _main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Validate a final Experiment 8 package result and bind it to the "
            "canonical APK from a clean final source commit."
        )
    )
    parser.add_argument("--package", required=True, type=Path)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--source-repository", required=True, type=Path)
    parser.add_argument("--planning-result", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parsed = parser.parse_args(arguments)
    try:
        rebind_final_package_result(
            package_directory=parsed.package,
            apk_path=parsed.apk,
            source_repository=parsed.source_repository,
            planning_result_path=parsed.planning_result,
            output_path=parsed.output,
        )
    except ReferencePackageInstallError as error:
        parser.exit(1, f"error: {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(_main())


__all__ = [
    "CANONICAL_APK_RELATIVE_PATH",
    "FINAL_APK_SELECTION",
    "rebind_final_package_result",
]
