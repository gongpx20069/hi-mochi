<div align="center">

<img src="docs/assets/mochi-banner.svg" alt="Mochi — a native Android voice AI companion" width="100%">

# Mochi

### Your phone, now a little more alive.

**Turn an old Android phone into an always-ready, voice-first life agent.
Local-first data, BYOK intelligence, native actions.**

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#requirements--current-status)
[![Native Kotlin](https://img.shields.io/badge/Native-Kotlin-7F52FF?logo=kotlin&logoColor=white)](android)
[![Open Source](https://img.shields.io/badge/Open%20Source-Free-06B6D4)](#license)
[![CI](https://github.com/gongpx20069/hi-mochi/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/gongpx20069/hi-mochi/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-MIT-111827)](#license)

[English](README.md) · [简体中文](README.zh-CN.md) · [Documentation](docs/README.md)

</div>

---

Mochi is a free and open-source, voice-first life agent built natively for
Android. Data stays on the device by default, and you choose the model through
your own provider key. Say **“Hi Mochi”** to remember something, research
public information, plan daily life, or open the right native screen. Install
it on a spare or retired Android phone to create a dedicated always-ready
companion, desk display, planner, and smart-home voice terminal instead of
leaving useful hardware in a drawer.

### Quick start: choose the right APK

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
3. Enable the [Tools](#built-in-tools) and [Skills](#built-in-skills) you
   trust, then talk by text, microphone, or wake trigger.

The ABI-specific APKs contain the same Mochi features as the universal APK;
they omit native speech libraries for other CPU architectures and are
therefore much smaller. Developers can check a connected device with
`adb shell getprop ro.product.cpu.abi`.

### Always ready for your voice

- Wake Mochi anytime with the on-device **“Hi Mochi”** wake word.
- Continue speaking naturally through automatic follow-up listening.
- Use Android speech recognition by default, or optionally connect built-in
  iFlytek/Azure Speech-to-Text settings for greater reliability.
- Hear responses through Android text-to-speech.
- Let the conversation open the relevant date, weather, planner, or result
  screen automatically.

### More than a chat screen

| Voice first | Remember what matters | Get real work done |
| --- | --- | --- |
| Always-ready “Hi Mochi” wake word and continuous voice conversation | Local conversation history and ICU-tokenized lexical memory recall | Built-in Tools for planning, schedules, location, weather, web, maps, documents, and local calculations |
| Android speech recognition by default, with optional built-in iFlytek/Azure STT settings; Android TTS and text input remain available | Editable `SOUL`, `USER`, and `AGENTS` persona files | Trusted cards, native navigation, and serial Researcher/Analyst Subagents surface useful results |

| Cowork with **Notion** and **Tencent Docs** | Expand through the Skill Market |
| --- | --- |
| Turn your authorized workspaces into private, writable knowledge bases. Mochi can find your material, research new topics, organize sources, cowork with you to draft documents, and publish the finished work back into **Notion** or **Tencent Docs**. | Discover and install community Agent Skills from the built-in skills.sh market |

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

> **Amap setup:** Select **Web Service** as the key platform, not Android.
> Web Service keys do not require release or debug SHA1 fingerprints. Mochi's
> optional Security Key is not a SHA1 value; enter it only when digital
> signatures are enabled in the Amap console.

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

### Serial Subagents

The Main Agent can delegate a focused task to an isolated **Researcher** or
**Analyst** and receive the result before continuing. Delegation is serial,
limited to two child runs per request, and never grants a child access to
planner mutations, device location, credentials, UI navigation, or other
foreground-only capabilities. Researcher uses enabled Browser and approved
read-only MCP Tools; Analyst can additionally use the local JavaScript
sandbox.

### Supported LLM Providers

| Provider | Configuration | Credentials |
| --- | --- | --- |
| OpenAI | OpenAI endpoint and model | [OpenAI API key](https://platform.openai.com/api-keys) |
| Azure OpenAI | Azure resource endpoint, deployment name, and API version | [Azure OpenAI resource](https://portal.azure.com/#create/Microsoft.CognitiveServicesOpenAI) |
| Custom OpenAI-compatible | User-defined HTTPS endpoint and model using the OpenAI chat/tool-call protocol | API key issued by that provider |

### Supported Speech Providers

| Provider | Default | Configuration |
| --- | --- | --- |
| Android system speech recognition | Yes | No API credential; availability depends on the device and installed recognition service |
| iFlytek Speech-to-Text | No | App ID, API Key, and API Secret from [Real-time Voice Dictation](https://www.xfyun.cn/services/voicedictation) |
| Azure Speech-to-Text | No | Speech endpoint and API key from an [Azure Speech resource](https://portal.azure.com/#create/Microsoft.CognitiveServicesSpeechServices) |

Need another LLM or Speech Provider? Please
[open an issue](https://github.com/gongpx20069/hi-mochi/issues/new) describing
the provider and API compatibility, or submit a pull request.

### Cowork with **Notion** and **Tencent Docs**

Mochi connects your authorized **Notion** or **Tencent Docs** workspace as a
private, writable knowledge base—not merely a read-only search source. It can
find relevant material from your own documents, use enabled research Tools to
investigate new topics, collect and organize sources, and cowork with you to
draft a new page or document. Mochi can then write the finished work back to
the selected workspace and continue updating it through the official MCP
integration.

The built-in Skill Market makes Mochi extensible beyond its default
capabilities. Browse trending Skills, search the skills.sh ecosystem, install
the ones you need, and enable them when you want Mochi to use them.

> Enabling a Skill never enables its required Tools automatically.

Mochi follows the Android system language by default and can also be fixed to
English or Chinese.

Mochi checks the latest stable GitHub Release each time it opens and lets the
user decide whether to open the release page when a newer `1.0.x` build exists.

A configured user can create an encrypted Provider share link for a friend.
The link contains both the encrypted LLM/Speech credentials and its random
decryption key, so no separate password is required. Anyone holding the full
link can use those API resources and consume their quota. Persona, memories,
planner data, Tool credentials, and Android permissions are not included.

### Privacy by design

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

### Requirements & current status

- Android 8.0 or newer.
- An OpenAI, Azure OpenAI, or compatible provider configuration.
- Microphone permission for voice input.
- Optional location and notification permissions for related features.

Stable builds are distributed as signed APKs through GitHub Releases. Mochi
remains under active development; speech recognition, wake behavior, audio
focus, reminders, and background operation can vary by device and
manufacturer.

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
