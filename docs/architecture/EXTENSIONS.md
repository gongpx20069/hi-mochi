# Optional Android Extension Architecture

## 1. Scope

Mochi extensions are separately installed Android APKs that provide optional
typed Tools without increasing every base APK. They are not Agent Skills,
downloaded scripts, remote MCP servers, dynamic-feature splits, or dynamically
loaded DEX/JAR files.

The first extension is **Mochi Mi Home Extension**, an optional unofficial
connector for selected Mi Home devices. The initial host accepts only explicit
official package IDs signed by the same release certificate as Mochi. A future
third-party extension ecosystem requires a separate trust and review design;
it is not implied by this contract.

## 2. Module and package layout

```text
android/
├── app/
├── extension-api/
└── extensions/
    └── mijia/
```

| Module | Application ID / namespace | Responsibility |
| --- | --- | --- |
| `:app` | `com.example.mochi_pet` | Host UI, trust validation, Tool adapter, trusted cards |
| `:extension-api` | `com.example.mochi_extension` | AIDL and immutable cross-process models |
| `:extensions:mijia` | `com.example.mochi_pet.extension.mijia` | QR login, Xiaomi cloud/MIoT, device Tools, images |

Both applications use `minSdk 26`, the same release version, and the same
signing key. The extension is one universal APK because it contains no native
ABI libraries. The host both defines and requests the shared signature-level
bind permission; defining the permission alone does not authorize the host to
start the protected extension Service or configuration Activity.

## 3. Installation and discovery

The Tools surface contains a stable Mi Home card even when the package is
absent. **Install extension** opens the repository's trusted latest Release
page; Android owns download and package-install confirmation. Mochi never
silently installs packages or requests broad package installation access.

The extension manifest has no `MAIN/LAUNCHER` activity. Its label and icon are
visible only in the system installer and Android application settings.

Before binding, the host validates:

1. exact application ID;
2. exact exported service class;
3. same signing-certificate lineage as the host;
4. the signature-level bind permission and protected configuration Activity.

Once those static checks pass, the card reports the package as installed and
trusted even if Android has not started its process yet. **Connect Mi Home**
remains available and launches the protected configuration Activity directly;
this is required on systems that keep a newly installed no-launcher package in
the stopped state or block its first background Binder start. Binder metadata
then validates the supported API version, minimum host version, identity, and
bounded Tool definitions before any Tool becomes available.

Package install, replacement, removal, force-stop, Binder death, and host
restart trigger rediscovery. No discovered extension is automatically enabled.

## 4. Binder contract

The shared API provides immutable Parcelable values and asynchronous AIDL
callbacks for:

- extension metadata and protocol version;
- connection state;
- bounded Tool definitions;
- Tool execution by request ID;
- cancellation by request ID;
- launching extension configuration through an explicit Activity intent;
- opening one bounded read-only attachment by opaque ID.

The configuration intent may carry only the host's resolved Chinese or English
UI language tag under the shared protocol constant. This keeps a separate
extension APK aligned with Mochi's in-app language selection without exposing
host settings or storage.

Binder calls never carry account passwords, service tokens, encryption keys,
raw cookies, image byte arrays, filesystem paths, executable code, Android
`Context`, Room entities, model prompts, or navigation directives.

Each request has a unique ID. Exactly one terminal callback is accepted.
Cancellation, timeout, Binder death, or stale Agent session invalidates later
callbacks. The service must not keep a Tool call running after cancellation.

## 5. Tool lifecycle

An extension Tool is eligible only after trust validation, protocol
negotiation, successful provider connection, provider enablement, and
individual Tool enablement. Definitions are loaded before each top-level Agent
registry is assembled so install, update, logout, and switch changes take
effect without restarting Mochi.

Mi Home keeps its aggregate provider disabled by default after connection, but
all child Tool definitions default to enabled. The first provider enablement
therefore activates all supported Tools while preserving the user's ability to
disable individual Tools afterward.

The host validates Tool names and JSON Schema, then wraps each remote definition
in an `ExtensionToolAdapter`. Arguments and results pass through the same
bounded typed envelopes as native Tools. Extensions cannot directly navigate,
render UI, call the model, register Skills, invoke native Tools, or see other
extension results.

The initial release excludes extension Tools from Scheduled Agents and
Subagents.

## 6. Mi Home authentication and storage

The Mi Home extension displays a Xiaomi Passport QR login challenge. The user
scans it using another phone already authenticated in Mi Home and confirms the
login. Password entry is not offered.

The extension owns and Keystore-encrypts the resulting session material. It
refreshes expiring service credentials, detects the account region by bounded
official-host probes, and removes its Tool definitions when authorization can
no longer be refreshed.

The connector is explicitly described as unofficial. Open-source code licenses
do not grant permission to Xiaomi cloud interfaces. The extension must not copy
Xiaomi's Home Assistant integration or imply Xiaomi sponsorship.

## 7. Initial device scope

Capabilities are derived from each device's MIoT specification and then reduced
through an application-controlled semantic allowlist.

### Common devices

The first implementation prioritizes devices whose public MIoT specification
maps cleanly to a small semantic contract:

- lights: power, brightness, and color temperature;
- switches and plugs: power;
- fans: power, mode, and fan level;
- air conditioners: power, mode, target temperature, and fan level;
- air purifiers and humidifiers: power, mode, target value, and fan level;
- curtains: open, close, stop, and target position;
- temperature, humidity, air-quality, contact, motion, and battery sensors:
  read-only state.

Every exposed operation requires the corresponding property/action access and
range or enum metadata in the fetched specification. Unknown identifiers and
private binary protocols are not guessed or forwarded.

### Television

Supported when declared by the device: state, power, input, volume, mute,
home/menu/settings/back, directional navigation, confirmation, play, and
pause. Generic message-router calls and arbitrary MIoT IDs are excluded.

### Camera

Readable camera state and explicitly allowed settings may be exposed.
Surveillance-affecting mutations require explicit intent and confirmation.
Storage mutation, live streams, playback, PTZ, audio, arbitrary stream tokens,
and generic actions are excluded.

The only image feature is the newest available motion or doorbell event image.
It is not a live snapshot.

### Scale

Only identity, connectivity, and battery state are exposed initially. BLE
measurement capture, cloud measurement history, user profiles, weight, body
fat, heart rate, and other body-composition data are excluded.

### Scenes

Only enabled manual scenes from user-selected homes are listed. Execution
requires exact same-run scene evidence, explicit intent, and confirmation.

Robot-vacuum maps and room cleaning, appliances whose useful behavior is only
available through private protocols, and unsupported categories remain
unavailable until they receive a separate typed contract and deterministic
fixtures.

## 8. Camera event attachments

The extension may retrieve the latest supported camera event metadata and
encrypted still image from Xiaomi cloud interfaces. Model and region support
vary; absence is an explicit `NOT_FOUND` or `PROVIDER_ERROR`, never an empty
success.

The extension decrypts into its private cache and returns:

- camera, home, and room identity;
- event type and capture time when available;
- MIME type and byte count;
- an opaque attachment ID with a short expiry.

On demand, the host opens a read-only `ParcelFileDescriptor`, enforces a 5 MiB
encoded limit and bounded decoded dimensions, decodes one JPEG/PNG locally,
normalizes it to JPEG with at most a 2048-pixel edge, 4,194,304 pixels, and
2 MiB, closes the descriptor, and causes the extension cache entry to be
deleted.
Expiry, disconnect, cancellation, package removal, and process death also
delete pending images.

The normalized image never enters Tool JSON, TTS, Room, conversation history,
Agent Memory, diagnostic logs, export, sharing, Browser, JavaScript, MCP,
or scheduled runs. It may be attached once to the current foreground
Main-Agent provider request when the provider's default-off image-input
permission is enabled and the camera Tool successfully returns it. The camera
Tool remains restricted to explicit requests to view, describe, or analyze the
latest event; the host does not repeat that intent decision with a brittle
query-keyword filter after the Tool has run. A run-local relay may then pass the
same normalized bytes to one explicitly requested serial Subagent, where they
appear only in a dedicated no-Tool multimodal prepass. The host rejects Tool
calls and raw image echo, then provides only bounded text observations to the
normal Subagent loop as JSON-escaped, explicitly untrusted user-role evidence
rather than system instructions. The Subagent receives no extension Tool,
file descriptor, attachment ID, URL, raw bytes, or forwarding capability. The
host does not infer visual support from a model name. Gallery export remains
out of scope.

## 9. Release and compatibility

The standard Android release builds and signs:

- five Mochi ABI APKs;
- one universal Mi Home extension APK, not split by native ABI;
- one shared SHA-256 manifest and release metadata file.

The publisher verifies application ID, embedded version, signature, absence of
a launcher entry, protocol version metadata, and hashes. The extension APK is
named `Mochi-Mijia-Extension-v<version>.apk`.

The protocol uses an integer major version plus additive capability flags.
Unknown optional fields are ignored. Major-version mismatch leaves the
extension disabled and shows an update requirement instead of attempting a
partial connection.
