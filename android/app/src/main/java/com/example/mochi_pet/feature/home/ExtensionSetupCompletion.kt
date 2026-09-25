package com.example.mochi_pet.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.mochi_pet.core.tools.ToolCatalogSummary

enum class ExtensionSetupKind(val title: String, val skillId: String) {
    TERMUX("Termux", "builtin:termux"),
    MIJIA("Mi Home", "builtin:mi-home-smart-home"),
    AGENTLINK("AgentLink", "builtin:agentlink");

    fun connected(catalog: ToolCatalogSummary): Boolean = when (this) {
        TERMUX -> catalog.termux.trusted && catalog.termux.connected
        MIJIA -> catalog.mijia.trusted && catalog.mijia.connected
        AGENTLINK -> catalog.agentLink.authorized && catalog.agentLink.connected
    }

    fun enabled(catalog: ToolCatalogSummary): Boolean = when (this) {
        TERMUX -> catalog.termux.enabled
        MIJIA -> catalog.mijia.enabled
        AGENTLINK -> catalog.agentLink.enabled
    }

    companion object {
        fun fromPackage(packageName: String?): ExtensionSetupKind? = when (packageName) {
            "com.example.mochi_pet.extension.termux" -> TERMUX
            "com.example.mochi_pet.extension.mijia" -> MIJIA
            else -> null
        }
    }
}

@Composable
internal fun ExtensionSetupCompletionDialog(
    kind: ExtensionSetupKind,
    onEnable: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(kind.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Connection verified. Enable this extension and its Skill?")
                Text(when (kind) {
                    ExtensionSetupKind.TERMUX -> "Run local shell commands and inspect or stop tasks. Output may go to your model Provider."
                    ExtensionSetupKind.MIJIA -> "Read and control selected smart-home devices and scenes."
                    ExtensionSetupKind.AGENTLINK -> "Use authorized remote workspaces, shared chats, and agent controls."
                })
                Text("This also enables any disabled tools required by the Skill. Other tool choices are preserved.")
                if (kind == ExtensionSetupKind.TERMUX) {
                    Text("Enabled commands run automatically for conversations, Scheduled Agents, and Subagents. Output may go to your model Provider.")
                }
            }
        },
        confirmButton = { TextButton(onEnable) { Text("Enable tools and Skill") } },
        dismissButton = { TextButton(onDismiss) { Text("Not now") } },
    )
}
