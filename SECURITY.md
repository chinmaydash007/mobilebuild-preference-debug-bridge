# Security policy

The bridge is intended only for debuggable Android variants. Consumers must use `debugImplementation`, place their subclass and manifest declaration under `src/debug`, require `android.permission.DUMP`, and keep URI grants disabled.

The provider also rejects requests unless the host application has `FLAG_DEBUGGABLE` and the caller is Android's shell UID. These checks reduce accidental exposure but do not make a debug build suitable for production distribution.

Register only the stores needed for debugging. Factories must open encrypted stores through their real `SharedPreferences` implementation; never expose encryption keys or encrypted XML.

Report suspected vulnerabilities privately through GitHub's security advisory flow. Do not attach preference values, device identifiers, tokens, or key material.
