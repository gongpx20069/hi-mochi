<div align="center">

<img src="docs/assets/mochi-banner.svg" alt="Mochi — a native Android voice AI companion" width="100%">

# Mochi

**Turn an old Android phone into an always-ready, voice-first life agent.
Local-first data, BYOK intelligence, native actions.**

[**Download APK**](https://github.com/gongpx20069/hi-mochi/releases/latest)
· **Watch Demo: coming soon**
· [**Build from source**](docs/DEVELOPMENT.md)

**Android 8.0+** · **`arm64-v8a` recommended** ·
**OpenAI, Azure OpenAI, or compatible provider required**

> **Local-first, not fully offline:** Persona, conversations, memory, planner
> data, and credentials stay on-device by default. Model requests, optional
> cloud speech, and enabled external Tools use the providers you configure.

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#requirements--current-status)
[![Native Kotlin](https://img.shields.io/badge/Native-Kotlin-7F52FF?logo=kotlin&logoColor=white)](android)
[![Open Source](https://img.shields.io/badge/Open%20Source-Free-06B6D4)](#license)
[![CI](https://github.com/gongpx20069/hi-mochi/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/gongpx20069/hi-mochi/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-MIT-111827)](#license)

[English](README.md) · [简体中文](README.zh-CN.md) · [Documentation](docs/README.md)

</div>

---

Say **“Hi Mochi”** to remember something, research public information, plan
daily life, or open the right native screen. Native Android voice, memory,
planning, Tools, and Skills turn a spare phone into a dedicated companion,
desk display, and planner.

[Quick start](#quick-start-choose-the-right-apk) · [Configuration](#configuration) ·
[Core features](#core-features) · [Skills and Tools](#skills-and-tools) ·
[Optional extensions](#optional-extensions)

## Quick start: choose the right APK

Open the [latest GitHub Release](https://github.com/gongpx20069/hi-mochi/releases/latest)
and choose the APK that matches the device:

| Device | APK to install |
| --- | --- |
| Most current Android phones and tablets | **`arm64-v8a` — recommended** |
| Older 32-bit ARM phones and tablets | `armeabi-v7a` |
| 64-bit Android emulator or rare Intel device | `x86_64` |
| 32-bit Android emulator or older Intel device | `x86` |
| Architecture is unknown, or one file must support different devices | `universal` — largest download |

1. Download and install the matching APK from the release page above.
2. Open **Settings** and configure an
   [AI provider endpoint, model, and API key](#supported-llm-providers), plus
   an optional [Speech Provider](#supported-speech-providers).
3. Start a conversation by text or microphone; grant microphone permission
   for voice input and enable the **“Hi Mochi”** wake word when ready.
   Configure additional Tools and Skills only as you need them.

The ABI-specific APKs contain the same Mochi features as the universal APK;
they omit native speech libraries for other CPU architectures and are
therefore much smaller. Developers can check a connected device with
`adb shell getprop ro.product.cpu.abi`.

**Start with the base Mochi APK.** Optional extension APKs are not required
for conversation, voice, memory, or Mochi Planner. Update through the same
signing channel to preserve app data; do not uninstall just to upgrade.

## Configuration

### Supported LLM Providers

An LLM Provider is required. First launch opens Settings when none is configured;
Settings remains available from the top-right action.

| Provider | Configuration | Credentials |
| --- | --- | --- |
| OpenAI | OpenAI endpoint and model | [OpenAI API key](https://platform.openai.com/api-keys) |
| Azure OpenAI | Azure resource endpoint, **deployment name** as the model, and API version | [Azure OpenAI resource](https://portal.azure.com/#create/Microsoft.CognitiveServicesOpenAI) |
| Custom OpenAI-compatible | User-defined HTTPS endpoint and model using the OpenAI chat/tool-call protocol | API key issued by that provider |

### Supported Speech Providers

Open **Settings > Speech recognition and synthesis**. Android speech is the
default; cloud speech is optional and separate from the LLM configuration.

| Provider | Default | Configuration |
| --- | --- | --- |
| Android system speech | Yes | No API credential; recognition and offline TTS voices depend on installed device services |
| iFlytek | No | App ID, API Key, and API Secret from [Real-time Voice Dictation](https://www.xfyun.cn/services/voicedictation); manage synthesis access in the [iFlytek console](https://console.xfyun.cn/) |
| Azure Speech | No | Speech endpoint and API key from an [Azure Speech resource](https://portal.azure.com/#create/Microsoft.CognitiveServicesSpeechServices) |

For iFlytek or Azure, **Also use this provider for speech synthesis** reuses
the saved speech credentials for replies. It is off by default. Choose and
preview a voice, then **Save speech settings** to apply it. Cloud synthesis
sends reply text to that Provider; previews also consume its service quota.
Listed voices do not guarantee account access or free usage. Wake
acknowledgements remain local.

Need another LLM or Speech Provider? Please
[open an issue](https://github.com/gongpx20069/hi-mochi/issues/new) describing
the provider and API compatibility, or submit a pull request.

### Tools and Skills setup

Every Tool has an independent switch. Provider-backed Tools also require a
connected, enabled provider. Enable only the capabilities you want to use.
Skills describe workflows; enabling a Skill does not automatically enable
its dependencies. The Skills screen names any missing Tool groups.

For maps and merchant discovery, create an [Amap Web Service Key](https://console.amap.com/).
Select **Web Service**, not Android: no release/debug SHA1 is required.
Mochi's optional Security Key is not a SHA1 value; enter it only when digital
signatures are enabled in the Amap console.

For document collaboration, use [Notion MCP OAuth](https://www.notion.com/help/notion-mcp)
or a [Tencent Docs MCP token](https://docs.qq.com/open/auth/mcp.html), then
enable the corresponding Tools and Skill.

### Language, updates, and Provider sharing

Mochi follows the Android system language by default and can be fixed to
English or Chinese in Settings. It checks the latest stable GitHub Release
each time it opens; you decide whether to download an available update.

**Share Providers** lets you choose which connections to include. LLM and
Speech connections start selected; Amap, Tencent Docs, and manual MCP Tool
credentials are optional and start unselected. The encrypted link also contains
its decryption key: **anyone holding the full link can use the selected API
resources and consume their quota**. Persona, memories, planner data, Notion
OAuth, Mi Home sessions, and Android permissions are not shared.

## Core features

### Always ready for your voice

- Wake Mochi anytime with the on-device **“Hi Mochi”** wake word.
- Continue speaking naturally through automatic follow-up listening.
- Use Android speech recognition by default, or optionally connect built-in
  iFlytek/Azure Speech-to-Text settings for greater reliability.
- Hear responses through Android text-to-speech or opt-in iFlytek/Azure synthesis.
- Let the conversation open the relevant date, weather, planner, or result
  screen automatically.

### More than a chat screen

| Voice first | Remember what matters | Get real work done |
| --- | --- | --- |
| Always-ready “Hi Mochi” wake word and continuous voice conversation | Local conversation history and ICU-tokenized lexical memory recall | Built-in Tools for planning, schedules, location, weather, web, maps, documents, and local calculations |
| Android speech by default, with optional iFlytek/Azure recognition and synthesis; text input remains available | Editable `SOUL`, `USER`, and `AGENTS` persona files | Trusted cards, native navigation, and serial Researcher/Analyst Subagents surface useful results |

| Cowork with **Notion** and **Tencent Docs** | Expand through the Skill Market |
| --- | --- |
| Turn your authorized workspaces into private, writable knowledge bases. Mochi can find your material, research new topics, organize sources, cowork with you to draft documents, and publish the finished work back into **Notion** or **Tencent Docs**. | Discover and install community Agent Skills from the built-in skills.sh market |

### Cowork with **Notion** and **Tencent Docs**

Mochi connects your authorized **Notion** or **Tencent Docs** workspace as a
private, writable knowledge base—not merely a read-only search source. It can
find relevant material from your own documents, use enabled research Tools to
investigate new topics, collect and organize sources, and cowork with you to
draft a new page or document. Mochi can then write the finished work back to
the selected workspace and continue updating it through the official MCP
integration.

### Serial Subagents

The Main Agent can delegate a focused task to an isolated **Researcher** or
**Analyst** and receive the result before continuing. Delegation is serial,
limited to two child runs per request, and never grants a child access to
planner mutations, device location, credentials, UI navigation, or other
foreground-only capabilities. Researcher uses enabled Browser and approved
read-only MCP Tools; Analyst can additionally use the local JavaScript
sandbox. Both roles may also use Termux after you explicitly enable the
separate [Background Shell authorization](#run-local-commands-with-termux).

## Skills and Tools

### Built-in Skills

| Skill | Default | What it does | Required setup |
| --- | --- | --- | --- |
| Mochi Planner | Enabled | Manages Mochi calendar events and dated todos | None |
| Voice Navigation | Enabled | Opens the relevant native Mochi surface from conversation intent | None |
| Scheduled Automations | Enabled | Runs one-time or recurring Agent prompts and writes results to Conversation | Notification permission; exact-alarm access is optional |
| Web Search | Enabled | Researches public web and WeChat official-account content through Agent Browser | None |
| Product Search | Enabled | Finds and compares public product pages without ordering or payment | None |
| Douban Ratings | Enabled | Reads public Douban ratings, counts, and review themes | None |
| US Stock Analysis | Enabled | Compares the Magnificent Seven using quotes, capital flow, support/resistance, ratings, targets, financials, and news | None; uses public Baidu Stock and issuer pages |
| **Notion Knowledge** | Disabled | Coworks with you to research, organize material, draft new pages, and find or update information in your authorized private knowledge base | [Connect through Notion MCP OAuth](https://www.notion.com/help/notion-mcp) |
| **Tencent Docs Knowledge** | Disabled | Coworks with you to research, organize material, draft new documents, and find or update information in your authorized private knowledge base | [Get a Tencent Docs MCP token](https://docs.qq.com/open/auth/mcp.html) |
| Travel Planning | Enabled | Uses Amap for grounded routes and weather, then Agent Browser to research public no-login train or flight options without booking | [Create an Amap Web Service Key](https://console.amap.com/); enable Agent Browser |
| Merchant Discovery | Enabled | Finds and compares merchants using available Amap ratings, average cost, hours, phone, tags, and photos | [Create an Amap Web Service Key](https://console.amap.com/) |

Skills requiring separately installed apps are listed under
[Optional extensions](#optional-extensions), not required for the core setup.

### Built-in Tools

| Group | Included Tools | Purpose and setup |
| --- | --- | --- |
| **Planner** | `manage_mochi_calendar`<br>`manage_mochi_todo` | Read and update Mochi-owned events and dated todos. No additional setup. |
| **Automations** | `manage_mochi_schedule` | Manage one-time and recurring Agent prompts. Notification permission is required; exact-alarm access is optional. |
| **Device context** | `get_current_location`<br>`get_current_weather` | Read permission-gated location or local weather. Location returns WGS-84 and, inside China, GCJ-02 coordinates. |
| **Agent Browser** | `browser_read` · `browser_navigate`<br>`browser_click` · `browser_input` · `browser_scroll` | Research public HTTPS pages in one visible, bounded Android WebView session. |
| **Native UX** | `navigate_mochi_ui`<br>`run_sandboxed_javascript` | Open trusted Mochi surfaces or run bounded pure JavaScript calculations locally. |
| **Amap Maps** | Place search · Merchant details · Routes<br>Geocoding · Reverse geocoding · Weather | Search places and merchants, compare available ratings and average cost, and plan routes with trusted GCJ-02 coordinates. Requires a Web Service Key. |
| **Connected MCP** | **Notion** · **Tencent Docs**<br>Manually configured MCP servers | Search private knowledge, conduct research, and collaboratively create or update documents in authorized workspaces. |

Agent Browser, Mochi built-ins, and provider Tool details are grouped and
collapsed by default in Tools. Scheduled runs receive only the read-only
Browser subset; foreground conversations may also click and enter page data.
Every Tool has an independent switch, and provider-backed Tools also require
their provider switch to be enabled.

Travel Planning uses the normal visible controls on public HTTPS pages. Train
research starts from the official 12306 query page; flight research prefers
official airline sites. Mochi never logs in, bypasses verification, enters
passenger or payment data, or continues into booking. It stops when a site
requires authentication, CAPTCHA, identity verification, or checkout.

### Skill Market

The built-in Skill Market makes Mochi extensible beyond its default
capabilities. Browse trending Skills, search the skills.sh ecosystem, install
the ones you need, and enable them when you want Mochi to use them.

> Enabling a Skill never enables its required Tools automatically.

## Privacy by design

Persona files, settings, conversations, memories, calendar items, and todos
stay on the device by default. Provider credentials use Android
Keystore-backed local storage.

Conversation bubbles show the locally stored send date and time beside
**Mochi** or **You**, including restored history and Scheduled Agent results.

When answering, Mochi sends the necessary conversation context to the AI
provider you configured. An external Tool receives only the information needed
for an enabled Tool call. When `get_current_location` is called, its
permission-gated coordinates are included in Tool evidence sent to that
configured AI provider; the Tool can be disabled independently in Tools.

## Requirements & current status

- Android 8.0 or newer.
- An OpenAI, Azure OpenAI, or compatible provider configuration.
- Microphone permission for voice input.
- Optional location and notification permissions for related features.

Stable builds are distributed as signed APKs through GitHub Releases. Mochi
remains under active development; speech recognition, wake behavior, audio
focus, reminders, and background operation can vary by device and
manufacturer.

## Optional extensions

These integrations add capabilities to Mochi but are **not required for its
core features**. Install only what you need. Mi Home and Termux use optional
Mochi-signed extension APKs; AgentLink connects through its independent
companion app. Mi Home and AgentLink are foreground Main-Agent integrations;
Termux can also serve Subagents and Scheduled Agents with separate explicit
background authorization, disabled by default.

| Integration | Built-in Skill (default) | Additional capabilities | Installation |
| --- | --- | --- | --- |
| Mi Home | Mi Home Smart Home — disabled | Selected device state/control, manual scenes, latest camera event images | [Mochi Releases](https://github.com/gongpx20069/hi-mochi/releases): `Mochi-Mijia-Extension` |
| [AgentLink](https://github.com/gongpx20069/android-agent-link) | AgentLink — disabled | `agentlink_workspace` · `agentlink_chat` · `agentlink_control` for shared remote coding chats | [AgentLink Releases](https://github.com/gongpx20069/android-agent-link/releases) |
| Termux | Termux — disabled | `termux_exec` · `termux_task` for unrestricted local shell and task management | [Mochi Releases](https://github.com/gongpx20069/hi-mochi/releases): `Mochi-Termux-Extension`, plus [official Termux](https://github.com/termux/termux-app#installation) |

### Mi Home smart-home devices

Install `Mochi-Mijia-Extension` from the same Release/signing channel as Mochi.
Open **Tools > Extensions > Mi Home > Connect Mi Home**, scan the QR code
using another phone already signed into Mi Home, and confirm the connection.
Select homes and supported devices, then enable the provider, desired Tools,
and **Mi Home Smart Home** Skill.

This is an unofficial connector. Capabilities depend on each selected device;
unsupported actions are not exposed. Camera images are the latest available
cloud events, **not a live view**. Xiaomi credentials stay in the extension;
Mochi never asks you to paste an account password.

### Control shared AgentLink coding chats

Download the Android APK from [AgentLink Releases](https://github.com/gongpx20069/android-agent-link/releases)
(currently marked **Pre-release**). See the [AgentLink repository](https://github.com/gongpx20069/android-agent-link)
for desktop Bridge setup and connection instructions.
After installing and connecting AgentLink, open **Tools > AgentLink > Connect/Open AgentLink**.
Approve the scoped access request in AgentLink and return to Mochi. Enable the
provider, its three switches (`agentlink_workspace`, `agentlink_chat`,
`agentlink_control`), and the built-in **AgentLink** Skill.
Mochi can discover authorized machines/workspaces, create or continue the same
CLI/App chat, read changes, send work and request cancellation/configuration.
Linked chat buttons open the trusted native AgentLink view; Refresh and
Manage access/Revoke remain available in Tools.

Remote tasks continue when Mochi closes. Human CLI/App changes override stale
automation: conflicts stop follow-ups instead of retrying. Credentials remain
in AgentLink, never Mochi exports/share. No browser/network bypass or default
Subagent/Scheduled Agent access is provided. Linked chats are not cached-live
task monitors; read again to inspect current state.

### Run local commands with Termux

Install `Mochi-Termux-Extension` from the same Release/signing channel as Mochi
and install [official Termux](https://github.com/termux/termux-app#installation)
separately. Open **Tools > Extensions > Termux > Configure**. Copy the visible
one-time configuration command into Termux, grant command permission, then
connect/test and return to **Enable tools and Skill**.

Speak normally to Mochi; approved commands run in the background without an App
switch. Foreground Main-Agent calls require approval for one call or the current
task. To allow **all Scheduled Agents and Subagents** to execute automatically,
enable **Background Shell authorization** in the Termux card and confirm the
warning. This is a separate, default-off permission that survives restarts;
**Enable tools and Skill** does not grant it. It also covers children delegated
from a foreground conversation, but never skips Main-Agent approval.
Turning it off blocks subsequent calls; disabling/disconnecting Termux clears
the permission. Keep both Mochi and its Termux extension updated.

This is unrestricted shell under Termux permissions, not root or a sandbox.
Agent-visible output is sent to your chosen model Provider.
**Termux tasks** lets you inspect output locally, refresh, stop and clean up
completed tasks. Closing Mochi does not stop submitted commands, Android can
kill background work, and detached processes may survive a stop request.

---

## Documentation

Product design, architecture, source builds, and contribution guidance live in
[`docs/README.md`](docs/README.md).

## Contribute

Contributions are welcome. Bug reports, feature proposals, provider requests,
documentation improvements, tests, and code changes can be submitted through
[GitHub Issues](https://github.com/gongpx20069/hi-mochi/issues) and
[Pull Requests](https://github.com/gongpx20069/hi-mochi/pulls).

Before opening a PR, read [`CONTRIBUTING.md`](CONTRIBUTING.md), keep changes
focused, and include the smallest relevant verification.

## License

[MIT](LICENSE)
