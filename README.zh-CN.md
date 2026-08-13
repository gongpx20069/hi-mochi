<div align="center">

<img src="docs/assets/mochi-banner.svg" alt="Mochi 原生 Android 语音 AI 助手" width="100%">

# Mochi

### 让你的手机，多一点生命力。

**强大、免费、开源的 Android 语音 AI 助手，也是一个让旧手机重新发挥价值的
改造计划。**

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#使用要求与当前状态)
[![Native Kotlin](https://img.shields.io/badge/Native-Kotlin-7F52FF?logo=kotlin&logoColor=white)](android)
[![Open Source](https://img.shields.io/badge/Open%20Source-Free-06B6D4)](#license)
[![CI](https://github.com/gongpx20069/hi-mochi/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/gongpx20069/hi-mochi/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-MIT-111827)](#license)

[English](README.md) · [简体中文](README.zh-CN.md) · [文档](docs/README.md)

</div>

---

Mochi 是一款使用原生 Android 技术构建，强大、免费且开源的个人语音 AI
助手。全天候随时说出 **“Hi Mochi”** 即可唤醒，并自然地继续免手持语音对话。
Mochi 将语音、记忆、规划、Tools 和 Agent Skills 集成在一起，还能随着对话
自动展示合适的界面与结果。你可以把 Mochi 安装在闲置或退役的 Android
手机上，将旧设备改造成常驻待命的 AI 伙伴、桌面信息屏、计划助手和智能家居
语音终端，而不是让仍可使用的硬件一直躺在抽屉里。

### 快速开始：选择正确的 APK

打开[最新 GitHub Release](https://github.com/gongpx20069/hi-mochi/releases/latest)，
根据设备选择安装包：

| 设备 | 应安装的 APK |
| --- | --- |
| 目前绝大多数 Android 手机和平板 | **`arm64-v8a`——推荐** |
| 较老的 32 位 ARM 手机和平板 | `armeabi-v7a` |
| 64 位 Android 模拟器或少见的 Intel 设备 | `x86_64` |
| 32 位 Android 模拟器或更早的 Intel 设备 | `x86` |
| 不清楚设备架构，或需要一个文件兼容不同设备 | `universal`——下载体积最大 |

1. 从上述发布页下载并安装与设备架构匹配的 APK。
2. 打开**设置**，填写 AI 提供商端点、模型名称和 API 密钥。
3. 启用你信任的 Tools 与 Skills，然后通过文字、麦克风或唤醒词开始交流。

各 ABI 专用 APK 与通用版功能完全相同，只是不包含其他 CPU 架构的本地语音库，
因此体积会小很多。开发者可通过
`adb shell getprop ro.product.cpu.abi` 查看已连接设备的架构。

### 全天候，随时语音唤醒

- 通过设备本地的 **“Hi Mochi”** 唤醒词随时唤醒 Mochi。
- 回答结束后自动继续聆听，让多轮语音对话更加自然。
- 默认使用 Android 语音识别，也可按需配置内置的讯飞或 Azure STT。
- 使用 Android 语音合成直接播报回答。
- 根据对话自动打开相关日期、天气、计划或结果页面。

### 不只是一个聊天窗口

| 语音优先 | 记住重要信息 | 真正处理工作 |
| --- | --- | --- |
| 全天候 “Hi Mochi” 语音唤醒和连续语音对话 | 在本地保存对话历史，并通过 ICU 分词的词法检索召回相关长期记忆 | 内置计划、定时任务、定位、天气、网页、地图、文档和本地计算 Tools |
| 默认使用 Android 语音识别，也可选配讯飞/Azure STT；语音输出使用 Android TTS，并支持文字输入 | 可直接编辑 `SOUL`、`USER` 和 `AGENTS` Persona 文件 | 通过可信卡片、原生导航以及串行 Researcher/Analyst Subagent 呈现有用结果 |

| 与 **Notion** 和 **腾讯文档** Cowork | 通过 Skill Market 持续扩展 |
| --- | --- |
| 将已授权工作区变成你的私人可读写知识库。Mochi 能查找你的资料、调研新主题、整理信息，与你共同撰写文档，并把成稿直接写回 **Notion** 或 **腾讯文档**。 | 从内置的 skills.sh 市场发现并安装社区 Agent Skills |

### 内置 Skills

| Skill | 默认状态 | 能力 | 所需配置 |
| --- | --- | --- | --- |
| Mochi Planner | 启用 | 管理 Mochi 日历事件和带日期的待办 | 无 |
| Voice Navigation | 启用 | 根据对话意图打开相关 Mochi 原生页面 | 无 |
| Scheduled Automations | 启用 | 执行一次性或周期 Agent 任务，并把结果写入 Conversation | 通知权限；精确闹钟权限可选 |
| Web Search | 启用 | 通过 Agent Browser 搜索公开网页和微信公众号内容 | 无 |
| Product Search | 启用 | 搜索并比较公开商品页面，不下单、不支付 | 无 |
| Douban Ratings | 启用 | 获取公开豆瓣评分、评分人数和评论主题 | 无 |
| US Stock Analysis | 启用 | 对比美股七姐妹的行情、资金、支撑/压力、评级、目标价、财务和新闻 | 无；使用百度股市通和公司官网公开页面 |
| **Notion Knowledge** | 禁用 | 与你一起调研、整理资料和撰写新页面，并在已授权的私人知识库中查找或更新信息 | [通过 Notion MCP OAuth 连接](https://www.notion.com/help/notion-mcp) |
| **腾讯文档 Knowledge** | 禁用 | 与你一起调研、整理资料和撰写新文档，并在已授权的私人知识库中查找或更新信息 | [获取腾讯文档 MCP Token](https://docs.qq.com/open/auth/mcp.html) |
| Travel & Transport | 禁用 | 在用户请求时使用当前位置，并搜索地点、规划路线、解析地址和查询目的地天气 | [申请百度地图 Agent Plan Service Key](https://lbs.baidu.com/apiconsole/agentplan) |
| Dianping Discovery | 禁用 | 在用户请求附近搜索时使用当前位置，并查询已授权的大众点评 POI 和官方详情 | [通过美团技术服务合作中心申请](https://developer.meituan.com/?applyFrom=dianping_c_pc_home) |

### 内置 Tools

| 分组 | 包含的 Tools | 能力与配置 |
| --- | --- | --- |
| **计划** | `manage_mochi_calendar`<br>`manage_mochi_todo` | 读取和更新 Mochi 自己的日历事件与带日期待办，无需额外配置。 |
| **自动化** | `manage_mochi_schedule` | 管理一次性与周期 Agent 任务；需要通知权限，精确闹钟权限可选。 |
| **设备上下文** | `get_current_location`<br>`get_current_weather` | 在权限允许时读取当前位置或本地天气；定位返回 WGS-84，并在中国境内同时返回 GCJ-02 坐标。 |
| **Agent Browser** | `browser_read` · `browser_navigate`<br>`browser_click` · `browser_input` · `browser_scroll` | 在一个用户可见、内容有界的 Android WebView 会话中研究公开 HTTPS 页面。 |
| **原生体验** | `navigate_mochi_ui`<br>`run_sandboxed_javascript` | 打开可信 Mochi 界面，或在本地运行有界的纯 JavaScript 计算。 |
| **百度地图 Agent Plan** | 地点 · 路线 · 地理编码<br>逆地理编码 · 天气 | 使用可信 GCJ-02 坐标搜索地点并规划驾车、步行、骑行或公交路线，需要 Service Key。 |
| **已连接 MCP** | **Notion** · **腾讯文档** · 大众点评<br>手动配置的 MCP Server | 检索私人知识、开展调研，并在已授权工作区中协作创建或更新文档。 |

Tools 页面会将 Agent Browser、Mochi 内建能力和 Provider Tools 分组并默认
收起。定时 Agent 仅获得只读 Browser 能力；前台对话还可点击和输入网页控件。
每个 Tool 都有独立开关；Provider 支持的 Tools 还要求对应 Provider 开关已启用。

### 串行 Subagent

Main Agent 可以把一个聚焦任务交给隔离的 **Researcher** 或 **Analyst**，
等待结果返回后再继续处理。每次请求最多串行运行两个 Child Agent，并且不会向
它们开放计划修改、设备定位、凭据、界面导航或其他仅限前台的能力。
Researcher 可使用已启用的 Browser 与经批准的只读 MCP Tools；Analyst 还可
使用本地 JavaScript 沙箱。

### 支持的 LLM Provider

| Provider | 配置 | 凭据 |
| --- | --- | --- |
| OpenAI | OpenAI Endpoint 和模型名称 | [OpenAI API Key](https://platform.openai.com/api-keys) |
| Azure OpenAI | Azure 资源 Endpoint、Deployment 名称和 API Version | [创建 Azure OpenAI 资源](https://portal.azure.com/#create/Microsoft.CognitiveServicesOpenAI) |
| 自定义 OpenAI 兼容 Provider | 用户填写 HTTPS Endpoint 和模型，服务需兼容 OpenAI Chat/Tool Call 协议 | 对应服务商签发的 API Key |

### 支持的 Speech Provider

| Provider | 默认 | 配置 |
| --- | --- | --- |
| Android 系统语音识别 | 是 | 无需 API 凭据；可用性取决于设备和已安装的语音识别服务 |
| 讯飞语音转文字 | 否 | 从[实时语音听写](https://www.xfyun.cn/services/voicedictation)申请 App ID、API Key 和 API Secret |
| Azure Speech-to-Text | 否 | 从 [Azure Speech 资源](https://portal.azure.com/#create/Microsoft.CognitiveServicesSpeechServices) 获取 Endpoint 和 API Key |

需要其他 LLM 或 Speech Provider？欢迎
[提交 Issue](https://github.com/gongpx20069/hi-mochi/issues/new)说明 Provider
及其 API 兼容性，或直接提交 Pull Request。

### 与 **Notion** 和 **腾讯文档** 一起 Cowork

Mochi 可将已授权的 **Notion** 或 **腾讯文档** 工作区连接为你的私人可读写
知识库，而不只是只读搜索源。它可以从你的文档中查找相关资料，结合已启用的
研究 Tools 调研新主题、收集并整理信息，再与你共同撰写新的页面或文档。
完成后，Mochi 会将成稿写回指定工作区，并通过官方 MCP 集成继续更新已有
知识。

内置 Skill Market 让 Mochi 的能力不受默认功能限制。你可以浏览热门 Skills、
搜索 skills.sh 生态、安装需要的能力，并在需要时启用它们。

> 启用 Skill 不会自动开启它所依赖的 Tools。

Mochi 默认跟随 Android 系统语言，也可以在设置中固定使用中文或英文。
每次打开应用时，Mochi 会检查最新稳定 GitHub Release；发现更高的 `1.0.x`
版本后由用户决定是否打开发布页下载。

已配置用户可以生成一个加密的 Provider 分享链接，将 LLM 与 Speech Provider
资源交给朋友使用。随机解密密钥包含在链接本身，因此无需另输密码，但任何拿到
完整链接的人都能使用对应 API 资源并消耗其额度。链接不包含 Persona、记忆、
Planner 数据、Tools 凭据或 Android 系统权限。

### 隐私从本地开始

Persona 文件、设置、对话、记忆、日历和待办默认保存在设备本地。AI 提供商
凭据使用 Android Keystore 支持的本地安全存储。

Conversation 中每条消息会在 **Mochi / 你** 标志旁显示本地保存的发送日期和
时间，包括恢复的历史消息和 Scheduled Agent 结果。

回答问题时，Mochi 会把必要的对话上下文发送给你配置的 AI 提供商。外部 Tool
只会在已启用的调用中收到完成任务所需的信息。调用 `get_current_location`
时，获得权限的坐标会作为 Tool 证据发送给你配置的 AI 提供商；你可以在 Tools
中单独关闭该能力。

### 使用要求与当前状态

- Android 8.0 或更高版本。
- OpenAI、Azure OpenAI 或兼容 AI 提供商的配置。
- 语音输入需要麦克风权限。
- 位置和通知权限仅在使用相关功能时需要。

稳定版本会以签名 APK 的形式通过 GitHub Releases 分发。Mochi 仍在积极开发
中；语音识别、唤醒、音频焦点、提醒和后台运行效果可能因设备及手机厂商而异。

---

## 文档

产品设计、技术架构、源码构建和贡献指南位于
[`docs/README.md`](docs/README.md)。

## 参与贡献

欢迎参与 Mochi 的开发。你可以通过
[GitHub Issues](https://github.com/gongpx20069/hi-mochi/issues) 提交 Bug、
功能建议和 Provider 需求，也可以通过
[Pull Requests](https://github.com/gongpx20069/hi-mochi/pulls) 贡献文档、
测试与代码。

提交 PR 前请阅读 [`CONTRIBUTING.md`](CONTRIBUTING.md)，保持改动聚焦，并
附上覆盖该改动的最小验证结果。

## License

MIT
