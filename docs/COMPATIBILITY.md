# Compatibility policy

## Android

- Library minimum SDK: 21.
- Sample minimum SDK: 23 because its demonstration encryption dependency uses Android Keystore APIs.
- Mobile Build protocol `v1` read/write support: Android 9 (API 28) and newer. Earlier
  Android versions do not provide the streamed `content write` shell command used for saves.
- Compile SDK: 35.
- Build JDK: 17.
- Published bytecode target: Java 8.

The AAR has no provider declaration and no runtime dependency on an encryption library. Consumers may use any `SharedPreferences` implementation available to their debug variant. The lower library minimum SDK lets the same debug dependency remain in applications that support older devices, but Mobile Build must use an API 28 or newer device to edit values through protocol `v1`.

## Protocol

The Maven major version and ADB protocol version evolve independently. Releases `1.0.0` and
`1.1.0` implement protocol `v1`, matching Mobile Build's existing Shared Preferences Inspector.

Release `1.1.0` adds an opt-in plain-file discovery API. Consumers that do not call it retain the
explicit-registration-only behavior from `1.0.0`.

- Additive JSON fields may be introduced within `v1`; decoders must ignore unknown fields.
- Route, field, or type changes that would break Mobile Build require a new protocol path such as `/v2`.
- Existing protocol paths remain available while a supported Mobile Build release still uses them.

## Versioning

The library follows semantic versioning. Published Maven Central versions are immutable. Deprecations remain for at least one minor release before removal in the next major release.
