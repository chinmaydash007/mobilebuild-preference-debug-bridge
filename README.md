# Mobile Build Preference Debug Bridge

An Android debug library that lets **Mobile Build → Tools → Shared Preferences Inspector** read
and edit explicitly registered or opt-in discovered `SharedPreferences` stores over ADB.

The bridge works with ordinary and encrypted preferences because the host app opens each store with its real configuration. It never reads XML directly, asks for encryption keys, or links a particular encryption implementation.

## Installation

Version `1.1.0` is available from Maven Central under these coordinates:

```kotlin
dependencies {
    debugImplementation(
        "io.github.chinmaydash007.mobilebuild:preference-debug-bridge:1.1.0"
    )
}
```

Use `debugImplementation`. The library intentionally contributes no manifest provider, so the host app controls exactly which debug variant exposes the bridge.

## Register stores

Create a provider subclass under `src/debug`:

```kotlin
package com.example.app.debug

import android.content.Context
import io.github.chinmaydash007.mobilebuild.preferences.PreferenceDebugProvider
import io.github.chinmaydash007.mobilebuild.preferences.PreferenceStoreRegistry

class AppPreferenceDebugProvider : PreferenceDebugProvider() {
    override fun registerStores(registry: PreferenceStoreRegistry) {
        registry.register(
            id = "settings",
            label = "Settings",
        ) { context ->
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        }

        registry.register(
            id = "secure-session",
            label = "Secure session",
        ) { context ->
            openEncryptedPreferences(context)
        }
    }
}
```

The encrypted factory must use the same filename, key alias, key scheme, and value scheme as the app. The bridge has no dependency on AndroidX Security and works with any implementation of `SharedPreferences`.

Declare the subclass in `src/debug/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <provider
            android:name=".debug.AppPreferenceDebugProvider"
            android:authorities="${applicationId}.mobilebuild.preferences"
            android:exported="true"
            android:grantUriPermissions="false"
            android:permission="android.permission.DUMP" />
    </application>
</manifest>
```

No `Application` call is needed. Android creates the provider automatically. Registration only retains factories; stores are opened lazily when Mobile Build requests them.

Each ID must be nonblank and unique. IDs are stable protocol identifiers and do not need to match
preference filenames.

## Discover ordinary preference files

Discovery is disabled by default. An app can explicitly opt into discovering ordinary preference
XML files that were not registered individually:

```kotlin
override fun registerStores(registry: PreferenceStoreRegistry) {
    registry.register(
        id = "secure-session",
        label = "Secure session",
    ) { context ->
        openEncryptedPreferences(context)
    }

    registry.discoverPlainPreferences(
        excludedFileNames = setOf(
            "secure_session",
            "${BuildConfig.APPLICATION_ID}_preferences",
        ),
    )
}
```

Discovered stores use their preference filename as their ID and label and remain editable through
`SharedPreferences.Editor`. Explicit registrations take priority. Exclusions accept names with or
without `.xml`, and repeated calls merge their exclusions. Discovery runs when Mobile Build makes
a request, so Refresh finds files created after the provider started.

Files with encrypted-looking names or AndroidX Security keyset markers are listed as unavailable
and never return raw keys or values. Generic encrypted formats cannot always be detected, so every
encrypted preference file must still be explicitly registered with its real configuration or
excluded from discovery. Opting into discovery may expose third-party SDK preferences within the
debug app's sandbox; enable it only in trusted internal debug builds.

## Behavior and security

- The provider accepts calls only when the host application is debuggable and the caller is Android's shell UID.
- The manifest requires `android.permission.DUMP`; URI grants are disabled.
- Version 1 edits existing keys only. It never creates, deletes, or renames keys.
- Boolean, Int, Long, Float, String, and String Set values are editable. Unknown values remain visible as read-only text.
- A save compares each edited key with the value Mobile Build loaded, then applies all changes with one `SharedPreferences.Editor.commit()`.
- Preference values stay in process memory and the ADB stream. The bridge does not log, export, or persist them elsewhere.
- Plain-file discovery is opt-in, rejects symlinks and nested paths, and never rewrites XML directly.
- Requests are limited to 1 MiB. At most 64 request IDs remain active, and acknowledgements expire after two minutes.

Do not move the provider or dependency into a production source set. Verify the release merged manifest before shipping.

## Development

Requirements: JDK 17 and Android SDK 35.

```bash
bash scripts/verify.sh
```

Verification stages the unsigned Maven publication under `build/validation-repository`, validates
its AAR/POM/Gradle metadata/source/documentation contents, and resolves it from an independent
consumer. It never uploads to Maven Central.

The `sample` app demonstrates registered plain/encrypted preferences plus an opt-in discovered
plain store while keeping the encryption dependency out of the library. With a device or emulator
attached:

```bash
./gradlew :sample:assembleDebug
python3 scripts/device_smoke_test.py \
  --adb "$ANDROID_HOME/platform-tools/adb"
```

On Android 9 (API 28) or newer, the smoke test installs the sample, exercises the same `content read` and streamed `content write` commands used by Mobile Build, and restores every value it changes. The AAR itself retains a minimum SDK of 21; see the compatibility policy for the protocol distinction.

See [the protocol reference](docs/PROTOCOL.md), [compatibility policy](docs/COMPATIBILITY.md), and [publishing guide](docs/PUBLISHING.md).

## License

Apache License 2.0. See [LICENSE](LICENSE).
