package com.example.mochi_pet.feature.home

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.mochi_pet.core.tools.ExtensionProviderSummary
import com.example.mochi_ui.ExtensionCard
import com.example.mochi_ui.ExtensionHeading

sealed interface WebdavUiAction {
    data object Install : WebdavUiAction
    data object Configure : WebdavUiAction
    data object Disconnect : WebdavUiAction
    data object EnableWithSkill : WebdavUiAction
    data class Enable(val enabled: Boolean) : WebdavUiAction
    data class EnableTool(val name: String, val enabled: Boolean) : WebdavUiAction
}

@Composable
internal fun WebdavProviderCard(summary: ExtensionProviderSummary, disabled: Boolean, onAction: (WebdavUiAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var disconnect by remember { mutableStateOf(false) }
    ExtensionCard {
        ExtensionHeading(localizeUiText("WebDAV Files"), localizeUiText(when {
            !summary.installed -> "Optional extension · not installed"
            !summary.trusted -> "Installed package could not be trusted"
            !summary.connected -> "Installed · connection required"
            !summary.enabled -> "Connected - not enabled"
            else -> "Connected"
        })) {
            Switch(summary.enabled, { onAction(WebdavUiAction.Enable(it)) }, enabled = summary.connected && !disabled)
        }
        Text("Browse a scoped WebDAV folder. File content may go to your model Provider. Foreground conversations only.")
        Text("Read-only by default. Writing, deletion and file transfer require separate Tool switches.")
        summary.detail?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        when {
            !summary.installed || !summary.trusted ->
                Button({ onAction(WebdavUiAction.Install) }, enabled = !disabled) { Text("Install extension") }
            !summary.connected ->
                Button({ onAction(WebdavUiAction.Configure) }, enabled = !disabled) { Text("Connect WebDAV") }
            !summary.enabled ->
                Button({ onAction(WebdavUiAction.EnableWithSkill) }, enabled = !disabled) { Text("Enable tools and Skill") }
        }
        if (summary.trusted) {
            if (summary.connected) TextButton({ onAction(WebdavUiAction.Configure) }, enabled = !disabled) {
                Text("Connection and files")
            }
            if (summary.connected || summary.status == "error" || summary.detail != null) {
                TextButton({ disconnect = true }, enabled = !disabled) { Text("Disconnect") }
                if (summary.enabled) TextButton({ onAction(WebdavUiAction.EnableWithSkill) }, enabled = !disabled) {
                    Text("Enable tools and Skill")
                }
            }
        }
        if (summary.tools.isNotEmpty()) {
            TextButton({ expanded = !expanded }) { Text(if (expanded) "Hide tools" else "Show tools") }
            if (expanded) summary.tools.forEach { tool ->
                Row {
                    Text(tool.name, Modifier.weight(1f))
                    Switch(tool.enabled, { onAction(WebdavUiAction.EnableTool(tool.name, it)) },
                        enabled = summary.enabled && !disabled)
                }
            }
        }
    }
    if (disconnect) AlertDialog(
        onDismissRequest = { disconnect = false },
        title = { Text("Disconnect WebDAV?") },
        text = { Text("Remove the saved connection and staged files. Remote files are not deleted. Inspect any interrupted write before retrying.") },
        confirmButton = { TextButton({ disconnect = false; onAction(WebdavUiAction.Disconnect) }) { Text("Disconnect") } },
        dismissButton = { TextButton({ disconnect = false }) { Text("Cancel") } },
    )
}
