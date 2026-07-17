from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

from tools.experiment8.reference_package_install import (
    AdbInstallDevice,
    ReferencePackageInstaller,
    ReferencePackageInstallError,
    SubprocessCommandRunner,
    _hash_regular_file,
)


APP_ID = "com.flightalert"
ACTIVITY = "com.flightalert/.MainActivity"
LISTENER = "com.flightalert/com.flightalert.alerts.MonitoringNotificationHiderService"


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Install a source-matched APK and capture a phone-scale reference-label gate."
    )
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--evidence-directory", type=Path, required=True)
    parser.add_argument("--lease-helper", type=Path, required=True)
    parser.add_argument("--lease-token", required=True)
    parser.add_argument("--device", default="RFCX40KPN3B")
    parser.add_argument("--adb", default=str(Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe"))
    parser.add_argument("--label-scale", type=float, default=1.0)
    return parser.parse_args()


def _write_bytes(path: Path, raw: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as stream:
        stream.write(raw)
        stream.flush()


def _write_text(path: Path, text: str) -> None:
    _write_bytes(path, text.encode("utf-8"))


def _lease_guard(helper: Path, token: str) -> None:
    result = subprocess.run(
        ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(helper), "status"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
        timeout=30,
    )
    if result.returncode != 0:
        raise ReferencePackageInstallError("device lease status failed")
    state = json.loads(result.stdout.decode("utf-8"))
    reported_token = state.get("token") or (state.get("lease") or {}).get("token")
    if state.get("held") is not True or reported_token != token:
        raise ReferencePackageInstallError("device lease ownership changed")


def _thermal(device: AdbInstallDevice, destination: Path) -> float:
    result = device._checked(("shell", "dumpsys", "thermalservice"), timeout=60.0)
    text = result.stdout.decode("utf-8", errors="strict")
    _write_text(destination, text)
    values = [float(item) for item in re.findall(r"mValue=(-?[0-9]+(?:\.[0-9]+)?)", text)]
    if not values:
        raise ReferencePackageInstallError("thermalservice returned no temperatures")
    maximum = max(values)
    if maximum >= 60.0:
        raise ReferencePackageInstallError(f"device temperature reached {maximum:.1f} C")
    return maximum


def _set_preference(root: ET.Element, tag: str, name: str, value: str) -> None:
    matches = [child for child in root if child.get("name") == name]
    if len(matches) > 1:
        raise ReferencePackageInstallError(f"preference is duplicated: {name}")
    if matches and matches[0].tag != tag:
        raise ReferencePackageInstallError(f"preference has wrong XML type: {name}")
    node = matches[0] if matches else ET.SubElement(root, tag, {"name": name})
    if tag == "string":
        node.text = value
        node.attrib = {"name": name}
    else:
        node.text = None
        node.attrib = {"name": name, "value": value}


def _prepared_preferences(raw: bytes, scale: float) -> bytes:
    if not 0.75 <= scale <= 1.75:
        raise ReferencePackageInstallError("label scale is outside the product range")
    root = ET.fromstring(raw)
    if root.tag != "map":
        raise ReferencePackageInstallError("SharedPreferences root is not map")
    for child in list(root):
        if child.get("name") == "reference_filter_state":
            root.remove(child)
    _set_preference(root, "string", "map_source", "SATELLITE")
    _set_preference(root, "float", "map_label_text_scale", format(scale, ".3f").rstrip("0").rstrip("."))
    for name, value in {
        "map_labels_enabled": True,
        "map_borders_enabled": True,
        "layer_place_labels_enabled": True,
        "layer_water_labels_enabled": True,
        "layer_region_labels_enabled": True,
        "layer_public_lands_enabled": True,
        "layer_atc_boundaries_enabled": False,
        "layer_restricted_airspaces_enabled": False,
        "layer_oceanic_tracks_enabled": False,
        "layer_airport_labels_enabled": False,
    }.items():
        _set_preference(root, "boolean", name, str(value).lower())
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def _pull(device: AdbInstallDevice, remote: str, local: Path) -> None:
    device._checked(("pull", remote, str(local)), timeout=120.0)
    if not local.is_file() or local.stat().st_size == 0:
        raise ReferencePackageInstallError(f"pulled evidence is missing: {local.name}")


def _capture_screenshot(device: AdbInstallDevice, remote: str, local: Path) -> None:
    device._mutating_checked(("shell", "screencap", "-p", remote), timeout=60.0)
    _pull(device, remote, local)


def _assert_main_activity_foreground(device: AdbInstallDevice, evidence_path: Path) -> None:
    pid = device._checked(("shell", "pidof", APP_ID), timeout=30.0).stdout.decode("ascii").strip()
    activities = device._checked(("shell", "dumpsys", "activity", "activities"), timeout=60.0)
    activity_text = activities.stdout.decode("utf-8", errors="strict")
    _write_text(evidence_path, f"pid={pid}\n{activity_text}")
    if not pid or not all(item.isdigit() for item in pid.split()):
        raise ReferencePackageInstallError("Flight Alert process is not running")
    if not re.search(
        r"topResumedActivity=.*\bcom\.flightalert/\.MainActivity(?:\s|\})",
        activity_text,
    ):
        raise ReferencePackageInstallError("Flight Alert MainActivity is not foreground")


def _record_motion(device: AdbInstallDevice, adb: str, serial: str, token: str, evidence: Path) -> None:
    remote_video = f"/data/local/tmp/flightalert-visual-{token}.mp4"
    remote_images = {
        "before": f"/data/local/tmp/flightalert-visual-{token}-before.png",
        "pan-left": f"/data/local/tmp/flightalert-visual-{token}-pan-left.png",
        "pan-diagonal": f"/data/local/tmp/flightalert-visual-{token}-pan-diagonal.png",
        "after": f"/data/local/tmp/flightalert-visual-{token}-after.png",
    }
    try:
        _assert_main_activity_foreground(device, evidence / "foreground-before.txt")
        _capture_screenshot(device, remote_images["before"], evidence / "before.png")
        recorder = subprocess.Popen(
            [adb, "-s", serial, "shell", "screenrecord", "--time-limit", "20", remote_video],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        time.sleep(0.75)
        gestures = [
            ((700, 1100, 220, 1100, 900), 2.0, "pan-left"),
            ((220, 1100, 700, 1100, 900), 2.0, None),
            ((700, 1050, 250, 1200, 900), 2.0, "pan-diagonal"),
            ((250, 1200, 700, 1050, 900), 2.0, None),
        ]
        for coordinates, pause, capture in gestures:
            device._mutating_checked(
                ("shell", "input", "swipe", *(str(item) for item in coordinates)), timeout=30.0
            )
            time.sleep(pause)
            _assert_main_activity_foreground(
                device,
                evidence / f"foreground-after-gesture-{coordinates[0]}-{coordinates[2]}.txt",
            )
            if capture:
                _capture_screenshot(device, remote_images[capture], evidence / f"{capture}.png")
        stdout, stderr = recorder.communicate(timeout=25)
        if recorder.returncode != 0:
            raise ReferencePackageInstallError(
                f"screenrecord failed: {stderr.decode('utf-8', errors='replace')}"
            )
        _pull(device, remote_video, evidence / "kent-chester-pan-20s.mp4")
        _assert_main_activity_foreground(device, evidence / "foreground-after.txt")
        _capture_screenshot(device, remote_images["after"], evidence / "after.png")
    finally:
        device._mutating_adb(
            ("shell", "rm", "-f", "--", remote_video, *remote_images.values()),
            timeout=60.0,
            allow_failure=True,
        )


def main() -> int:
    args = _parse_args()
    evidence = args.evidence_directory.resolve()
    evidence.mkdir(parents=True, exist_ok=False)
    apk = args.apk.resolve(strict=True)
    helper = args.lease_helper.resolve(strict=True)
    if not re.fullmatch(r"[0-9a-f]{32}", args.lease_token):
        raise ReferencePackageInstallError("lease token is malformed")
    _lease_guard(helper, args.lease_token)
    runner = SubprocessCommandRunner()
    device = AdbInstallDevice(adb=args.adb, runner=runner)
    serial = device.require_single_ready_device()
    if serial != args.device:
        raise ReferencePackageInstallError("connected device differs")
    device.prepare_evidence_directory(evidence)
    device.set_mutation_guard(lambda: _lease_guard(helper, args.lease_token))
    final_package = ReferencePackageInstaller.FINAL_PACKAGE_PATH
    prestate = device.capture_prestate(evidence, final_package)
    stay_before = device._checked(
        ("shell", "settings", "get", "global", "stay_on_while_plugged_in"), timeout=30.0
    ).stdout.decode("ascii").strip()
    _write_text(evidence / "stay-awake-before.txt", stay_before + "\n")
    thermal_before = _thermal(device, evidence / "thermal-before.txt")
    prepared = _prepared_preferences(prestate.preferences, args.label_scale)
    _write_bytes(evidence / "preferences-prepared.xml", prepared)
    apk_identity = _hash_regular_file(apk, "label visual gate APK")
    installed = False
    success = False
    failure: Exception | None = None
    try:
        device.disallow_listener()
        device.force_stop()
        device.install_apk(
            apk,
            expected_bytes=apk_identity.byte_length,
            expected_sha256=apk_identity.sha256,
            expected_stat_identity=apk_identity.stat_identity,
        )
        installed = True
        device.restore_preferences(True, prepared)
        device.restore_listener(prestate.listener_state)
        device._mutating_checked(("shell", "am", "start", "-W", "-n", ACTIVITY), timeout=90.0)
        time.sleep(8.0)
        _assert_main_activity_foreground(device, evidence / "foreground-after-launch.txt")
        _record_motion(device, args.adb, serial, args.lease_token, evidence)
        thermal_after = _thermal(device, evidence / "thermal-after.txt")
        stay_after = device._checked(
            ("shell", "settings", "get", "global", "stay_on_while_plugged_in"), timeout=30.0
        ).stdout.decode("ascii").strip()
        _write_text(evidence / "stay-awake-after.txt", stay_after + "\n")
        if stay_after != stay_before or stay_after != "2":
            raise ReferencePackageInstallError("USB stay-awake state changed")
        inventory_after = device.immediate_inventory(ReferencePackageInstaller.REFERENCE_ROOT)
        final_after = device.entry_identity(final_package)
        if inventory_after != prestate.reference_inventory or final_after != prestate.final_package_entry:
            raise ReferencePackageInstallError("reference package identity changed")
        installed_identity = device.verify_installed_apk()
        if installed_identity.byte_length != apk_identity.byte_length or installed_identity.sha256 != apk_identity.sha256:
            raise ReferencePackageInstallError("installed APK identity differs")
        result = {
            "status": "ready-for-user-visual-judgment",
            "device": serial,
            "sourceApk": {"bytes": apk_identity.byte_length, "sha256": apk_identity.sha256},
            "labelScale": args.label_scale,
            "thermalMaxBeforeC": thermal_before,
            "thermalMaxAfterC": thermal_after,
            "stayAwake": stay_after,
            "referencePackagePath": final_package,
            "referencePackageUnchanged": True,
        }
        _write_text(evidence / "result.json", json.dumps(result, indent=2, sort_keys=True) + "\n")
        success = True
    except Exception as error:
        failure = error
    finally:
        if not success and installed:
            cleanup_errors: list[str] = []
            for label, action in (
                ("force stop", device.force_stop),
                ("restore APK", lambda: device.restore_apk(prestate.apk_backup_path)),
                (
                    "restore preferences",
                    lambda: device.restore_preferences(prestate.preferences_present, prestate.preferences),
                ),
                ("restore listener", lambda: device.restore_listener(prestate.listener_state)),
                ("restore process", lambda: device.restore_process_state(prestate.process_state)),
            ):
                try:
                    action()
                except Exception as cleanup_error:
                    cleanup_errors.append(f"{label}: {cleanup_error}")
            _write_text(
                evidence / "failure.json",
                json.dumps(
                    {
                        "failure": str(failure),
                        "cleanupFailures": cleanup_errors,
                    },
                    indent=2,
                    sort_keys=True,
                )
                + "\n",
            )
            if cleanup_errors:
                raise ReferencePackageInstallError("; ".join(cleanup_errors)) from failure
    if failure is not None:
        raise failure
    print(str(evidence / "result.json"))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"visual gate failed: {error}", file=sys.stderr)
        raise SystemExit(1)
