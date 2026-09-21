package com.example.mochi_pet.feature.home

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.mochi_pet.core.settings.AppLanguage
import com.example.mochi_pet.core.settings.SpeechProvider
import com.example.mochi_pet.core.voice.IFLYTEK_BASIC_VOICES
import com.example.mochi_pet.core.voice.SpeechPlaybackStage
import com.example.mochi_pet.core.voice.SpeechVoice
import com.example.mochi_pet.core.voice.VoiceRuntimeState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechVoicePicker(
    provider: SpeechProvider,
    voiceId: String,
    catalog: SpeechVoiceUiState,
    playback: VoiceRuntimeState,
    connectionReady: Boolean,
    onChoose: (String) -> Unit,
    onLoad: () -> Unit,
    onPreview: (String) -> Unit,
    onStop: () -> Unit,
) {
    var open by rememberSaveable(provider) { mutableStateOf(false) }
    var selected by rememberSaveable(provider, voiceId) { mutableStateOf(voiceId) }
    var search by rememberSaveable(provider) { mutableStateOf("") }
    var allLanguages by rememberSaveable(provider) { mutableStateOf(false) }
    var custom by rememberSaveable(provider) { mutableStateOf(false) }
    val stop by rememberUpdatedState(onStop)
    DisposableEffect(Unit) { onDispose { stop() } }
    LaunchedEffect(provider, connectionReady) { stop() }
    val voices = when {
        provider == SpeechProvider.IFLYTEK -> IFLYTEK_BASIC_VOICES
        catalog.provider == provider -> catalog.voices
        else -> emptyList()
    }
    val voiceName = voices.firstOrNull { it.id == voiceId }?.name
        ?: voiceId.ifBlank { "Follow app default" }
    OutlinedButton(
        onClick = {
            selected = voiceId
            custom = false
            open = true
            if (connectionReady || provider != SpeechProvider.AZURE) onLoad()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Text("Voice")
            Text(voiceName)
            if (voiceId.isNotEmpty() && voiceName != voiceId) Text(voiceId)
        }
    }
    PreviewButton(voiceId, catalog, playback, connectionReady, onPreview, onStop)
    Text(
        if (provider == SpeechProvider.SYSTEM) {
            "Offline voices installed on this phone. Playback follows media volume."
        } else {
            "Preview uses a fixed greeting and consumes synthesis quota. Listed voices are not a guarantee of authorization or free usage."
        },
        style = MaterialTheme.typography.bodySmall,
    )
    if (!connectionReady) Text("Save the connection settings first")
    if (!open) PreviewFeedback(catalog)
    if (provider == SpeechProvider.SYSTEM) {
        val context = LocalContext.current
        TextButton(onClick = {
            onStop()
            try {
                context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(
                    context,
                    localizeUiText("System speech settings are unavailable", AppLanguage.resolveContentLocale().language),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }) { Text("Open system speech settings") }
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { onStop(); open = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Choose voice", style = MaterialTheme.typography.titleLarge)
                if (provider != SpeechProvider.IFLYTEK) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        label = { Text("Search voices") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = allLanguages, onCheckedChange = { allLanguages = it })
                        Text("All languages", modifier = Modifier.weight(1f))
                        TextButton(onClick = onLoad, enabled = connectionReady && !catalog.isLoading) {
                            Text("Refresh")
                        }
                    }
                }
                if (catalog.isLoading) Text("Loading voices...")
                catalog.catalogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val filtered = filterSpeechVoices(
                    voices, search, allLanguages || provider == SpeechProvider.IFLYTEK, AppLanguage.resolveContentLocale(),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item {
                        VoiceChoice("", "Follow app default", "", selected, catalog, playback, connectionReady,
                            { onStop(); selected = it; custom = false }, onPreview, onStop)
                    }
                    items(filtered, key = { it.id }) { voice ->
                        VoiceChoice(
                            voice.id, voice.name,
                            "${Locale.forLanguageTag(voice.languageTag).getDisplayName(AppLanguage.resolveContentLocale())} · " +
                                localizeUiText(voice.category, AppLanguage.resolveContentLocale().language),
                            selected, catalog, playback, connectionReady,
                            { onStop(); selected = it; custom = false }, onPreview, onStop,
                        )
                    }
                    if (filtered.isEmpty() && !catalog.isLoading) {
                        item { Text("No matching voices. Try all languages or refresh.") }
                    }
                }
                if (provider != SpeechProvider.SYSTEM) {
                    TextButton(onClick = { onStop(); custom = !custom }) { Text("Custom voice ID") }
                    if (custom) {
                        OutlinedTextField(
                            value = selected,
                            onValueChange = { onStop(); selected = it },
                            label = { Text("Synthesis voice ID (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PreviewButton(selected.trim(), catalog, playback, connectionReady, onPreview, onStop)
                    } else if (selected.isNotEmpty() && voices.none { it.id == selected }) {
                        Text(selected)
                    }
                }
                PreviewFeedback(catalog)
                Button(
                    onClick = {
                        onStop()
                        onChoose(if (provider == SpeechProvider.SYSTEM) selected else selected.trim())
                        open = false
                    },
                    enabled = provider == SpeechProvider.SYSTEM ||
                        selected.isBlank() || selected.trim().matches(Regex("[A-Za-z0-9_:-]{1,100}")),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Confirm selection") }
                Text("Save speech settings to apply this selection.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun VoiceChoice(
    id: String,
    name: String,
    detail: String,
    selected: String,
    catalog: SpeechVoiceUiState,
    playback: VoiceRuntimeState,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onPreview: (String) -> Unit,
    onStop: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        RadioButton(selected = selected == id, onClick = { onSelect(id) })
        Column(Modifier.weight(1f)) {
            TextButton(onClick = { onSelect(id) }) { Text(name) }
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall)
            if (id.isNotEmpty()) Text(id, style = MaterialTheme.typography.bodySmall)
            if (id in catalog.previewedIds) Text("Last preview succeeded", style = MaterialTheme.typography.bodySmall)
        }
        PreviewButton(id, catalog, playback, enabled, onPreview, onStop, compact = true)
    }
}

@Composable
private fun PreviewButton(
    id: String,
    catalog: SpeechVoiceUiState,
    playback: VoiceRuntimeState,
    enabled: Boolean,
    onPreview: (String) -> Unit,
    onStop: () -> Unit,
    compact: Boolean = false,
) {
    val active = catalog.previewVoiceId == id
    if (compact) {
        val description = localizeUiText(
            if (active) "Stop preview" else "Preview voice",
            AppLanguage.resolveContentLocale().language,
        ) + " " + id
        val color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled || active) 1f else 0.38f)
        IconButton(
            onClick = { if (active) onStop() else onPreview(id) },
            enabled = enabled || active,
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            Canvas(Modifier.size(18.dp)) {
                if (active) {
                    drawRect(color)
                } else {
                    drawPath(
                        Path().apply {
                            moveTo(0f, 0f)
                            lineTo(size.width, size.height / 2)
                            lineTo(0f, size.height)
                            close()
                        },
                        color,
                    )
                }
            }
        }
        return
    }
    TextButton(onClick = { if (active) onStop() else onPreview(id) }, enabled = enabled || active) {
        Text(
            if (!active) "Preview voice" else if (playback.playbackStage == SpeechPlaybackStage.PLAYING) {
                "Stop preview"
            } else {
                "Cancel synthesis"
            },
        )
    }
}

@Composable
private fun PreviewFeedback(state: SpeechVoiceUiState) {
    state.previewError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    state.diagnosticCode?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

internal fun filterSpeechVoices(
    voices: List<SpeechVoice>,
    search: String,
    allLanguages: Boolean,
    locale: Locale,
): List<SpeechVoice> = voices.filter {
    (allLanguages || Locale.forLanguageTag(it.languageTag).language == locale.language) &&
        (search.isBlank() ||
            "${it.id} ${it.name} ${localizeUiText(it.name, locale.language)} ${it.languageTag}"
                .contains(search.trim(), ignoreCase = true))
}
