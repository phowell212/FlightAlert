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
_GRADLE_DISTRIBUTION_URL = re.compile(
    r"https\\://services\.gradle\.org/distributions/"
    r"gradle-([0-9]+(?:\.[0-9]+){1,2})-bin\.zip\Z"
)
_GRADLE_VERSION_LINE = re.compile(r"^Gradle ([0-9]+(?:\.[0-9]+){1,2})$", re.MULTILINE)
_BUILD_INPUT_PATHS = (
    "app/src",
    "app/build.gradle",
    "app/build.gradle.kts",
    "app/proguard-rules.pro",
    "build.gradle",
    "build.gradle.kts",
    "buildSrc",
    "gradle",
    "gradle.properties",
    "gradlew",
    "gradlew.bat",
    "local.properties",
    "settings.gradle",
    "settings.gradle.kts",
)
_GRADLE_BUILD_ARGUMENTS = (
    "clean",
    "assembleDebug",
    "--no-daemon",
    "--offline",
)


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

    try:
        tracked = subprocess.run(
            ["git", "-C", str(repository), "ls-files", "-v", "-z", "--"],
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except OSError as error:
        raise ReferencePackageInstallError(
            "Git tracked-file flags cannot be inspected"
        ) from error
    if tracked.returncode != 0:
        raise ReferencePackageInstallError(
            "Git tracked-file flags cannot be inspected"
        )
    if any(
        entry and not entry.startswith(b"H ")
        for entry in tracked.stdout.split(b"\0")
    ):
        raise ReferencePackageInstallError(
            "source repository has hidden tracked file state"
        )

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

    try:
        untracked = subprocess.run(
            [
                "git",
                "-C",
                str(repository),
                "ls-files",
                "--others",
                "--exclude-standard",
                "-z",
            ],
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except OSError as error:
        raise ReferencePackageInstallError(
            "Git source cleanliness cannot be inspected"
        ) from error
    if untracked.returncode != 0:
        raise ReferencePackageInstallError(
            "Git source cleanliness cannot be inspected"
        )
    if untracked.stdout:
        raise ReferencePackageInstallError(
            "source repository has untracked source files"
        )

    try:
        build_inputs = subprocess.run(
            [
                "git",
                "-C",
                str(repository),
                "ls-files",
                "--others",
                "-z",
                "--",
                *_BUILD_INPUT_PATHS,
            ],
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except OSError as error:
        raise ReferencePackageInstallError(
            "Git build-input cleanliness cannot be inspected"
        ) from error
    if build_inputs.returncode != 0:
        raise ReferencePackageInstallError(
            "Git build-input cleanliness cannot be inspected"
        )
    if build_inputs.stdout:
        raise ReferencePackageInstallError(
            "source repository has untracked build input files"
        )
    return lines[0]


def _validate_prior_rebind(
    value: object, install_policy: str, apk_source_commit_value: object
) -> None:
    provenance = installer._exact_mapping(value, "rebind provenance")
    anchors = {
        name
        for name in ("originalResultSha256", "trustedPlanningResultSha256")
        if name in provenance
    }
    if len(anchors) != 1:
        raise ReferencePackageInstallError("rebind provenance fields differ")
    expected_fields = {next(iter(anchors)), "sourceCommit"}
    if "installPolicy" in provenance:
        expected_fields.add("installPolicy")
    if set(provenance) != expected_fields:
        raise ReferencePackageInstallError("rebind provenance fields differ")

    source_commit = installer._exact_string(
        provenance.get("sourceCommit"), "rebind source commit"
    )
    if _FULL_COMMIT.fullmatch(source_commit) is None:
        raise ReferencePackageInstallError("rebind source commit is malformed")
    apk_source_commit = installer._exact_string(
        apk_source_commit_value, "planning result APK source commit"
    )
    if source_commit != apk_source_commit:
        raise ReferencePackageInstallError(
            "rebind source commit differs from APK source commit"
        )

    if "installPolicy" not in provenance:
        if (
            install_policy
            == installer.INSTALL_POLICY_FULL_FIDELITY_VISUAL_EVALUATION
        ):
            raise ReferencePackageInstallError(
                "rebind install policy binding is missing"
            )
        return
    bound_policy = provenance.get("installPolicy")
    if bound_policy is None:
        raise ReferencePackageInstallError("rebind install policy cannot be null")
    if installer._validated_install_policy(bound_policy) != install_policy:
        raise ReferencePackageInstallError("rebind install policy binding differs")


def _gradle_version(repository: Path) -> str:
    properties_path = repository / "gradle" / "wrapper" / "gradle-wrapper.properties"
    properties_file = installer._hash_regular_file(
        properties_path, "Gradle wrapper properties"
    )
    raw = installer._read_bounded_regular_file(
        properties_path, "Gradle wrapper properties", installer.MAX_JSON_BYTES
    )
    installer._assert_install_file_unchanged(properties_file)
    if raw.startswith(b"\xef\xbb\xbf") or b"\0" in raw:
        raise ReferencePackageInstallError("Gradle wrapper properties are malformed")
    try:
        lines = raw.decode("utf-8", "strict").replace("\r\n", "\n").splitlines()
    except UnicodeError as error:
        raise ReferencePackageInstallError(
            "Gradle wrapper properties are malformed"
        ) from error
    distribution_urls = [
        line.split("=", 1)[1]
        for line in lines
        if line.startswith("distributionUrl=") and "=" in line
    ]
    if len(distribution_urls) != 1:
        raise ReferencePackageInstallError("Gradle distribution URL is malformed")
    match = _GRADLE_DISTRIBUTION_URL.fullmatch(distribution_urls[0])
    if match is None:
        raise ReferencePackageInstallError("Gradle distribution URL is unsupported")
    return match.group(1)


def _cached_gradle(
    repository: Path,
) -> tuple[Path, tuple[int, int, int, int]]:
    version = _gradle_version(repository)
    configured_home = os.environ.get("GRADLE_USER_HOME")
    home_path = Path(configured_home) if configured_home else Path.home() / ".gradle"
    if not home_path.is_absolute():
        raise ReferencePackageInstallError("Gradle user home is not absolute")
    home = installer._real_directory(home_path, "Gradle user home")
    wrapper = installer._real_directory(home / "wrapper", "cached Gradle wrapper root")
    dists = installer._real_directory(wrapper / "dists", "cached Gradle dists root")
    distribution = installer._real_directory(
        dists / f"gradle-{version}-bin", "cached Gradle distribution"
    )
    try:
        cache_entries = tuple(
            entry
            for entry in distribution.iterdir()
            if stat.S_ISDIR(entry.stat(follow_symlinks=False).st_mode)
        )
    except OSError as error:
        raise ReferencePackageInstallError(
            "cached Gradle distribution cannot be inspected"
        ) from error
    if not cache_entries:
        raise ReferencePackageInstallError("cached Gradle distribution is missing")
    if len(cache_entries) != 1:
        raise ReferencePackageInstallError("cached Gradle distribution is ambiguous")
    cache_entry = installer._real_directory(
        cache_entries[0], "cached Gradle cache entry"
    )
    expanded = installer._real_directory(
        cache_entry / f"gradle-{version}", "cached Gradle expanded distribution"
    )
    binary = installer._real_directory(expanded / "bin", "cached Gradle bin")
    executable = installer._real_file(
        binary / ("gradle.bat" if os.name == "nt" else "gradle"),
        "cached Gradle executable",
    )
    executable_information = executable.stat(follow_symlinks=False)
    executable_identity = (
        executable_information.st_dev,
        executable_information.st_ino,
        executable_information.st_size,
        executable_information.st_mtime_ns,
    )
    try:
        reported = subprocess.run(
            [str(executable), "--version"],
            check=False,
            cwd=repository,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=60.0,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ReferencePackageInstallError(
            "cached Gradle version cannot be inspected"
        ) from error
    if reported.returncode != 0 or len(reported.stdout) > 128 * 1024:
        raise ReferencePackageInstallError("cached Gradle version cannot be inspected")
    try:
        version_lines = _GRADLE_VERSION_LINE.findall(
            reported.stdout.decode("ascii", "strict").replace("\r\n", "\n")
        )
    except UnicodeError as error:
        raise ReferencePackageInstallError(
            "cached Gradle version cannot be inspected"
        ) from error
    if version_lines != [version]:
        raise ReferencePackageInstallError("cached Gradle version differs")
    _assert_cached_gradle_unchanged(executable, executable_identity)
    return executable, executable_identity


def _assert_cached_gradle_unchanged(
    executable: Path, expected: tuple[int, int, int, int]
) -> None:
    try:
        information = executable.stat(follow_symlinks=False)
    except OSError as error:
        raise ReferencePackageInstallError(
            "cached Gradle executable changed"
        ) from error
    actual = (
        information.st_dev,
        information.st_ino,
        information.st_size,
        information.st_mtime_ns,
    )
    if (
        not stat.S_ISREG(information.st_mode)
        or installer._is_reparse(information)
        or actual != expected
    ):
        raise ReferencePackageInstallError("cached Gradle executable changed")


def _clean_build_final_apk(
    repository: Path, source_commit: str, canonical_apk: Path
) -> Path:
    gradle, gradle_identity = _cached_gradle(repository)
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
        _assert_cached_gradle_unchanged(gradle, gradle_identity)
        build = subprocess.run(
            [str(gradle), *_GRADLE_BUILD_ARGUMENTS],
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
    _assert_cached_gradle_unchanged(gradle, gradle_identity)
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
    install_policy: str = installer.INSTALL_POLICY_RELEASE,
    trusted_planning_result_sha256: str | None = None,
) -> Path:
    """Validate the planning result and atomically publish its final APK binding."""

    install_policy = installer._validated_install_policy(install_policy)
    release_policy = install_policy == installer.INSTALL_POLICY_RELEASE
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
        original_apk_document = installer._exact_mapping(
            original.get("apk"), "planning result APK"
        )
        original_apk_source_commit = installer._exact_string(
            original_apk_document.get("sourceCommit"),
            "planning result APK source commit",
        )
        if _FULL_COMMIT.fullmatch(original_apk_source_commit) is None:
            raise ReferencePackageInstallError(
                "planning result APK source commit is malformed"
            )
        prior_rebind = original.get("rebind")
        if prior_rebind is None:
            if trusted_planning_result_sha256 is not None:
                raise ReferencePackageInstallError(
                    "trusted planning result SHA-256 requires existing rebind provenance"
                )
        else:
            if trusted_planning_result_sha256 is None:
                raise ReferencePackageInstallError(
                    "trusted planning result SHA-256 is required"
                )
            trusted_sha256 = installer._exact_sha256(
                trusted_planning_result_sha256,
                "trusted planning result SHA-256",
            )
            if trusted_sha256 != original_result_file.sha256:
                raise ReferencePackageInstallError(
                    "trusted planning result SHA-256 differs"
                )
            _validate_prior_rebind(
                prior_rebind,
                install_policy,
                original_apk_source_commit,
            )
        original_apk = Path(
            installer._exact_string(
                original_apk_document.get("path"),
                "planning result APK path",
            )
        )
        original_plan = HostInstallPlan.validate(
            package_directory=Path(package_directory),
            apk_path=original_apk,
            final_result_path=planning_result_path,
            install_policy=install_policy,
            require_install_policy_binding=(
                prior_rebind is not None and not release_policy
            ),
        )
        if not _same_install_file(
            original_result_file,
            installer._hash_regular_file(planning_result_path, "planning result"),
        ):
            raise ReferencePackageInstallError(
                "planning result changed during original validation"
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
        if release_policy and not hard:
            raise ReferencePackageInstallError(
                "rebound footprint is not strictly below the hard ceiling"
            )

        rebound["completedUtc"] = _completed_utc()
        if not release_policy:
            rebound["state"] = (
                installer.FINAL_RESULT_STATE_COMPLETE
                if hard
                else installer.FINAL_RESULT_STATE_FAILED_HARD_CEILING
            )
        rebound_rebind = {
            "sourceCommit": source_commit,
        }
        if prior_rebind is None:
            rebound_rebind["originalResultSha256"] = original_result_file.sha256
        else:
            rebound_rebind["trustedPlanningResultSha256"] = (
                original_result_file.sha256
            )
        if not release_policy:
            rebound_rebind["installPolicy"] = install_policy
        rebound["rebind"] = rebound_rebind
        rebound_raw = installer._canonical_json_bytes(rebound)
        stage, stage_identity = _write_stage(output, rebound_raw)

        rebound_plan = HostInstallPlan.validate(
            package_directory=Path(package_directory),
            apk_path=final_apk,
            final_result_path=stage,
            install_policy=install_policy,
            require_install_policy_binding=not release_policy,
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
    parser.add_argument("--trusted-planning-result-sha256")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument(
        "--install-policy",
        choices=installer.INSTALL_POLICIES,
        default=installer.INSTALL_POLICY_RELEASE,
    )
    parsed = parser.parse_args(arguments)
    try:
        rebind_final_package_result(
            package_directory=parsed.package,
            apk_path=parsed.apk,
            source_repository=parsed.source_repository,
            planning_result_path=parsed.planning_result,
            output_path=parsed.output,
            install_policy=parsed.install_policy,
            trusted_planning_result_sha256=(
                parsed.trusted_planning_result_sha256
            ),
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
