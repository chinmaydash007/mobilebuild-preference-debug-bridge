#!/usr/bin/env python3
"""Verifies that only debug consumers declare the Mobile Build provider."""

from pathlib import Path
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
def merged_manifests(module: Path, variant: str) -> list[Path]:
    return [
        path
        for path in (module / "build" / "intermediates").rglob("AndroidManifest.xml")
        if variant.lower() in {part.lower() for part in path.parts}
        and ("merged_manifest" in str(path) or "merged_manifests" in str(path))
    ]


def assert_variant(module: Path, variant: str, should_contain_provider: bool) -> None:
    manifests = merged_manifests(module, variant)
    if not manifests:
        raise AssertionError(f"No merged {variant} manifest found under {module}")
    contents = [path.read_text(errors="ignore") for path in manifests]
    contains = any("<provider" in content for content in contents)
    if contains != should_contain_provider:
        expectation = "contain" if should_contain_provider else "exclude"
        raise AssertionError(f"{module.name} {variant} manifest must {expectation} the provider")


def assert_not_debuggable(module: Path, variant: str) -> None:
    manifests = merged_manifests(module, variant)
    if not manifests:
        raise AssertionError(f"No merged {variant} manifest found under {module}")
    if any('android:debuggable="true"' in path.read_text(errors="ignore") for path in manifests):
        raise AssertionError(f"{module.name} {variant} must remain non-debuggable")


aar = ROOT / "preference-debug-bridge" / "build" / "outputs" / "aar" / "preference-debug-bridge-release.aar"
if not aar.is_file():
    raise AssertionError(f"Library AAR not found: {aar}")
with ZipFile(aar) as archive:
    manifest = archive.read("AndroidManifest.xml").decode(errors="ignore")
    if "<provider" in manifest:
        raise AssertionError("The published library AAR must not declare a provider")

assert_variant(ROOT / "sample", "debug", True)
assert_variant(ROOT / "sample", "hostControlled", True)
assert_not_debuggable(ROOT / "sample", "hostControlled")
assert_variant(ROOT / "sample", "release", False)
assert_variant(ROOT / "consumer-fixture" / "app", "debug", True)
assert_variant(ROOT / "consumer-fixture" / "app", "release", False)

print("Manifest isolation verified for the AAR, sample, and published-artifact consumer.")
