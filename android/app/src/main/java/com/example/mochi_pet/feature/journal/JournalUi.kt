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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mochi_pet.core.journal.DailyJournal
import com.example.mochi_pet.core.journal.JournalSettings
import com.example.mochi_pet.core.journal.JournalStatus
import com.example.mochi_pet.feature.home.Text
import java.time.LocalDate

@Composable
fun JournalSettings(viewModel: JournalViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    JournalSettingsCard(settings, busy, error, viewModel::setEnabled)
}

@Composable
internal fun JournalSettingsCard(settings: JournalSettings?, busy: Boolean, error: String?, onEnabled: (Boolean) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Daily journal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Keep the moments that matter.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = settings?.enabled == true, enabled = settings != null && !busy,
                    onCheckedChange = { if (it) confirm = true else onEnabled(false) })
            }
            Text("After midnight, quietly summarize the previous day's saved conversations and schedules. Find your journal in past calendar days.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (settings?.enabled == true) {
                Text("Uses your selected AI connection. No notifications or speech.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Enable Daily journal?") },
        text = { Text("Starting today, saved conversations and schedules will be processed by your selected AI service. This uses model credits. Journals stay on this device; switching off keeps existing journals.") },
        confirmButton = { TextButton(onClick = { confirm = false; onEnabled(true) }) { Text("Enable") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@Composable
fun JournalDay(viewModel: JournalViewModel, date: LocalDate) {
    val today by viewModel.today.collectAsStateWithLifecycle()
    if (date >= today) return
    val entry by remember(date, viewModel) { viewModel.observe(date) }.collectAsStateWithLifecycle(null)
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    entry?.let { journal ->
        JournalCard(journal, today, settings?.enabled == true, busy,
            onRetry = { viewModel.retry(date) }, onDelete = { viewModel.delete(date) })
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
    Card(onClick = { expanded = true }, shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f))) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            JournalLabel()
            if (written) {
                MaterialText(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                MaterialText(entry.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(statusLabel(entry.status), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (entry.status == JournalStatus.GENERATING) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (written && entry.status != JournalStatus.READY) Text(statusLabel(entry.status),
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
            } else Text(statusLabel(entry.status), style = MaterialTheme.typography.titleMedium)
            entry.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("AI-generated from saved records.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text("Daily journal", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelSmall)
    }
}

private fun statusLabel(status: JournalStatus) = when (status) {
    JournalStatus.EMPTY -> "Nothing important to record"
    JournalStatus.WAITING -> "Waiting for background processing and a network connection"
    JournalStatus.GENERATING -> "Writing this day's journal"
    JournalStatus.FAILED -> "Journal could not be generated"
    JournalStatus.READY -> "Daily journal"
    JournalStatus.DELETED -> "Deleted"
}
