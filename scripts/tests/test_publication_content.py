"""Publication-content checks using synthetic metadata and archives."""

from __future__ import annotations

import io
from pathlib import Path
import sys
import unittest
import xml.etree.ElementTree as ET
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from publication_content import (
    EXPECTED_DEPENDENCIES,
    validate_classes,
    validate_dependencies,
    validate_document_archive,
    validate_text,
)


NAMESPACE = "io.github.chinmaydash007.mobilebuild.preferences"
PACKAGE = NAMESPACE.replace(".", "/") + "/"


def archive_bytes(entries: dict[str, bytes | str]) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        for name, contents in entries.items():
            archive.writestr(name, contents)
    return output.getvalue()


def metadata(dependencies=EXPECTED_DEPENDENCIES):
    xml_namespace = "http://maven.apache.org/POM/4.0.0"
    root = ET.Element("{" + xml_namespace + "}project")
    elements = ET.SubElement(root, "{" + xml_namespace + "}dependencies")
    records = []
    for group, module, version, scope in sorted(dependencies):
        dependency = ET.SubElement(elements, "{" + xml_namespace + "}dependency")
        for key, value in (
            ("groupId", group),
            ("artifactId", module),
            ("version", version),
            ("scope", scope),
        ):
            ET.SubElement(dependency, "{" + xml_namespace + "}" + key).text = value
        records.append({"group": group, "module": module, "version": {"requires": version}})
    return root, {"variants": [{"name": "runtime", "dependencies": records}]}


class PublicationContentTests(unittest.TestCase):
    def test_bridge_archive_accepts_only_bridge_classes(self):
        data = archive_bytes(
            {
                PACKAGE + "PreferenceDebugProvider.class": b"provider",
                PACKAGE + "PreferenceStoreRegistry.class": b"registry",
                PACKAGE + "BridgeInternals.class": b"internals",
                "META-INF/library_release.kotlin_module": b"metadata",
            }
        )
        validate_classes(data, NAMESPACE)

    def test_foreign_class_is_rejected(self):
        data = archive_bytes(
            {
                PACKAGE + "PreferenceDebugProvider.class": b"provider",
                PACKAGE + "PreferenceStoreRegistry.class": b"registry",
                "com/customer/Host.class": b"foreign",
            }
        )
        with self.assertRaisesRegex(ValueError, "Unexpected runtime class"):
            validate_classes(data, NAMESPACE)

    def test_required_api_classes_are_enforced(self):
        with self.assertRaisesRegex(ValueError, "API is missing"):
            validate_classes(archive_bytes({}), NAMESPACE)

    def test_exact_dependency_inventory_is_accepted(self):
        validate_dependencies(*metadata())

    def test_private_dependency_is_rejected(self):
        dependencies = EXPECTED_DEPENDENCIES | {("com.customer", "internal", "1.0", "runtime")}
        with self.assertRaisesRegex(ValueError, "Unexpected POM dependency"):
            validate_dependencies(*metadata(dependencies))

    def test_private_workspace_path_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Private workspace path"):
            validate_text("Built from /Users/developer/private/project", "README")

    def test_sources_reject_foreign_paths(self):
        data = archive_bytes(
            {
                "README.md": "usage",
                "LICENSE": "license",
                "customer/Host.kt": "class Host",
            }
        )
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            with self.assertRaisesRegex(ValueError, "Unexpected published source"):
                validate_document_archive(archive, NAMESPACE, sources=True)

    def test_documentation_requires_html_and_public_files(self):
        data = archive_bytes({"README.md": "usage", "LICENSE": "license", "index.html": "docs"})
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            validate_document_archive(archive, NAMESPACE, sources=False)


if __name__ == "__main__":
    unittest.main()
