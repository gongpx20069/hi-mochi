<div align="center">

<img src="docs/assets/mochi-banner.svg" alt="Mochi 原生 Android 语音 AI 助手" width="100%">

# Mochi

**把闲置 Android 手机变成随时待命的开源语音生活 Agent：数据默认留在本机，
模型由你选择，说话就能记事、查资料、规划生活并打开正确的原生界面。**

[**下载 APK**](https://github.com/gongpx20069/hi-mochi/releases/latest)
· **观看演示：即将提供**
· [**从源码构建**](docs/DEVELOPMENT.md)

**Android 8.0+** · **推荐 `arm64-v8a`** ·
**需要 OpenAI、Azure OpenAI 或兼容 Provider**

> **本地优先，但并非完全离线：** Persona、对话、记忆、计划数据和凭据默认
> 保留在设备上；模型请求、可选云端语音及已启用的外部 Tools 会使用你配置的
> 服务。

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#使用要求与当前状态)
[![Native Kotlin](https://img.shields.io/badge/Native-Kotlin-7F52FF?logo=kotlin&logoColor=white)](android)
[![Open Source](https://img.shields.io/badge/Open%20Source-Free-06B6D4)](#license)
[![CI](https://github.com/gongpx20069/hi-mochi/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/gongpx20069/hi-mochi/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-MIT-111827)](#license)

[English](README.md) · [简体中文](README.zh-CN.md) · [文档](docs/README.md)

</div>

---

说出 **“Hi Mochi”**，就能让它记事、查询公开资料、规划日常生活并打开正确的
原生界面。原生 Android 语音、记忆、计划、Tools 和 Skills 能把闲置手机变成
常驻待命的伙伴、桌面信息屏和计划助手。

[快速开始](#快速开始选择正确的-apk) · [配置指南](#配置指南) ·
[核心功能](#核心功能) · [Skills 与 Tools](#skills-与-tools) ·
[可选扩展](#可选扩展)

## 快速开始：选择正确的 APK

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
2. 打开**设置**，配置
   [AI Provider 端点、模型名称和 API 密钥](#支持的-llm-provider)，并按需设置
   [Speech Provider](#支持的-speech-provider)。
3. 通过文字或麦克风开始交流；语音输入需授予麦克风权限，准备好后可启用
   **“Hi Mochi”** 唤醒词。其他 Tools 和 Skills 按需配置即可。

各 ABI 专用 APK 与通用版功能完全相同，只是不包含其他 CPU 架构的本地语音库，
因此体积会小很多。开发者可通过
`adb shell getprop ro.product.cpu.abi` 查看已连接设备的架构。

**先安装 Mochi 主应用 APK 即可。** 对话、语音、记忆和 Mochi Planner 不要求
安装任何扩展 APK。升级时使用相同签名渠道的安装包覆盖更新，保留应用数据，
不要为了升级而先卸载。

## 配置指南

### 支持的 LLM Provider

LLM Provider 是必需配置。未配置时，首次启动会打开设置；之后也可以从右上角
随时进入设置。

| Provider | 配置 | 凭据 |
| --- | --- | --- |
| OpenAI | OpenAI Endpoint 和模型名称 | [OpenAI API Key](https://platform.openai.com/api-keys) |
| Azure OpenAI | Azure 资源 Endpoint、以 **Deployment 名称**填写模型、API Version | [创建 Azure OpenAI 资源](https://portal.azure.com/#create/Microsoft.CognitiveServicesOpenAI) |
| 自定义 OpenAI 兼容 Provider | 用户填写 HTTPS Endpoint 和模型，服务需兼容 OpenAI Chat/Tool Call 协议 | 对应服务商签发的 API Key |

### 支持的 Speech Provider

进入**设置 > 语音识别与合成**。默认使用 Android 语音；云端语音是可选项，
与 LLM Provider 分开配置。

| Provider | 默认 | 配置 |
| --- | --- | --- |
| Android 系统语音 | 是 | 无需 API 凭据；语音识别和离线合成音色取决于设备已安装的服务 |
| 讯飞 | 否 | 从[实时语音听写](https://www.xfyun.cn/services/voicedictation)申请 App ID、API Key 和 API Secret；在[讯飞控制台](https://console.xfyun.cn/)管理语音合成权限 |
| Azure Speech | 否 | 从 [Azure Speech 资源](https://portal.azure.com/#create/Microsoft.CognitiveServicesSpeechServices) 获取 Endpoint 和 API Key |

选择讯飞或 Azure 后，可打开默认关闭的**同时用于语音合成**，复用已保存的语音
凭据播报回答。选择并试听音色后，点击**保存语音设置**才会生效。云端合成会把
回答文本发送给对应 Provider，试听也会消耗服务额度；音色出现在列表中不代表
账号已获授权或可以免费使用。唤醒回应仍使用本地语音。

需要其他 LLM 或 Speech Provider？欢迎
[提交 Issue](https://github.com/gongpx20069/hi-mochi/issues/new)说明 Provider
及其 API 兼容性，或直接提交 Pull Request。

### Tools 与 Skills 配置

每个 Tool 都有独立开关，Provider 支持的 Tools 还要求对应服务已连接并启用。
只开启你需要的能力。Skill 描述执行流程，启用 Skill 不会自动开启依赖；
Skills 页面会提示尚未就绪的 Tool 分组。

地图与商家发现需要[高德 Web 服务 Key](https://console.amap.com/)。
创建时请选择 **Web 服务**，不是 Android；不需要发布版或调试版 SHA1。
Mochi 中可选的“安全密钥”也不是 SHA1，仅在高德控制台启用数字签名时填写。

文档协作可使用 [Notion MCP OAuth](https://www.notion.com/help/notion-mcp)
或[腾讯文档 MCP Token](https://docs.qq.com/open/auth/mcp.html)，再启用对应
Tools 和 Skill。

### 语言、更新与 Provider 分享

Mochi 默认跟随 Android 系统语言，也可以在设置中固定使用中文或英文。
每次打开应用时会检查最新稳定 GitHub Release，由你决定是否下载更新。

**分享 Providers**会先让你选择要包含的连接。LLM 与 Speech 默认选中；
高德、腾讯文档和手动配置的 MCP 工具凭据是可选项，默认不选中。
加密链接本身包含解密密钥：**任何拿到完整链接的人都能使用所选 API 资源并
消耗其额度**。Persona、记忆、Planner 数据、Notion OAuth、米家会话和
Android 系统权限不会被分享。

## 核心功能

### 全天候，随时语音唤醒

- 通过设备本地的 **“Hi Mochi”** 唤醒词随时唤醒 Mochi。
- 回答结束后自动继续聆听，让多轮语音对话更加自然。
- 默认使用 Android 语音识别，也可按需配置内置的讯飞或 Azure STT。
- 使用 Android 语音合成播报回答，也可按需启用讯飞/Azure 合成。
- 根据对话自动打开相关日期、天气、计划或结果页面。

### 不只是一个聊天窗口

| 语音优先 | 记住重要信息 | 真正处理工作 |
| --- | --- | --- |
| 全天候 “Hi Mochi” 语音唤醒和连续语音对话 | 在本地保存对话历史，并通过 ICU 分词的词法检索召回相关长期记忆 | 内置计划、定时任务、定位、天气、网页、地图、文档和本地计算 Tools |
| 默认使用 Android 语音，也可选配讯飞/Azure 识别与合成，并支持文字输入 | 可直接编辑 `SOUL`、`USER` 和 `AGENTS` Persona 文件 | 通过可信卡片、原生导航以及串行 Researcher/Analyst Subagent 呈现有用结果 |

| 与 **Notion** 和 **腾讯文档** Cowork | 通过 Skill Market 持续扩展 |
| --- | --- |
| 将已授权工作区变成你的私人可读写知识库。Mochi 能查找你的资料、调研新主题、整理信息，与你共同撰写文档，并把成稿直接写回 **Notion** 或 **腾讯文档**。 | 从内置的 skills.sh 市场发现并安装社区 Agent Skills |

### 与 **Notion** 和 **腾讯文档** 一起 Cowork

Mochi 可将已授权的 **Notion** 或 **腾讯文档** 工作区连接为你的私人可读写
知识库，而不只是只读搜索源。它可以从你的文档中查找相关资料，结合已启用的
研究 Tools 调研新主题、收集并整理信息，再与你共同撰写新的页面或文档。
完成后，Mochi 会将成稿写回指定工作区，并通过官方 MCP 集成继续更新已有
知识。

### 串行 Subagent

Main Agent 可以把一个聚焦任务交给隔离的 **Researcher** 或 **Analyst**，
等待结果返回后再继续处理。每次请求最多串行运行两个 Child Agent，并且不会向
它们开放计划修改、设备定位、凭据、界面导航或其他仅限前台的能力。
Researcher 可使用已启用的 Browser 与经批准的只读 MCP Tools；Analyst 还可
使用本地 JavaScript 沙箱。单独开启[后台 Shell 授权](#使用-termux-执行本机命令)后，
两种子 Agent 还可使用 Termux。

## Skills 与 Tools

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
| 出行规划 | 启用 | 使用高德规划可信路线与查询天气，再通过 Agent Browser 调研无需登录的公开火车票或机票信息，但不进行预订 | [创建高德 Web 服务 Key](https://console.amap.com/)；启用 Agent Browser |
| 商家发现 | 启用 | 使用高德提供的评分、人均、营业时间、电话、标签和图片发现并比较商家 | [创建高德 Web 服务 Key](https://console.amap.com/) |

需要另行安装应用的 Skills 集中列在后面的[可选扩展](#可选扩展)中，
不属于首次使用的必需配置。

### 内置 Tools

| 分组 | 包含的 Tools | 能力与配置 |
| --- | --- | --- |
| **计划** | `manage_mochi_calendar`<br>`manage_mochi_todo` | 读取和更新 Mochi 自己的日历事件与带日期待办，无需额外配置。 |
| **自动化** | `manage_mochi_schedule` | 管理一次性与周期 Agent 任务；需要通知权限，精确闹钟权限可选。 |
| **设备上下文** | `get_current_location`<br>`get_current_weather` | 在权限允许时读取当前位置或本地天气；定位返回 WGS-84，并在中国境内同时返回 GCJ-02 坐标。 |
| **Agent Browser** | `browser_read` · `browser_navigate`<br>`browser_click` · `browser_input` · `browser_scroll` | 在一个用户可见、内容有界的 Android WebView 会话中研究公开 HTTPS 页面。 |
| **原生体验** | `navigate_mochi_ui`<br>`run_sandboxed_javascript` | 打开可信 Mochi 界面，或在本地运行有界的纯 JavaScript 计算。 |
| **高德地图** | 地点搜索 · 商家详情 · 路线<br>地理编码 · 逆地理编码 · 天气 | 使用可信 GCJ-02 坐标搜索地点和商家、比较可用评分与人均并规划路线，需要 Web 服务 Key。 |
| **已连接 MCP** | **Notion** · **腾讯文档**<br>手动配置的 MCP Server | 检索私人知识、开展调研，并在已授权工作区中协作创建或更新文档。 |

Tools 页面会将 Agent Browser、Mochi 内建能力和 Provider Tools 分组并默认
收起。定时 Agent 仅获得只读 Browser 能力；前台对话还可点击和输入网页控件。
每个 Tool 都有独立开关；Provider 支持的 Tools 还要求对应 Provider 开关已启用。

出行规划只使用公开 HTTPS 页面上的正常可见控件。火车票查询从 12306 官方
公开查询页开始，机票优先查询航空公司官网。Mochi 不登录、不绕过验证、不填写
乘客或支付信息，也不会进入预订流程；遇到登录、验证码、身份验证或结算页面时
立即停止。

### Skill Market

内置 Skill Market 让 Mochi 的能力不受默认功能限制。你可以浏览热门 Skills、
搜索 skills.sh 生态、安装需要的能力，并在需要时启用它们。

> 启用 Skill 不会自动开启它所依赖的 Tools。

## 隐私从本地开始

Persona 文件、设置、对话、记忆、日历和待办默认保存在设备本地。AI 提供商
凭据使用 Android Keystore 支持的本地安全存储。

Conversation 中每条消息会在 **Mochi / 你** 标志旁显示本地保存的发送日期和
时间，包括恢复的历史消息和 Scheduled Agent 结果。

回答问题时，Mochi 会把必要的对话上下文发送给你配置的 AI 提供商。外部 Tool
只会在已启用的调用中收到完成任务所需的信息。调用 `get_current_location`
时，获得权限的坐标会作为 Tool 证据发送给你配置的 AI 提供商；你可以在 Tools
中单独关闭该能力。

## 使用要求与当前状态

- Android 8.0 或更高版本。
- OpenAI、Azure OpenAI 或兼容 AI 提供商的配置。
- 语音输入需要麦克风权限。
- 位置和通知权限仅在使用相关功能时需要。

稳定版本会以签名 APK 的形式通过 GitHub Releases 分发。Mochi 仍在积极开发
中；语音识别、唤醒、音频焦点、提醒和后台运行效果可能因设备及手机厂商而异。

## 可选扩展

这些集成用于补充 Mochi 的能力，**不是核心功能的使用前提**，只需按需安装。
米家和 Termux 使用与 Mochi 同签名的独立扩展 APK；AgentLink 通过独立的
配套应用连接。米家和 AgentLink 用于前台 Main Agent；Termux 在单独明确授权后，
也可用于 Subagent 和定时 Agent，后台授权默认关闭。

| 集成 | 内置 Skill（默认状态） | 附加能力 | 安装入口 |
| --- | --- | --- | --- |
| 米家 | Mi Home Smart Home——禁用 | 查询/控制选定设备、执行手动场景、查看最新摄像头事件图片 | [Mochi Releases](https://github.com/gongpx20069/hi-mochi/releases)：`Mochi-Mijia-Extension` |
| [AgentLink](https://github.com/gongpx20069/android-agent-link) | AgentLink——禁用 | 通过 `agentlink_workspace`、`agentlink_chat`、`agentlink_control` 控制远程共享编程聊天 | [AgentLink Releases](https://github.com/gongpx20069/android-agent-link/releases) |
| Termux | Termux——禁用 | 通过 `termux_exec`、`termux_task` 运行完整本机 Shell 并管理任务 | [Mochi Releases](https://github.com/gongpx20069/hi-mochi/releases)：`Mochi-Termux-Extension`，另需[官方 Termux](https://github.com/termux/termux-app#installation) |

### 米家智能家居

安装与 Mochi 同一发布/签名渠道的 `Mochi-Mijia-Extension`。
进入 **Tools > Extensions > 米家 > 连接米家**，使用另一台已登录米家的手机
扫码并确认连接。按家庭搜索和选择支持的设备，然后保存。返回 Mochi 验证后，
点击**启用工具和 Skill**即可开启 Provider 及 Skill 必需的工具；选择**暂不启用**
则仅保留连接。管理已启用的扩展时，不会自动改动已有工具开关。

这是非官方连接器，能力取决于所选设备，不会开放不支持的操作。
摄像头图片是最新可用的云端事件，**不是实时画面**。小米凭据保留在扩展中，
Mochi 不要求你粘贴账号密码。

### 控制 AgentLink 共享编程聊天

从 [AgentLink Releases](https://github.com/gongpx20069/android-agent-link/releases)
下载 Android APK（目前标记为 **Pre-release**）。电脑端 Bridge 的安装和连接方法
见 [AgentLink 仓库](https://github.com/gongpx20069/android-agent-link)。
安装并连接 AgentLink 后，进入 **Tools > AgentLink > 连接/打开 AgentLink**，
在 AgentLink 原生页面确认限定范围的权限后返回 Mochi。启用 Provider、
`agentlink_workspace`、`agentlink_chat`、`agentlink_control` 三个开关，
再启用内置 **AgentLink** Skill。Mochi 可发现已授权机器和工作区，创建或继续
CLI/App 使用的同一个聊天、读取变化、发送任务，以及请求取消或更改配置。
Tools 中的关联聊天按钮打开可信的原生 AgentLink 页面，也可刷新、管理或撤销权限。

关闭 Mochi 不会停止远程任务。人工 CLI/App 输入优先于过期自动操作；发生冲突后
停止后续操作，不自动重发。凭据保留在 AgentLink，不进入 Mochi 导出或分享。
不允许使用网络/浏览器绕过权限，也不默认向 Subagent 或定时 Agent 开放。
关联记录并非实时任务监控；请重新读取以了解当前状态。

### 使用 Termux 执行本机命令

安装与 Mochi 同一发布/签名渠道的 `Mochi-Termux-Extension`，另行安装
[官方 Termux](https://github.com/termux/termux-app#installation)。
打开 **Tools > Extensions > Termux > 配置 Termux**，向导先检查安装和 Android
命令权限，再检查连接。已配置好的 Termux 无需再次跳转终端；首次配置时，
选择**第一次连接？配置 Termux**，复制命令并打开 Termux，长按提示符粘贴、
按回车，再返回，向导会自动检查连接。验证成功后选择**启用工具和 Skill**
或**暂不启用**。

日常直接语音控制 Mochi，批准后的命令在后台执行，不切换 App。前台 Main Agent
仍需授权执行一次或本次任务。若要让**所有定时 Agent 和子 Agent**自动执行，
在 Termux 卡片的**连接设置**中开启**后台 Shell 授权**并确认风险提示。此权限默认关闭，
重启后仍有效；**启用工具和 Skill**不会自动授予它。它也适用于前台对话委派的
子 Agent，但不会跳过 Main Agent 的确认。关闭后台授权会阻止后续调用；
禁用或断开 Termux 会清除此权限。请同步更新 Mochi 与 Termux 扩展 APK。

这是 Termux 权限下的完整 Shell，不是 Root 或文件沙箱；供 Agent 分析的输出
会发送给当前模型 Provider。
**Termux 任务**支持在本机查看输出、刷新、停止和清理已结束任务。
关闭 Mochi 不会停止已提交命令，Android 仍可能终止后台运行；脱离任务的
进程可能在停止请求后继续运行。

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

[MIT](LICENSE)
