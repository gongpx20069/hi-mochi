# Development

Mochi is a native Android project. Product behavior and architecture are
defined by the documents linked from [`docs/README.md`](README.md).

## Repository layout

```text
android/   Kotlin applications, shared extension API, and Gradle wrapper
docs/      Product, architecture, delivery, and development documentation
AGENTS.md  Harness entry point and documentation routing
README.md  User-facing project introduction
```

Android modules:

```text
android/
├── app/                 Mochi base application and ABI APKs
├── extension-api/       AIDL and immutable extension contracts
├── extensions/mijia/    optional universal Mi Home extension APK
└── extensions/termux/   optional universal Termux extension APK
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
the debug APK, and extension contract/provider tests. `verifyRelease` runs the
release checks and assembles the five base APKs plus the universal signed Mi
Home and Termux extension APKs. `verifyNative` also runs `verifyTermuxRunner`
on Linux: Python standard-library acceptance tests exercise the exact bundled
Bash supervisor, deadlines, output caps, exit status and process-group stop.
That Linux-only task is skipped on Windows; CI must pass it before delivery.

For a narrow iteration, run the smallest affected Gradle test or compile task
before returning to the full gates.

AgentLink domain/registry regression coverage:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*AgentLinkToolTest" --no-daemon
```

For device acceptance install both Mochi and the independently signed AgentLink
APK. Verify nonce-confirmed authorization and cancellation, provider/individual
switch exclusion, shared CLI/App chat reads, native linked-chat opening, human
revision conflict, revoke, Binder death, reconnect and unknown-write outcomes.
Verify a running remote task survives Mochi cancellation. JVM tests do not prove
cross-APK Android identity, Activity result, or Bridge connectivity behavior.

To diagnose cloud synthesis against the phone's saved settings, build
`:app:assembleDebug :app:assembleDebugAndroidTest`, update the matching-signed
app and test APKs with `adb install -r`, and run:

```powershell
adb -s <device-id> shell am instrument -w `
  -e class com.example.mochi_pet.platform.voice.SpeechSynthesisDiagnosticTest `
  -e mochiSpeechDiagnostic true `
  com.example.mochi_pet.test/androidx.test.runner.AndroidJUnitRunner
adb -s <device-id> logcat -d -s MochiSpeech
```

This explicit opt-in sends only a fixed test greeting and consumes the selected
Provider's synthesis quota. Credentials stay on the device, returned audio stays
in memory without playback, and saved settings/history are unchanged. Without
the opt-in argument the diagnostic test is skipped. Never uninstall the target
app or clear its data to run diagnostics.
Optional `-e mochiSpeechDiagnosticExtended true` uses a longer fixed test passage
to exercise streaming responses. Optional `-e mochiSpeechDiagnosticPlayback true`
plays the returned test audio through the same media-volume path as replies,
with transient speech audio focus; it does not change system volume levels.

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

Install or update the optional extension without uninstalling either package:

```powershell
adb -s <device-id> install -r `
  extensions\mijia\build\outputs\apk\release\mijia-release.apk
```

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
  -ReleaseDirectory .\dist\android-release\Mochi-v1.0.3
```

The build script calculates the next remote version and embeds it in five
signed base APKs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`, and `universal`,
plus one signed universal `Mochi-Mijia-Extension` APK. It writes them to one
versioned directory together with `Mochi-Termux-Extension`. Both extension APKs
must match the base application's signing certificate and version. It writes a
release metadata file and shared SHA-256 manifest. Use
the exact directory printed by that script in the publish command. Local builds
require a clean worktree. The publish script verifies every APK's application
ID, metadata, signature, embedded version, expected ABI set, launcher policy,
and hash, then uploads the APKs and checksum manifest to a new GitHub Release.
If another release wins the version race, rebuild with the newly allocated
version instead of overwriting or reusing a tag. Once a publisher reserves a
remote tag it is never deleted automatically; an interrupted publication may
therefore leave a skipped `1.0.x` value, but can never make a released version
move backward or be silently replaced.

### Termux acceptance

Use matching-signed base and `extensions\termux\build\outputs\apk\debug\termux-debug.apk`
APKs, updating existing packages with `adb install -r`. Termux is installed
separately from its official channels; do not uninstall an existing Termux to
change its source. Follow Tools > Extensions > Termux > Configure.
The connector uses RUN_COMMAND callbacks (Termux 0.109+) and per-command log
control (0.118+); use a current compatible official Termux build.

Verify permission denial/revocation, initial stopped-package setup, callback
delivery with the host foreground, no App switch during background execution,
one-shot/task-wide native approval, voice confirmation, normal/nonzero exit,
large stdout/stderr, timeout, Stop, process death, reconnect and retained task
inspection. Verify background Shell authorization defaults off, requires native
confirmation, survives restart, and enables Termux for Scheduled Agents and
both Subagent roles without per-call prompts. Check foreground confirmation
still applies, Skill readiness follows each registry, and permission/provider/
individual Tool revocation blocks subsequent calls. Provider disable/disconnect
must clear background authorization without claiming to stop submitted work.
Mi Home and AgentLink must remain excluded from scheduled/subagent registries.
Update both base and Termux extension APKs for background-context support.
JVM and Linux tests do not establish real-device cross-App or
OEM background behavior. No connected Android device means that acceptance is
blocked, not passed.

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
