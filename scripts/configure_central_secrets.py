#!/usr/bin/env python3
"""Encrypt a protected Central token file into this repository's Actions secrets."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import stat
import subprocess

from nacl.encoding import Base64Encoder
from nacl.public import PublicKey, SealedBox

from release import REPOSITORY, github, require_release_repository


parser = argparse.ArgumentParser()
parser.add_argument("credentials", type=Path, help="Mode-600 JSON with username/password")
args = parser.parse_args()
info = args.credentials.stat()
if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) & 0o077:
    raise SystemExit("Credential file must belong to you and have mode 600.")
credentials = json.loads(args.credentials.read_text())
if not all(
    isinstance(credentials.get(name), str) and credentials[name].strip()
    for name in ("username", "password")
):
    raise SystemExit("Expected nonempty token username/password fields.")
token = os.environ.get("GITHUB_TOKEN")
if not token:
    raw = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        text=True,
        capture_output=True,
        check=True,
    ).stdout
    token = dict(line.split("=", 1) for line in raw.splitlines() if "=" in line).get("password")
if not token:
    raise SystemExit("GitHub authentication is required.")
require_release_repository(token)
secret_path = "/repos/" + REPOSITORY + "/actions/secrets"
key = github(secret_path + "/public-key", token)
box = SealedBox(PublicKey(key["key"], encoder=Base64Encoder))
for name, value in (
    ("CENTRAL_USERNAME", credentials["username"]),
    ("CENTRAL_PASSWORD", credentials["password"]),
):
    encrypted = Base64Encoder.encode(box.encrypt(value.encode())).decode()
    github(
        secret_path + "/" + name,
        token,
        "PUT",
        {"key_id": key["key_id"], "encrypted_value": encrypted},
    )
    print("Configured encrypted secret:", name)
