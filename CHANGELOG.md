# Changelog

All notable changes follow [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and semantic versioning.

## [Unreleased]

## [1.2.0] - 2026-11-27

### Added

- Host-controlled preference inspection for deliberately selected non-debuggable internal builds.
- Non-debuggable sample and API 28/API 35 device coverage for the host enablement callback.

### Security

- Shell UID and `android.permission.DUMP` remain mandatory for every host policy.
- Host policy exceptions fail closed, and streamed saves recheck access before committing.

## [1.1.0] - 2026-11-26

### Added

- Opt-in discovery for unregistered plain SharedPreferences files, with exclusions and refresh-time
  reconciliation.
- Unavailable descriptors for encrypted-looking files and AndroidX Security keyset markers.
- Device coverage for editing and restoring a discovered plain store through protocol v1.

## [1.0.0] - 2026-11-26

### Added

- Debug-only `PreferenceDebugProvider` and explicit `PreferenceStoreRegistry` API.
- Mobile Build version-1 typed read, conflict-aware write, and acknowledgement protocol.
- Support for Boolean, Int, Long, Float, String, and String Set values.
- Plain and encrypted sample stores, a published-artifact consumer fixture, and device smoke tests.
- Maven Central publication metadata, signing, bundle generation, and release workflows.

[Unreleased]: https://github.com/chinmaydash007/mobilebuild-preference-debug-bridge/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/chinmaydash007/mobilebuild-preference-debug-bridge/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/chinmaydash007/mobilebuild-preference-debug-bridge/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/chinmaydash007/mobilebuild-preference-debug-bridge/releases/tag/v1.0.0
