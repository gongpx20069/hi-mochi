package com.example.mochi_pet.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mochi_pet.core.agentlink.AgentLinkState
import com.example.mochi_pet.core.agentlink.AgentLinkUiAction
import com.example.mochi_pet.core.tools.BuiltInToolDescriptor
import com.example.mochi_pet.core.tools.BuiltInToolSummary
import com.example.mochi_ui.ExtensionCard
import com.example.mochi_ui.ExtensionHeading

internal val AGENTLINK_TOOL_DESCRIPTORS = listOf(
    BuiltInToolDescriptor(
        name = "agentlink_workspace",
        displayName = "AgentLink Workspaces",
        description = "List and create authorized remote workspaces.",
        defaultEnabled = true,
    ),
    BuiltInToolDescriptor(
        name = "agentlink_chat",
        displayName = "AgentLink Chats",
        description = "List, create, read, and open shared remote chats.",
        defaultEnabled = true,
    ),
    BuiltInToolDescriptor(
        name = "agentlink_control",
        displayName = "AgentLink Control",
        description = "Send instructions, cancel tasks, and configure remote agents.",
        defaultEnabled = true,
    ),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentLinkProviderCard(
    state: AgentLinkState,
    loading: Boolean,
    onAction: (AgentLinkUiAction) -> Unit,
) {
    var toolsExpanded by rememberSaveable { mutableStateOf(false) }
    var management by rememberSaveable { mutableStateOf(false) }
    var chatsExpanded by rememberSaveable { mutableStateOf(false) }
    ExtensionCard {
        ExtensionHeading(
            title = "AgentLink",
            status = localizeUiText(when {
                loading -> "Refreshing connection..."
                state.connected && !state.enabled -> "Connected - not enabled"
                else -> when (state.status) {
                    "connected" -> "Connected at last check · not a live monitor"
                    "authorization_required" -> "Native authorization required"
                    "incompatible" -> "Update AgentLink to a compatible version"
                    "not_installed" -> "AgentLink is not installed or is incompatible"
                    "stale" -> "Status stale · refresh required"
                    else -> "Disconnected · linked chats are not live"
                }
            }),
        ) {
            if (state.authorized) {
                Switch(
                    checked = state.enabled,
                    enabled = !loading,
                    onCheckedChange = { onAction(AgentLinkUiAction.Enable(it)) },
                )
            }
        }
        Text(
            text = "Confirm scoped access in AgentLink, then return here. " +
                "Credentials stay in AgentLink. Remote tasks continue after Mochi closes.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        if (!state.installed || state.status == "incompatible") {
            Button(onClick = { onAction(AgentLinkUiAction.Install) }, enabled = !loading) {
                Text("Install or update AgentLink")
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.authorized) {
                OutlinedButton(
                    onClick = { onAction(AgentLinkUiAction.Connect) },
                    enabled = state.installed && !loading,
                ) {
                    Text("Connect/Open AgentLink")
                }
            } else {
                Button(
                    onClick = { onAction(AgentLinkUiAction.Connect) },
                    enabled = state.installed && !loading,
                ) {
                    Text("Connect/Open AgentLink")
                }
            }
            TextButton(
                onClick = { onAction(AgentLinkUiAction.Refresh) },
                enabled = !loading,
            ) {
                Text("Refresh")
            }
        }
        TextButton({ management = !management }) {
            Text(if (management) "Hide connection settings" else "Connection settings")
        }
        if (management) {
            Text("Authorize in AgentLink, then return here. A connected desktop Bridge is needed for remote work.",
                style = MaterialTheme.typography.bodyMedium)
            TextButton({ onAction(AgentLinkUiAction.SetupGuide) }, enabled = !loading) { Text("Desktop Bridge setup") }
            if (state.authorized) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onAction(AgentLinkUiAction.Manager) },
                        enabled = !loading,
                    ) {
                        Text("Manage access")
                    }
                    TextButton(
                        onClick = { onAction(AgentLinkUiAction.Revoke) },
                        enabled = !loading,
                    ) {
                        Text("Revoke")
                    }
                }
            }
        }
        TextButton(
            onClick = { toolsExpanded = !toolsExpanded },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (toolsExpanded) {
                    "Hide tools (${AGENTLINK_TOOL_DESCRIPTORS.size})"
                } else {
                    "Show tools (${AGENTLINK_TOOL_DESCRIPTORS.size})"
                },
            )
        }
        AnimatedVisibility(visible = toolsExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AGENTLINK_TOOL_DESCRIPTORS.forEach { tool ->
                    BuiltInToolRow(
                        tool = BuiltInToolSummary(
                            name = tool.name,
                            displayName = tool.displayName,
                            description = tool.description,
                            enabled = tool.name in state.enabledTools,
                        ),
                        disabled = loading,
                        onSetEnabled = { name, enabled ->
                            onAction(AgentLinkUiAction.EnableTool(name, enabled))
                        },
                    )
                }
            }
        }
        if (state.links.isNotEmpty()) {
            if (!chatsExpanded && state.links.any { it.outcomeUnknown }) {
                Text("Outcome unknown", color = MaterialTheme.colorScheme.error)
            }
            TextButton({ chatsExpanded = !chatsExpanded }) { Text("Linked remote chats · refresh via tools") }
            if (chatsExpanded) {
                state.links.forEach { link ->
                    TextButton(
                        enabled = state.authorized && !loading,
                        onClick = { onAction(AgentLinkUiAction.OpenChat(link)) },
                    ) {
                        Text(
                            link.title.take(100) +
                                (link.taskId?.let { " · ${it.take(40)}" } ?: ""),
                        )
                    }
                    if (link.outcomeUnknown) {
                        Text(
                            text = "Outcome unknown",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
