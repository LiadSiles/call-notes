# Build and release

_Last verified: 2026-10-19_

## Toolchain

Gradle 8.14.3 (wrapper), AGP 8.11.1, Kotlin 2.4.0 + KSP 2.3.11 (Kotlin 2.4 is required by
LiteRT-LM 0.17.1), Compose BOM 2025.06.01, Room 2.7.2, LiteRT-LM 0.17.1. APK limited to
`arm64-v8a`. `compileSdk`/`targetSdk` 36, `minSdk` 29, JVM target 17.
Versions live in `gradle/libs.versions.toml`.

## CI and releases

`.github/workflows/build.yml` runs on every push to `main` and `dev/**` branches:
`./gradlew testDebugUnitTest assembleDebug`. Only `main` publishes a release, so larger
work goes on a `dev/<version>` branch until CI is green, then merges to `main`.
Steps: build → upload artifact → `gh release create
v<versionName>-build.<run_number>` as a **prerelease** with
`call-notes-<version>-build.<n>.apk`.

- This is the reference compile check. Sessions without Google Maven access should
  push and read the run result (REST: `repos/LiadSiles/call-notes/actions/runs`).
- Raw job logs may be unreachable (blob storage blocked by a proxy). On failure the
  workflow re-emits compiler/AAPT errors as `::error::` annotations — read them with
  `gh api repos/LiadSiles/call-notes/check-runs/<id>/annotations`.
- Gotcha hit in 0.2: `android:accessibilityEventTypes="0"` fails resource linking
  (it's a flags attribute); omit it to subscribe to no events.
- Gotcha hit in 0.3.5: `ServiceCompat.startForeground` (androidx.core 1.16) masks the
  type with the API 34 set, so `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` (API 35)
  becomes 0 and Android 15+ throws `InvalidForegroundServiceTypeException: Starting FGS
  with type none`. Call `Service.startForeground(id, n, type)` directly for API 35+ types.
- The repo is private; downloading a release on the phone requires being signed in to
  GitHub in the browser.

## Native code

`third_party/whisper.cpp` is a git submodule pinned to tag v1.9.5; clone with
`--recurse-submodules` (CI checks out with `submodules: true`). CMake 3.22.1 via AGP;
the NDK is AGP's default. Debug and release APKs both compile it with Release flags.

## Signing

`keystore/debug.keystore` (password `android`, alias `androiddebugkey`) is committed on
purpose so every build — local or CI — has the same signature and installs as an
update. 1.0 will use a release key kept **out** of the repo (GitHub secret).

## Installing on a phone

- **From Releases (normal):** download the APK, allow "install unknown apps" for the
  browser once, install. Updates install over the old version; data is kept.
  Then App info → ⋮ → "Allow restricted settings" before enabling the accessibility
  service (Android 13+).
- **From Android Studio:** USB debugging; on Xiaomi also "Install via USB" and "USB
  debugging (Security settings)" (need a Mi account and SIM). ADB installs are not
  subject to restricted settings.

## Size

The APK does not contain any model. Debug APK ≈ 27 MB (unminified Compose); a minified
release is expected around 5–8 MB plus the LiteRT-LM runtime. Models (2.6 GB+) are
downloaded at setup.
