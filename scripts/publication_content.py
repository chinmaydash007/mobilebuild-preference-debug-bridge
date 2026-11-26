"""Publication-boundary checks for the preference debug bridge."""

from __future__ import annotations

import json
from pathlib import PurePosixPath
import re
import xml.etree.ElementTree as ET
import zipfile


EXPECTED_DEPENDENCIES = {("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.24", "compile")}
PRIVATE_PATH = re.compile(r"(?:/Users/|/home/|[A-Za-z]:\\Users\\)")


def validate_text(text: str, label: str) -> None:
    if PRIVATE_PATH.search(text):
        raise ValueError(f"Private workspace path in {label}")
    forbidden = ("com.visit.", "com.example.mobile", "AndroidPartnerApp", "Android2/")
    if any(value in text for value in forbidden):
        raise ValueError(f"Unexpected host-application identity in {label}")


def validate_classes(classes_jar: bytes, namespace: str) -> None:
    package = namespace.replace(".", "/") + "/"
    required = {
        package + "PreferenceDebugProvider.class",
        package + "PreferenceStoreRegistry.class",
    }
    with zipfile.ZipFile(__import__("io").BytesIO(classes_jar)) as archive:
        entries = set(archive.namelist())
        missing = required - entries
        if missing:
            raise ValueError("Published bridge API is missing: " + ", ".join(sorted(missing)))
        for name in entries:
            if name.endswith(".class") and not name.startswith(package):
                raise ValueError("Unexpected runtime class: " + name)
            if name.startswith(package + "sample/") or name.startswith(package + "consumer/"):
                raise ValueError("Sample or consumer class leaked into the AAR: " + name)


def _pom_dependencies(root: ET.Element) -> set[tuple[str, str, str, str]]:
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    result = set()
    for dependency in root.findall("m:dependencies/m:dependency", namespace):
        result.add(
            (
                dependency.findtext("m:groupId", namespaces=namespace) or "",
                dependency.findtext("m:artifactId", namespaces=namespace) or "",
                dependency.findtext("m:version", namespaces=namespace) or "",
                dependency.findtext("m:scope", default="compile", namespaces=namespace),
            )
        )
    return result


def _metadata_dependencies(metadata: dict) -> set[tuple[str, str, str]]:
    result = set()
    for variant in metadata.get("variants", []):
        for dependency in variant.get("dependencies", []):
            version = dependency.get("version", {})
            resolved = version.get("requires") or version.get("strictly") or ""
            result.add((dependency.get("group", ""), dependency.get("module", ""), resolved))
    return result


def validate_dependencies(pom_root: ET.Element, metadata: dict) -> None:
    pom = _pom_dependencies(pom_root)
    if pom != EXPECTED_DEPENDENCIES:
        raise ValueError("Unexpected POM dependency inventory: " + repr(sorted(pom)))
    expected_metadata = {(group, module, version) for group, module, version, _ in EXPECTED_DEPENDENCIES}
    actual_metadata = _metadata_dependencies(metadata)
    if actual_metadata != expected_metadata:
        raise ValueError("Unexpected Gradle metadata dependency inventory: " + repr(sorted(actual_metadata)))


def validate_document_archive(
    archive: zipfile.ZipFile,
    namespace: str,
    *,
    sources: bool,
) -> None:
    names = set(archive.namelist())
    if "README.md" not in names or "LICENSE" not in names:
        raise ValueError("Public usage guide or license is missing from documentation archive")
    package = namespace.replace(".", "/") + "/"
    if sources:
        source_files = {name for name in names if name.endswith((".kt", ".java"))}
        if not source_files or any(not name.startswith(package) for name in source_files):
            raise ValueError("Unexpected published source path")
    elif not any(name.endswith(".html") for name in names):
        raise ValueError("Documentation JAR contains no HTML")
    for info in archive.infolist():
        path = PurePosixPath(info.filename)
        if path.is_absolute() or ".." in path.parts:
            raise ValueError("Unsafe documentation archive path")
        if info.is_dir() or info.file_size > 4_000_000:
            continue
        if info.filename.endswith((".html", ".md", ".txt", ".kt", ".java", ".xml", ".json")):
            validate_text(archive.read(info).decode("utf-8", errors="replace"), info.filename)


def validate_module_metadata(data: str) -> dict:
    metadata = json.loads(data)
    if metadata.get("formatVersion") != "1.1":
        raise ValueError("Unexpected Gradle module metadata format")
    return metadata
