<div align="center">

<img src="docs/assets/mochi-banner.svg" alt="Mochi — your Android AI companion" width="100%">

# Mochi

**Your Android AI companion for conversations, daily plans and getting things done.**

[**Download APK**](https://github.com/gongpx20069/hi-mochi/releases/latest)
· [Website](https://gongpx20069.github.io/hi-mochi/) · [Wiki](https://gongpx20069.github.io/hi-mochi/wiki/)
· [Watch introduction (Chinese)](https://www.bilibili.com/video/BV1u6aB6GEDD/)
· [English](README.md) · [简体中文](README.zh-CN.md)

**Android 8.0+ · Your choice of model · No Mochi account required**

[![CI](https://github.com/gongpx20069/hi-mochi/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/gongpx20069/hi-mochi/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-MIT-111827)](LICENSE)

</div>

## Get started

1. **Install** the [latest APK](https://github.com/gongpx20069/hi-mochi/releases/latest).
   Most phones use `arm64-v8a`; choose `universal` if unsure. Update in place without uninstalling.
2. **Connect your models.**

   **LLM:** open **Settings > AI connections**.
   **We recommend Groq for its free API tier:** [sign in](https://console.groq.com/),
   [get an API key](https://console.groq.com/keys), then choose a
   [Tool Use model](https://console.groq.com/docs/models).

   **Speech:** we recommend **iFlytek**. Get your AppID, APIKey and APISecret from the
   [console](https://console.xfyun.cn/), add them in **Settings > Speech and wake**,
   then save and select the account. Or use built-in Android speech without an extra key.
3. **Chat or talk.** Type or hold the input to dictate, then tap Send.
   Switch to **Companion** and say **“Hi Mochi”** for hands-free conversation.

Wake listening requires Companion to be visible with the screen unlocked.

## What you can do

| You want to… | Mochi can help |
| --- | --- |
| Chat or keep a desk companion | Text and voice conversations, expressions, full-screen clock, customizable personality |
| Organize your day | Calendar events, dated todos, reminders, recurring AI tasks and voice navigation between screens |
| Recall earlier conversations | Optional Daily journal and Memory Recall with links to original messages |
| Research and browse | Read links, browse manually, compare products, check Douban ratings and research the Magnificent Seven |
| Work with documents | Find, draft and update authorized [Notion](https://www.notion.com/help/notion-mcp), [Tencent Docs](https://docs.qq.com/open/auth/mcp.html) and [Feishu](https://open.feishu.cn/app) documents |
| Plan a trip | Research routes, weather, trains, flights and nearby businesses with [Amap](https://console.amap.com/) and the browser |
| Use phone apps | Operate allowed apps, request music, control playback and adjust media volume |

> “Summarize this page.” · “Remind me tomorrow at three.” · “Find a nearby café.”

Enable the **Skills** and **Tools** you need; the app shows any missing setup.
Browse the **skills.sh market** for community Skills, or add MCP and HTTPS REST tools.
Shopping and travel research do not place orders or bookings; stock research is not financial advice.

## Connect your services

### LLM Models

Configure in **Settings > AI connections**. Choose a tool-capable model;
presets fill in the endpoint. Other OpenAI-compatible APIs are also supported.

| Provider | Official setup links |
| --- | --- |
| **Groq · Recommended** | [API key](https://console.groq.com/keys) · [Models](https://console.groq.com/docs/models) |
| ModelScope | [Access Token](https://modelscope.cn/my/myaccesstoken) · [Model library](https://modelscope.cn/models) |
| OpenAI | [API key](https://platform.openai.com/api-keys) · [API guide](https://developers.openai.com/api/docs) |
| Azure OpenAI | [Create resource](https://portal.azure.com/#create/Microsoft.CognitiveServicesOpenAI) · [Guide](https://learn.microsoft.com/azure/ai-foundry/openai/) |
| DeepSeek | [API guide](https://api-docs.deepseek.com/) |
| Kimi | [API key guide](https://platform.kimi.com/docs/get-api-key) |
| GLM | [API guide](https://docs.bigmodel.cn/cn/guide/develop/openai/introduction) |
| MiniMax | [China API guide](https://platform.minimax.cn/docs/api-reference/text-openai-api) |
| Agnes-AI | [API guide](https://wiki.agnes-ai.com/en/docs/overview) |

### Speech Models

Configure in **Settings > Speech and wake**. Save and select your speech account;
cloud voice replies are a separate opt-in.

| Provider | Official setup links |
| --- | --- |
| **iFlytek · Recommended** | [Console](https://console.xfyun.cn/) · [Speech dictation guide](https://www.xfyun.cn/doc/asr/voicedictation/API.html) |
| Azure Speech | [Create resource](https://portal.azure.com/#create/Microsoft.CognitiveServicesSpeechServices) · [Quick start](https://learn.microsoft.com/azure/ai-services/speech-service/get-started-speech-to-text) |
| Android system speech | Built in; no extra API key |

## Optional extensions

Chat, voice, planner and browser work in the base app. Add only what you need.

| Integration | What it adds | Get it |
| --- | --- | --- |
| Mi Home Smart Home | Control selected devices, run scenes, view camera event images—not live video | [Mijia extension](https://github.com/gongpx20069/hi-mochi/releases) |
| AgentLink | Send coding tasks to authorized desktop workspaces and continue shared chats | [Android app and desktop Bridge](https://github.com/gongpx20069/android-agent-link) |
| Termux | Run local shell commands and inspect their output | [Termux extension](https://github.com/gongpx20069/hi-mochi/releases) + [Termux](https://github.com/termux/termux-app#installation) |
| WebDAV Files (preview) | Find, read, edit and transfer files in an allowed folder | [WebDAV extension](https://github.com/gongpx20069/hi-mochi/releases) |

Use matching, same-signed Mochi extension APKs and follow the connection guide in **Tools**.
Mi Home is an unofficial integration. Termux is a full shell, **not a sandbox**.

## Privacy and control

- **Local-first, not fully offline.** History and settings stay on your phone; necessary
  context and tool results go to your chosen model. Cloud speech uses its selected service.
- **You choose access.** App control requires accessibility permission; player access
  requires notification access. Daily journal and Memory Recall start off.
- **Protect credentials.** Keys use Keystore-backed encryption. Share connection links
  only with trusted people: they include access to API credentials.
- **Enable actions carefully.** Browser and Termux actions can run without per-action
  approval. Stopping Mochi does not undo changes or necessarily stop submitted jobs.

Voice, app compatibility and background timing depend on your device and services.
Do not rely on Mochi for critical alerts.

## Help

[Report an issue](https://github.com/gongpx20069/hi-mochi/issues) ·
[Documentation](docs/README.md) · [Build from source](docs/DEVELOPMENT.md) ·
[Contribute](CONTRIBUTING.md) · [MIT License](LICENSE)
