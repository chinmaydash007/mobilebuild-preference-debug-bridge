#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew --no-daemon clean test lint \
  :preference-debug-bridge:assembleRelease \
  :sample:assembleDebug :sample:assembleRelease \
  stageRelease
version=$(sed -n 's/^VERSION_NAME=//p' gradle.properties)
./gradlew --no-daemon --refresh-dependencies -p consumer-fixture \
  -PbridgeVersion="$version" \
  -PvalidationRepository="$PWD/build/validation-repository" \
  :app:assembleDebug :app:assembleRelease
python3 scripts/verify_manifests.py
python3 scripts/bundle.py
python3 -m unittest discover -s scripts/tests -v
