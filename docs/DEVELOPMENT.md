# Development

Mochi is a native Android project. Product behavior and architecture are
defined by the documents linked from [`docs/README.md`](README.md).

## Repository layout

```text
android/   Kotlin and Jetpack Compose application
docs/      Product, architecture, delivery, and development documentation
AGENTS.md  Harness entry point and documentation routing
README.md  User-facing project introduction
```

## Prerequisites

- JDK 17
- Android SDK with API 36
- An Android 8.0 or newer device or emulator

Use the checked-in Gradle wrapper. A global Gradle installation is not needed.
Keep machine-specific SDK paths in `android/local.properties`; do not commit
that file. The first build downloads the pinned sherpa-onnx Android AAR from
its upstream GitHub release and verifies its SHA-256 before use.

## Build and verify

From PowerShell:

```powershell
Set-Location android
.\gradlew.bat verifyNative verifyRelease --no-daemon
```

`verifyNative` checks architecture rules, formatting, Android Lint, JVM tests,
and the debug APK. `verifyRelease` runs the release checks and assembles the
release APK.

For a narrow iteration, run the smallest affected Gradle test or compile task
before returning to the full gates.

## Install on a device

List connected devices:

```powershell
adb devices -l
```

Install the debuggable APK:

```powershell
adb -s <device-id> install -r `
  app\build\outputs\apk\debug\app-debug.apk
```

The default release artifact is unsigned unless a local signing configuration
is supplied.

## Engineering expectations

- Follow the harness loop in [`AGENTS.md`](../AGENTS.md).
- Keep Compose, ViewModel, repository, and platform boundaries aligned with
  `architecture/APP_ARCHITECTURE.md`.
- Add Room migrations, schema snapshots, and migration coverage together.
- Use fake clocks, fake providers, in-memory Room, and local mock servers for
  deterministic tests.
- Validate wake word, recognition, TTS, audio focus, notifications, alarms,
  media buttons, process death, and OEM behavior on a real device.
- Never commit credentials, local SDK paths, APKs, build output, or captured
  private user data.

## Documentation changes

Put user onboarding in the root README, development instructions here, concise
product and delivery documents in `docs/`, and detailed architecture contracts
in `architecture/`. Link to an authoritative definition instead of copying it
into another document.
