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
├── extension-ui/        shared Mochi theme, cards, and setup shell
├── extensions/mijia/    optional universal Mi Home extension APK
├── extensions/termux/   optional universal Termux extension APK
└── extensions/webdav/   optional universal WebDAV Files extension APK
```

## Prerequisites

- JDK 21 or newer (required by Android 17 Robolectric tests)
- Android SDK with API 37
- An Android 8.0 or newer device or emulator

Use the checked-in Gradle wrapper. A global Gradle installation is not needed.
The wrapper pins Gradle 9.8.2 with its official distribution SHA-256.
The build uses AGP 9.1.1 with built-in Kotlin, Kotlin 2.4.10 compiler plugins,
and serialization 1.12.0 across the host and extensions. The root buildscript
pins KGP because AGP's default compiler is older; do not reapply
`org.jetbrains.kotlin.android` in modules. Serialization 1.12.0 uses Kotlin 2.4
metadata and cannot compile with the former Kotlin 2.2.20 toolchain.
The host and all three extensions compile against and target Android 17 (API 37);
the shared libraries also compile against API 37. Android 8.0 (API 26) remains
the minimum supported version. Keep Lint warnings fatal, including target-SDK
checks; do not suppress `OldTargetApi` to pass CI.
CI and release builds use JDK 21; Kotlin and Java application bytecode still
target Java 17. The build rejects older Gradle JVMs before running tasks.
The app's unit-test JVM exports `java.base/jdk.internal.access` for Robolectric's
API 37 shared-memory emulation; this does not change the APK runtime.
Host UI tests pin Espresso 3.8.0-alpha01 and Test Core 1.8.0-alpha01: Espresso
3.7.0 reflects on the removed `InputManager.getInstance()` API on Android 17.
These are test-only dependencies, not production application libraries.
Keep machine-specific SDK paths in `android/local.properties`; do not commit
that file. The first build downloads the pinned sherpa-onnx Android AAR from
its upstream GitHub release and verifies its SHA-256 before use.

CI and the manual release workflow share `.github/actions/prepare-sherpa`.
It caches that exact AAR by its pinned SHA-256, uses the job token only in the
GitHub CLI download step to avoid anonymous API rate-limit failures, retries
download failures at most three times, and checks the digest on cache hits too.
A failed download or checksum fails the build; no alternate unpinned artifact
or skipped speech dependency is allowed.

## Build and verify

The standby clock bundles the unmodified Fredoka variable font from
`google/fonts` revision `23e54b51ddffbc7713c583748e3bd86f62b1fa4a`
(`ofl/fredoka/Fredoka[wdth,wght].ttf`) as `app/src/main/res/font/fredoka.ttf`.
Its SIL Open Font License and copyright notice ship in
`app/src/main/assets/licenses/fredoka-OFL.txt`. Font rendering is offline;
unsupported glyphs (including Chinese) use Android's font fallback.

Date/weekday text separately uses the unmodified Aoyagi Reisho SIMO 2.01 font
from `https://opentype.jp/bin/aoyagireisyosimo_ttf_2_01.zip`,
bundled as `app/src/main/res/font/aoyagi_reisho.ttf`. Aoyagi Kouzan supplied
the calligraphy and SIMO created the font. Its included terms permit free
commercial use and free redistribution, but prohibit charging for the font
itself and require both the usage terms and font description to accompany it.
Both ship under `app/src/main/assets/licenses/aoyagi-reisho-*` (usage text
and original description PDF). The font covers all digits, Chinese date and
weekday characters, and English month/weekday names used by standby.

From PowerShell:

```powershell
Set-Location android
.\gradlew.bat verifyNative verifyRelease --no-daemon
```

`verifyNative` checks architecture rules, formatting, Android Lint, JVM tests,
the debug APK, and extension contract/provider tests. `verifyRelease` runs the
release checks and assembles the five base APKs plus the universal signed Mi
Home, Termux and WebDAV extension APKs. `verifyNative` also runs `verifyTermuxRunner`
on Linux: Python standard-library acceptance tests exercise the exact bundled
Bash supervisor, deadlines, output caps, exit status and process-group stop.
That Linux-only task is skipped on Windows; CI must pass it before delivery.

For a narrow iteration, run the smallest affected Gradle test or compile task
before returning to the full gates.

Tencent Docs Smart Canvas regressions:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*ToolCatalogTest" `
  --tests "*MochiDatabaseTest" --tests "*AgentOrchestratorTest" --no-daemon
```

These use synthetic schemas to cover default-on current Smart Canvas Tools,
version-1 catalog upgrade, preserved individual switches, deferred discovery
for disabled connections, and the concise Skill's update/readiness contract.
For separately authorized device diagnostics, `DocumentToolCatalogDiagnosticTest`
has an opt-in `inspectLiveTencentTools` method (`mochiTencentDiscoveryDiagnostic=true`).
It lists the live, unfiltered Tencent MCP catalog using the existing native
connection, logging only tool metadata, never credentials or document contents.
This distinguishes server changes from stale caches and allowlist omissions;
normal instrumentation runs skip live discovery.

Only after explicit user authorization, the same class's
`createAndEditIndependentSmartCanvas` (`mochiTencentWriteDiagnostic=true`) creates
one uniquely titled fixture through the enabled production Tools, verifies its
initial body, appends MDX, updates that exact appended block, and reads back
the result while checking the original paragraph. It never searches,
edits or deletes existing user documents. A private cache receipt is written
before creation and retained with the returned file ID/link to prevent blind
replay after a timeout. Inspect that exact receipt and its fixture before
authorizing another run; never delete unrelated app data.
`updatePreviouslyCreatedFixture` (`mochiTencentUpdateFixtureDiagnostic=true`)
can separately verify updating the still-unmodified appended paragraph of that
exact receipt's fixture; it rejects missing or ambiguous targets and does not
create another document. Live checks are not
part of CI and require a same-signer in-place app/test APK update.

Browser assistance regressions:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*UserAssistanceTest" `
  --tests "*BrowserSettingsTest" --tests "*BrowserModeTest" `
  --tests "*BrowserCredentialsTest" --tests "*BrowserCredentialCaptureTest" `
  --tests "*BrowserCredentialsUiTest" `
  --tests "*AgentOrchestratorTest" --tests "*Skill*Test" --no-daemon
```

Skill catalog changes additionally run `:app:testDebugUnitTest --tests
"*MochiDatabaseTest" --tests "*AgentOrchestratorTest"`. These cover legacy Web
Search enablement moving to Browser without duplicate entries, new-ID override
precedence, read-only discovery/loading, and optional tool headers restricted to
the calling registry. These deterministic checks validate the contract, not
live-provider model choices or real-site authentication.

`AgentBrowserSemanticSnapshotTest` uses local synthetic HTML to cover unmarked
DIV/SPAN statistics, action prioritization beyond 100 links, shadow controls,
editable-value redaction, native input/select events, reference replacement and
covered/hidden click rejection. No real video likes/favorites are mutated.
`WakeKeywordDeviceTest` feeds 16 kHz mono PCM16 fixtures through the production
native detector. For each selected language, it requires its wake phrase to be
accepted and the other phrase plus two ordinary commands to be rejected.
Fixtures under `app/src/androidTest/assets/wake` are offline Windows SAPI output:
Zira says "Hi Mochi" and "Please show the latest video"; Huihui says "老莫老莫"
and "请看看最新的视频有多少收藏". They contain no user recordings and do not
ship in the application. Generate with `SAPI.SpVoice` and `SAPI.SpFileStream`,
`Format.Type=18` (16 kHz, 16-bit mono), rate 0. Zira mispronounces the brand by
default, so its wake fixture uses SAPI
`<pron sym="m ow 1 ch iy">Mochi</pron>` for "MOH-chee". Synthetic acceptance is not
evidence of human, distance, noise or echo accuracy; those still need device
acoustic evaluation.

After an in-place app/test APK update, `feature.home.BrowserExperienceSmokeTest`
checks the always-visible management entry, desktop viewport, explicit handoff,
snapshot pause, continuation, credential-save confirmation, password redaction,
account deletion without logout, Cookie reuse across turns and complete per-site
deletion. Its form-less SMS fixture uses a real touch to verify phone-only saving,
masked-code exclusion, and filling without changing the code or auto-submitting.
No real SMS is sent. JVM coverage verifies legacy password-catalog reads, SMS
records rejecting codes, encrypted storage, stale revisions, exact-origin
capture, public/private suffix grouping, and half-width credential/data swipe
actions. Grouping tests initialize OkHttp's Android assets under Robolectric,
so the actual bundled public-suffix list is exercised rather than a fake resolver.
Handoff layout cases in `BrowserCredentialsUiTest` cover compact page allocation,
large text, explicit return, details/menu actions and question-sheet answers.
The device handoff case checks website history without returning control, keyboard
insets hiding/restoring the request strip, filling through the exact-origin picker,
and rejection of stale handoff navigation; it never uses a real saved account.
A separate device case submits the
non-sensitive query `mochi browser fixture` to public Bing and checks full-screen
manual browsing, back/forward, the shared credentials popup and Home/resume
preserving the same session and entered form text, then confirms scoped data
deletion closes the manual page without losing its result. A fourth case opens
the nested website list, deletes a credential without removing its Cookie, and
deletes parent/subdomain data without removing the remaining account or an
unrelated fixture site's Cookie. The layout test renders
`app/build/reports/browser-accounts-fixture.png` and
`app/build/reports/browser-website-details-fixture.png` using synthetic metadata.
Login fixtures use synthetic HTML under the reserved
`mochi-browser-fixture.example` host, no real website/account request, and
deletes only that fixture site's data. Actual website login/expiry, authentication
compatibility and mode quality still require manual user-owned website testing.
The handoff may speak its synthetic explanation through the configured TTS.
Never clear all phone website data as a test or record real login credentials.

On MIUI, a runner can report a test as started while
`Instrumentation.startActivitySync` is still waiting: system logs may show
`Abort background activity starts` before any test-body or Activity lifecycle
events. Keep the phone unlocked and inspect that evidence before retrying.
For an already-waiting `MainActivity` scenario, explicitly launch its matching
intent from a second terminal; a component-only launch does not match:

```powershell
adb -s <serial> shell am start -a android.intent.action.MAIN `
  -c android.intent.category.LAUNCHER -f 0x10008000 `
  -n com.example.mochi_pet/.MainActivity
```

Use this only for a confirmed blocked launch, not while a test body is running.
Each subsequent scenario can need the same assistance. Do not change background
permissions or weaken assertions to get a pass. Require `OK (4 tests)` from this
suite; an instrumentation crash can still return shell exit code zero.
Expression-bodied JUnit methods must return `Unit`; use `runBlocking<Unit>`
when the last UI assertion/action returns a semantics interaction rather than
`Unit`, otherwise JUnit rejects the class before any fixture runs.
If the user approves temporary stay-awake, record and restore the original
`stay_on_while_plugged_in` value in `finally`. Check the actual charging type:
some phones report computer power as AC, so USB-only stay-awake does not apply.

Custom REST regressions:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*Rest*Test" `
  --tests "*ToolCatalogTest" --tests "*MochiHomeViewModelTest" --no-daemon
```

These cover typed requests, encrypted catalog persistence, revision revocation,
single-dispatch HTTP fixtures, cancellation, response limits and Compose setup.
Transport fixtures allow loopback only through an internal test client; the
production client always applies the public-address policy. No real API
credentials or provider calls are used.
After `adb install -r`, `feature.home.RestApiSmokeTest` verifies both Add tool
paths without saving or testing an endpoint and checks AI/speech catalogs remain
unchanged. Real API business semantics require separate authorized testing.

Saved AI connection regressions:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*ProviderSettingsRepositoryTest" `
  --tests "*ProviderShareManagerTest" --tests "*ProviderSharingTest" `
  --tests "*ProviderProfilesUiTest" --tests "*OkHttpOpenAiChatClientTest" `
  --tests "*MochiHomeViewModelTest" --no-daemon
```

These use synthetic credentials and intercept preset requests without contacting
real providers. Device `feature.home.ProviderProfilesSmokeTest` opens Settings,
checks Share/import is first, opens the saved speech profile editor, confirms
active AI/speech credential decryption without printing it, and verifies both
catalogs are unchanged. Update with `adb install -r`;
never uninstall or clear data. Real account/model Tool compatibility still needs
authorized provider-specific testing and is not implied by endpoint presets.

Responses regressions additionally run `:app:testDebugUnitTest --tests
"*ResponsesAdapterTest" --tests "*OkHttpOpenAiChatClientTest" --tests
"*AgentOrchestratorTest" --tests "*ProviderSharingTest" --tests
"*ProviderShareManagerTest" --tests "*ProviderSettingsRepositoryTest" --tests
"*ProviderProfilesUiTest"`. They cover stateless tool continuation, images, exact
URLs/auth, optional schemas, failure/cancellation without fallback, legacy Chat
defaults, same-key protocol changes, v5 shares and the advanced selector.

After an in-place app/test APK update, the opt-in
`core.agent.llm.AzureResponsesDiagnosticTest` uses the active saved connection
only when its host and deployment match explicit instrumentation arguments:
`-e mochiResponsesDiagnostic true -e mochiAzureResourceHost <host>
-e mochiAzureDeployment <deployment>`. It sends a fixed synthetic Agent prompt,
executes only a local no-side-effect fixture tool, and checks the final reply
through the production Agent loop with the normal 20-second foreground deadline.
It consumes model quota, but sends no user history/persona, real Tool definitions,
or business data. Logs contain only request durations and counts.
Only with explicit permission, `-e mochiEnableResponses true` switches that
connection's protocol after success, preserving its key and all other settings.
Without the diagnostic opt-in, the test is skipped.

Speech profile regressions additionally cover `SpeechSettingsRepositoryTest`,
`SpeechSynthesisSettingsTest`, `CloudSpeechRuntimeTest`, and `WakeBriefingTest`:
legacy active/inactive migration, isolated same-provider accounts, non-overwriting
multi-share import, inactive-account preview, and voice-only switch cancellation.
Device smoke does not send recognition audio or consume synthesis quota.

Feishu integration regressions:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*Feishu*Test" `
  --tests "*McpClientTest" --tests "*ToolCatalogTest" `
  --tests "*MochiDatabaseTest" --no-daemon
```

These use synthetic credentials and local fixtures, not an actual Feishu account.
Device acceptance separately requires a user-owned published Feishu app: follow
Tools > Feishu MCP, confirm browser return, permission denial, cancellation,
refresh and process-death retry, then read/search an authorized document.
Only with explicit permission create/update a test document and verify readback.
Never request App Secret/tokens in chat or put them in logs/screenshots.
The callback is `http://127.0.0.1:43827/oauth/feishu` on the same phone; do not
configure it as a manual MCP endpoint or weaken the public HTTPS policy.

After an in-place app/test APK update, the opt-in
`com.example.mochi_pet.core.tools.FeishuCallbackDiagnosticTest` with
`-e mochiFeishuCallbackDiagnostic true` opens the phone's browser on a synthetic
localhost callback and checks receipt and port release. It does not contact
Feishu, exchange tokens, or modify provider settings. This proves local browser
return only, not acceptance of the redirect by a real Feishu app.

Task center and configuration-check regression coverage:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*AgentTaskRuntimeTest" `
  --tests "*AgentLinkTaskSnapshotTest" --tests "*ConfigurationCheckTest" `
  --tests "*ConfigurationDiagnosticsTest" --tests "*TaskCenterUiTest" `
  --tests "*TaskDashboardTest" --tests "*AgentScheduleTest" --no-daemon
```

Device acceptance should cover empty and restored task lists, foreground and
child cancellation, stopping a queued/running schedule without disabling the
next recurrence, Termux refresh/output/Stop/Forget, and AgentLink revocation,
unknown outcomes and native chat handoff. Confirm checks do not run on open,
show the quota disclosure, remain cancellable, and never change configuration.
Run model checks only with explicitly authorized test credentials; the JVM
suite verifies the fixed request and failure handling without network access.
Verify repair entry points and narrow-screen/large-font reachability.
The dashboard Compose tests also render English/Chinese synthetic fixtures to
`app/build/reports/task-dashboard-fixture.png` and
`app/build/reports/task-dashboard-zh-fixture.png`. These use the real Mochi
theme without user data; inspect them when changing task layout or typography.
`feature.home.TaskCenterSmokeTest` in the app instrumentation suite exercises
the real header, task center and diagnostic entry without starting a model
probe or changing Provider settings. It may perform the task center's normal
read-only refresh of existing connected tasks.

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

Receipt/next-wake regressions also cover `AgentLinkResultsTest`,
`DataStoreAgentLinkResultsTest`, `WakeBriefingTest` and `MochiHomeViewModelTest`.
Run with an updated AgentLink APK and Bridge supporting `task.read`. Device
acceptance must cover lost send replies, reconnect/restart, approval/completion,
permission/switch revocation, unread versus announced state, and exact linked-chat
handoff. Test immediate user speech, full local playback, ordinary interruption,
a second `Hi Mochi` (including a command immediately after it), echo-only capture,
unavailable offline TTS/AEC, all three STT providers, and no microphone overlap.
No attached device means these gates are outstanding, not proven by unit tests.

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

Document Tool catalog acceptance is an opt-in test:
build `:app:assembleDebugAndroidTest`, update the matching-signed app/test APKs
with `adb install -r`, then run
`adb shell am instrument -w -e class com.example.mochi_pet.core.tools.DocumentToolCatalogDiagnosticTest -e mochiDocumentToolDiagnostic true com.example.mochi_pet.test/androidx.test.runner.AndroidJUnitRunner`.
It applies the normal local default-policy migration and may rediscover schemas
using saved authorization. It never calls document read/write Tools, and logs
only built-in provider IDs, Tool names and enabled flags to `MochiDocumentTools`,
not tokens, document content or account information.

Mi Home television capability inspection is an explicit read-only device test:
build `:extensions:mijia:assembleDebugAndroidTest`, install the matching-signed
test APK with `adb install -r`, then run
`adb shell am instrument -w -e class com.example.mochi_mijia.TelevisionCapabilityDiagnosticTest -e mochiTvDiagnostic true com.example.mochi_pet.extension.mijia.test/androidx.test.runner.AndroidJUnitRunner`.
It uses the extension's existing authorization in-process and queries selected
televisions only. `MochiTvDiagnostic` logs public model/specification identifiers
and capability IDs, never credentials, home names or device IDs. It also reads
supported state to validate response identity, logging only counts/errors.
Without the opt-in it is skipped. The ordinary diagnostic does not control a
device. Only after explicit user authorization for a physical shutdown, add
`-e mochiTvPowerOff <exact-model>`; this requires exactly one selected television,
sends one power-off through the production Tool, and reports verification
without claiming visual confirmation. Ask the user to observe whether it
actually shuts down or only opens a menu. Never automatically repeat the write.

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
versioned directory together with `Mochi-Termux-Extension` and
`Mochi-WebDAV-Extension`. All three extension APKs
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

If Git tag discovery fails for a GitHub remote, local release scripts warn and
read the same remote tag namespace through the GitHub CLI REST API instead.
Both paths include reserved tags, not only published Releases; API failure
stops allocation rather than guessing a version. Run
`.\scripts\Test-AndroidReleaseVersion.ps1` to check this fallback without network
access. Git transfers use a bounded low-speed timeout; configure `gh`
authentication as above before relying on the API fallback.

### WebDAV acceptance

Run `:extensions:webdav:testDebugUnitTest :extensions:webdav:lintDebug
:extensions:webdav:assembleDebug`, plus the host ToolCatalog, extension-client,
setup-UI, database Skill and AgentOrchestrator tests. Local HTTPS fixtures cover
scope, XML, chunked/oversized bodies, conditional mutations, partial failure,
TLS trust, cancellation and staged-file lifetime; they do not prove live-provider
compatibility.

Before promotion, use user-owned test folders on Nutstore and a compatible
NAS/private cloud. Check valid-certificate HTTPS, LAN opt-in/denial, strong
ETag compatibility, read-only Skill gating, mutation switches, SAF import/export
byte equality, expired IDs, connection-change revocation and interrupted writes.
Exercise the configuration page in English/Chinese, at 320 dp and 200% font
scale, with keyboard and file-picker return. Never use real user files as
mutation fixtures, put passwords in command arguments, or clear app data.
Real-service/device acceptance remains pending until that evidence is recorded.

### Termux acceptance

Use matching-signed base and `extensions\termux\build\outputs\apk\debug\termux-debug.apk`
APKs, updating existing packages with `adb install -r`. Termux is installed
separately from its official channels; do not uninstall an existing Termux to
change its source. Follow Tools > Extensions > Termux > Configure.
The connector uses RUN_COMMAND callbacks (Termux 0.109+) and per-command log
control (0.118+); use a current compatible official Termux build.

Verify permission denial/revocation, initial stopped-package setup, callback
delivery with the host foreground, no App switch during background execution,
automatic execution without native/voice confirmation, normal/nonzero exit,
large stdout/stderr, timeout, Stop, process death, reconnect and retained task
inspection. Verify connected, enabled Termux works for foreground conversations,
Scheduled Agents and both Subagent roles with no separate background switch.
Check upgrades preserve provider/Tool selections and ignore/remove the old
background flag. Skill readiness follows each registry; permission/provider/
individual Tool revocation blocks subsequent calls, including stale registries
after re-enabling, without claiming to stop submitted work.
Mi Home and AgentLink must remain excluded from scheduled/subagent registries.
Update both base and Termux extension APKs for background-context support.
JVM and Linux tests do not establish real-device cross-App or
OEM background behavior. No connected Android device means that acceptance is
blocked, not passed.

For an opt-in connection diagnostic, build
`:extensions:termux:assembleDebug :extensions:termux:assembleDebugAndroidTest`,
update the matching-signed extension and its test APK with `adb install -r`,
and run:

```powershell
adb -s <device-id> shell am instrument -w `
  -e class com.example.mochi_termux.TermuxConnectionDiagnosticTest `
  -e mochiTermuxDiagnostic true `
  com.example.mochi_pet.extension.termux.test/androidx.test.runner.AndroidJUnitRunner
adb -s <device-id> logcat -d -s MochiTermuxCheck
```

This checks only a fixed utility list and installs/verifies the bundled helper.
It requires existing Termux command permission and external-access setup.
Only allowlisted availability markers are logged, never user files, raw
command errors, credentials, or model requests. Without explicit opt-in it is
skipped. Shell assets must retain LF line endings in Windows-built APKs;
the formatting gate rejects CR bytes before delivery.

To verify automatic execution across all three real Binder contexts, build
`:app:assembleDebug :app:assembleDebugAndroidTest`, update the matching-signed
base and test APKs in place, and run:

```powershell
adb -s <device-id> shell am instrument -w `
  -e class com.example.mochi_pet.platform.extensions.TermuxAutomaticExecutionTest `
  -e mochiTermuxExecutionDiagnostic true `
  com.example.mochi_pet.test/androidx.test.runner.AndroidJUnitRunner
```

The provider must already be connected and enabled; this diagnostic never
changes provider or Tool settings. It submits one fixed `printf` command per
foreground/scheduled/subagent registry, reads the callback-backed result, and
forgets only its own completed task records. It does not call a model or read
user files. This proves the real execution adapters, not voice recognition,
schedule timing, or LLM delegation.

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
