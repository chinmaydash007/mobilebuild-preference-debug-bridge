#!/usr/bin/env python3
"""Exercises the v1 protocol through adb and restores every changed sample value."""

from __future__ import annotations

import argparse
import base64
import json
import subprocess
import time
import uuid
from pathlib import Path


DEFAULT_PACKAGE = "io.github.chinmaydash007.mobilebuild.preferences.sample.debug"
ACTIVITY = "io.github.chinmaydash007.mobilebuild.preferences.sample.MainActivity"


def run(
    adb: str,
    serial: str | None,
    arguments: list[str],
    data: bytes | None = None,
    *,
    require_output: bool = False,
) -> bytes:
    result = execute(adb, serial, arguments, data)
    if result.returncode != 0 or (require_output and not result.stdout.strip()):
        detail = result.stderr.decode(errors="replace").strip()
        output = result.stdout.decode(errors="replace").strip()
        raise RuntimeError(detail or output or "adb command returned no output")
    return result.stdout


def execute(
    adb: str,
    serial: str | None,
    arguments: list[str],
    data: bytes | None = None,
) -> subprocess.CompletedProcess[bytes]:
    command = [adb]
    if serial:
        command += ["-s", serial]
    command += arguments
    return subprocess.run(command, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def read_json(adb: str, serial: str | None, authority: str, path: str) -> dict:
    result = execute(
        adb,
        serial,
        ["exec-out", "content", "read", "--uri", f"content://{authority}/v1/{path}"],
    )
    output = result.stdout.decode(errors="replace").strip()
    error = result.stderr.decode(errors="replace").strip()
    if result.returncode != 0 or not output:
        raise RuntimeError(error or output or "adb content read returned no output")
    try:
        return json.loads(output)
    except json.JSONDecodeError as exception:
        raise RuntimeError(
            f"adb content read returned invalid JSON: stdout={output!r}, stderr={error!r}",
        ) from exception


def store_segment(store_id: str) -> str:
    return base64.urlsafe_b64encode(store_id.encode()).decode().rstrip("=")


def snapshot(adb: str, serial: str | None, authority: str, store_id: str) -> dict[str, dict]:
    payload = read_json(adb, serial, authority, f"store/{store_segment(store_id)}")
    return {entry["key"]: entry["value"] for entry in payload["entries"]}


def save(
    adb: str,
    serial: str | None,
    authority: str,
    store_id: str,
    key: str,
    before: dict,
    after: dict,
) -> None:
    request_id = str(uuid.uuid4())
    request = {
        "version": 1,
        "storeID": store_id,
        "changes": [{"key": key, "before": before, "after": after}],
    }
    run(
        adb,
        serial,
        ["shell", "-T", "content", "write", "--uri", f"content://{authority}/v1/save/{request_id}"],
        json.dumps(request, separators=(",", ":")).encode(),
    )
    for _ in range(35):
        result = read_json(adb, serial, authority, f"result/{request_id}")
        if result["status"] == "ok":
            return
        if result["status"] != "pending":
            raise AssertionError(f"Save failed with status {result['status']}")
        time.sleep(0.2)
    raise TimeoutError("Preference save acknowledgement timed out")


def toggle_and_restore(
    adb: str,
    serial: str | None,
    authority: str,
    store_id: str,
    key: str,
) -> None:
    original = snapshot(adb, serial, authority, store_id)[key]
    if original["type"] != "boolean":
        raise AssertionError(f"{store_id}/{key} is not a boolean")
    changed = {"type": "boolean", "value": not original["value"]}
    changed_on_device = False
    try:
        save(adb, serial, authority, store_id, key, original, changed)
        changed_on_device = True
        if snapshot(adb, serial, authority, store_id)[key] != changed:
            raise AssertionError(f"{store_id}/{key} did not change")
    finally:
        if changed_on_device:
            save(adb, serial, authority, store_id, key, changed, original)
            if snapshot(adb, serial, authority, store_id)[key] != original:
                raise AssertionError(f"{store_id}/{key} was not restored")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--keep-installed", action="store_true")
    parser.add_argument(
        "--apk",
        type=Path,
        default=Path("sample/build/outputs/apk/debug/sample-debug.apk"),
    )
    arguments = parser.parse_args()
    authority = f"{arguments.package}.mobilebuild.preferences"

    # Older emulator images may start adbd as root. The bridge intentionally accepts only the
    # shell UID, so normalize the test transport before exercising the production protocol.
    execute(arguments.adb, arguments.serial, ["unroot"])
    run(arguments.adb, arguments.serial, ["wait-for-device"])

    run(arguments.adb, arguments.serial, ["install", "-r", "-t", str(arguments.apk)])
    try:
        run(
            arguments.adb,
            arguments.serial,
            ["shell", "am", "start", "-W", "-n", f"{arguments.package}/{ACTIVITY}"],
        )

        handshake = read_json(arguments.adb, arguments.serial, authority, "handshake")
        if handshake.get("version") != 1 or handshake.get("packageID") != arguments.package:
            raise AssertionError("Unexpected handshake")
        stores = read_json(arguments.adb, arguments.serial, authority, "stores")
        descriptors = {store["id"]: store for store in stores["stores"]}
        expected = {"sample-plain", "sample-encrypted", "sample_discovered"}
        if set(descriptors) != expected:
            raise AssertionError(f"Unexpected sample stores: {sorted(descriptors)}")
        if any(not descriptors[store_id].get("available") for store_id in expected):
            raise AssertionError("Expected every sample store to be available")
        if descriptors["sample_discovered"].get("kind") != "plain":
            raise AssertionError("Expected the unregistered sample store to be discovered as plain")

        toggle_and_restore(
            arguments.adb, arguments.serial, authority, "sample-plain", "sample.boolean",
        )
        toggle_and_restore(
            arguments.adb, arguments.serial, authority, "sample-encrypted", "secure.enabled",
        )
        toggle_and_restore(
            arguments.adb, arguments.serial, authority, "sample_discovered", "discovered.enabled",
        )
        print("ADB v1 read/write/restore passed for registered and discovered stores.")
    finally:
        if not arguments.keep_installed:
            run(arguments.adb, arguments.serial, ["uninstall", arguments.package])


if __name__ == "__main__":
    main()
