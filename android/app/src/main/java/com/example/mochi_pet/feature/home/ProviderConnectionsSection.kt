package com.example.mochi_pet.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.example.mochi_pet.core.settings.ProviderPreset
import com.example.mochi_pet.core.settings.ProviderProfileInput
import com.example.mochi_pet.core.settings.ProviderProfileSummary
import com.example.mochi_pet.core.settings.ProviderSettingsInput
import com.example.mochi_pet.core.settings.ProviderSettingsSummary

sealed interface ProviderProfileAction {
    class Save(val input: ProviderProfileInput) : ProviderProfileAction
    data class Activate(val id: String) : ProviderProfileAction
    data class Delete(val id: String) : ProviderProfileAction
}

@Composable
internal fun SettingsPartHeading(title: String, description: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("settings-part-$title"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun ProviderConnectionsSection(
    state: ProviderSettingsUiState,
    onAction: (ProviderProfileAction) -> Unit,
) {
    var editing by remember { mutableStateOf<ProviderProfileSummary?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ProviderProfileSummary?>(null) }
    var submitted by remember { mutableStateOf(false) }
    LaunchedEffect(state.isSaving, state.feedback) {
        if (submitted && !state.isSaving && state.feedback == "AI connection saved") {
            showEditor = false
            submitted = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Switching connections cancels running Agent tasks, including their child agents and scheduled runs. Future schedules remain enabled.")
        if (state.profiles.profiles.isEmpty()) {
            Text("Add your first AI connection. Official endpoints are filled in for you; your API key stays encrypted on this device.")
        } else if (state.profiles.active == null) {
            Text("No active AI connection. Choose a saved connection to continue.")
        }
        state.profiles.profiles.forEach { profile ->
            Surface(
                color = if (state.profiles.activeId == profile.id) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag("provider-profile-${profile.id}"),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.Text(profile.name, fontWeight = FontWeight.Bold)
                    Text(if (state.profiles.activeId == profile.id) "Currently selected" else "Saved connection")
                    androidx.compose.material3.Text(
                        "${profile.preset.title} · ${profile.settings.model}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    androidx.compose.material3.Text(
                        profile.settings.endpoint, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = !state.isSaving && state.profiles.activeId != profile.id && profile.settings.isReady,
                            onClick = { onAction(ProviderProfileAction.Activate(profile.id)) },
                        ) { Text("Use") }
                        TextButton(
                            enabled = !state.isSaving,
                            onClick = { editing = profile; submitted = false; showEditor = true },
                        ) { Text("Edit") }
                        TextButton(enabled = !state.isSaving, onClick = { deleting = profile }) { Text("Delete") }
                    }
                    if (!profile.settings.isReady) Text("Edit this connection to complete its settings.")
                }
            }
        }
        Button(
            onClick = { editing = null; submitted = false; showEditor = true },
            enabled = !state.isLoading && !state.isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add AI connection") }
        state.feedback?.let { Text(it) }
        if (state.isSaving) Text("Applying connection change...")
    }
    if (showEditor) {
        ProviderConnectionDialog(
            profile = editing, state = state,
            onDismiss = { if (!state.isSaving) showEditor = false },
            onSave = { submitted = true; onAction(ProviderProfileAction.Save(it)) },
        )
    }
    deleting?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete AI connection") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Text(profile.name)
                    Text(if (state.profiles.activeId == profile.id) {
                        "This cancels running Agent tasks and removes this connection and key. No other connection will be selected automatically."
                    } else "This removes only this saved connection and its encrypted key.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onAction(ProviderProfileAction.Delete(profile.id))
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun ProviderConnectionDialog(
    profile: ProviderProfileSummary?,
    state: ProviderSettingsUiState,
    onDismiss: () -> Unit,
    onSave: (ProviderProfileInput) -> Unit,
) {
    val original = profile?.settings ?: ProviderSettingsSummary()
    var preset by remember(profile) { mutableStateOf(profile?.preset ?: ProviderPreset.OPENAI) }
    var name by remember(profile) { mutableStateOf(profile?.name ?: ProviderPreset.OPENAI.title) }
    var endpoint by remember(profile) { mutableStateOf(profile?.settings?.endpoint ?: preset.endpoint) }
    var model by remember(profile) { mutableStateOf(original.model) }
    var version by remember(profile) { mutableStateOf(original.apiVersion) }
    var timeout by remember(profile) { mutableStateOf(original.timeoutSeconds.toString()) }
    var images by remember(profile) { mutableStateOf(original.imageInputEnabled) }
    var key by remember(profile) { mutableStateOf("") }
    var choosePreset by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = onDismiss,
        title = { Text(if (profile == null) "Add AI connection" else "Edit AI connection") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).testTag("provider-editor"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Connection name") },
                    enabled = !state.isSaving, singleLine = true, modifier = Modifier.fillMaxWidth())
                Column {
                    OutlinedButton(onClick = { choosePreset = true }, enabled = !state.isSaving) { Text(preset.title) }
                    DropdownMenu(expanded = choosePreset, onDismissRequest = { choosePreset = false }) {
                        ProviderPreset.entries.forEach { option ->
                            DropdownMenuItem(text = { Text(option.title) }, onClick = {
                                if (option != preset) {
                                    if (name == preset.title) name = option.title
                                    preset = option
                                    endpoint = option.endpoint
                                    model = ""
                                    key = ""
                                }
                                choosePreset = false
                            })
                        }
                    }
                }
                Text("Official presets use standard usage-billed APIs, not Coding Plans. Check your account region; you can replace any endpoint.")
                OutlinedTextField(
                    endpoint, { endpoint = it }, enabled = !state.isSaving,
                    label = { Text(if (preset == ProviderPreset.AZURE) "Azure resource endpoint" else "API endpoint") },
                    supportingText = { Text(if (preset == ProviderPreset.AZURE) {
                        "Azure Portal → Azure OpenAI → Keys and Endpoint"
                    } else "Mochi appends /chat/completions when needed.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (preset.endpoint.isNotEmpty()) TextButton(
                    onClick = { endpoint = preset.endpoint }, enabled = !state.isSaving,
                ) { Text("Restore official endpoint") }
                if (preset.documentation.isNotEmpty()) TextButton(onClick = { uriHandler.openUri(preset.documentation) }) {
                    Text("Open official API guide")
                }
                OutlinedTextField(
                    model, { model = it }, enabled = !state.isSaving,
                    label = { Text(if (preset == ProviderPreset.AZURE) "Deployment name" else "Model name") },
                    supportingText = { Text("Enter a model available to your account that supports tool calling.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (preset == ProviderPreset.AZURE) OutlinedTextField(
                    version, { version = it }, enabled = !state.isSaving,
                    label = { Text("Azure API version") }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    key, { key = it }, enabled = !state.isSaving, label = { Text("API key") },
                    supportingText = { Text(if (original.hasApiKey) "Leave blank to keep the stored key." else "Encrypted using Android Keystore.") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Changing provider or endpoint host requires a new API key.")
                TextButton(onClick = { advanced = !advanced }) { Text("Advanced connection settings") }
                if (advanced) {
                    OutlinedTextField(timeout, { timeout = it.filter(Char::isDigit) },
                        label = { Text("Timeout seconds") }, enabled = !state.isSaving,
                        supportingText = { Text("Per AI request. Chat and voice use at most 20 seconds; scheduled agents use this value.") })
                    Row {
                        Text("Multimodal input", modifier = Modifier.weight(1f))
                        Switch(images, { images = it }, enabled = !state.isSaving)
                    }
                    Text("Enable image input only when this model supports images. This setting is saved separately for each connection.")
                }
                state.feedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !state.isSaving && name.isNotBlank() && endpoint.isNotBlank() && model.isNotBlank() &&
                    (original.hasApiKey || key.isNotBlank()),
                onClick = {
                    onSave(ProviderProfileInput(
                        id = profile?.id, name = name, preset = preset,
                        settings = ProviderSettingsInput(
                            providerType = preset.protocol, endpoint = endpoint, model = model,
                            apiVersion = version, timeoutSeconds = timeout.toIntOrNull() ?: 0,
                            maxResponseBytes = original.maxResponseBytes, imageInputEnabled = images,
                            apiKeyReplacement = key,
                        ),
                    ))
                },
            ) { Text(if (state.isSaving) "Saving..." else "Save connection") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.isSaving) { Text("Cancel") } },
    )
}
