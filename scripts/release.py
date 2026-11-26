#!/usr/bin/env python3
"""Publish immutable signed GitHub Release assets through the Central Portal API."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

from bundle import ARTIFACT, ROOT, properties, validate_saved_bundle as validate_archive


REPOSITORY = os.environ.get(
    "GITHUB_REPOSITORY",
    "chinmaydash007/mobilebuild-preference-debug-bridge",
)
REPOSITORY_ID = 1374022344
CENTRAL = "https://central.sonatype.com/api/v1/publisher"
MAVEN = "https://repo.maven.apache.org/maven2/"


class StripCrossOriginAuthorization(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        redirected = super().redirect_request(request, fp, code, msg, headers, newurl)
        if redirected and urllib.parse.urlsplit(request.full_url).netloc != urllib.parse.urlsplit(newurl).netloc:
            redirected.remove_header("Authorization")
        return redirected


OPENER = urllib.request.build_opener(StripCrossOriginAuthorization())


def fetch(
    url: str,
    data: bytes | None = None,
    method: str = "GET",
    token: str | None = None,
    content_type: str | None = None,
    accept: str = "application/json",
) -> bytes:
    headers = {"Accept": accept, "User-Agent": "preference-debug-bridge-release"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if content_type:
        headers["Content-Type"] = content_type
    request = urllib.request.Request(url, data=data, method=method, headers=headers)
    with OPENER.open(request, timeout=120) as response:
        return response.read()


def github(path: str, token: str, method: str = "GET", data: dict | None = None):
    body = json.dumps(data).encode() if data is not None else None
    response = fetch(
        "https://api.github.com" + path,
        body,
        method,
        token,
        "application/json",
        accept="application/vnd.github+json",
    )
    return json.loads(response) if response else None


def validate_tag(tag: str, version: str, expected_commit: str) -> None:
    if tag != "v" + version or version.endswith("-SNAPSHOT"):
        raise ValueError("Release tag and VERSION_NAME must match a non-snapshot version.")
    actual = subprocess.check_output(
        ["git", "rev-parse", "refs/tags/" + tag + "^{commit}"],
        cwd=ROOT,
        text=True,
    ).strip()
    if actual != expected_commit:
        raise ValueError("Release tag does not identify the built commit.")


def tagged_source(tag: str) -> tuple[dict[str, str], str]:
    if not tag.startswith("v") or any(character in tag for character in ("/", ":", "\\", "\n")):
        raise ValueError("Expected a version tag such as v1.0.0.")
    reference = "refs/tags/" + tag
    source = subprocess.check_output(
        ["git", "show", reference + ":gradle.properties"],
        cwd=ROOT,
        text=True,
    )
    tagged = dict(
        line.split("=", 1)
        for line in source.splitlines()
        if "=" in line and not line.startswith("#")
    )
    version = tagged["VERSION_NAME"].strip()
    commit = subprocess.check_output(
        ["git", "rev-parse", reference + "^{commit}"],
        cwd=ROOT,
        text=True,
    ).strip()
    validate_tag(tag, version, commit)
    tagged["VERSION_NAME"] = version
    return tagged, commit


def require_release_repository(token: str) -> None:
    repository = github("/repos/" + REPOSITORY, token)
    if repository.get("id") != REPOSITORY_ID:
        raise ValueError("Release operations require the original preference bridge repository.")


def release_for(tag: str, token: str) -> dict:
    try:
        return github(
            "/repos/" + REPOSITORY + "/releases/tags/" + urllib.parse.quote(tag),
            token,
        )
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        page = 1
        while True:
            releases = github(
                f"/repos/{REPOSITORY}/releases?per_page=100&page={page}",
                token,
            )
            matching = [release for release in releases if release["tag_name"] == tag and release["draft"]]
            if matching:
                return matching[0]
            if len(releases) < 100:
                raise error
            page += 1


def upload_asset(release: dict, name: str, data: bytes, token: str) -> None:
    existing = next((asset for asset in release["assets"] if asset["name"] == name), None)
    if existing:
        previous = fetch(existing["url"], token=token, accept="application/octet-stream")
        if previous != data:
            raise ValueError("Refusing to replace released asset: " + name)
        return
    url = release["upload_url"].split("{")[0] + "?name=" + urllib.parse.quote(name)
    fetch(url, data, "POST", token, "application/octet-stream")


def restore(tag: str, output: Path, token: str) -> None:
    require_release_repository(token)
    release = release_for(tag, token)
    output.mkdir(parents=True, exist_ok=True)
    names = {
        "publication-manifest.json",
        "deployment.json",
        "upload-intent.json",
        "SHA256SUMS",
    }
    for asset in release["assets"]:
        name = asset["name"]
        if name in names or name.endswith("-central.zip"):
            if Path(name).name != name:
                raise ValueError("Unsafe release asset name")
            (output / name).write_bytes(
                fetch(asset["url"], token=token, accept="application/octet-stream")
            )


def validate_saved_bundle(
    output: Path,
    tagged: dict[str, str],
    commit: str,
) -> tuple[dict, Path]:
    version = tagged["VERSION_NAME"]
    manifest = json.loads((output / "publication-manifest.json").read_text())
    if not manifest.get("signed") or manifest["version"] != version or manifest["source_commit"] != commit:
        raise ValueError("Bundle is unsigned or does not belong to this tagged commit.")
    bundle = output / manifest["bundle"]
    if bundle.name != manifest["bundle"] or hashlib.sha256(bundle.read_bytes()).hexdigest() != manifest["sha256"]:
        raise ValueError("Release bundle checksum mismatch")
    validate_archive(bundle, version, signed=True)
    return manifest, bundle


def status(deployment_id: str, credential: str) -> dict:
    raw = fetch(
        CENTRAL + "/status?" + urllib.parse.urlencode({"id": deployment_id}),
        b"",
        "POST",
        credential,
    )
    return json.loads(raw)


def wait_published(deployment_id: str, credential: str, timeout: int = 3600) -> None:
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        state = status(deployment_id, credential)
        current = state["deploymentState"]
        if current != last:
            print("Central deployment:", deployment_id, current, flush=True)
            last = current
        if current == "PUBLISHED":
            return
        if current == "FAILED":
            raise ValueError(
                "Central validation failed; inspect the Portal and do not replace the released tag.",
            )
        time.sleep(15)
    raise TimeoutError("Central is still processing. Resume this deployment; do not upload again.")


def upload_bundle(bundle: Path, version: str, credential: str) -> str:
    boundary = "PreferenceDebugBridgeCentralUpload"
    body = (
        (
            "--"
            + boundary
            + '\r\nContent-Disposition: form-data; name="bundle"; filename="'
            + bundle.name
            + '"\r\nContent-Type: application/octet-stream\r\n\r\n'
        ).encode()
        + bundle.read_bytes()
        + ("\r\n--" + boundary + "--\r\n").encode()
    )
    query = urllib.parse.urlencode(
        {"name": ARTIFACT + "-" + version, "publishingType": "AUTOMATIC"},
    )
    return fetch(
        CENTRAL + "/upload?" + query,
        body,
        "POST",
        credential,
        "multipart/form-data; boundary=" + boundary,
        accept="text/plain",
    ).decode().strip()


def verify_downloads(manifest: dict, timeout: int = 1800) -> None:
    deadline = time.monotonic() + timeout
    pending = dict(manifest["files"])
    while pending and time.monotonic() < deadline:
        for path, digest in list(pending.items()):
            try:
                data = fetch(MAVEN + path, accept="application/octet-stream")
            except urllib.error.HTTPError as error:
                if error.code == 404:
                    continue
                raise
            if hashlib.sha256(data).hexdigest() != digest:
                raise ValueError("Published bytes differ from release bundle: " + path)
            del pending[path]
        if pending:
            time.sleep(15)
    if pending:
        raise TimeoutError("Published files are not all downloadable; retry without re-uploading.")
    print("All public Central publication files match the signed release bundle.")


def publish(
    tag: str,
    output: Path,
    token: str,
    *,
    publish_draft: bool = False,
) -> None:
    require_release_repository(token)
    tagged, commit = tagged_source(tag)
    version = tagged["VERSION_NAME"]
    manifest, bundle = validate_saved_bundle(output, tagged, commit)
    username = os.environ.get("CENTRAL_USERNAME")
    password = os.environ.get("CENTRAL_PASSWORD")
    if not username or not password:
        raise ValueError("Central token setup is pending. No upload was attempted.")
    credential = base64.b64encode((username + ":" + password).encode()).decode()
    release = release_for(tag, token)
    if release["draft"] and not publish_draft:
        raise ValueError("Publish the GitHub Release to trigger Central publication.")
    for name in (bundle.name, "publication-manifest.json", "SHA256SUMS"):
        upload_asset(release, name, (output / name).read_bytes(), token)

    deployment_file = output / "deployment.json"
    intent_file = output / "upload-intent.json"
    if intent_file.exists() and not deployment_file.exists():
        raise ValueError(
            "An upload was attempted without a saved deployment ID. Inspect Central before retrying.",
        )
    if deployment_file.exists():
        deployment = json.loads(deployment_file.read_text())
        if deployment["sha256"] != manifest["sha256"]:
            raise ValueError("Existing deployment refers to another bundle.")

    if release["draft"]:
        release = github(
            "/repos/" + REPOSITORY + "/releases/" + str(release["id"]),
            token,
            "PATCH",
            {"draft": False},
        )
        require_release_repository(token)

    if not deployment_file.exists():
        intent = {"sha256": manifest["sha256"], "version": version}
        intent_file.write_text(json.dumps(intent, indent=2) + "\n")
        upload_asset(release, "upload-intent.json", intent_file.read_bytes(), token)
        deployment_id = upload_bundle(bundle, version, credential)
        deployment = {
            "deployment_id": deployment_id,
            "sha256": manifest["sha256"],
            "version": version,
        }
        deployment_file.write_text(json.dumps(deployment, indent=2) + "\n")
        upload_asset(release, "deployment.json", deployment_file.read_bytes(), token)

    wait_published(deployment["deployment_id"], credential)
    verify_downloads(manifest)
    note = (
        "\n\nMaven Central: publication and exact-file download verification passed for "
        f"`{version}`."
    )
    if note.strip() not in (release.get("body") or ""):
        github(
            "/repos/" + REPOSITORY + "/releases/" + str(release["id"]),
            token,
            "PATCH",
            {"body": (release.get("body") or "") + note},
        )
    require_release_repository(token)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("restore", "publish"))
    parser.add_argument("--tag", required=True)
    parser.add_argument("--publish-draft", action="store_true")
    args = parser.parse_args()
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        raise SystemExit("GITHUB_TOKEN is required; never put it on the command line.")
    output = ROOT / "build" / "release"
    try:
        if args.command == "restore":
            restore(args.tag, output, token)
        else:
            publish(args.tag, output, token, publish_draft=args.publish_draft)
    except Exception as error:
        raise SystemExit(type(error).__name__ + ": " + str(error).split("https://")[0])


if __name__ == "__main__":
    main()
