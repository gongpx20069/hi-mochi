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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.example.mochi_pet.core.settings.SpeechProfileInput
import com.example.mochi_pet.core.settings.SpeechProfileSummary
import com.example.mochi_pet.core.settings.SpeechProvider
import com.example.mochi_pet.core.settings.SpeechSettingsInput
import com.example.mochi_pet.core.settings.SpeechSettingsSummary
import com.example.mochi_pet.core.settings.SYSTEM_SPEECH_PROFILE_ID
import com.example.mochi_pet.core.settings.connectionTitle
import com.example.mochi_pet.core.voice.VoiceRuntimeState

sealed interface SpeechProfileAction {
    class Save(val input: SpeechProfileInput) : SpeechProfileAction
    data class Activate(val id: String) : SpeechProfileAction
    data class Delete(val id: String) : SpeechProfileAction
}

@Composable
internal fun SpeechConnectionsSection(
    state: SpeechSettingsUiState,
    catalog: SpeechVoiceUiState,
    playback: VoiceRuntimeState,
    onAction: (SpeechProfileAction) -> Unit,
    onLoadVoices: (SpeechProvider, String?) -> Unit,
    onPreview: (SpeechProvider, String, String?) -> Unit,
    onStopPreview: () -> Unit,
) {
    var editing by remember { mutableStateOf<SpeechProfileSummary?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SpeechProfileSummary?>(null) }
    var submitted by remember { mutableStateOf(false) }
    LaunchedEffect(state.isSaving, state.feedback) {
        if (submitted && !state.isSaving && state.feedback == "Speech connection saved") {
            editorOpen = false
            submitted = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Save separate speech accounts and voices. Switching stops the current voice interaction and preview, not text conversations or background Agent tasks.")
        state.profiles.profiles.forEach { profile ->
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("speech-profile-${profile.id}"),
                shape = MaterialTheme.shapes.medium,
                color = if (profile.id == state.profiles.activeId) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.Text(profile.name, fontWeight = FontWeight.Bold)
                    Text(if (profile.id == state.profiles.activeId) "Currently selected" else "Saved connection")
                    Text(profile.settings.provider.connectionTitle)
                    Text(if (profile.settings.synthesisEnabled) "Cloud recognition and synthesis" else "Recognition with Android speech output")
                    val voice = when (profile.settings.provider) {
                        SpeechProvider.SYSTEM -> profile.settings.systemVoice
                        SpeechProvider.IFLYTEK -> profile.settings.iFlytekVoice
                        SpeechProvider.AZURE -> profile.settings.azureVoice
                    }
                    Text(voice.ifEmpty { "Follow app default" }, style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onAction(SpeechProfileAction.Activate(profile.id)) },
                            enabled = !state.isSaving && !state.isLoading && profile.id != state.profiles.activeId && profile.settings.isReady,
                        ) { Text("Use") }
                        TextButton(onClick = { editing = profile; editorOpen = true; submitted = false },
                            enabled = !state.isSaving && !state.isLoading) { Text("Edit") }
                        if (profile.id != SYSTEM_SPEECH_PROFILE_ID) TextButton(
                            onClick = { deleting = profile },
                            enabled = !state.isSaving && profile.id != state.profiles.activeId,
                        ) { Text("Delete") }
                    }
                    if (!profile.settings.isReady) Text("Edit this connection to complete its settings.")
                    if (profile.id != SYSTEM_SPEECH_PROFILE_ID && profile.id == state.profiles.activeId) {
                        Text("Select another speech connection before deleting this one.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Button(onClick = { editing = null; editorOpen = true; submitted = false },
            enabled = !state.isLoading && !state.isSaving, modifier = Modifier.fillMaxWidth()) { Text("Add speech connection") }
        state.feedback?.let { Text(it) }
    }
    if (editorOpen) SpeechConnectionDialog(
        editing, state, catalog, playback,
        onDismiss = { if (!state.isSaving) { onStopPreview(); editorOpen = false } },
        onSave = { submitted = true; onAction(SpeechProfileAction.Save(it)) },
        onLoadVoices = onLoadVoices, onPreview = onPreview, onStopPreview = onStopPreview,
    )
    deleting?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text("Delete speech connection") },
            text = { Text("This removes only this saved connection and its encrypted key.") },
            confirmButton = { TextButton(onClick = { onAction(SpeechProfileAction.Delete(profile.id)); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun SpeechConnectionDialog(
    profile: SpeechProfileSummary?,
    state: SpeechSettingsUiState,
    catalog: SpeechVoiceUiState,
    playback: VoiceRuntimeState,
    onDismiss: () -> Unit,
    onSave: (SpeechProfileInput) -> Unit,
    onLoadVoices: (SpeechProvider, String?) -> Unit,
    onPreview: (SpeechProvider, String, String?) -> Unit,
    onStopPreview: () -> Unit,
) {
    val original = profile?.settings ?: SpeechSettingsSummary(provider = SpeechProvider.IFLYTEK)
    var provider by remember(profile) { mutableStateOf(original.provider) }
    var name by remember(profile) { mutableStateOf(profile?.name ?: provider.connectionTitle) }
    var appId by remember(profile) { mutableStateOf(original.iFlytekAppId) }
    var endpoint by remember(profile) { mutableStateOf(original.azureEndpoint) }
    var key by remember(profile) { mutableStateOf("") }
    var secret by remember(profile) { mutableStateOf("") }
    var synthesis by remember(profile) { mutableStateOf(original.synthesisEnabled) }
    var voice by remember(profile) { mutableStateOf(when (provider) {
        SpeechProvider.SYSTEM -> original.systemVoice
        SpeechProvider.IFLYTEK -> original.iFlytekVoice
        SpeechProvider.AZURE -> original.azureVoice
    }) }
    var choosing by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    DisposableEffect(Unit) { onDispose { onStopPreview() } }
    AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = onDismiss,
        title = { Text(if (profile == null) "Add speech connection" else "Edit speech connection") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("speech-editor"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Connection name") },
                    enabled = !state.isSaving, modifier = Modifier.fillMaxWidth())
                if (profile == null) Column {
                    OutlinedButton(onClick = { choosing = true }, enabled = !state.isSaving) { Text(provider.connectionTitle) }
                    DropdownMenu(choosing, { choosing = false }) {
                        listOf(SpeechProvider.IFLYTEK, SpeechProvider.AZURE).forEach { option ->
                            DropdownMenuItem(text = { Text(option.connectionTitle) }, onClick = {
                                if (option != provider) {
                                    if (name == provider.connectionTitle) name = option.connectionTitle
                                    provider = option; key = ""; secret = ""; appId = ""; endpoint = ""; voice = ""; synthesis = false
                                }
                                choosing = false
                            })
                        }
                    }
                } else Text(provider.connectionTitle)
                if (provider == SpeechProvider.IFLYTEK) {
                    OutlinedTextField(appId, { appId = it }, label = { Text("iFlytek AppID") },
                        enabled = !state.isSaving, modifier = Modifier.fillMaxWidth())
                    SpeechSecretField(key, { key = it }, "iFlytek APIKey", original.hasIFlytekApiKey, !state.isSaving)
                    SpeechSecretField(secret, { secret = it }, "iFlytek APISecret", original.hasIFlytekApiSecret, !state.isSaving)
                    TextButton(onClick = { uri.openUri("https://www.xfyun.cn/services/voicedictation") }) { Text("Open iFlytek registration") }
                }
                if (provider == SpeechProvider.AZURE) {
                    OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Azure Speech endpoint") },
                        placeholder = { Text("https://your-resource.cognitiveservices.azure.com") },
                        enabled = !state.isSaving, modifier = Modifier.fillMaxWidth())
                    SpeechSecretField(key, { key = it }, "Azure Speech key", original.hasAzureApiKey, !state.isSaving)
                    Text("Changing the speech endpoint requires a new API key.")
                    TextButton(onClick = { uri.openUri("https://portal.azure.com/#create/Microsoft.CognitiveServicesSpeechServices") }) { Text("Open Azure Speech setup") }
                }
                if (provider != SpeechProvider.SYSTEM) {
                    Row {
                        Text("Also use this provider for speech synthesis", modifier = Modifier.weight(1f))
                        Switch(synthesis, { synthesis = it }, enabled = !state.isSaving)
                    }
                    Text("Reuses the saved credentials. When enabled, assistant reply text is sent to this provider. Wake acknowledgements always use Android speech. Disabled by default.")
                }
                if (provider == SpeechProvider.SYSTEM || synthesis) {
                    val ready = profile != null && original.isReady && !state.isSaving && key.isBlank() && secret.isBlank() &&
                        appId.trim() == original.iFlytekAppId && endpoint.trim().trimEnd('/') == original.azureEndpoint
                    SpeechVoicePicker(
                        provider, voice,
                        if (catalog.provider == provider && catalog.profileId == profile?.id) catalog else SpeechVoiceUiState(provider = provider, profileId = profile?.id),
                        playback, ready,
                        onChoose = { voice = it },
                        onLoad = { if (profile != null) onLoadVoices(provider, profile.id) },
                        onPreview = { if (profile != null) onPreview(provider, it, profile.id) },
                        onStop = onStopPreview,
                    )
                    if (profile == null) Text("Save this connection, then edit it to load voices and preview without switching.")
                }
                if (profile == null) Text("Saving adds this connection without changing the current selection. Tap Use when you are ready.")
                state.feedback?.let { Text(it) }
            }
        },
        confirmButton = {
            Button(enabled = !state.isSaving && name.isNotBlank(), onClick = {
                onSave(SpeechProfileInput(profile?.id, name, SpeechSettingsInput(
                    provider = provider, iFlytekAppId = appId, azureEndpoint = endpoint,
                    iFlytekApiKeyReplacement = if (provider == SpeechProvider.IFLYTEK) key else null,
                    iFlytekApiSecretReplacement = if (provider == SpeechProvider.IFLYTEK) secret else null,
                    azureApiKeyReplacement = if (provider == SpeechProvider.AZURE) key else null,
                    synthesisEnabled = synthesis,
                    iFlytekVoice = voice.takeIf { provider == SpeechProvider.IFLYTEK },
                    azureVoice = voice.takeIf { provider == SpeechProvider.AZURE },
                    systemVoice = voice.takeIf { provider == SpeechProvider.SYSTEM },
                )))
            }) { Text(if (state.isSaving) "Saving..." else "Save speech connection") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.isSaving) { Text("Cancel") } },
    )
}

@Composable
private fun SpeechSecretField(value: String, onChange: (String) -> Unit, label: String, saved: Boolean, enabled: Boolean) {
    OutlinedTextField(value, onChange, label = { Text(label) }, enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        supportingText = { Text(if (saved) "Leave blank to keep the stored key." else "Encrypted using Android Keystore.") },
        modifier = Modifier.fillMaxWidth())
}
