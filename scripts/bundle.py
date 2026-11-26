#!/usr/bin/env python3
"""Validate one Maven publication and assemble a reproducible Central bundle."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

from publication_content import (
    validate_classes,
    validate_dependencies,
    validate_document_archive,
    validate_module_metadata,
    validate_text,
)


ROOT = Path(__file__).resolve().parents[1]
ARTIFACT = "preference-debug-bridge"
NAMESPACE = "io.github.chinmaydash007.mobilebuild.preferences"


def properties(root: Path = ROOT) -> dict[str, str]:
    return dict(
        line.split("=", 1)
        for line in (root / "gradle.properties").read_text().splitlines()
        if "=" in line and not line.startswith("#")
    )


def required_files(version: str) -> list[str]:
    prefix = f"{ARTIFACT}-{version}"
    return [
        prefix + ".aar",
        prefix + ".pom",
        prefix + ".module",
        prefix + "-sources.jar",
        prefix + "-javadoc.jar",
    ]


def _verify_signature(signature: Path, artifact: Path) -> None:
    if shutil.which("gpg"):
        subprocess.run(
            ["gpg", "--batch", "--verify", str(signature), str(artifact)],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
        )
        return
    try:
        from pgpy import PGPKey, PGPSignature
    except ImportError as error:
        raise ValueError("Signature verification requires GnuPG or PGPy") from error
    public, _ = PGPKey.from_file(str(ROOT / "signing-public.asc"))
    signature_value = PGPSignature.from_file(str(signature))
    if not public.verify(artifact.read_bytes(), signature_value):
        raise ValueError("Invalid signature: " + artifact.name)


def validate_publication(folder: Path, version: str, *, signed: bool = False) -> list[str]:
    required = required_files(version)
    for name in required:
        if not (folder / name).is_file():
            raise ValueError("Missing publication file: " + name)

    prefix = f"{ARTIFACT}-{version}"
    pom_file = folder / (prefix + ".pom")
    pom = ET.parse(pom_file)
    xml_namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    config = properties()
    expected = {
        "groupId": config["GROUP"],
        "artifactId": ARTIFACT,
        "version": version,
        "packaging": "aar",
    }
    for field, value in expected.items():
        if pom.findtext("m:" + field, namespaces=xml_namespace) != value:
            raise ValueError("Incorrect POM " + field)
    for field in ("name", "description", "url", "licenses/license/name", "developers/developer/id", "scm/url"):
        query = "/".join("m:" + part for part in field.split("/"))
        if not pom.findtext(query, namespaces=xml_namespace):
            raise ValueError("Missing POM metadata: " + field)

    metadata_file = folder / (prefix + ".module")
    metadata = validate_module_metadata(metadata_file.read_text())
    validate_dependencies(pom.getroot(), metadata)
    validate_text(pom_file.read_text(), "POM")
    validate_text(metadata_file.read_text(), "Gradle metadata")

    with zipfile.ZipFile(folder / (prefix + ".aar")) as aar:
        validate_classes(aar.read("classes.jar"), NAMESPACE)
        if any(name.startswith("libs/") and name.endswith(".jar") for name in aar.namelist()):
            raise ValueError("Unexpected embedded dependency JAR in AAR")
        manifest = aar.read("AndroidManifest.xml").decode("utf-8", errors="replace")
        manifest_root = ET.fromstring(manifest)
        if manifest_root.get("package") != NAMESPACE:
            raise ValueError("Unexpected AAR manifest namespace")
        uses_sdk = manifest_root.find("uses-sdk")
        android = "{http://schemas.android.com/apk/res/android}"
        if uses_sdk is None or uses_sdk.get(android + "minSdkVersion") != "21":
            raise ValueError("Published AAR must declare minimum SDK 21")
        forbidden = ("<provider", "<activity", "<service", "<receiver", "uses-permission")
        if any(value in manifest for value in forbidden):
            raise ValueError("Consumer component or permission leaked into AAR manifest")

    for suffix, sources in (("-sources.jar", True), ("-javadoc.jar", False)):
        with zipfile.ZipFile(folder / (prefix + suffix)) as archive:
            validate_document_archive(archive, NAMESPACE, sources=sources)

    if signed:
        for name in required:
            artifact = folder / name
            signature = folder / (name + ".asc")
            if not signature.is_file():
                raise ValueError("Unsigned publication: " + name)
            _verify_signature(signature, artifact)
    return required


def build_bundle(*, signed: bool) -> tuple[Path, dict]:
    config = properties()
    version = config["VERSION_NAME"]
    group_path = config["GROUP"].replace(".", "/")
    publication = ROOT / "build" / "validation-repository" / group_path / ARTIFACT / version
    required = validate_publication(publication, version, signed=signed)
    output = ROOT / "build" / "release"
    output.mkdir(parents=True, exist_ok=True)
    files: dict[str, bytes] = {}
    for name in required:
        relative = "/".join((group_path, ARTIFACT, version, name))
        data = (publication / name).read_bytes()
        files[relative] = data
        for algorithm in ("md5", "sha1", "sha256", "sha512"):
            files[relative + "." + algorithm] = hashlib.new(algorithm, data).hexdigest().encode()
        if signed:
            files[relative + ".asc"] = (publication / (name + ".asc")).read_bytes()

    bundle = output / f"{ARTIFACT}-{version}-central.zip"
    with zipfile.ZipFile(bundle, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)

    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    digest = hashlib.sha256(bundle.read_bytes()).hexdigest()
    primary_hashes = {
        name: hashlib.sha256(data).hexdigest()
        for name, data in sorted(files.items())
        if not name.endswith((".md5", ".sha1", ".sha256", ".sha512", ".asc"))
    }
    manifest = {
        "version": version,
        "source_commit": commit,
        "signed": signed,
        "bundle": bundle.name,
        "sha256": digest,
        "files": primary_hashes,
    }
    (output / "publication-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (output / "SHA256SUMS").write_text(f"{digest}  {bundle.name}\n")
    print("Validated publication. Bundle:", bundle)
    print("Signed:", signed)
    return bundle, manifest


def validate_saved_bundle(bundle: Path, version: str, *, signed: bool) -> None:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        with zipfile.ZipFile(bundle) as archive:
            for info in archive.infolist():
                destination = (root / info.filename).resolve()
                if not destination.is_relative_to(root.resolve()):
                    raise ValueError("Unsafe publication path")
            archive.extractall(root)
        config = properties()
        folder = root / config["GROUP"].replace(".", "/") / ARTIFACT / version
        validate_publication(folder, version, signed=signed)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--signed", action="store_true")
    args = parser.parse_args()
    build_bundle(signed=args.signed)


if __name__ == "__main__":
    main()
