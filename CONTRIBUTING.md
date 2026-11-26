# Contributing

Use JDK 17 and Android SDK 35. Before opening a pull request, run:

```bash
bash scripts/verify.sh
```

Protocol changes must remain compatible with Mobile Build or use a new versioned route. Never add automatic preference discovery, log values, add a provider to the library manifest, or add a production encryption dependency.

Do not include real application preference values, encryption keys, tokens, or signing credentials in issues, tests, or pull requests.

Publishing is restricted to the original repository and an immutable version tag. A normal pull
request or `main` build cannot upload to Maven Central.
