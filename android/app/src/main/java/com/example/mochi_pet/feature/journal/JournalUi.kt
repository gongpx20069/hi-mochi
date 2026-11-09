package com.example.mochi_pet.feature.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text as MaterialText
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mochi_pet.core.journal.DailyJournal
import com.example.mochi_pet.core.journal.JournalSettings
import com.example.mochi_pet.core.journal.JournalStatus
import com.example.mochi_pet.feature.home.Text
import com.example.mochi_pet.feature.home.localizeUiText
import com.example.mochi_pet.ui.theme.AgendaKind
import com.example.mochi_pet.ui.theme.AgendaIcon
import com.example.mochi_pet.ui.theme.agendaColor
import java.time.LocalDate

@Composable
fun JournalSettings(viewModel: JournalViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    JournalSettingsCard(settings, busy, error, onEnabled = viewModel::setEnabled)
}

@Composable
internal fun JournalSettingsCard(
    settings: JournalSettings?,
    busy: Boolean,
    error: String?,
    agenda: Boolean = false,
    onEnabled: (Boolean) -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    val accent = if (agenda) agendaColor(AgendaKind.JOURNAL) else MaterialTheme.colorScheme.primary
    val switchLabel = localizeUiText("Daily journal")
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (agenda) AgendaIcon(AgendaKind.JOURNAL)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Daily journal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (!agenda || settings != null) {
                        Text(when {
                            !agenda -> "Keep the moments that matter."
                            settings?.enabled == true -> "Automatically collect important moments after each day."
                            else -> "Keep what matters in a short journal."
                        }, style = if (agenda) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(checked = settings?.enabled == true, enabled = settings != null && !busy,
                    modifier = Modifier.semantics { contentDescription = switchLabel },
                    colors = if (agenda) SwitchDefaults.colors(checkedTrackColor = accent,
                        checkedThumbColor = MaterialTheme.colorScheme.surface) else SwitchDefaults.colors(),
                    onCheckedChange = { if (it) confirm = true else onEnabled(false) })
            }
            if (agenda) {
                if (settings == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (!settings.enabled) Text(
                    "After each day, summarize important moments from saved conversations and schedules without repeating routine plans.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("After midnight, quietly summarize the previous day's saved conversations and schedules. Find your journal in past calendar days.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!agenda && settings?.enabled == true) {
                Text("Uses your selected AI connection. No notifications or speech.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Enable Daily journal?") },
        text = { Text("Starting today, saved conversations and schedules will be processed by your selected AI service. This uses model credits. Earlier dates are not automatically backfilled. Journals stay on this device; switching off keeps existing journals.") },
        confirmButton = { TextButton(onClick = { confirm = false; onEnabled(true) }) { Text("Enable") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@Composable
fun JournalDay(viewModel: JournalViewModel, date: LocalDate, showControls: Boolean = false) {
    val today by viewModel.today.collectAsStateWithLifecycle()
    val entry by remember(date, viewModel) { viewModel.observe(date) }.collectAsStateWithLifecycle(null)
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    JournalDayContent(date, today, entry, settings, busy, error, showControls,
        onEnabled = viewModel::setEnabled, onRetry = { viewModel.retry(date) }, onDelete = { viewModel.delete(date) })
}

@Composable
internal fun JournalDayContent(
    date: LocalDate,
    today: LocalDate,
    entry: DailyJournal?,
    settings: JournalSettings?,
    busy: Boolean,
    error: String?,
    showControls: Boolean,
    onEnabled: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showControls) JournalSettingsCard(settings, busy, error, agenda = true, onEnabled = onEnabled)
        else error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (date < today && entry?.date == date && entry.status != JournalStatus.DELETED) {
            JournalCard(entry, today, settings?.enabled == true, busy, onRetry, onDelete)
        } else if (showControls && settings?.enabled == true && error == null) {
            Text(when {
                date == today -> "Today's journal will be prepared after the day ends."
                date > today -> "Journals only record days that have passed."
                else -> "No journal for this day"
            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JournalCard(
    entry: DailyJournal,
    today: LocalDate,
    enabled: Boolean,
    busy: Boolean,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    if (entry.date >= today || entry.status == JournalStatus.DELETED) return
    var expanded by remember(entry.date) { mutableStateOf(false) }
    var confirmation by remember(entry.date) { mutableStateOf<String?>(null) }
    val active = entry.status in setOf(JournalStatus.WAITING, JournalStatus.GENERATING)
    val written = entry.text.isNotBlank()
    val statusText = if (entry.status == JournalStatus.EMPTY && entry.conversationCount == null)
        "No journal content; source information unavailable" else statusLabel(entry.status)
    Card(onClick = { expanded = true }, shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            JournalLabel()
            if (written) {
                MaterialText(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                MaterialText(entry.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(statusText, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (entry.status == JournalStatus.GENERATING) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (written && entry.status != JournalStatus.READY) Text(statusText,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            entry.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (expanded) ModalBottomSheet(onDismissRequest = { expanded = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            JournalLabel()
            MaterialText(entry.date.toString(), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (written) {
                MaterialText(entry.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                SelectionContainer { MaterialText(entry.text, style = MaterialTheme.typography.bodyLarge) }
            } else Text(statusText, style = MaterialTheme.typography.titleMedium)
            entry.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(when {
                entry.status == JournalStatus.NO_DATA -> "No saved sources were available; no AI request was made."
                entry.conversationCount == null -> "Source counts unavailable for this entry"
                else -> "AI-generated from saved records."
            }, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.conversationCount != null) {
                MaterialText("${localizeUiText("Saved conversations")}: ${entry.conversationCount}\n" +
                    "${localizeUiText("User schedules")}: ${entry.userScheduleCount}\n" +
                    "${localizeUiText("Mochi task records")}: ${entry.taskCount}",
                    style = MaterialTheme.typography.bodySmall)
            }
            MaterialText("${entry.date} · ${entry.zone.id}", style = MaterialTheme.typography.bodySmall)
            entry.generatedAt?.let {
                MaterialText(localizeUiText("Generated") + " · " + it.atZone(entry.zone).format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { confirmation = "retry" }, enabled = enabled && !busy && !active,
                modifier = Modifier.fillMaxWidth()) { Text(if (written) "Regenerate journal" else "Retry") }
            if (!enabled) Text("Enable Daily journal before retrying", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { confirmation = "delete" }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Delete") }
        }
    }
    confirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(if (action == "delete") "Delete this journal?" else "Regenerate journal?") },
            text = { Text(if (action == "delete") "This day will not be generated again automatically."
                else "This uses your AI connection again. Existing text is replaced only after a successful result.") },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                if (action == "delete") { onDelete(); expanded = false } else onRetry()
            }) { Text(if (action == "delete") "Delete" else "Continue") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun JournalLabel() {
    val accent = agendaColor(AgendaKind.JOURNAL)
    Surface(shape = RoundedCornerShape(8.dp), color = accent.copy(alpha = 0.12f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            com.example.mochi_pet.ui.theme.AgendaIcon(AgendaKind.JOURNAL)
            Text("Daily journal", color = accent, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun statusLabel(status: JournalStatus) = when (status) {
    JournalStatus.EMPTY -> "Nothing important to record"
    JournalStatus.NO_DATA -> "No saved records for this day"
    JournalStatus.WAITING -> "Waiting for background processing and a network connection"
    JournalStatus.GENERATING -> "Writing this day's journal"
    JournalStatus.FAILED -> "Journal could not be generated"
    JournalStatus.READY -> "Daily journal"
    JournalStatus.DELETED -> "Deleted"
}
