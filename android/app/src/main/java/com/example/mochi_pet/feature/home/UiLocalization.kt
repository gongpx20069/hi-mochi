package com.example.mochi_pet.feature.home

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.example.mochi_pet.core.settings.AppLanguage
import java.util.Locale

@Composable
internal fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    MaterialText(
        text = localizeUiText(text),
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout,
        style = style,
    )
}

@Composable
internal fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = mapOf(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) {
    MaterialText(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        inlineContent = inlineContent,
        onTextLayout = onTextLayout,
        style = style,
    )
}

internal fun localizeUiText(
    text: String,
    language: String =
        AppLanguage.resolveContentLocale().language,
): String {
    if (language.lowercase() != Locale.CHINESE.language) {
        return text
    }
    ZH_UI_TEXT[text]?.let { return it }
    return when {
        text.startsWith("Hi Mochi is ") ->
            "Hi Mochi 当前状态：${localizeUiText(text.removePrefix("Hi Mochi is "), language)}"
        text.startsWith("Mochi is ") ->
            "Mochi 正在${localizeUiText(text.removePrefix("Mochi is "), language)}"
        text.startsWith("Back to ") ->
            "返回 ${text.removePrefix("Back to ")}"
        text.startsWith("Scheduled for ") ->
            "计划日期：${text.removePrefix("Scheduled for ")}"
        text.startsWith("Carried from ") ->
            "从 ${text.removePrefix("Carried from ")} 顺延"
        text.startsWith("Remove ") && text.endsWith("?") ->
            "移除 ${text.removePrefix("Remove ").removeSuffix("?")}？"
        text.startsWith("Update ") && text.endsWith("?") ->
            "更新 ${text.removePrefix("Update ").removeSuffix("?")}？"
        text.startsWith("Hide tools (") ->
            text.replace("Hide tools", "收起工具")
        text.startsWith("Show tools (") ->
            text.replace("Show tools", "显示工具")
        text.matches(Regex(".+ · \\d+ tools")) -> {
            val parts = text.split(" · ", limit = 2)
            "${localizeUiText(parts[0], language)} · " +
                "${parts[1].removeSuffix(" tools")} 个工具"
        }
        text.matches(Regex("\\d+ selected devices")) ->
            "${text.substringBefore(' ')} 个已选设备"
        text.startsWith("Extension ") ->
            "扩展 ${text.removePrefix("Extension ")}"
        text.matches(Regex(".*\\d+ homes · \\d+ devices")) ->
            text.replace(Regex("(\\d+) homes"), "$1 个家庭")
                .replace(Regex("(\\d+) devices"), "$1 个设备")
        text.endsWith(" installs in 24h") ->
            "${text.removeSuffix(" installs in 24h")} 次安装（24 小时）"
        text.contains(" installs in 24h · ") -> {
            val parts = text.split(" installs in 24h · ", limit = 2)
            "${parts[0]} 次安装（24 小时） · ${localizeUiText(parts[1], language)}"
        }
        text.contains(" total installs · ") -> {
            val parts = text.split(" total installs · ", limit = 2)
            "${parts[0]} 次总安装 · ${localizeUiText(parts[1], language)}"
        }
        text.startsWith("Default: ") ->
            "默认：${text.removePrefix("Default: ")}"
        text.startsWith("Event: ") ->
            "事件：${text.removePrefix("Event: ")}"
        text.startsWith("Captured: ") ->
            "拍摄时间：${text.removePrefix("Captured: ")}"
        text.endsWith(" · Modified") ->
            "${text.removeSuffix(" · Modified")} · 已修改"
        text.startsWith("Requires enabled Tools: ") ->
            "需要先启用以下工具：${text.removePrefix("Requires enabled Tools: ")}"
        text.startsWith("Enable required Tool groups first: ") -> {
            val requirements = text
                .removePrefix("Enable required Tool groups first: ")
                .split(", ")
                .joinToString("、") { localizeUiText(it, language) }
            "请先启用所需的工具组：$requirements"
        }
        else -> text
    }
}

private val ZH_UI_TEXT = mapOf(
    "Termux extension" to "Termux 扩展",
    "Termux extension is unavailable" to "Termux 扩展不可用",
    "The extension is unavailable" to "扩展不可用",
    "Android blocked the extension" to "Android 阻止了扩展",
    "Optional extension · not installed" to "可选扩展 · 未安装",
    "Unrestricted local shell. Commands require approval; output may be sent to your model Provider." to
        "不受命令白名单限制的本机 Shell。执行需要授权，输出可能发送给当前模型 Provider。",
    "Configure Termux" to "配置 Termux",
    "Connect Termux first." to "请先连接 Termux。",
    "Enable Termux tools first." to "请先启用 Termux 工具。",
    "Background Shell authorization" to "后台 Shell 授权",
    "Off by default. Allows all Scheduled Agents and Subagents to use enabled Termux tools without per-call confirmation." to
        "默认关闭。允许所有定时 Agent 和子 Agent 自动使用已启用的 Termux 工具，不再逐次确认。",
    "Allow background Shell execution?" to "允许后台执行 Shell？",
    "Authorize background Shell" to "授权后台 Shell",
    ("All Scheduled Agents and Subagents, including those delegated from a conversation, " +
        "can run unrestricted commands, change or delete accessible files, and use the network. " +
        "Output may go to your model Provider. This permission persists across restarts. " +
        "Foreground Main-Agent calls still require approval. Turning this off blocks new calls, " +
        "but does not stop submitted commands. Disabling or disconnecting Termux clears this permission.") to
        ("所有定时 Agent 和子 Agent（包括前台对话委派的子 Agent）均可执行不受白名单限制的命令，" +
            "修改或删除可访问的文件，并使用网络。输出可能发送给当前模型 Provider。此授权在重启后仍有效。" +
            "前台 Main Agent 仍需确认。关闭此开关会阻止后续调用，但不会停止已提交的命令。" +
            "禁用或断开 Termux 会清除此授权。"),
    "Enable tools and Skill" to "启用工具和 Skill",
    "Termux tasks" to "Termux 任务",
    "Allow Termux command?" to "允许执行 Termux 命令？",
    "Commands can read or change Termux files and use the network. Output goes to your model Provider." to
        "命令可以读取、修改 Termux 文件并访问网络。输出会发送给当前模型 Provider。",
    "Say: execute once, allow this task, or cancel." to "可以说：执行一次、允许本次任务，或取消。",
    "Voice confirmation" to "语音确认",
    "Execute once" to "执行一次",
    "Allow this task" to "允许本次任务",
    "Closing Mochi does not stop commands. Detached processes may survive a stop request." to
        "关闭 Mochi 不会停止命令。脱离任务的进程可能在请求停止后继续运行。",
    "No tasks" to "暂无任务",
    "Forget" to "清理记录",
    "Command output" to "命令输出",
    "submitted" to "已提交",
    "running" to "执行中",
    "succeeded" to "已成功",
    "failed" to "已失败",
    "stopping" to "正在停止",
    "stopped" to "已停止",
    "timed_out" to "已超时",
    "unknown" to "状态未知",
    "forgotten" to "已清理",
    "Settings" to "设置",
    "Mochi settings" to "Mochi 设置",
    "Configure persona, speech, and AI connections independently." to
        "分别配置 Persona、语音和 AI 连接。",
    "Share Providers" to "共享 Providers",
    "Receive Providers" to "接收 Providers",
    "Creates an encrypted link containing the Providers and Tool connections selected for this share. LLM and speech are selected by default; Tool credentials are not." to
        "创建包含本次所选 Providers 与工具连接的加密链接。默认选择 LLM 和语音 Provider，工具凭据默认不选。",
    "The decryption key is part of the link. Anyone who receives or copies it can use the selected API resources and consume their quota." to
        "解密密钥包含在链接中。任何收到或复制完整链接的人都能使用所选 API 资源并消耗其额度。",
    "Notion OAuth, Mi Home sessions, Android permissions, persona, memories, and planner data are never included." to
        "始终不会包含 Notion OAuth、米家会话、Android 权限、Persona、记忆和计划数据。",
    "Choose what to share" to "选择共享内容",
    "Providers are selected by default. Tool credentials start unselected each time." to
        "Providers 默认选中；每次打开时，工具凭据均默认不选。",
    "LLM Provider" to "LLM Provider",
    "Speech Provider" to "语音 Provider",
    "Tool credentials" to "工具凭据",
    "Credential and selected Tools" to "凭据和已选工具",
    "Token and selected Tools" to "Token 和已选工具",
    "MCP connection and selected Tools" to "MCP 连接和已选工具",
    "Share selected" to "共享所选项目",
    "Preparing..." to "正在准备...",
    "Shared Providers imported" to "共享的 Providers 已导入",
    "Paste the complete Mochi Provider link received from someone you trust. Importing replaces only the included connections and immediately enables their selected Providers and Tools." to
        "粘贴从可信任的人那里收到的完整 Mochi Provider 链接。导入只会替换其中包含的连接，并立即启用其所选 Providers 和工具。",
    "Mochi Provider link" to "Mochi Provider 链接",
    "Continue" to "继续",
    "Import shared Providers?" to "导入共享的 Providers？",
    "This link grants access to another user's selected API resources. Importing replaces only included connections, stores their credentials on this device, and enables their selected Providers and Tools. Only continue if you trust the sender." to
        "此链接允许使用另一位用户所选的 API 资源。导入只会替换其中包含的连接，将凭据存入本设备，并启用其所选 Providers 和工具。只有信任发送者时才继续。",
    "Import" to "导入",
    "Mochi persona" to "Mochi Persona",
    "Local prompt files can be edited before connecting any AI provider." to
        "无需连接任何 AI 提供商，即可编辑本地 Prompt 文件。",
    "AI provider" to "AI 提供商",
    "Connection details" to "连接信息",
    "Conversation context" to "对话上下文",
    "Save conversation context" to "保存对话上下文",
    "Done" to "完成",
    "Exit focus" to "退出专注",
    "Exit focus mode" to "退出专注模式",
    "Restore Mochi's face" to "恢复 Mochi 表情",
    "Listening" to "聆听",
    "Choosing skills" to "选择技能",
    "Thinking" to "思考",
    "Delegating" to "委派子代理",
    "Working" to "执行",
    "Composing" to "整理回答",
    "Speaking" to "朗读",
    "disabled" to "已禁用",
    "starting" to "启动中",
    "listening" to "聆听",
    "paused" to "已暂停",
    "error" to "错误",
    "choosing skills" to "选择技能",
    "thinking" to "思考",
    "working" to "执行",
    "composing" to "整理回答",
    "speaking" to "朗读",
    "I'm here. Say what you need." to "我在，请告诉我你需要什么。",
    "Finding the best way to help." to "正在寻找最合适的处理方式。",
    "Making sense of your request." to "正在理解你的请求。",
    "Waiting for Mochi's specialist to finish." to "正在等待 Mochi 的专业子代理完成。",
    "Using Mochi's local tools." to "正在使用 Mochi 的本地工具。",
    "Turning the result into a clear answer." to "正在将结果整理成清晰回答。",
    "Reading the answer aloud." to "正在朗读回答。",
    "Your quiet companion" to "你的安静伙伴",
    "Date and time" to "日期与时间",
    "Local weather" to "本地天气",
    "Mochi card" to "Mochi 卡片",
    "Conversation" to "对话",
    "Skills" to "技能",
    "Tools" to "工具",
    "Today" to "今天",
    "Todo" to "待办",
    "Good to see you" to "很高兴见到你",
    "Use Talk whenever you want to chat." to "想聊天时，请使用“对话”。",
    "Mochi is ready." to "Mochi 已就绪。",
    "Talk to Mochi" to "与 Mochi 对话",
    "Voice or text" to "语音或文字",
    "View today" to "查看今天",
    "Focus mode" to "专注模式",
    "Full screen" to "全屏",
    "Full screen · stays awake" to "全屏 · 保持唤醒",
    "Fullscreen standby" to "全屏待机",
    "Low-power standby" to "低功耗待机",
    "After Focus is idle, show a dim Mochi, date, and time on pure black." to
        "专注模式空闲后，在纯黑背景上显示低亮度 Mochi、日期和时间。",
    "Enter standby after" to "进入待机前等待",
    "30 sec" to "30 秒",
    "1 min" to "1 分钟",
    "2 min" to "2 分钟",
    "5 min" to "5 分钟",
    "10 min" to "10 分钟",
    "Fullscreen standby settings saved" to "全屏待机设置已保存",
    "Could not save fullscreen standby settings" to "无法保存全屏待机设置",
    "Dismiss" to "关闭",
    "Sources" to "来源",
    "Daily briefing" to "每日简报",
    "Agenda timeline" to "日程时间线",
    "Todo focus" to "待办重点",
    "Content" to "内容",
    "Research summary" to "研究摘要",
    "Comparison" to "对比",
    "Insight" to "洞察",
    "Progress" to "进度",
    "Current date and time" to "当前日期与时间",
    "LOCAL TIME" to "本地时间",
    "Mochi will keep this clock live for you." to "Mochi 会持续为你显示当前时间。",
    "Current local weather" to "当前本地天气",
    "RIGHT NOW" to "当前",
    "Reading the sky" to "正在读取天气",
    "Using your approximate location" to "正在使用你的大致位置",
    "Feels like" to "体感",
    "Humidity" to "湿度",
    "Weather unavailable" to "天气不可用",
    "Mochi could not read the local weather." to "Mochi 无法读取本地天气。",
    "Try again" to "重试",
    "Updated for your approximate location." to "已根据你的大致位置更新。",
    "Current location" to "当前位置",
    "Events" to "日程",
    "Active" to "进行中",
    "Active · includes carry-over" to "进行中 · 包含顺延待办",
    "Active tasks" to "当前任务",
    "No active todos" to "没有进行中的待办",
    "Completed" to "已完成",
    "No matching todos" to "没有匹配的待办",
    "Add todo" to "添加待办",
    "Previous" to "上一个",
    "Next" to "下一个",
    "Mon" to "一",
    "Tue" to "二",
    "Wed" to "三",
    "Thu" to "四",
    "Fri" to "五",
    "Sat" to "六",
    "Sun" to "日",
    "Calendar" to "日历",
    "Explore skills.sh" to "探索 skills.sh",
    "Built-in and installed capabilities" to "内置及已安装能力",
    "Installed" to "已安装",
    "Explore" to "探索",
    "Search skills.sh" to "搜索 skills.sh",
    "Search" to "搜索",
    "Check for updates" to "检查更新",
    "This removes the locally installed market skill." to "这会移除本地安装的市场技能。",
    "Remove" to "移除",
    "Cancel" to "取消",
    "Update" to "更新",
    "Keep local" to "保留本地版本",
    "Choose what Mochi may call" to "选择 Mochi 可以调用的工具",
    "Built-in" to "内置",
    "Mochi Built-ins" to "Mochi 内建能力",
    "Calendar, todos, schedules, weather, navigation, and sandbox" to
        "日历、待办、Agent 定时任务、当前天气、Mochi 导航和 JS 沙箱",
    "Web Service Key required" to "需要 Web 服务 Key",
    "Disconnect" to "断开连接",
    "Configure token" to "配置令牌",
    "Configure Amap" to "配置高德地图",
    "MCP servers" to "MCP 服务",
    "Add MCP" to "添加 MCP",
    "Extensions" to "扩展",
    "Refreshing connection..." to "正在刷新连接…",
    "Connected at last check · not a live monitor" to "上次检查已连接 · 非实时监控",
    "Native authorization required" to "需要在 AgentLink 中授权",
    "Update AgentLink to a compatible version" to "请更新 AgentLink 至兼容版本",
    "AgentLink is not installed or is incompatible" to "未安装 AgentLink 或版本不兼容",
    "Status stale · refresh required" to "状态已过期 · 请刷新",
    "Disconnected · linked chats are not live" to "已断开 · 关联聊天并非实时状态",
    "Confirm scoped access in AgentLink, then return here. Credentials stay in AgentLink. Remote tasks continue after Mochi closes." to
        "在 AgentLink 确认权限后返回。凭据保留在 AgentLink；关闭 Mochi 不会停止远程任务。",
    "Connect/Open AgentLink" to "连接/打开 AgentLink",
    "Manage access" to "管理权限",
    "Revoke" to "撤销授权",
    "Linked remote chats · refresh via tools" to "关联的远程聊天 · 使用工具刷新",
    "Outcome unknown" to "结果未知",
    "AgentLink Workspaces" to "AgentLink 工作区",
    "List and create authorized remote workspaces." to "列出和创建已授权的远程工作区。",
    "AgentLink Chats" to "AgentLink 聊天",
    "List, create, read, and open shared remote chats." to "列出、创建、读取和打开共享的远程聊天。",
    "AgentLink Control" to "AgentLink 控制",
    "Send instructions, cancel tasks, and configure remote agents." to "发送指令、取消任务和配置远程 Agent。",
    "Mi Home" to "米家",
    "Optional unofficial extension · not installed" to
        "可选非官方扩展 · 未安装",
    "Installed package could not be trusted" to "已安装的软件包不受信任",
    "Authorization expired" to "授权已过期",
    "Installed · connection required" to "已安装 · 需要连接",
    "Lights, switches, climate and air devices, curtains, sensors, televisions, camera event images, scales, and scenes." to
        "支持灯、开关、温控与空气设备、窗帘、传感器、电视、摄像头事件图片、体脂秤和场景。",
    "Get trusted extension" to "获取可信扩展",
    "Install extension" to "安装扩展",
    "Reconnect Mi Home" to "重新连接米家",
    "Connect Mi Home" to "连接米家",
    "Manage" to "管理",
    "read" to "读取",
    "write" to "写入",
    "sensitive" to "敏感操作",
    "Connected" to "已连接",
    "Authorization required" to "需要授权",
    "Personal token required" to "需要个人令牌",
    "Ready" to "就绪",
    "Connect Notion" to "连接 Notion",
    "Connect" to "连接",
    "Remove server" to "移除服务",
    "Connect Tencent Docs" to "连接腾讯文档",
    "Get personal token" to "获取个人令牌",
    "Tencent Docs MCP token" to "腾讯文档 MCP 令牌",
    "Connect Amap Maps" to "连接高德地图",
    "Open Amap console" to "打开高德开放平台控制台",
    "Web Service Key" to "Web 服务 Key",
    "Security Key (optional)" to "安全密钥（可选）",
    "Add MCP server" to "添加 MCP 服务",
    "Name" to "名称",
    "Endpoint" to "端点",
    "****** (optional)" to "******（可选）",
    "Add" to "添加",
    "View" to "查看",
    "Install" to "安装",
    "Very hot" to "非常热门",
    "Hot" to "热门",
    "Popular" to "受欢迎",
    "Growing" to "增长中",
    "New" to "新",
    "Very high popularity" to "极高热度",
    "High popularity" to "高热度",
    "Enabled" to "已启用",
    "Disabled" to "已禁用",
    "Preview" to "预览",
    "Edit" to "编辑",
    "Save" to "保存",
    "Close" to "关闭",
    "All day" to "全天",
    "Setup" to "设置",
    "Connect an AI provider in Settings to start chatting." to "请先在设置中连接 AI 提供商。",
    "Ask Mochi anything..." to "问 Mochi 任何问题……",
    "Stop listening" to "停止聆听",
    "Speak" to "说话",
    "Send" to "发送",
    "What are we doing today?" to "今天要做什么？",
    "Speak naturally or type a message below." to "自然说话，或在下方输入消息。",
    "You" to "你",
    "AI connection" to "AI 连接",
    "Connect Mochi" to "连接 Mochi",
    "Your key is encrypted on this device." to "你的密钥已在此设备上加密保存。",
    "Choose your provider, then enter the values from its portal." to "选择提供商，然后填写其控制台中的信息。",
    "App language" to "应用语言",
    "Follow system" to "跟随系统",
    "Chinese" to "中文",
    "English" to "英文",
    "Chinese system languages use Chinese; all other system languages use English." to
        "系统语言为中文时使用中文，其他系统语言使用英文。",
    "1. Choose a provider" to "1. 选择提供商",
    "2. Connection details" to "2. 连接信息",
    "3. Agent context" to "3. Agent 上下文",
    "4. Persona files" to "4. Persona 文件",
    "Azure resource endpoint + deployment + API key" to "Azure 资源端点 + 部署 + API 密钥",
    "api.openai.com with a model name" to "api.openai.com 与模型名称",
    "Custom compatible API" to "自定义兼容 API",
    "Any OpenAI-compatible /chat/completions API" to "任何兼容 OpenAI 的 /chat/completions API",
    "Azure resource endpoint" to "Azure 资源端点",
    "API endpoint" to "API 端点",
    "Azure Portal → Azure OpenAI → Keys and Endpoint" to "Azure Portal → Azure OpenAI → 密钥和端点",
    "Mochi appends /chat/completions when needed." to "Mochi 会在需要时追加 /chat/completions。",
    "SOUL.md" to "SOUL.md",
    "Identity, values, and communication style." to "身份、价值观和沟通风格。",
    "USER.md" to "USER.md",
    "Stable user facts and preferences." to "稳定的用户事实和偏好。",
    "AGENTS.md" to "AGENTS.md",
    "Operational rules for Mochi." to "Mochi 的操作规则。",
    "Save persona files" to "保存 Persona 文件",
    "Deployment name" to "部署名称",
    "Model name" to "模型名称",
    "Your Azure deployment name" to "你的 Azure 部署名称",
    "Use the deployment name, not the base model name." to "请使用部署名称，而不是基础模型名称。",
    "Azure API version" to "Azure API 版本",
    "API key" to "API 密钥",
    "Leave blank to keep the stored key." to "留空以保留已保存的密钥。",
    "Encrypted using Android Keystore." to "使用 Android Keystore 加密。",
    "Recent conversation turns" to "最近对话轮数",
    "Default 20; allowed range 1-50." to "默认 20；允许范围 1–50。",
    "Timeout seconds" to "超时秒数",
    "Wake word" to "唤醒词",
    "Say Hi Mochi hands-free" to "免手持说“Hi Mochi”",
    "Disable" to "禁用",
    "Enable" to "启用",
    "Saving..." to "正在保存……",
    "Save connection" to "保存连接",
    "Task" to "任务",
    "Home" to "主页",
    "Talk" to "对话",
    "Planner" to "计划",
    "No browser is available" to "没有可用的浏览器",
    "This source cannot be opened safely" to "无法安全打开此来源",
    "This source URL is invalid" to "来源网址无效",
    "The browser blocked this source" to "浏览器阻止了此来源",
    "Agent runtime is unavailable" to "Agent 运行时不可用",
    "Mochi could not complete this request" to "Mochi 无法完成此请求",
    "The AI service timed out. Check your network and provider status." to
        "AI 服务响应超时，请检查网络和 AI 服务状态。",
    "Could not connect to the AI service. Check your network, proxy, and provider endpoint." to
        "无法连接 AI 服务，请检查网络、代理和服务地址。",
    "AI service access was denied. Check your API key and model permissions in Settings." to
        "AI 服务拒绝访问，请在设置中检查 API Key 和模型权限。",
    "The AI service is rate-limited or out of quota. Check your quota or try again later." to
        "AI 服务请求受限或额度不足，请检查额度或稍后重试。",
    "The AI service is temporarily unavailable. Try again later." to
        "AI 服务暂时不可用，请稍后重试。",
    "The AI service rejected this request. Check your provider and model settings." to
        "AI 服务拒绝了此请求，请检查提供商和模型设置。",
    "The AI provider configuration is invalid. Check the endpoint and model in Settings." to
        "AI 提供商配置无效，请在设置中检查服务地址和模型。",
    "The AI service returned an unsupported response. Check model compatibility." to
        "AI 服务返回了不支持的响应，请检查模型兼容性。",
    "The AI service response exceeded the size limit. Try a smaller request." to
        "AI 服务响应超出大小限制，请缩小请求范围。",
    "Microphone permission is required" to "需要麦克风权限",
    "Microphone and notification permissions are required" to "需要麦克风和通知权限",
    "Provider settings saved" to "提供商设置已保存",
    "Invalid provider settings" to "提供商设置无效",
    "Persona files saved" to "Persona 文件已保存",
    "Persona update failed" to "Persona 更新失败",
    "Agent context settings saved" to "Agent 上下文设置已保存",
    "Could not save Agent context settings" to "无法保存 Agent 上下文设置",
    "Search results" to "搜索结果",
    "Trending today" to "今日热门",
    "Skill search failed" to "技能搜索失败",
    "Popular skills could not be loaded" to "无法加载热门技能",
    "Skill preview failed" to "技能预览失败",
    "Skill installation failed" to "技能安装失败",
    "Local skill changes saved" to "本地技能更改已保存",
    "Skill update failed" to "技能更新失败",
    "Skill enabled" to "技能已启用",
    "Skill disabled" to "技能已禁用",
    "Skill removed" to "技能已移除",
    "Skill removal failed" to "技能移除失败",
    "Checking for updates..." to "正在检查更新……",
    "Update check complete" to "更新检查完成",
    "Update check failed" to "更新检查失败",
    "Skill updated from skills.sh" to "技能已从 skills.sh 更新",
    "Preparing Notion authorization..." to "正在准备 Notion 授权……",
    "Complete authorization in Notion" to "请在 Notion 中完成授权",
    "Notion knowledge tools connected" to "Notion 知识工具已连接",
    "Notion disconnected" to "Notion 已断开",
    "Copy your personal Tencent Docs MCP token" to "复制你的腾讯文档 MCP 个人令牌",
    "Tencent Docs knowledge tools connected" to "腾讯文档知识工具已连接",
    "Tencent Docs disconnected" to "腾讯文档已断开",
    "Create an Amap Web Service Key" to "创建高德 Web 服务 Key",
    "Amap connected. Travel Planning and Merchant Discovery are ready." to
        "高德地图已连接。出行规划和商家发现 Skills 已可使用。",
    "Amap disconnected" to "高德地图已断开",
    "MCP server removed" to "MCP 服务已移除",
    "Connected · 6 map and merchant tools" to "已连接 · 6 个地图与商家工具",
    "Open the official Tencent Docs page, copy your personal MCP token, then paste it below." to
        "打开腾讯文档官方页面，复制个人 MCP 令牌并粘贴到下方。",
    "In the Amap console, add a key for the Web Service platform, not Android. Web Service keys do not need release or debug SHA1 fingerprints. The optional Security Key is not a SHA1; enter it only when digital signatures are enabled." to
        "在高德控制台添加 Key 时，服务平台请选择 Web 服务，不要选择 Android。Web 服务 Key 无需发布版或调试版安全码 SHA1。可选的安全密钥不是 SHA1，仅在启用数字签名时填写。",
    "Only public HTTPS Streamable HTTP MCP endpoints are accepted." to
        "仅接受公开的 HTTPS Streamable HTTP MCP 端点。",
    "This skill has local edits. Updating will replace them with the latest skills.sh version." to
        "此技能包含本地修改。更新会使用最新 skills.sh 版本覆盖这些修改。",
    "Replace the installed content with the latest skills.sh version." to
        "使用最新 skills.sh 版本替换已安装内容。",
    "Built-in · Enabled · Read only" to "内置 · 已启用 · 只读",
    "Built-in · Disabled · Read only" to "内置 · 已禁用 · 只读",
    "Modified" to "已修改",
    "Mochi Calendar" to "Mochi 日历",
    "Read and update Mochi calendar events." to "读取和更新 Mochi 日历事件。",
    "Mochi Todo" to "Mochi 待办",
    "Read and update Mochi todos." to "读取和更新 Mochi 待办。",
    "Current Location" to "当前位置",
    "Read the device location after Android permission." to
        "获得 Android 权限后读取设备当前位置。",
    "Current Weather" to "当前天气",
    "Read current local weather with device permission." to "在获得设备权限后读取当前本地天气。",
    "Web Search" to "网页搜索",
    "Search current public web information." to "搜索当前公开网页信息。",
    "Web Page Reader" to "网页阅读器",
    "Read bounded text from public HTTPS pages." to "读取公开 HTTPS 页面中的有限文本。",
    "Mochi Navigation" to "Mochi 导航",
    "Open trusted native Mochi surfaces." to "打开可信的 Mochi 原生页面。",
    "JavaScript Sandbox" to "JavaScript 沙箱",
    "Run bounded pure JavaScript calculations locally." to "在本地运行受限的纯 JavaScript 计算。",
    "Amap Place Search" to "高德地点搜索",
    "Search Amap places and nearby merchants." to "搜索高德地点和附近商家。",
    "Amap Place Details" to "高德地点详情",
    "Read merchant details, ratings, cost, hours, and photos." to
        "读取商家详情、评分、人均、营业时间和图片。",
    "Amap Route Planning" to "高德路线规划",
    "Plan driving, walking, cycling, or transit routes." to "规划驾车、步行、骑行或公交路线。",
    "Amap Geocoding" to "高德地理编码",
    "Convert complete addresses to map coordinates." to "将完整地址转换为地图坐标。",
    "Amap Reverse Geocoding" to "高德逆地理编码",
    "Convert trusted coordinates to addresses." to "将可信坐标转换为地址。",
    "Amap Weather" to "高德天气",
    "Read weather forecasts for an administrative region." to
        "按行政区划读取天气预报。",
    "Mochi Planner" to "Mochi 计划",
    "Manage Mochi calendar events and dated todos." to "管理 Mochi 日历事件和带日期的待办。",
    "Agent Schedules" to "Agent 定时任务",
    "Create and manage scheduled Mochi Agent prompts." to "创建和管理 Mochi Agent 定时提示。",
    "Scheduled Automations" to "定时自动化",
    "Agent schedules" to "Agent 定时任务",
    "Run now" to "立即运行",
    "Delete" to "删除",
    "Paused" to "已暂停",
    "Voice Navigation" to "语音导航",
    "Navigate to the relevant native surface by intent." to "根据意图导航到相关原生页面。",
    "Notion Knowledge" to "Notion 知识",
    "Tencent Docs Knowledge" to "腾讯文档知识",
    "Tencent Docs MCP" to "腾讯文档 MCP",
    "Agent Browser" to "代理浏览器",
    "Amap Maps" to "高德地图",
    "Mi Home extension" to "米家扩展",
    "Travel Planning" to "出行规划",
    "Plan routes and research public train or flight options without logging in." to
        "规划路线并调研无需登录的公开火车票或机票信息。",
    "Merchant Discovery" to "商家发现",
    "Find and compare merchants using Amap ratings, cost, hours, and details." to
        "使用高德评分、人均、营业时间和详情发现并比较商家。",
    "Mi Home Smart Home" to "米家智能家居",
    "Control selected Mi Home devices, inspect state, run scenes, and review the latest supported camera event." to
        "控制已选择的米家设备、查看状态、执行场景并查看支持的最新摄像头事件。",
    "List Mi Home Devices" to "列出米家设备",
    "Read Mi Home Device State" to "读取米家设备状态",
    "Control Mi Home Device" to "控制米家设备",
    "Control Mi Home Television" to "控制米家电视",
    "Configure Mi Home Camera" to "配置米家摄像头",
    "Get Latest Camera Event Image" to "获取最新摄像头事件图片",
    "List Mi Home Scenes" to "列出米家场景",
    "Run Mi Home Scene" to "执行米家场景",
    "List user-selected supported Mi Home devices and operations." to
        "列出用户选择且受支持的米家设备和操作。",
    "Read supported state from one selected Mi Home device." to
        "读取一个已选择米家设备的受支持状态。",
    "Control one selected light, switch, plug, fan, climate, air, or curtain device." to
        "控制一个已选择的灯、开关、插座、风扇、温控、空气或窗帘设备。",
    "Control one selected television using only declared MIoT capabilities." to
        "仅使用已声明的 MIoT 能力控制一个已选择的电视。",
    "Change one explicitly confirmed supported camera setting." to
        "更改一项已明确确认且受支持的摄像头设置。",
    "Retrieve the newest available motion or doorbell event image from one selected camera." to
        "从一个已选择的摄像头获取最新可用的移动或门铃事件图片。",
    "List enabled manually triggered scenes from selected homes." to
        "列出所选家庭中已启用的手动触发场景。",
    "Run one exact Mi Home scene after explicit confirmation." to
        "在明确确认后执行一个指定的米家场景。",
    "Reconnect Mi Home." to "请重新连接米家。",
    "Select devices to complete setup." to "请选择设备以完成设置。",
    "Android rejected the extension connection." to "Android 拒绝了扩展连接。",
    "Extension signature does not match Mochi." to "扩展签名与 Mochi 不匹配。",
    "Expected extension service is missing." to "缺少预期的扩展服务。",
    "Extension service permission is invalid." to "扩展服务权限无效。",
    "Expected extension configuration activity is missing." to
        "缺少预期的扩展配置页面。",
    "Extension configuration permission is invalid." to "扩展配置权限无效。",
    "Extension protocol version is unsupported." to "不支持此扩展协议版本。",
    "Update Mochi before using this extension." to "请先更新 Mochi 再使用此扩展。",
    "Extension identity is invalid." to "扩展身份无效。",
    "Extension package version metadata is invalid." to "扩展软件包版本元数据无效。",
    "The Mi Home extension is unavailable" to "米家扩展不可用",
    "Android blocked the Mi Home extension" to "Android 阻止了米家扩展",
    "Multimodal input" to "多模态输入",
    "Allow supported images, currently validated Mi Home camera event images, in the current Main Agent run and one explicit Subagent handoff. Enable only when the configured model supports images." to
        "允许在当前主 Agent 运行和一次明确的子 Agent 委派中使用受支持的图片，目前包括已验证的米家摄像头事件图片。仅在配置的模型支持图片时启用。",
    "Latest Mi Home camera event" to "米家摄像头最新事件",
    "LATEST EVENT · NOT LIVE" to "最新事件 · 非实时画面",
    "Mochi image analysis on · current run only" to
        "已开启 Mochi 图像分析 · 仅限当前会话",
    "Device-only · model image input is off" to
        "仅本机显示 · 模型图片输入未开启",
    "Could not load persisted conversation history" to "无法加载已保存的对话历史",
    "Could not load persona files" to "无法加载 Persona 文件",
    "Reply succeeded, but conversation memory was not saved" to "回答已完成，但未能保存对话记忆",
    "Tool catalog is unavailable" to "工具目录不可用",
    "Tool configuration failed" to "工具配置失败",
    "Could not load Skills" to "无法加载技能",
    "Weather runtime is unavailable" to "天气运行时不可用",
    "Could not load weather" to "无法加载天气",
    "Could not complete todo" to "无法完成待办",
    "Planner operation failed" to "计划操作失败",
    "Speech recognition is unavailable on this device" to "此设备不支持语音识别",
    "Speech recognition" to "语音识别",
    "Optional: Android speech recognition is used when no cloud provider is configured. It may be unstable on some phones, so you might need to try a voice request more than once." to
        "语音服务为可选配置：未配置云端服务时使用 Android 默认语音识别。部分手机上的系统服务可能不稳定，一次语音请求可能需要多试几次。",
    "Android default" to "Android 默认",
    "No setup · device service may be unstable" to "无需配置 · 设备服务可能不稳定",
    "iFlytek Speech" to "讯飞语音",
    "Recommended for speech recognition in China" to "建议用于中国大陆语音识别",
    "Azure Speech-to-Text short audio API" to "Azure 短语音转文字 API",
    "iFlytek AppID" to "讯飞 AppID",
    "iFlytek APIKey" to "讯飞 APIKey",
    "iFlytek APISecret" to "讯飞 APISecret",
    "Leave blank to keep the stored secret." to "留空以保留已保存的密钥。",
    "Open iFlytek registration" to "打开讯飞注册页面",
    "Azure Speech endpoint" to "Azure Speech 端点",
    "Azure Speech key" to "Azure Speech 密钥",
    "Open Azure Speech setup" to "打开 Azure Speech 配置页面",
    "Speech settings saved" to "语音设置已保存",
    "Save speech settings" to "保存语音设置",
    "Speech recognition and synthesis" to "语音识别与合成",
    "Also use this provider for speech synthesis" to "同时用于语音合成",
    "Reuses the saved credentials. When enabled, assistant reply text is sent to this provider. Wake acknowledgements always use Android speech. Disabled by default." to
        "复用已保存的密钥。开启后，助手回复文本会发送给此服务商；唤醒提示始终使用 Android 本地播报。默认关闭。",
    "Synthesis voice ID (optional)" to "合成音色 ID（选填）",
    "Voice" to "音色",
    "Choose voice" to "选择音色",
    "Follow app default" to "跟随应用默认",
    "Preview voice" to "试听",
    "Stop preview" to "停止试听",
    "Cancel synthesis" to "取消合成",
    "Search voices" to "搜索音色",
    "All languages" to "全部语言",
    "Refresh" to "刷新",
    "Loading voices..." to "正在加载音色…",
    "Custom voice ID" to "自定义音色 ID",
    "Confirm selection" to "确认选择",
    "Save speech settings to apply this selection." to "保存语音设置后，此选择才会用于正式对话。",
    "Save the connection settings first" to "请先保存连接配置",
    "Last preview succeeded" to "上次试听成功",
    "No matching voices. Try all languages or refresh." to "没有匹配的音色，请切换全部语言或刷新。",
    "Offline" to "离线",
    "Basic voice" to "基础音色",
    "Xiaoyan" to "讯飞小燕",
    "Yezi" to "讯飞叶子",
    "Xu Jiu" to "讯飞许久",
    "Xiao Jing" to "讯飞小婧",
    "Xu Xiaobao" to "讯飞许小宝",
    "Offline voices installed on this phone. Playback follows media volume." to
        "仅显示本机已安装的离线音色，播报跟随媒体音量。",
    "Preview uses a fixed greeting and consumes synthesis quota. Listed voices are not a guarantee of authorization or free usage." to
        "试听使用固定问候语，会消耗合成额度。列表中的音色不代表当前账号已授权或可免费使用。",
    "Open system speech settings" to "打开系统语音设置",
    "System speech settings are unavailable" to "无法打开系统语音设置",
    "Invalid system voice ID" to "系统音色 ID 无效",
    "Voice preview is unavailable" to "语音试听暂不可用",
    "Voice preview failed" to "语音试听失败",
    "Finish the current voice interaction before previewing" to "请先结束当前对话或播报，再进行试听",
    "Unable to load voices. Check the saved connection and try again." to "无法加载音色，请检查已保存的连接配置后重试。",
    "Voice catalog unavailable. Check the saved connection or system speech settings." to
        "音色列表不可用，请检查已保存的连接配置或系统语音设置。",
    "Default: x4_xiaoyan. Enable streaming TTS and the selected voice in the iFlytek console." to
        "默认：x4_xiaoyan。请在讯飞控制台开通流式语音合成及所选音色。",
    "Default: Xiaoxiao (Chinese) or Jenny (English). Enter a full voice ID, e.g. zh-CN-XiaoxiaoNeural. The Speech resource must support synthesis." to
        "默认：中文晓晓、英文 Jenny。可填写完整音色 ID，例如 zh-CN-XiaoxiaoNeural。Speech 资源需支持语音合成。",
    "Speech voice must be a valid provider voice ID" to "请填写有效的服务商音色 ID",
    "Cannot connect to the speech synthesis provider." to "无法连接语音合成服务，请检查网络与服务状态。",
    "Speech synthesis timed out. Please try again." to "语音合成超时，请重试。",
    "Speech synthesis authorization failed. Check your provider credentials." to "语音合成鉴权失败，请检查服务商密钥。",
    "Speech synthesis is rate limited or its quota is exhausted." to "语音合成请求过于频繁或额度已用完。",
    "Speech synthesis was rejected. Check service activation and voice access." to "语音合成请求被拒绝，请检查是否已开通服务及所选音色。",
    "The speech provider returned invalid or oversized audio." to "语音服务返回的音频无效或过大。",
    "Speech audio could not be played on this device." to "此设备无法播放合成语音，请检查系统语音引擎与音频设置。",
    "Speech synthesis settings are incomplete or invalid." to "语音合成设置不完整或无效，请检查语音服务配置。",
    "Speech audio focus was lost" to "语音播报已被其他音频打断",
    "Speech audio focus is unavailable" to "暂时无法获取音频焦点进行语音播报",
    "Set up speech settings" to "设置语音服务",
    "Android speech recognition is busy. Try again." to
        "Android 语音识别正忙，请重试。",
    "Android speech recognition is unstable. Try again, or set up iFlytek/Azure Speech in Settings for more reliable recognition." to
        "Android 语音识别不稳定，请重试；也可以在设置中配置讯飞或 Azure Speech，以获得更可靠的识别。",
    "Android did not recognize that. Try speaking again, or set up iFlytek/Azure Speech in Settings." to
        "Android 未识别到语音，请再说一次；也可以在设置中配置讯飞或 Azure Speech。",
    "Android speech recognition failed" to "Android 语音识别失败",
    "Microphone audio focus was lost" to "麦克风音频焦点已丢失",
    "Microphone audio focus is unavailable" to "麦克风音频焦点不可用",
    "Microphone audio error" to "麦克风音频错误",
    "Speech recognition network error" to "语音识别网络错误",
    "No speech was recognized" to "未识别到语音",
    "Speech recognition is already busy" to "语音识别正忙",
    "Speech recognition service error" to "语音识别服务错误",
    "No speech was detected" to "未检测到语音",
    "Speech recognition failed" to "语音识别失败",
    "Wake capture failed" to "唤醒监听失败",
    "Android blocked wake-word microphone access" to "Android 阻止了唤醒词麦克风访问",
    "Wake-word service cannot start in the current state" to "当前状态下无法启动唤醒词服务",
)
