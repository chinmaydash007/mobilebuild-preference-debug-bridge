# Compatibility policy

## Android

- Library minimum SDK: 21.
- Sample minimum SDK: 23 because its demonstration encryption dependency uses Android Keystore APIs.
- Compile SDK: 35.
- Build JDK: 17.
- Published bytecode target: Java 8.

The AAR has no provider declaration and no runtime dependency on an encryption library. Consumers may use any `SharedPreferences` implementation available to their debug variant.

## Protocol

The Maven major version and ADB protocol version evolve independently. Release `1.0.0` implements protocol `v1`, matching Mobile Build's existing Shared Preferences Inspector.

- Additive JSON fields may be introduced within `v1`; decoders must ignore unknown fields.
- Route, field, or type changes that would break Mobile Build require a new protocol path such as `/v2`.
- Existing protocol paths remain available while a supported Mobile Build release still uses them.

## Versioning

The library follows semantic versioning. Published Maven Central versions are immutable. Deprecations remain for at least one minor release before removal in the next major release.
