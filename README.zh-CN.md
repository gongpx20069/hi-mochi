<div align="center">

<img src="docs/assets/mochi-banner.svg" alt="Mochi 老莫——你的 Android AI 伙伴" width="100%">

# Mochi · 老莫

**把 Android 手机变成你的 AI 伙伴：陪你聊天、安排日常、查资料、办事情。**

[**下载 APK**](https://github.com/gongpx20069/hi-mochi/releases/latest)
· [官网](https://gongpx20069.github.io/hi-mochi/zh-CN/) · [使用指南](https://gongpx20069.github.io/hi-mochi/zh-CN/wiki/)
· [观看介绍视频](https://www.bilibili.com/video/BV1u6aB6GEDD/)
· [English](README.md) · [简体中文](README.zh-CN.md)

**Android 8.0+ · 自选模型 · 无需注册 Mochi 账号**

[![CI](https://github.com/gongpx20069/hi-mochi/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/gongpx20069/hi-mochi/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-MIT-111827)](LICENSE)

</div>

## 快速开始

1. **安装老莫。** 下载[最新 APK](https://github.com/gongpx20069/hi-mochi/releases/latest)，
   大多数手机选 `arm64-v8a`，不确定选 `universal`。升级时覆盖安装，不要先卸载。
2. **连接模型。** 打开**设置 > AI 连接**。
   **推荐魔搭 ModelScope，可用免费 API 额度入门：**
   [注册或登录](https://modelscope.cn/) → [获取 Access Token](https://modelscope.cn/my/myaccesstoken) 并粘贴 →
   从下拉列表选择推荐模型；也可到[模型库](https://modelscope.cn/models)选择后自定义填写。
3. **开始交流。** 打字或长按输入框说话，点击发送；
   切换到**陪伴**模式，叫一声 **“老莫老莫”** 即可语音交流。

内置 Android 系统语音，无需额外语音密钥或扩展。
唤醒需要陪伴页面在前台可见、屏幕解锁，不支持锁屏或后台唤醒。

## 老莫能做什么

| 你想做的事 | 老莫可以帮你 |
| --- | --- |
| 聊天与桌面陪伴 | 文字、语音交流，表情陪伴、全屏时钟、自定义性格 |
| 安排日常 | 管理日程、带日期的待办和提醒，定时执行 AI 任务，语音切换页面 |
| 回忆过去 | 按需开启每日小记与回忆检索，找到之前聊过的事及原始消息 |
| 浏览与研究 | 阅读链接、手动浏览网页、比较商品、查豆瓣评分、研究美股七姐妹 |
| 处理文档 | 查找、起草和更新已授权的 Notion、腾讯文档、飞书云文档 |
| 出行与找店 | 结合高德和浏览器查询路线、天气、火车与航班信息、附近商家 |
| 操作手机 | 操作允许的应用，点歌、控制播放、调节媒体音量 |

> “总结一下这个网页。” · “提醒我明天下午三点赴约。” · “附近有什么咖啡店？”

在**技能**和**工具**中按需开启能力，缺少的配置会直接提示。
还可以从 **skills.sh 技能市场**安装社区技能，或添加 MCP、HTTPS REST 工具。
商品与出行研究不代下单或预订；股票研究不构成投资建议。

## 按需连接服务

**模型：** 除魔搭外，也支持以下服务和其他 OpenAI 兼容 API。
预设会填写地址并提供配置引导，可保存多个连接；请选择支持工具调用的模型。

[OpenAI](https://platform.openai.com/api-keys) ·
[Azure OpenAI](https://portal.azure.com/#create/Microsoft.CognitiveServicesOpenAI) ·
[DeepSeek](https://api-docs.deepseek.com/) ·
[Kimi](https://platform.kimi.com/docs/get-api-key) ·
[GLM](https://docs.bigmodel.cn/cn/guide/develop/openai/introduction) ·
[MiniMax](https://platform.minimax.cn/docs/api-reference/text-openai-api) ·
[Agnes-AI](https://wiki.agnes-ai.com/en/docs/overview) ·
[Groq](https://console.groq.com/keys)（[模型库](https://console.groq.com/docs/models)）。

**文档与出行：** [Notion](https://www.notion.com/help/notion-mcp) ·
[腾讯文档](https://docs.qq.com/open/auth/mcp.html) ·
[飞书](https://open.feishu.cn/app) · [高德 Web 服务 Key](https://console.amap.com/)。
需要时在**工具**中连接即可。

**可选云端语音：** [讯飞](https://console.xfyun.cn/)或
[Azure Speech](https://portal.azure.com/#create/Microsoft.CognitiveServicesSpeechServices)，
在**设置 > 语音与唤醒**中配置。

## 可选扩展

主应用已包含聊天、语音、日程和浏览器，只有需要额外能力时才安装扩展。

| 集成 | 附加能力 | 获取 |
| --- | --- | --- |
| 米家智能家居 | 控制选定设备、执行场景、查看摄像头事件图片（非直播） | [米家扩展](https://github.com/gongpx20069/hi-mochi/releases) |
| AgentLink | 向已授权的电脑工作区发送编程任务，继续共享聊天 | [Android 应用与电脑端 Bridge](https://github.com/gongpx20069/android-agent-link) |
| Termux | 执行本机 Shell 命令，查看输出 | [Termux 扩展](https://github.com/gongpx20069/hi-mochi/releases) + [Termux](https://github.com/termux/termux-app#installation) |
| WebDAV 文件（预览） | 在授权目录中查找、读写和传输文件 | [WebDAV 扩展](https://github.com/gongpx20069/hi-mochi/releases) |

Mochi 扩展使用匹配版本、相同签名的 APK，按**工具**页引导连接。
米家为非官方集成；Termux 是完整 Shell，**不是沙箱**。

## 隐私与控制

- **本地优先，不等于完全离线。** 历史和设置保存在手机上，必要的上下文与工具结果会发送给所选模型；云端语音由对应服务处理。
- **权限由你决定。** 应用操作需要无障碍权限，播放器访问需要通知使用权；每日小记与回忆检索默认关闭。
- **保护凭据。** 密钥使用 Keystore 加密存储；连接分享链接包含 API 凭据使用权限，只发给可信的人。
- **谨慎开启操作能力。** 浏览器和 Termux 可不经逐次审批执行；停止老莫不会撤销修改，也不一定停止已提交的任务。

语音效果、应用兼容性和后台任务时间受设备与服务影响，不要将关键提醒完全依赖于老莫。

## 帮助

[反馈问题](https://github.com/gongpx20069/hi-mochi/issues) ·
[文档](docs/README.md) · [从源码构建](docs/DEVELOPMENT.md) ·
[参与贡献](CONTRIBUTING.md) · [MIT 许可](LICENSE)
