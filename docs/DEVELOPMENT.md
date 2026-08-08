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

## Publish an Android release

Android releases use the next unused monotonic `1.0.x` tag. The tag is the
shared allocator for both supported publication paths, so a pipeline release
and a locally uploaded APK cannot intentionally reuse the same version.

To build and publish in GitHub Actions, open **Actions > Manual Android
Release > Run workflow**. The workflow calculates the next version, injects
it into Gradle, builds and verifies a signed APK, uploads the APK as a workflow
artifact, and publishes it with the matching `v1.0.x` GitHub Release.

For a locally signed APK, create `android\signing.properties`, authenticate
GitHub CLI with `gh auth login`, then run:

```powershell
.\scripts\Build-LocalAndroidRelease.ps1
.\scripts\Publish-LocalAndroidRelease.ps1 `
  -ApkPath .\dist\android-release\Mochi-v1.0.1.apk
```

The build script calculates the next remote version and embeds it in the APK.
Use the exact versioned APK path printed by that script in the publish command.
Local builds require a clean worktree and write a sidecar containing the source
commit, version, and APK hash. The publish script verifies that metadata, the
APK signature, and embedded version, rejects a stale or reused version,
generates the SHA-256 file, and uploads both assets to a new GitHub Release. If
another release wins the version race, rebuild with the newly allocated
version instead of overwriting or reusing a tag. Once a publisher reserves a
remote tag it is never deleted automatically; an interrupted publication may
therefore leave a skipped `1.0.x` value, but can never make a released version
move backward or be silently replaced.

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
