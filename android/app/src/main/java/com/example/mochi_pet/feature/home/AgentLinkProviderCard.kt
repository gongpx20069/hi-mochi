package com.example.mochi_pet.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mochi_pet.core.agentlink.AGENTLINK_TOOLS
import com.example.mochi_pet.core.agentlink.AgentLinkState
import com.example.mochi_pet.core.agentlink.AgentLinkUiAction
import com.example.mochi_pet.core.settings.AppLanguage

@Composable
fun AgentLinkProviderCard(
    state: AgentLinkState,
    loading: Boolean,
    onAction: (AgentLinkUiAction) -> Unit,
) {
    val chinese = AppLanguage.resolveContentLocale().language == "zh"
    fun text(en: String, zh: String) = if (chinese) zh else en
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AgentLink", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = state.enabled,
                    enabled = !loading && state.authorized,
                    onCheckedChange = { onAction(AgentLinkUiAction.Enable(it)) },
                )
            }
            Text(when {
                loading -> text("Refreshing connection…", "正在刷新连接…")
                state.status == "connected" -> text("Connected at last check · not a live monitor", "上次检查已连接 · 非实时监控")
                else -> when (state.status) {
                "authorization_required" -> text("Native authorization required", "需要在 AgentLink 中授权")
                "incompatible" -> text("Update AgentLink to a compatible version", "请更新 AgentLink 至兼容版本")
                "not_installed" -> text("AgentLink is not installed or is incompatible", "未安装 AgentLink 或版本不兼容")
                "stale" -> text("Status stale · refresh required", "状态已过期 · 请刷新")
                else -> text("Disconnected · linked chats are not live", "已断开 · 关联聊天并非实时状态")
                }
            })
            Text(text(
                "Confirm scoped access in AgentLink, then return here. Credentials stay in AgentLink. Remote tasks continue after Mochi closes.",
                "在 AgentLink 确认权限后返回。凭据保留在 AgentLink；关闭 Mochi 不会停止远程任务。",
            ), style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(
                    onClick = { onAction(AgentLinkUiAction.Connect) },
                    enabled = state.installed && !loading,
                ) { Text(text("Connect/Open AgentLink", "连接/打开 AgentLink")) }
                TextButton(onClick = { onAction(AgentLinkUiAction.Refresh) }, enabled = !loading) {
                    Text(text("Refresh", "刷新"))
                }
            }
            if (state.authorized) {
                Row {
                    TextButton(onClick = { onAction(AgentLinkUiAction.Manager) }, enabled = !loading) {
                        Text(text("Manage access", "管理权限"))
                    }
                    TextButton(onClick = { onAction(AgentLinkUiAction.Revoke) }, enabled = !loading) {
                        Text(text("Revoke", "撤销授权"))
                    }
                }
            }
            AGENTLINK_TOOLS.forEach { name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(
                        checked = name in state.enabledTools,
                        enabled = !loading,
                        onCheckedChange = { onAction(AgentLinkUiAction.EnableTool(name, it)) },
                    )
                }
            }
            if (state.links.isNotEmpty()) {
                Text(text("Linked remote chats · refresh via tools", "关联的远程聊天 · 使用工具刷新"))
                state.links.forEach { link ->
                    TextButton(
                        enabled = state.authorized && !loading,
                        onClick = { onAction(AgentLinkUiAction.OpenChat(link)) },
                    ) {
                        Text(
                            link.title.take(100) + (link.taskId?.let { " · ${it.take(40)}" } ?: "") +
                                if (link.outcomeUnknown) text(" · outcome unknown", " · 结果未知") else "",
                        )
                    }
                }
            }
        }
    }
}
