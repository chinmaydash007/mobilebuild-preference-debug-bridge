# Contributing

Use JDK 17 and Android SDK 35. Before opening a pull request, run:

```bash
./gradlew test lint :sample:assembleDebug :sample:assembleRelease publishToMavenLocal
./gradlew -p consumer-fixture :app:assembleDebug :app:assembleRelease
python3 scripts/verify_manifests.py
```

Protocol changes must remain compatible with Mobile Build or use a new versioned route. Never add automatic preference discovery, log values, add a provider to the library manifest, or add a production encryption dependency.

Do not include real application preference values, encryption keys, tokens, or signing credentials in issues, tests, or pull requests.
