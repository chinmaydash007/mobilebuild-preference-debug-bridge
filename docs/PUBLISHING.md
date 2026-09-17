# Publishing version 1.0.0

## One-time setup

1. Create the public `chinmaydash007/mobilebuild-preference-debug-bridge` GitHub repository.
2. Sign in to the Central Publisher Portal and verify the `io.github.chinmaydash007` namespace.
   This project is configured as community open source. Recheck
   [Central's publisher-tier rules](https://central.sonatype.org/publish/maven-central-publishing-limits/)
   if it later becomes support infrastructure for a commercial product.
3. Generate a Central user token.
4. Create a PGP signing key whose public key is available from a supported public keyserver.
5. Configure these GitHub Actions secrets:
   - `CENTRAL_TOKEN_USERNAME`
   - `CENTRAL_TOKEN_PASSWORD`
   - `SIGNING_KEY` containing the armored private key
   - `SIGNING_PASSWORD`

Do not commit publishing credentials or signing material.

## Local validation

```bash
./gradlew clean test lint \
  :preference-debug-bridge:assembleRelease \
  :sample:assembleDebug :sample:assembleRelease \
  publishToMavenLocal
./gradlew -p consumer-fixture clean :app:assembleDebug :app:assembleRelease
python3 scripts/verify_manifests.py
```

The consumer fixture resolves `io.github.chinmaydash007.mobilebuild:preference-debug-bridge:1.0.0` from `mavenLocal()` and does not depend on the library project.

## Release

1. Ensure `VERSION_NAME` and `CHANGELOG.md` contain the release version.
2. Create and push the immutable signed tag `v1.0.0`.
3. Run the **Prepare Maven Central release** workflow with that tag.
4. Download and inspect the generated Central bundle artifact if needed.
5. Wait for the workflow to report `VALIDATED`.
6. Review the deployment in the Central Portal and publish it manually.
7. Verify the coordinates resolve from `mavenCentral()` before announcing the release.

The workflow deliberately uploads with `publishingType=USER_MANAGED`. It never performs the irreversible final publish action.
