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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.mochi_pet.core.extensions.TermuxApprovalChoice
import com.example.mochi_pet.core.extensions.TermuxApprovalRequest
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
    data class EnableBackground(val enabled: Boolean) : TermuxUiAction
    data class EnableTool(val name: String, val enabled: Boolean) : TermuxUiAction
    data class Task(val id: String, val action: String) : TermuxUiAction
    data class Approve(val id: String, val choice: TermuxApprovalChoice) : TermuxUiAction
}

@Composable
internal fun TermuxProviderCard(
    summary: ExtensionProviderSummary,
    backgroundEnabled: Boolean,
    disabled: Boolean,
    onAction: (TermuxUiAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var management by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var confirmBackground by remember(summary.enabled) { mutableStateOf(false) }
    val backgroundLabel = localizeUiText("Background Shell authorization")
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
        Text("Unrestricted local shell. Commands require approval; output may be sent to your model Provider.",
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Background Shell authorization", Modifier.weight(1f))
                Switch(
                    checked = backgroundEnabled,
                    onCheckedChange = {
                        if (it) confirmBackground = true else onAction(TermuxUiAction.EnableBackground(false))
                    },
                    enabled = !disabled && (backgroundEnabled || summary.enabled),
                    modifier = Modifier.semantics { contentDescription = backgroundLabel },
                )
            }
            Text("Off by default. Allows all Scheduled Agents and Subagents to use enabled Termux tools without per-call confirmation.")
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
        text = { Text("New calls and background authorization will be disabled. Submitted commands will not be stopped.") },
        confirmButton = { TextButton({
            confirmDisconnect = false
            onAction(TermuxUiAction.Disconnect)
        }) { Text("Disconnect") } },
        dismissButton = { TextButton({ confirmDisconnect = false }) { Text("Cancel") } },
    )
    if (confirmBackground) {
        AlertDialog(
            onDismissRequest = { confirmBackground = false },
            title = { Text("Allow background Shell execution?") },
            text = {
                Text(
                    "All Scheduled Agents and Subagents, including those delegated from a conversation, " +
                        "can run unrestricted commands, change or delete accessible files, and use the network. " +
                        "Output may go to your model Provider. This permission persists across restarts. " +
                        "Foreground Main-Agent calls still require approval. Turning this off blocks new calls, " +
                        "but does not stop submitted commands. Disabling or disconnecting Termux clears this permission.",
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmBackground = false
                        onAction(TermuxUiAction.EnableBackground(true))
                    },
                    enabled = !disabled && summary.enabled,
                ) { Text("Authorize background Shell") }
            },
            dismissButton = {
                TextButton({ confirmBackground = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
internal fun TermuxApprovalDialog(
    request: TermuxApprovalRequest,
    onAction: (TermuxUiAction) -> Unit,
    onVoice: () -> Unit,
) {
    fun choose(choice: TermuxApprovalChoice) = onAction(TermuxUiAction.Approve(request.id, choice))
    AlertDialog(
        onDismissRequest = { choose(TermuxApprovalChoice.DENY) },
        title = { Text("Allow Termux command?") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Commands can read or change Termux files and use the network. Output goes to your model Provider.")
                SelectionContainer {
                    Text("${request.tool}\n${request.arguments}", fontFamily = FontFamily.Monospace)
                }
                Text("Say: execute once, allow this task, or cancel.")
                TextButton(onVoice) { Text("Voice confirmation") }
            }
        },
        confirmButton = {
            Column {
                TextButton({ choose(TermuxApprovalChoice.ONCE) }) { Text("Execute once") }
                TextButton({ choose(TermuxApprovalChoice.THIS_RUN) }) { Text("Allow this task") }
            }
        },
        dismissButton = { TextButton({ choose(TermuxApprovalChoice.DENY) }) { Text("Cancel") } },
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

internal fun termuxVoiceChoice(transcript: String): TermuxApprovalChoice? =
    when (transcript.trim().trimEnd('.', '!', '?', '。', '！', '？').lowercase()) {
        "execute once", "执行", "执行一次", "确认执行" -> TermuxApprovalChoice.ONCE
        "allow this task", "允许本次任务", "本次任务自动执行" -> TermuxApprovalChoice.THIS_RUN
        "cancel", "取消", "拒绝" -> TermuxApprovalChoice.DENY
        else -> null
    }
