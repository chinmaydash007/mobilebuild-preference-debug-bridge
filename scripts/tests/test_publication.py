"""Release-boundary tests without network or publishing side effects."""

from __future__ import annotations

import contextlib
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
import urllib.error
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release


TAGGED = {
    "VERSION_NAME": "1.0.0",
    "GROUP": "io.github.chinmaydash007.mobilebuild",
    "POM_ARTIFACT_ID": "preference-debug-bridge",
}


class ReleaseTests(unittest.TestCase):
    def test_tag_and_commit_must_match(self):
        with self.assertRaisesRegex(ValueError, "tag"):
            release.validate_tag("v1.0.1", "1.0.0", "commit")
        with patch.object(release.subprocess, "check_output", return_value="different\n"):
            with self.assertRaisesRegex(ValueError, "commit"):
                release.validate_tag("v1.0.0", "1.0.0", "commit")

    def test_tagged_source_reads_the_immutable_tag(self):
        source = "VERSION_NAME=1.0.0\nGROUP=io.github.chinmaydash007.mobilebuild\n"
        with patch.object(
            release.subprocess,
            "check_output",
            side_effect=[source, "commit\n", "commit\n"],
        ) as git:
            tagged, commit = release.tagged_source("v1.0.0")
        self.assertEqual("1.0.0", tagged["VERSION_NAME"])
        self.assertEqual("commit", commit)
        self.assertEqual(["git", "show", "refs/tags/v1.0.0:gradle.properties"], git.call_args_list[0].args[0])

    def test_repository_id_is_enforced(self):
        with patch.object(release, "github", return_value={"id": release.REPOSITORY_ID}):
            release.require_release_repository("token")
        with patch.object(release, "github", return_value={"id": release.REPOSITORY_ID + 1}):
            with self.assertRaisesRegex(ValueError, "original preference bridge"):
                release.require_release_repository("token")

    def test_release_asset_is_never_replaced(self):
        value = {"assets": [{"name": "bundle.zip", "url": "https://api.github.com/asset"}]}
        with patch.object(release, "fetch", return_value=b"original"):
            with self.assertRaisesRegex(ValueError, "replace"):
                release.upload_asset(value, "bundle.zip", b"changed", "token")

    def test_cross_origin_redirect_strips_authorization(self):
        import urllib.request

        request = urllib.request.Request(
            "https://api.github.com/assets/1",
            headers={"Authorization": "Bearer synthetic"},
        )
        redirected = release.StripCrossOriginAuthorization().redirect_request(
            request,
            None,
            302,
            "",
            {},
            "https://release-assets.githubusercontent.com/file",
        )
        self.assertIsNone(redirected.get_header("Authorization"))

    def test_failed_deployment_is_not_reported_published(self):
        with patch.object(release, "status", return_value={"deploymentState": "FAILED"}):
            with self.assertRaisesRegex(ValueError, "validation failed"):
                release.wait_published("id", "credential")

    def test_public_hash_mismatch_is_rejected(self):
        with patch.object(release, "fetch", return_value=b"wrong"):
            with self.assertRaisesRegex(ValueError, "differ"):
                release.verify_downloads({"files": {"artifact.aar": "bad"}}, timeout=1)

    def test_unsigned_saved_bundle_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / "publication-manifest.json").write_text(
                json.dumps({"signed": False, "version": "1.0.0", "source_commit": "commit"})
            )
            with self.assertRaisesRegex(ValueError, "unsigned"):
                release.validate_saved_bundle(folder, TAGGED, "commit")

    def test_retry_resumes_saved_deployment(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.ExitStack() as stack:
            folder = Path(directory)
            manifest = {"sha256": "digest", "files": {}}
            bundle = folder / "bundle.zip"
            bundle.write_bytes(b"bundle")
            for name in ("publication-manifest.json", "SHA256SUMS"):
                (folder / name).write_text("fixture")
            (folder / "deployment.json").write_text(
                json.dumps({"deployment_id": "existing", "sha256": "digest"})
            )
            stack.enter_context(patch.object(release, "require_release_repository"))
            stack.enter_context(patch.object(release, "tagged_source", return_value=(TAGGED, "commit")))
            stack.enter_context(patch.object(release, "validate_saved_bundle", return_value=(manifest, bundle)))
            stack.enter_context(
                patch.object(
                    release,
                    "release_for",
                    return_value={"draft": False, "id": 1, "body": "", "assets": [], "upload_url": ""},
                )
            )
            stack.enter_context(patch.object(release, "upload_asset"))
            stack.enter_context(patch.object(release, "github"))
            upload = stack.enter_context(patch.object(release, "upload_bundle"))
            wait = stack.enter_context(patch.object(release, "wait_published"))
            verify = stack.enter_context(patch.object(release, "verify_downloads"))
            stack.enter_context(
                patch.dict(
                    release.os.environ,
                    {"CENTRAL_USERNAME": "username", "CENTRAL_PASSWORD": "password"},
                )
            )
            release.publish("v1.0.0", folder, "token")
            upload.assert_not_called()
            self.assertEqual("existing", wait.call_args.args[0])
            verify.assert_called_once_with(manifest)

    def test_upload_intent_blocks_a_duplicate(self):
        with tempfile.TemporaryDirectory() as directory, contextlib.ExitStack() as stack:
            folder = Path(directory)
            bundle = folder / "bundle.zip"
            bundle.write_bytes(b"bundle")
            for name in ("publication-manifest.json", "SHA256SUMS", "upload-intent.json"):
                (folder / name).write_text("fixture")
            stack.enter_context(patch.object(release, "require_release_repository"))
            stack.enter_context(patch.object(release, "tagged_source", return_value=(TAGGED, "commit")))
            stack.enter_context(
                patch.object(
                    release,
                    "validate_saved_bundle",
                    return_value=({"sha256": hashlib.sha256(b"bundle").hexdigest()}, bundle),
                )
            )
            stack.enter_context(
                patch.object(
                    release,
                    "release_for",
                    return_value={"draft": False, "assets": [], "upload_url": ""},
                )
            )
            stack.enter_context(patch.object(release, "upload_asset"))
            upload = stack.enter_context(patch.object(release, "upload_bundle"))
            stack.enter_context(
                patch.dict(
                    release.os.environ,
                    {"CENTRAL_USERNAME": "username", "CENTRAL_PASSWORD": "password"},
                )
            )
            with self.assertRaisesRegex(ValueError, "without a saved deployment ID"):
                release.publish("v1.0.0", folder, "token")
            upload.assert_not_called()


if __name__ == "__main__":
    unittest.main()
