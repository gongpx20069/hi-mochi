package com.example.mochi_pet.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.mochi_pet.core.extensions.TermuxTaskView
import com.example.mochi_pet.core.tools.ExtensionProviderSummary
import com.example.mochi_ui.ExtensionCard
import com.example.mochi_ui.ExtensionHeading
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed interface TermuxUiAction {
    data object Install : TermuxUiAction
    data object Configure : TermuxUiAction
    data object Disconnect : TermuxUiAction
    data object EnableWithSkill : TermuxUiAction
    data object Tasks : TermuxUiAction
    data object CloseTasks : TermuxUiAction
    data class Enable(val enabled: Boolean) : TermuxUiAction
    data class EnableTool(val name: String, val enabled: Boolean) : TermuxUiAction
    data class Task(val id: String, val action: String) : TermuxUiAction
}

@Composable
internal fun TermuxProviderCard(
    summary: ExtensionProviderSummary,
    disabled: Boolean,
    onAction: (TermuxUiAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var management by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    ExtensionCard {
        ExtensionHeading(
            title = "Termux",
            status = localizeUiText(when {
                !summary.installed -> "Optional extension · not installed"
                !summary.trusted -> "Installed package could not be trusted"
                summary.connected && !summary.enabled -> "Connected - not enabled"
                summary.connected -> "Connected"
                else -> "Installed · connection required"
            }),
        ) {
            Switch(summary.enabled, { onAction(TermuxUiAction.Enable(it)) },
                enabled = summary.connected && !disabled)
        }
        Text("Enabled commands run automatically for conversations, Scheduled Agents, and Subagents. Output may go to your model Provider.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        summary.detail?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        when {
            !summary.installed || !summary.trusted ->
                Button({ onAction(TermuxUiAction.Install) }, enabled = !disabled) { Text("Install extension") }
            !summary.connected ->
                Button({ onAction(TermuxUiAction.Configure) }, enabled = !disabled) { Text("Configure Termux") }
            !summary.enabled ->
                Button({ onAction(TermuxUiAction.EnableWithSkill) }, enabled = !disabled) { Text("Enable tools and Skill") }
            else -> TextButton({ onAction(TermuxUiAction.Tasks) }, enabled = !disabled) { Text("Termux tasks") }
        }
        TextButton({ management = !management }) { Text(if (management) "Hide connection settings" else "Connection settings") }
        if (management) {
            summary.versionName?.let { Text("Extension $it", style = MaterialTheme.typography.bodySmall) }
            if (summary.trusted) {
                TextButton({ onAction(TermuxUiAction.Configure) }, enabled = !disabled) { Text("Check connection") }
                if (summary.connected) {
                    if (summary.enabled) {
                        TextButton({ onAction(TermuxUiAction.EnableWithSkill) }, enabled = !disabled) { Text("Enable tools and Skill") }
                    }
                    TextButton({ confirmDisconnect = true }, enabled = !disabled) { Text("Disconnect") }
                }
            }
        }
        if (summary.tools.isNotEmpty()) {
            TextButton({ expanded = !expanded }) { Text(if (expanded) "Hide tools" else "Show tools") }
            if (expanded) summary.tools.forEach { tool ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(tool.name, Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                    Switch(tool.enabled, { onAction(TermuxUiAction.EnableTool(tool.name, it)) },
                        enabled = summary.enabled && !disabled)
                }
            }
        }
    }
    if (confirmDisconnect) AlertDialog(
        onDismissRequest = { confirmDisconnect = false },
        title = { Text("Disconnect Termux?") },
        text = { Text("New calls will be disabled for all Agents. Submitted commands will not be stopped.") },
        confirmButton = { TextButton({
            confirmDisconnect = false
            onAction(TermuxUiAction.Disconnect)
        }) { Text("Disconnect") } },
        dismissButton = { TextButton({ confirmDisconnect = false }) { Text("Cancel") } },
    )
}

@Composable
internal fun TermuxTasksDialog(
    tasks: List<TermuxTaskView>,
    onAction: (TermuxUiAction) -> Unit,
    feedback: String?,
    loading: Boolean,
) {
    AlertDialog(
        onDismissRequest = { onAction(TermuxUiAction.CloseTasks) },
        title = { Text("Termux tasks") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text("Closing Mochi does not stop commands. Detached processes may survive a stop request.")
                feedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (tasks.isEmpty()) Text("No tasks")
                tasks.forEach { task ->
                    val data = task.result.data as? JsonObject
                    Text(task.id, style = MaterialTheme.typography.labelSmall)
                    Text((data?.get("state") as? JsonPrimitive)?.content ?: task.result.message ?: "Unknown")
                    Row {
                        TextButton({ onAction(TermuxUiAction.Task(task.id, "read")) }, enabled = !loading) { Text("Refresh") }
                        TextButton({ onAction(TermuxUiAction.Task(task.id, "stop")) }, enabled = !loading) { Text("Stop") }
                        TextButton({ onAction(TermuxUiAction.Task(task.id, "forget")) }, enabled = !loading) { Text("Forget") }
                    }
                    var showOutput by remember(task.id) { mutableStateOf(false) }
                    TextButton({ showOutput = !showOutput }) { Text("Command output") }
                    if (showOutput) {
                        SelectionContainer { Text(data?.toString().orEmpty(), fontFamily = FontFamily.Monospace) }
                    }
                }
            }
        },
        confirmButton = { TextButton({ onAction(TermuxUiAction.Tasks) }, enabled = !loading) { Text("Refresh") } },
        dismissButton = { TextButton({ onAction(TermuxUiAction.CloseTasks) }) { Text("Close") } },
    )
}
