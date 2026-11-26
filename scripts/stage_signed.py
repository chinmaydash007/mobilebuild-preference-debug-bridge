#!/usr/bin/env python3
"""Stage signed artifacts from environment-provided private signing material."""

from __future__ import annotations

import os
from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parents[1]
environment = os.environ.copy()
if not environment.get("ORG_GRADLE_PROJECT_signingInMemoryKey"):
    raise SystemExit("Missing signing key. Configure GitHub Actions secrets or protected environment variables.")
if not environment.get("ORG_GRADLE_PROJECT_signingInMemoryKeyPassword"):
    raise SystemExit("Missing signing key password.")
subprocess.run([str(ROOT / "gradlew"), "--no-daemon", "stageRelease"], cwd=ROOT, env=environment, check=True)
