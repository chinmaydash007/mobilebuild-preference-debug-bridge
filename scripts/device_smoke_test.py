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


PACKAGE = "io.github.chinmaydash007.mobilebuild.preferences.sample.debug"
ACTIVITY = "io.github.chinmaydash007.mobilebuild.preferences.sample.MainActivity"
AUTHORITY = f"{PACKAGE}.mobilebuild.preferences"


def run(adb: str, serial: str | None, arguments: list[str], data: bytes | None = None) -> bytes:
    command = [adb]
    if serial:
        command += ["-s", serial]
    command += arguments
    result = subprocess.run(command, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.decode(errors="replace").strip() or "adb command failed")
    return result.stdout


def read_json(adb: str, serial: str | None, path: str) -> dict:
    output = run(
        adb,
        serial,
        ["exec-out", "content", "read", "--uri", f"content://{AUTHORITY}/v1/{path}"],
    )
    return json.loads(output.decode())


def store_segment(store_id: str) -> str:
    return base64.urlsafe_b64encode(store_id.encode()).decode().rstrip("=")


def snapshot(adb: str, serial: str | None, store_id: str) -> dict[str, dict]:
    payload = read_json(adb, serial, f"store/{store_segment(store_id)}")
    return {entry["key"]: entry["value"] for entry in payload["entries"]}


def save(adb: str, serial: str | None, store_id: str, key: str, before: dict, after: dict) -> None:
    request_id = str(uuid.uuid4())
    request = {
        "version": 1,
        "storeID": store_id,
        "changes": [{"key": key, "before": before, "after": after}],
    }
    run(
        adb,
        serial,
        ["shell", "-T", "content", "write", "--uri", f"content://{AUTHORITY}/v1/save/{request_id}"],
        json.dumps(request, separators=(",", ":")).encode(),
    )
    for _ in range(35):
        result = read_json(adb, serial, f"result/{request_id}")
        if result["status"] == "ok":
            return
        if result["status"] != "pending":
            raise AssertionError(f"Save failed with status {result['status']}")
        time.sleep(0.2)
    raise TimeoutError("Preference save acknowledgement timed out")


def toggle_and_restore(adb: str, serial: str | None, store_id: str, key: str) -> None:
    original = snapshot(adb, serial, store_id)[key]
    if original["type"] != "boolean":
        raise AssertionError(f"{store_id}/{key} is not a boolean")
    changed = {"type": "boolean", "value": not original["value"]}
    changed_on_device = False
    try:
        save(adb, serial, store_id, key, original, changed)
        changed_on_device = True
        if snapshot(adb, serial, store_id)[key] != changed:
            raise AssertionError(f"{store_id}/{key} did not change")
    finally:
        if changed_on_device:
            save(adb, serial, store_id, key, changed, original)
            if snapshot(adb, serial, store_id)[key] != original:
                raise AssertionError(f"{store_id}/{key} was not restored")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--keep-installed", action="store_true")
    parser.add_argument(
        "--apk",
        type=Path,
        default=Path("sample/build/outputs/apk/debug/sample-debug.apk"),
    )
    arguments = parser.parse_args()

    run(arguments.adb, arguments.serial, ["install", "-r", "-t", str(arguments.apk)])
    try:
        run(
            arguments.adb,
            arguments.serial,
            ["shell", "am", "start", "-W", "-n", f"{PACKAGE}/{ACTIVITY}"],
        )

        handshake = read_json(arguments.adb, arguments.serial, "handshake")
        if handshake.get("version") != 1 or handshake.get("packageID") != PACKAGE:
            raise AssertionError("Unexpected handshake")
        stores = read_json(arguments.adb, arguments.serial, "stores")
        available = {store["id"] for store in stores["stores"] if store.get("available")}
        if available != {"sample-plain", "sample-encrypted"}:
            raise AssertionError("Expected both registered sample stores")

        toggle_and_restore(arguments.adb, arguments.serial, "sample-plain", "sample.boolean")
        toggle_and_restore(arguments.adb, arguments.serial, "sample-encrypted", "secure.enabled")
        print("ADB v1 read/write/restore passed for plain and encrypted stores.")
    finally:
        if not arguments.keep_installed:
            run(arguments.adb, arguments.serial, ["uninstall", PACKAGE])


if __name__ == "__main__":
    main()
