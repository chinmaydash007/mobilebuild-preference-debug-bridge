# Publishing the Preference Debug Bridge

The original `chinmaydash007/mobilebuild-preference-debug-bridge` repository publishes
`io.github.chinmaydash007.mobilebuild:preference-debug-bridge`. Release workflows are bound to the
repository's immutable GitHub ID. GitHub Releases, attached publication evidence, workflow logs,
and Maven Central artifacts are public; credentials and private signing material are not.

## One-time account setup

1. Sign in to the Central Portal with the `chinmaydash007` GitHub identity. The verified namespace
   is `io.github.chinmaydash007`.
2. Generate a dedicated Central publishing user token for this repository. Its generated username
   and password are not the Central sign-in credentials.
3. Add the token to this repository's Actions secrets as `CENTRAL_USERNAME` and
   `CENTRAL_PASSWORD`. Do not put either value in Git, logs, command arguments, or chat.
4. Add the existing publisher key to Actions secrets as `SIGNING_KEY` and `SIGNING_PASSWORD`.
   The corresponding public key is [`signing-public.asc`](../signing-public.asc), fingerprint
   `B9C2F3CF6BDEC9054EA83F3FCD516702F4195002`.
5. Check the Central Usage Center before publishing because releases count against the namespace's
   monthly allowance.

If the Central token is stored in an owner-only JSON file with `username` and `password` fields,
`scripts/configure_central_secrets.py` can encrypt it directly into GitHub Actions secrets. The file
must belong to the current user and have mode 600. The helper requires PyNaCl and never prints the
credential values.

## Validation and signed dry run

Run the credential-free release gate from the repository root:

```bash
bash scripts/verify.sh
```

This runs tests and lint, builds the AAR and sample variants, stages the publication under
`build/validation-repository`, validates its contents, and resolves it from the independent
consumer fixture. It does not contact Maven Central.

Before creating a release, run **Signed Maven Central release → Run workflow** from `main`. A manual
workflow run repeats validation and the API 28/API 35 protocol tests, builds the signed Central ZIP,
verifies every signature against the committed public key, and uploads the evidence as an Actions
artifact. Manual dispatch never publishes.

## Publish version 1.1.0

1. Confirm `VERSION_NAME=1.1.0`, the changelog, and the README installation coordinates.
2. Confirm the manual signed dry run passed at the exact commit intended for release.
3. Create and push an annotated tag at that commit:

   ```bash
   git tag -a v1.1.0 -m "Release 1.1.0"
   git push origin v1.1.0
   ```

4. Create a GitHub Release using the existing `v1.1.0` tag and publish it. Publishing the GitHub
   Release triggers the Central workflow.
5. The workflow checks the original repository ID, tag/version/commit agreement, `main` ancestry,
   all local and emulator gates, and the signed publication contents. It attaches the Central ZIP,
   `publication-manifest.json`, and `SHA256SUMS` to the GitHub Release.
6. The publisher records upload intent before contacting Central, uploads once with automatic
   publishing, records the deployment ID and bundle hash, and waits for `PUBLISHED`.
7. It downloads every primary artifact from Maven Central, compares SHA-256 values with the signed
   manifest, and builds the independent consumer using only `google()` and `mavenCentral()`.
8. Remove the README's temporary `mavenLocal()` note only after all public checks pass.

Published Maven Central coordinates and release tags are immutable. Fixes require a new version.

## Retry and recovery

Release assets are immutable by convention. A rerun restores the exact attached bundle and manifest
before continuing. It rejects different bytes for an existing asset.

`upload-intent.json` is attached before the Central POST. If the response is lost before a
deployment ID is saved, retrying stops instead of uploading a duplicate. Inspect the Central Portal,
recover the real deployment ID and matching bundle hash into `deployment.json`, attach it to the
same GitHub Release, then rerun.

When a signed draft already contains the immutable release assets, run **Publish prepared Maven
Central release** from `main` with the existing tag. That workflow validates the tag and devices,
restores the original assets, publishes the draft, and resumes the recorded Central deployment. It
never substitutes a rebuilt bundle for tagged release assets.

Missing credentials block publication without changing source artifacts. A Central failure, invalid
signature, mismatched hash, or wrong coordinate blocks success. Do not retag or replace the release;
publish a corrected version instead.
